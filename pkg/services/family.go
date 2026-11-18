package services

import (
	"encoding/base64"
	"fmt"
	"math/big"
	"sort"
	"strconv"
	"strings"
	"time"

	"xorm.io/builder"

	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/mayswind/ezbookkeeping/pkg/datastore"
	"github.com/mayswind/ezbookkeeping/pkg/errs"
	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/mayswind/ezbookkeeping/pkg/utils"
)

const familyTimezoneName = "Asia/Shanghai"
const MaximumFamilyMembers = 10

// FamilyService is a read-only view of active users in this single-family instance.
// Personal write services never receive this multi-user scope.
type FamilyService struct{ ServiceUsingDB }

var Family = &FamilyService{ServiceUsingDB: ServiceUsingDB{container: datastore.Container}}

type familyReadScope struct {
	uids       []int64
	members    []*models.User
	names      map[int64]string
	accounts   map[int64]*models.Account
	categories map[int64]*models.TransactionCategory
}

func familyMonthRange(month string) (time.Time, time.Time, error) {
	location, err := time.LoadLocation(familyTimezoneName)
	if err != nil {
		return time.Time{}, time.Time{}, err
	}
	if month == "" {
		month = time.Now().In(location).Format("2006-01")
	}
	start, err := time.ParseInLocation("2006-01", month, location)
	if err != nil || start.Year() < 1970 || start.Year() > 9998 || start.Format("2006-01") != month {
		return time.Time{}, time.Time{}, errs.ErrFamilyMonthInvalid
	}
	return start, start.AddDate(0, 1, 0), nil
}

func (s *FamilyService) readScope(c core.Context, actorUID int64) (*familyReadScope, error) {
	if actorUID <= 0 {
		return nil, errs.ErrUserIdInvalid
	}
	scope := &familyReadScope{names: make(map[int64]string), accounts: make(map[int64]*models.Account), categories: make(map[int64]*models.TransactionCategory)}
	err := s.UserDB().NewSession(c).Cols("uid", "nickname").Where("deleted=? AND disabled=?", false, false).OrderBy("uid asc").Limit(MaximumFamilyMembers + 1).Find(&scope.members)
	if err != nil {
		return nil, err
	}
	if len(scope.members) > MaximumFamilyMembers {
		return nil, errs.ErrFamilyMemberLimit
	}
	for _, member := range scope.members {
		scope.uids = append(scope.uids, member.Uid)
		scope.names[member.Uid] = member.Nickname
	}
	if _, exists := scope.names[actorUID]; !exists {
		return nil, errs.ErrNotPermittedToPerformThisAction
	}
	var accounts []*models.Account
	err = s.UserDataDB(actorUID).NewSession(c).Where("deleted=?", false).In("uid", scope.uids).Find(&accounts)
	if err != nil {
		return nil, err
	}
	for _, account := range accounts {
		scope.accounts[account.AccountId] = account
	}
	var categories []*models.TransactionCategory
	err = s.UserDataDB(actorUID).NewSession(c).Where("uid=? AND deleted=?", models.SharedDictionaryOwnerUID, false).Find(&categories)
	if err != nil {
		return nil, err
	}
	for _, category := range categories {
		scope.categories[category.CategoryId] = category
	}
	return scope, nil
}

type familyAmountAccumulator struct {
	income, expense big.Int
	scratch         big.Int
}

func (a *familyAmountAccumulator) add(transaction *models.Transaction) {
	a.addValues(transaction.Type, transaction.Amount)
}

func (a *familyAmountAccumulator) addValues(transactionType models.TransactionDbType, amount int64) {
	a.scratch.SetInt64(amount)
	a.addBigValues(transactionType, &a.scratch)
}

func (a *familyAmountAccumulator) addBigValues(transactionType models.TransactionDbType, amount *big.Int) {
	if transactionType == models.TRANSACTION_DB_TYPE_INCOME {
		a.income.Add(&a.income, amount)
	}
	if transactionType == models.TRANSACTION_DB_TYPE_EXPENSE {
		a.expense.Add(&a.expense, amount)
	}
}

func (a *familyAmountAccumulator) response() models.FamilyAmounts {
	return models.FamilyAmounts{IncomeMinor: a.income.String(), ExpenseMinor: a.expense.String(), NetMinor: new(big.Int).Sub(&a.income, &a.expense).String()}
}

// familyReportAccumulator keeps expenses positive. The personal report uses
// the same convention, which makes the shared Android report components
// reusable without a family-only sign branch.
type familyReportAccumulator struct {
	income, expense big.Int
}

func (a *familyReportAccumulator) add(transactionType models.TransactionDbType, amount *big.Int) {
	value := new(big.Int).Abs(amount)
	if transactionType == models.TRANSACTION_DB_TYPE_INCOME {
		a.income.Add(&a.income, value)
	} else if transactionType == models.TRANSACTION_DB_TYPE_EXPENSE {
		a.expense.Add(&a.expense, value)
	}
}

func (a *familyReportAccumulator) response() models.FamilyAmounts {
	net := new(big.Int).Sub(&a.income, &a.expense)
	return models.FamilyAmounts{IncomeMinor: a.income.String(), ExpenseMinor: a.expense.String(), NetMinor: net.String()}
}

func familyReportRange(period string, yearly bool) (time.Time, time.Time, error) {
	location, err := time.LoadLocation(familyTimezoneName)
	if err != nil {
		return time.Time{}, time.Time{}, err
	}
	if yearly {
		start, parseErr := time.ParseInLocation("2006", period, location)
		if parseErr != nil || start.Year() < 1970 || start.Year() > 9998 || start.Format("2006") != period {
			return time.Time{}, time.Time{}, errs.ErrFamilyMonthInvalid
		}
		return start, start.AddDate(1, 0, 0), nil
	}
	return familyMonthRange(period)
}

func familyReportCategoryName(scope *familyReadScope, categoryID int64) string {
	if category := scope.categories[categoryID]; category != nil && category.Name != "" {
		return category.Name
	}
	return "未分类"
}

func familyReportMemberCondition(scope *familyReadScope, memberUID int64) (builder.Cond, error) {
	if memberUID == 0 {
		return builder.In("uid", scope.uids), nil
	}
	if _, exists := scope.names[memberUID]; !exists {
		return nil, errs.ErrNotPermittedToPerformThisAction
	}
	return builder.Eq{"uid": memberUID}, nil
}

func (s *FamilyService) familyReportRows(c core.Context, actorUID int64, scope *familyReadScope, start, end time.Time, memberUID int64) ([]familyOverviewTransaction, bool, int, error) {
	memberCondition, err := familyReportMemberCondition(scope, memberUID)
	if err != nil {
		return nil, false, 0, err
	}
	condition := builder.And(builder.Eq{"deleted": false}, memberCondition, builder.Gte{"transaction_time": utils.GetMinTransactionTimeFromUnixTime(start.Unix())}, builder.Lt{"transaction_time": utils.GetMinTransactionTimeFromUnixTime(end.Unix())})
	database := s.UserDataDB(actorUID)
	if c != nil && c.Err() != nil {
		return nil, false, 0, c.Err()
	}
	var rows []familyOverviewTransaction
	dayBucketExpression := familyDayBucketExpression(database)
	err = database.NewSession(c).Table(&models.Transaction{}).Select(fmt.Sprintf("uid, type, category_id, account_id, %s AS day_bucket, SUM(amount) AS total_amount, COUNT(1) AS row_count", dayBucketExpression)).Where(condition).GroupBy("uid, type, category_id, account_id, day_bucket").Find(&rows)
	if err != nil {
		return nil, false, 0, err
	}
	foreign := false
	foreignCount := 0
	valid := make([]familyOverviewTransaction, 0, len(rows))
	for i := range rows {
		row := &rows[i]
		if row.Type != models.TRANSACTION_DB_TYPE_INCOME && row.Type != models.TRANSACTION_DB_TYPE_EXPENSE {
			continue
		}
		category := scope.categories[row.CategoryId]
		if category != nil && category.FamilyInternalTransfer {
			continue
		}
		account := scope.accounts[row.AccountId]
		if account == nil || account.Uid != row.Uid {
			return nil, false, 0, errs.ErrAccountNotFound
		}
		if account.Currency != "CNY" {
			foreign = true
			foreignCount += int(row.RowCount)
			continue
		}
		valid = append(valid, *row)
	}
	return valid, foreign, foreignCount, nil
}

// Report returns a server-side, read-only family report. It intentionally
// aggregates in SQLite instead of sending all family transactions to Android.
func (s *FamilyService) Report(c core.Context, actorUID int64, period string, yearly bool, memberUID int64) (*models.FamilyReport, error) {
	start, end, err := familyReportRange(period, yearly)
	if err != nil {
		return nil, err
	}
	scope, err := s.readScope(c, actorUID)
	if err != nil {
		return nil, err
	}
	previousStart := start.AddDate(0, -1, 0)
	previousEnd := start
	if yearly {
		previousStart = start.AddDate(-1, 0, 0)
	}
	currentRows, foreign, foreignCount, err := s.familyReportRows(c, actorUID, scope, start, end, memberUID)
	if err != nil {
		return nil, err
	}
	previousRows, previousForeign, previousForeignCount, err := s.familyReportRows(c, actorUID, scope, previousStart, previousEnd, memberUID)
	if err != nil {
		return nil, err
	}
	foreign = foreign || previousForeign
	foreignCount += previousForeignCount

	result := &models.FamilyReport{Period: start.Format("2006-01"), Yearly: yearly, MemberId: memberUID, Timezone: familyTimezoneName, Currency: "CNY", Days: []models.FamilyDaySummary{}, Categories: []models.FamilyReportCategory{}, Members: []models.FamilyMemberSummary{}, Accounts: []models.FamilyAccount{}, HasExcludedForeignCurrency: foreign, ExcludedForeignTransactions: foreignCount, ReadOnly: true}
	if yearly {
		result.Period = start.Format("2006")
	}
	var total familyReportAccumulator
	daily := make(map[string]*familyReportAccumulator)
	memberAmounts := make(map[int64]*familyReportAccumulator)
	categoryAmounts := make(map[int64]*familyReportAccumulator)
	previousCategories := make(map[int64]*familyReportAccumulator)
	for _, member := range scope.members {
		memberAmounts[member.Uid] = &familyReportAccumulator{}
	}
	accumulate := func(rows []familyOverviewTransaction, destination *familyReportAccumulator, byDay map[string]*familyReportAccumulator, byMember map[int64]*familyReportAccumulator, byCategory map[int64]*familyReportAccumulator) error {
		for i := range rows {
			row := &rows[i]
			amount := new(big.Int)
			if _, ok := amount.SetString(row.TotalAmount, 10); !ok {
				return fmt.Errorf("invalid aggregated family amount %q", row.TotalAmount)
			}
			destination.add(row.Type, amount)
			if byDay != nil {
				day := time.Unix(row.DayBucket*86400-28800, 0).In(start.Location()).Format("2006-01-02")
				if byDay[day] == nil {
					byDay[day] = &familyReportAccumulator{}
				}
				byDay[day].add(row.Type, amount)
			}
			if byMember != nil {
				byMember[row.Uid].add(row.Type, amount)
			}
			if byCategory != nil {
				if byCategory[row.CategoryId] == nil {
					byCategory[row.CategoryId] = &familyReportAccumulator{}
				}
				byCategory[row.CategoryId].add(row.Type, amount)
			}
		}
		return nil
	}
	if err := accumulate(currentRows, &total, daily, memberAmounts, categoryAmounts); err != nil {
		return nil, err
	}
	previousTotal := &familyReportAccumulator{}
	if err := accumulate(previousRows, previousTotal, nil, nil, previousCategories); err != nil {
		return nil, err
	}
	result.IncomeMinor = total.response().IncomeMinor
	result.ExpenseMinor = total.response().ExpenseMinor
	result.NetMinor = total.response().NetMinor
	result.AverageExpenseMinor = total.expense.String()
	if yearly {
		activeMonths := map[string]bool{}
		for day := range daily {
			activeMonths[day[:7]] = true
		}
		if len(activeMonths) > 0 {
			result.AverageExpenseMinor = new(big.Int).Quo(&total.expense, big.NewInt(int64(len(activeMonths)))).String()
		}
	} else if days := int(end.Sub(start).Hours() / 24); days > 0 {
		result.AverageExpenseMinor = new(big.Int).Quo(&total.expense, big.NewInt(int64(days))).String()
	}
	for day, amount := range daily {
		response := amount.response()
		result.Days = append(result.Days, models.FamilyDaySummary{Date: day, FamilyAmounts: response})
	}
	sort.Slice(result.Days, func(i, j int) bool { return result.Days[i].Date < result.Days[j].Date })
	for categoryID, amount := range categoryAmounts {
		category := scope.categories[categoryID]
		if category == nil {
			continue
		}
		result.Categories = append(result.Categories, models.FamilyReportCategory{Id: categoryID, Name: familyReportCategoryName(scope, categoryID), Type: int(category.Type), Icon: category.Icon, IconType: int(category.IconType), Color: category.Color, AmountMinor: func() string {
			if category.Type == models.CATEGORY_TYPE_EXPENSE {
				return amount.expense.String()
			}
			return amount.income.String()
		}(), PreviousAmountMinor: func() string {
			if previous := previousCategories[categoryID]; previous != nil {
				if category.Type == models.CATEGORY_TYPE_EXPENSE {
					return previous.expense.String()
				}
				return previous.income.String()
			}
			return "0"
		}()})
	}
	sort.Slice(result.Categories, func(i, j int) bool { return result.Categories[i].Id < result.Categories[j].Id })
	for _, member := range scope.members {
		response := memberAmounts[member.Uid].response()
		result.Members = append(result.Members, models.FamilyMemberSummary{Id: member.Uid, Nickname: member.Nickname, FamilyAmounts: response})
	}
	for _, account := range scope.accounts {
		if memberUID != 0 && account.Uid != memberUID {
			continue
		}
		result.Accounts = append(result.Accounts, models.FamilyAccount{Id: account.AccountId, OwnerId: account.Uid, OwnerName: scope.names[account.Uid], Name: account.Name, Currency: account.Currency, BalanceMinor: account.Balance, Hidden: account.Hidden})
	}
	sort.Slice(result.Accounts, func(i, j int) bool { return result.Accounts[i].Id < result.Accounts[j].Id })
	return result, nil
}

func familyCategoryName(scope *familyReadScope, categoryID int64) string {
	category := scope.categories[categoryID]
	if category == nil {
		return ""
	}
	if parent := scope.categories[category.ParentCategoryId]; parent != nil {
		return parent.Name + " / " + category.Name
	}
	return category.Name
}

func familyTransactionCondition(scope *familyReadScope, start, end time.Time) builder.Cond {
	return builder.And(builder.Eq{"deleted": false}, builder.In("uid", scope.uids), builder.Gte{"transaction_time": utils.GetMinTransactionTimeFromUnixTime(start.Unix())}, builder.Lt{"transaction_time": utils.GetMinTransactionTimeFromUnixTime(end.Unix())})
}

// familyOverviewTransaction contains only the columns needed by Overview. In
// particular, it avoids materializing comments, geo data, and other fields for
// every row in a year-long family range.
type familyOverviewTransaction struct {
	Uid         int64                    `xorm:"uid"`
	Type        models.TransactionDbType `xorm:"type"`
	CategoryId  int64                    `xorm:"category_id"`
	AccountId   int64                    `xorm:"account_id"`
	DayBucket   int64                    `xorm:"day_bucket"`
	TotalAmount string                   `xorm:"total_amount"`
	RowCount    int64                    `xorm:"row_count"`
}

func familyDayBucketExpression(db datastore.Database) string {
	// Family statistics use Asia/Shanghai, which has a fixed UTC+08:00 offset.
	// SQLite and PostgreSQL perform integer division for integer operands. MySQL
	// needs FLOOR because / returns a decimal expression.
	if xormDatabase, ok := db.(*datastore.XOrmDatabase); ok && xormDatabase.IsMySQL() {
		return "FLOOR((transaction_time / 1000 + 28800) / 86400)"
	}
	return "((transaction_time / 1000 + 28800) / 86400)"
}

// Overview loads only the projected columns needed for aggregation. It never
// uses floating-point money or current exchange rates.
func (s *FamilyService) Overview(c core.Context, actorUID int64, month string) (*models.FamilyOverview, error) {
	start, end, err := familyMonthRange(month)
	if err != nil {
		return nil, err
	}
	scope, err := s.readScope(c, actorUID)
	if err != nil {
		return nil, err
	}
	result := &models.FamilyOverview{Month: start.Format("2006-01"), Timezone: familyTimezoneName, Currency: "CNY", Members: []models.FamilyMemberSummary{}, Categories: []models.FamilyCategorySummary{}, Daily: []models.FamilyDaySummary{}, Trend: []models.FamilyMonthSummary{}, Accounts: []models.FamilyAccount{}}
	var total familyAmountAccumulator
	memberAmounts := make(map[int64]*familyAmountAccumulator)
	categoryAmounts := make(map[int64]*familyAmountAccumulator)
	dailyAmounts := make(map[string]*familyAmountAccumulator)
	trendAmounts := make(map[string]*familyAmountAccumulator)
	for _, member := range scope.members {
		memberAmounts[member.Uid] = &familyAmountAccumulator{}
	}
	trendStart := start.AddDate(0, -11, 0)
	for i := 0; i < 12; i++ {
		trendAmounts[trendStart.AddDate(0, i, 0).Format("2006-01")] = &familyAmountAccumulator{}
	}
	database := s.UserDataDB(actorUID)
	condition := familyTransactionCondition(scope, trendStart, end)
	if c != nil && c.Err() != nil {
		return nil, c.Err()
	}
	var rows []familyOverviewTransaction
	dayBucketExpression := familyDayBucketExpression(database)
	err = database.NewSession(c).Table(&models.Transaction{}).Select(fmt.Sprintf("uid, type, category_id, account_id, %s AS day_bucket, SUM(amount) AS total_amount, COUNT(1) AS row_count", dayBucketExpression)).Where(condition).GroupBy("uid, type, category_id, account_id, day_bucket").Find(&rows)
	if err != nil {
		return nil, err
	}
	var aggregateAmount big.Int
	for i := range rows {
		transaction := &rows[i]
		if transaction.Type != models.TRANSACTION_DB_TYPE_INCOME && transaction.Type != models.TRANSACTION_DB_TYPE_EXPENSE {
			continue
		}
		category := scope.categories[transaction.CategoryId]
		if category != nil && category.FamilyInternalTransfer {
			continue
		}
		account := scope.accounts[transaction.AccountId]
		if account == nil || account.Uid != transaction.Uid {
			return nil, errs.ErrAccountNotFound
		}
		if account.Currency != "CNY" {
			result.ExcludedForeignTransactions += int(transaction.RowCount)
			result.HasExcludedForeignCurrency = true
			continue
		}
		if _, ok := aggregateAmount.SetString(transaction.TotalAmount, 10); !ok {
			return nil, fmt.Errorf("invalid aggregated family amount %q", transaction.TotalAmount)
		}
		transactionTime := transaction.DayBucket*86400 - 28800
		transactionMonth := time.Unix(transactionTime, 0).In(start.Location()).Format("2006-01")
		if amounts := trendAmounts[transactionMonth]; amounts != nil {
			amounts.addBigValues(transaction.Type, &aggregateAmount)
		}
		if transactionMonth != result.Month {
			continue
		}
		total.addBigValues(transaction.Type, &aggregateAmount)
		memberAmounts[transaction.Uid].addBigValues(transaction.Type, &aggregateAmount)
		day := time.Unix(transactionTime, 0).In(start.Location()).Format("2006-01-02")
		if dailyAmounts[day] == nil {
			dailyAmounts[day] = &familyAmountAccumulator{}
		}
		dailyAmounts[day].addBigValues(transaction.Type, &aggregateAmount)
		if categoryAmounts[transaction.CategoryId] == nil {
			categoryAmounts[transaction.CategoryId] = &familyAmountAccumulator{}
		}
		categoryAmounts[transaction.CategoryId].addBigValues(transaction.Type, &aggregateAmount)
	}
	result.Totals = total.response()
	for _, member := range scope.members {
		result.Members = append(result.Members, models.FamilyMemberSummary{Id: member.Uid, Nickname: member.Nickname, FamilyAmounts: memberAmounts[member.Uid].response()})
	}
	for id, amount := range categoryAmounts {
		result.Categories = append(result.Categories, models.FamilyCategorySummary{Id: id, Name: familyCategoryName(scope, id), IncomeMinor: amount.response().IncomeMinor, ExpenseMinor: amount.response().ExpenseMinor})
	}
	sort.Slice(result.Categories, func(i, j int) bool { return result.Categories[i].Id < result.Categories[j].Id })
	days := make([]string, 0, len(dailyAmounts))
	for day := range dailyAmounts {
		days = append(days, day)
	}
	sort.Strings(days)
	for _, day := range days {
		result.Daily = append(result.Daily, models.FamilyDaySummary{Date: day, FamilyAmounts: dailyAmounts[day].response()})
	}
	for i := 0; i < 12; i++ {
		key := trendStart.AddDate(0, i, 0).Format("2006-01")
		result.Trend = append(result.Trend, models.FamilyMonthSummary{Month: key, FamilyAmounts: trendAmounts[key].response()})
	}
	for _, account := range scope.accounts {
		result.Accounts = append(result.Accounts, models.FamilyAccount{Id: account.AccountId, OwnerId: account.Uid, OwnerName: scope.names[account.Uid], Name: account.Name, Currency: account.Currency, BalanceMinor: account.Balance, Hidden: account.Hidden})
	}
	sort.Slice(result.Accounts, func(i, j int) bool { return result.Accounts[i].Id < result.Accounts[j].Id })
	return result, nil
}

func (s *FamilyService) Transactions(c core.Context, actorUID int64, month string, memberUID int64, cursor string, count int) (*models.FamilyTransactionPage, error) {
	start, end, err := familyMonthRange(month)
	if err != nil {
		return nil, err
	}
	if count < 1 || count > 200 {
		return nil, errs.ErrPageCountInvalid
	}
	scope, err := s.readScope(c, actorUID)
	if err != nil {
		return nil, err
	}
	condition := familyTransactionCondition(scope, start, end)
	if memberUID != 0 {
		if _, exists := scope.names[memberUID]; !exists {
			return nil, errs.ErrNotPermittedToPerformThisAction
		}
		condition = builder.And(condition, builder.Eq{"uid": memberUID})
	}
	if cursor != "" {
		parts := strings.Split(cursor, ":")
		if len(parts) != 2 {
			return nil, errs.ErrFamilyCursorInvalid
		}
		cursorTime, timeErr := strconv.ParseInt(parts[0], 10, 64)
		cursorID, idErr := strconv.ParseInt(parts[1], 10, 64)
		if timeErr != nil || idErr != nil || cursorTime < 0 || cursorID <= 0 {
			return nil, errs.ErrFamilyCursorInvalid
		}
		condition = builder.And(condition, builder.Or(builder.Lt{"transaction_time": cursorTime}, builder.And(builder.Eq{"transaction_time": cursorTime}, builder.Lt{"transaction_id": cursorID})))
	}
	var rows []*models.Transaction
	err = s.UserDataDB(actorUID).NewSession(c).Where(condition).OrderBy("transaction_time desc, transaction_id desc").Limit(count + 1).Find(&rows)
	if err != nil {
		return nil, err
	}
	result := &models.FamilyTransactionPage{Items: []models.FamilyTransaction{}}
	if len(rows) > count {
		rows = rows[:count]
		last := rows[len(rows)-1]
		result.NextCursor = fmt.Sprintf("%d:%d", last.TransactionTime, last.TransactionId)
	}
	ids := make([]int64, 0, len(rows))
	for _, row := range rows {
		ids = append(ids, row.TransactionId)
	}
	pictures := make(map[int64][]models.FamilyPicture)
	if len(ids) > 0 {
		var pictureInfos []*models.TransactionPictureInfo
		err = s.UserDataDB(actorUID).NewSession(c).Where("deleted=?", false).In("uid", scope.uids).In("transaction_id", ids).OrderBy("picture_id asc").Find(&pictureInfos)
		if err != nil {
			return nil, err
		}
		for _, picture := range pictureInfos {
			pictures[picture.TransactionId] = append(pictures[picture.TransactionId], models.FamilyPicture{Id: picture.PictureId, Extension: picture.PictureExtension})
		}
	}
	for _, transaction := range rows {
		account := scope.accounts[transaction.AccountId]
		if account == nil || account.Uid != transaction.Uid {
			return nil, errs.ErrAccountNotFound
		}
		transactionPictures := pictures[transaction.TransactionId]
		if transactionPictures == nil {
			transactionPictures = []models.FamilyPicture{}
		}
		result.Items = append(result.Items, models.FamilyTransaction{Id: transaction.TransactionId, OwnerId: transaction.Uid, OwnerName: scope.names[transaction.Uid], Time: utils.GetUnixTimeFromTransactionTime(transaction.TransactionTime), Type: transaction.Type, Currency: account.Currency, AmountMinor: transaction.Amount, CategoryName: familyCategoryName(scope, transaction.CategoryId), AccountName: account.Name, Comment: transaction.Comment, Editable: transaction.Uid == actorUID, Pictures: transactionPictures})
	}
	return result, nil
}

// ReportTransactions is the read-only detail source for a family report. All
// filters are part of the cursor contract so a cursor cannot accidentally be
// reused for another member, date, category, or account scope.
func (s *FamilyService) ReportTransactions(c core.Context, actorUID int64, period string, yearly bool, memberUID, categoryID, accountID int64, date, cursor string, count int) (*models.FamilyTransactionPage, error) {
	start, end, err := familyReportRange(period, yearly)
	if err != nil {
		return nil, err
	}
	if count < 1 || count > 200 {
		return nil, errs.ErrPageCountInvalid
	}
	scope, err := s.readScope(c, actorUID)
	if err != nil {
		return nil, err
	}
	memberCondition, err := familyReportMemberCondition(scope, memberUID)
	if err != nil {
		return nil, err
	}
	condition := builder.And(builder.Eq{"deleted": false}, memberCondition, builder.In("type", []models.TransactionDbType{models.TRANSACTION_DB_TYPE_INCOME, models.TRANSACTION_DB_TYPE_EXPENSE}), builder.Gte{"transaction_time": utils.GetMinTransactionTimeFromUnixTime(start.Unix())}, builder.Lt{"transaction_time": utils.GetMinTransactionTimeFromUnixTime(end.Unix())})
	if categoryID != 0 {
		if scope.categories[categoryID] == nil {
			return nil, errs.ErrNotPermittedToPerformThisAction
		}
		condition = builder.And(condition, builder.Eq{"category_id": categoryID})
	}
	if accountID != 0 {
		account := scope.accounts[accountID]
		if account == nil || (memberUID != 0 && account.Uid != memberUID) {
			return nil, errs.ErrNotPermittedToPerformThisAction
		}
		condition = builder.And(condition, builder.Eq{"account_id": accountID})
	}
	if date != "" {
		day, parseErr := time.ParseInLocation("2006-01-02", date, time.FixedZone(familyTimezoneName, 8*60*60))
		if parseErr != nil || day.Before(start) || !day.Before(end) {
			return nil, errs.ErrFamilyMonthInvalid
		}
		condition = builder.And(condition, builder.Gte{"transaction_time": utils.GetMinTransactionTimeFromUnixTime(day.Unix())}, builder.Lt{"transaction_time": utils.GetMinTransactionTimeFromUnixTime(day.AddDate(0, 0, 1).Unix())})
	}
	internalCategories := make([]int64, 0)
	for id, category := range scope.categories {
		if category.FamilyInternalTransfer {
			internalCategories = append(internalCategories, id)
		}
	}
	if len(internalCategories) > 0 {
		condition = builder.And(condition, builder.NotIn("category_id", internalCategories))
	}
	cnyAccountIDs := make([]int64, 0)
	for id, account := range scope.accounts {
		if account.Currency == "CNY" {
			cnyAccountIDs = append(cnyAccountIDs, id)
		}
	}
	if len(cnyAccountIDs) == 0 {
		return &models.FamilyTransactionPage{Items: []models.FamilyTransaction{}}, nil
	}
	condition = builder.And(condition, builder.In("account_id", cnyAccountIDs))
	if cursor != "" {
		decoded, decodeErr := base64.RawURLEncoding.DecodeString(cursor)
		parts := strings.Split(string(decoded), "\x1f")
		if decodeErr != nil || len(parts) != 8 || parts[0] != period || parts[1] != strconv.FormatBool(yearly) || parts[2] != strconv.FormatInt(memberUID, 10) || parts[3] != strconv.FormatInt(categoryID, 10) || parts[4] != strconv.FormatInt(accountID, 10) || parts[5] != date {
			return nil, errs.ErrFamilyCursorInvalid
		}
		cursorTime, timeErr := strconv.ParseInt(parts[6], 10, 64)
		cursorID, idErr := strconv.ParseInt(parts[7], 10, 64)
		if timeErr != nil || idErr != nil || cursorTime < 0 || cursorID <= 0 {
			return nil, errs.ErrFamilyCursorInvalid
		}
		condition = builder.And(condition, builder.Or(builder.Lt{"transaction_time": cursorTime}, builder.And(builder.Eq{"transaction_time": cursorTime}, builder.Lt{"transaction_id": cursorID})))
	}
	var rows []*models.Transaction
	err = s.UserDataDB(actorUID).NewSession(c).Where(condition).OrderBy("transaction_time desc, transaction_id desc").Limit(count + 1).Find(&rows)
	if err != nil {
		return nil, err
	}
	result := &models.FamilyTransactionPage{Items: []models.FamilyTransaction{}}
	if len(rows) > count {
		rows = rows[:count]
		last := rows[len(rows)-1]
		result.NextCursor = base64.RawURLEncoding.EncodeToString([]byte(strings.Join([]string{period, strconv.FormatBool(yearly), strconv.FormatInt(memberUID, 10), strconv.FormatInt(categoryID, 10), strconv.FormatInt(accountID, 10), date, strconv.FormatInt(last.TransactionTime, 10), strconv.FormatInt(last.TransactionId, 10)}, "\x1f")))
	}
	ids := make([]int64, 0, len(rows))
	for _, row := range rows {
		ids = append(ids, row.TransactionId)
	}
	pictures := make(map[int64][]models.FamilyPicture)
	if len(ids) > 0 {
		var pictureInfos []*models.TransactionPictureInfo
		err = s.UserDataDB(actorUID).NewSession(c).Where("deleted=?", false).In("uid", scope.uids).In("transaction_id", ids).OrderBy("picture_id asc").Find(&pictureInfos)
		if err != nil {
			return nil, err
		}
		for _, picture := range pictureInfos {
			pictures[picture.TransactionId] = append(pictures[picture.TransactionId], models.FamilyPicture{Id: picture.PictureId, Extension: picture.PictureExtension})
		}
	}
	for _, transaction := range rows {
		account := scope.accounts[transaction.AccountId]
		if account == nil || account.Uid != transaction.Uid || account.Currency != "CNY" {
			continue
		}
		transactionPictures := pictures[transaction.TransactionId]
		if transactionPictures == nil {
			transactionPictures = []models.FamilyPicture{}
		}
		result.Items = append(result.Items, models.FamilyTransaction{Id: transaction.TransactionId, OwnerId: transaction.Uid, OwnerName: scope.names[transaction.Uid], Time: utils.GetUnixTimeFromTransactionTime(transaction.TransactionTime), Type: transaction.Type, Currency: account.Currency, AmountMinor: transaction.Amount, CategoryName: familyReportCategoryName(scope, transaction.CategoryId), AccountName: account.Name, Comment: transaction.Comment, Editable: transaction.Uid == actorUID, Pictures: transactionPictures})
	}
	return result, nil
}

func (s *FamilyService) Picture(c core.Context, actorUID int64, pictureID int64, extension string) ([]byte, error) {
	scope, err := s.readScope(c, actorUID)
	if err != nil {
		return nil, err
	}
	if pictureID <= 0 {
		return nil, errs.ErrTransactionPictureIdInvalid
	}
	picture := &models.TransactionPictureInfo{}
	has, err := s.UserDataDB(actorUID).NewSession(c).ID(pictureID).Where("deleted=?", false).In("uid", scope.uids).Get(picture)
	if err != nil {
		return nil, err
	}
	if !has || picture.TransactionId <= 0 {
		return nil, errs.ErrTransactionPictureNotFound
	}
	transaction := &models.Transaction{}
	has, err = s.UserDataDB(actorUID).NewSession(c).ID(picture.TransactionId).Where("uid=? AND deleted=?", picture.Uid, false).Get(transaction)
	if err != nil {
		return nil, err
	}
	if !has {
		return nil, errs.ErrTransactionPictureNotFound
	}
	return TransactionPictures.GetPictureByPictureId(c, picture.Uid, pictureID, extension)
}
