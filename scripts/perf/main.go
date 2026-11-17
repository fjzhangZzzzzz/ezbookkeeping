//go:build cgo

// perf is an isolated, reproducible benchmark for the real family overview and
// transaction-search service paths. It deliberately creates a temporary SQLite
// database and never connects to a configured application database.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/mayswind/ezbookkeeping/pkg/datastore"
	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/mayswind/ezbookkeeping/pkg/services"
	"github.com/mayswind/ezbookkeeping/pkg/settings"
	"github.com/mayswind/ezbookkeeping/pkg/utils"
)

const benchmarkMonth = "2026-11"

type config struct {
	members               int
	transactionsPerMember int
	spanDays              int
	runs                  int
	warmup                int
	concurrency           int
	mode                  string
	keyword               string
	out                   string
}

type operationResult struct {
	Name             string  `json:"name"`
	Runs             int     `json:"runs"`
	Concurrency      int     `json:"concurrency"`
	P50Ms            float64 `json:"p50_ms"`
	P95Ms            float64 `json:"p95_ms"`
	P99Ms            float64 `json:"p99_ms"`
	MaxMs            float64 `json:"max_ms"`
	Errors           int     `json:"errors"`
	MemoryDeltaBytes int64   `json:"memory_delta_bytes"`
}

type report struct {
	GeneratedAt           string            `json:"generated_at"`
	GoVersion             string            `json:"go_version"`
	OS                    string            `json:"os"`
	Arch                  string            `json:"arch"`
	CPUCount              int               `json:"cpu_count"`
	Database              string            `json:"database"`
	Month                 string            `json:"month"`
	Members               int               `json:"members"`
	TransactionsPerMember int               `json:"transactions_per_member"`
	TotalTransactions     int               `json:"total_transactions"`
	SpanDays              int               `json:"span_days"`
	Warmup                int               `json:"warmup"`
	Runs                  int               `json:"runs"`
	Concurrency           int               `json:"concurrency"`
	QueryPlan             []string          `json:"query_plan,omitempty"`
	Results               []operationResult `json:"results"`
}

func main() {
	cfg := config{}
	flag.IntVar(&cfg.members, "members", 2, "active family members")
	flag.IntVar(&cfg.transactionsPerMember, "transactions-per-member", 10000, "synthetic transactions per member")
	flag.IntVar(&cfg.spanDays, "span", 31, "data span in days (31 or 366)")
	flag.IntVar(&cfg.runs, "runs", 5, "measured calls per operation")
	flag.IntVar(&cfg.warmup, "warmup", 1, "warmup calls per operation")
	flag.IntVar(&cfg.concurrency, "concurrency", 1, "concurrent calls per measured batch")
	flag.StringVar(&cfg.mode, "mode", "all", "family, search, or all")
	flag.StringVar(&cfg.keyword, "keyword", "", "optional search keyword; empty measures the full search scope")
	flag.StringVar(&cfg.out, "out", "", "optional JSON report path")
	flag.Parse()

	if cfg.members < 1 || cfg.members > services.MaximumFamilyMembers || cfg.transactionsPerMember < 1 || cfg.spanDays < 1 || cfg.runs < 1 || cfg.warmup < 0 || cfg.concurrency < 1 {
		fatalf("invalid benchmark parameters")
	}
	if cfg.mode != "all" && cfg.mode != "family" && cfg.mode != "search" {
		fatalf("-mode must be family, search, or all")
	}

	dbPath, err := os.CreateTemp("", "ezbookkeeping-perf-*.db")
	if err != nil {
		fatalf("create temporary database: %v", err)
	}
	dbPathName := dbPath.Name()
	_ = dbPath.Close()
	defer os.Remove(dbPathName)

	config := &settings.Config{DatabaseConfig: &settings.DatabaseConfig{
		DatabaseType: settings.Sqlite3DbType,
		DatabasePath: dbPathName,
	}}
	if err := datastore.InitializeDataStore(config); err != nil {
		fatalf("initialize isolated datastore: %v", err)
	}
	db := datastore.Container.UserDataStore.Choose(0)
	defer func() {
		if err := db.NewSession(nil).(*datastore.XOrmSession).Engine().Close(); err != nil {
			fmt.Fprintf(os.Stderr, "close datastore: %v\n", err)
		}
	}()

	if err := db.SyncStructs(
		&models.User{}, &models.TokenRecord{}, &models.Account{},
		&models.TransactionCategory{}, &models.Transaction{},
		&models.TransactionPictureInfo{}, &models.TransactionTag{},
		&models.TransactionTagGroup{}, &models.TransactionTagIndex{},
	); err != nil {
		fatalf("sync isolated schema: %v", err)
	}
	if err := seed(db, cfg.members, cfg.transactionsPerMember, cfg.spanDays); err != nil {
		fatalf("seed isolated dataset: %v", err)
	}

	report := report{
		GeneratedAt:           time.Now().Format(time.RFC3339Nano),
		GoVersion:             runtime.Version(),
		OS:                    runtime.GOOS,
		Arch:                  runtime.GOARCH,
		CPUCount:              runtime.NumCPU(),
		Database:              "sqlite3-temporary",
		Month:                 benchmarkMonth,
		Members:               cfg.members,
		TransactionsPerMember: cfg.transactionsPerMember,
		TotalTransactions:     cfg.members * cfg.transactionsPerMember,
		SpanDays:              cfg.spanDays,
		Warmup:                cfg.warmup,
		Runs:                  cfg.runs,
		Concurrency:           cfg.concurrency,
		QueryPlan:             explainFamilyQuery(db, cfg.members, cfg.spanDays),
	}

	if cfg.mode == "all" || cfg.mode == "family" {
		result := measure("family_overview", cfg, func() error {
			value, err := services.Family.Overview(nil, 1, benchmarkMonth)
			if err == nil && value == nil {
				return fmt.Errorf("family overview returned nil")
			}
			return err
		})
		report.Results = append(report.Results, result)
	}
	if cfg.mode == "all" || cfg.mode == "search" {
		result := measure("transaction_search_summary", cfg, func() error {
			value, err := services.Transactions.GetTransactionSearchSummary(nil, 1, 0, 0, 0, nil, nil, nil, false, "", cfg.keyword, core.MATCH_MODE_DEFAULT, false)
			if err == nil && value == nil {
				return fmt.Errorf("transaction search summary returned nil")
			}
			return err
		})
		report.Results = append(report.Results, result)
	}

	encoded, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		fatalf("encode report: %v", err)
	}
	if cfg.out != "" {
		if err := os.WriteFile(cfg.out, encoded, 0600); err != nil {
			fatalf("write report: %v", err)
		}
	}
	fmt.Println(string(encoded))
}

func seed(db datastore.Database, members, transactionsPerMember, spanDays int) error {
	users := make([]*models.User, 0, members)
	accounts := make([]*models.Account, 0, members)
	for uid := int64(1); uid <= int64(members); uid++ {
		users = append(users, &models.User{
			Uid: uid, Username: fmt.Sprintf("perf-%d", uid), Email: fmt.Sprintf("perf-%d@example.invalid", uid),
			Nickname: fmt.Sprintf("Perf %d", uid), DefaultCurrency: "CNY", Deleted: false, Disabled: false,
		})
		accounts = append(accounts, &models.Account{
			AccountId: uid, Uid: uid, Category: models.ACCOUNT_CATEGORY_CASH, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT,
			Name: fmt.Sprintf("Cash %d", uid), Currency: "CNY", Deleted: false,
		})
	}
	if _, err := db.NewSession(nil).Insert(users); err != nil {
		return err
	}
	if _, err := db.NewSession(nil).Insert(accounts); err != nil {
		return err
	}
	if _, err := db.NewSession(nil).Insert(
		&models.TransactionCategory{CategoryId: 1, Uid: models.SharedDictionaryOwnerUID, Type: models.CATEGORY_TYPE_EXPENSE, Name: "Perf expense"},
		&models.TransactionCategory{CategoryId: 2, Uid: models.SharedDictionaryOwnerUID, Type: models.CATEGORY_TYPE_INCOME, Name: "Perf income"},
	); err != nil {
		return err
	}

	location, err := time.LoadLocation(familyTimezone())
	if err != nil {
		return err
	}
	monthStart, err := time.ParseInLocation("2006-01", benchmarkMonth, location)
	if err != nil {
		return err
	}
	lastDay := monthStart.AddDate(0, 1, 0).Add(-24 * time.Hour)
	firstDay := lastDay.AddDate(0, 0, -(spanDays - 1))
	dayMillis := int64(24 * time.Hour / time.Millisecond)
	nextID := int64(1)
	batch := make([]*models.Transaction, 0, 500)
	for uid := int64(1); uid <= int64(members); uid++ {
		for index := 0; index < transactionsPerMember; index++ {
			day := index % spanDays
			sequence := index / spanDays
			transactionTime := utils.GetMinTransactionTimeFromUnixTime(firstDay.Unix()) + int64(day)*dayMillis + int64(sequence)
			income := index%2 == 0
			transactionType := models.TRANSACTION_DB_TYPE_EXPENSE
			amount := int64(-100 + index%900)
			categoryID := int64(1)
			if income {
				transactionType = models.TRANSACTION_DB_TYPE_INCOME
				amount = int64(100 + index%900)
				categoryID = 2
			}
			batch = append(batch, &models.Transaction{
				TransactionId: nextID, Uid: uid, CategoryId: categoryID, AccountId: uid,
				Type: transactionType, TransactionTime: transactionTime, Amount: amount,
				Comment: "perf", Deleted: false,
			})
			nextID++
			if len(batch) == cap(batch) {
				if _, err := db.NewSession(nil).Insert(batch); err != nil {
					return err
				}
				batch = batch[:0]
			}
		}
	}
	if len(batch) > 0 {
		_, err := db.NewSession(nil).Insert(batch)
		return err
	}
	return nil
}

func explainFamilyQuery(db datastore.Database, members, spanDays int) []string {
	xormDatabase, ok := db.(*datastore.XOrmDatabase)
	if !ok || !xormDatabase.IsSQLite() {
		return nil
	}
	location, err := time.LoadLocation(familyTimezone())
	if err != nil {
		return nil
	}
	monthStart, err := time.ParseInLocation("2006-01", benchmarkMonth, location)
	if err != nil {
		return nil
	}
	trendStart := monthStart.AddDate(0, -11, 0)
	end := monthStart.AddDate(0, 1, 0)
	userIDs := make([]string, members)
	for index := range userIDs {
		userIDs[index] = strconv.Itoa(index + 1)
	}
	dayBucketExpression := "((transaction_time / 1000 + 28800) / 86400)"
	query := fmt.Sprintf("EXPLAIN QUERY PLAN SELECT uid, type, category_id, account_id, %s AS day_bucket, SUM(amount) AS total_amount, COUNT(1) AS row_count FROM \"transaction\" WHERE deleted = 0 AND uid IN (%s) AND transaction_time >= %d AND transaction_time < %d GROUP BY uid, type, category_id, account_id, day_bucket", dayBucketExpression, strings.Join(userIDs, ","), utils.GetMinTransactionTimeFromUnixTime(trendStart.Unix()), utils.GetMinTransactionTimeFromUnixTime(end.Unix()))
	rows, err := xormDatabase.NewSession(nil).(*datastore.XOrmSession).Engine().Query(query)
	if err != nil {
		return []string{"explain failed: " + err.Error()}
	}
	plan := make([]string, 0, len(rows))
	for _, row := range rows {
		if detail, ok := row["detail"]; ok {
			plan = append(plan, string(detail))
		}
	}
	return plan
}

func familyTimezone() string { return "Asia/Shanghai" }

func measure(name string, cfg config, operation func() error) operationResult {
	for i := 0; i < cfg.warmup; i++ {
		if err := runBatch(cfg.concurrency, operation); err != nil {
			fatalf("warmup %s: %v", name, err)
		}
	}

	values := make([]time.Duration, 0, cfg.runs*cfg.concurrency)
	errors := 0
	var before, after runtime.MemStats
	runtime.GC()
	runtime.ReadMemStats(&before)
	for i := 0; i < cfg.runs; i++ {
		batch, errCount := timedBatch(cfg.concurrency, operation)
		values = append(values, batch...)
		errors += errCount
	}
	runtime.ReadMemStats(&after)
	sort.Slice(values, func(i, j int) bool { return values[i] < values[j] })
	return operationResult{
		Name: name, Runs: cfg.runs, Concurrency: cfg.concurrency,
		P50Ms: durationMs(percentile(values, 0.50)), P95Ms: durationMs(percentile(values, 0.95)),
		P99Ms: durationMs(percentile(values, 0.99)), MaxMs: durationMs(values[len(values)-1]), Errors: errors,
		MemoryDeltaBytes: int64(after.TotalAlloc - before.TotalAlloc),
	}
}

func durationMs(value time.Duration) float64 {
	return float64(value) / float64(time.Millisecond)
}

func runBatch(concurrency int, operation func() error) error {
	_, errors := timedBatch(concurrency, operation)
	if errors > 0 {
		if err := operation(); err != nil {
			return err
		}
		return fmt.Errorf("%d operation(s) failed", errors)
	}
	return nil
}

func timedBatch(concurrency int, operation func() error) ([]time.Duration, int) {
	values := make([]time.Duration, concurrency)
	errors := 0
	var lock sync.Mutex
	var group sync.WaitGroup
	group.Add(concurrency)
	for index := 0; index < concurrency; index++ {
		go func(index int) {
			defer group.Done()
			started := time.Now()
			err := operation()
			values[index] = time.Since(started)
			if err != nil {
				lock.Lock()
				errors++
				lock.Unlock()
			}
		}(index)
	}
	group.Wait()
	return values, errors
}

func percentile(values []time.Duration, ratio float64) time.Duration {
	if len(values) == 0 {
		return 0
	}
	index := int(float64(len(values)-1) * ratio)
	return values[index]
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(1)
}
