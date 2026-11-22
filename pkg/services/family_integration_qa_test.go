//go:build cgo

package services

import (
	"encoding/base64"
	"fmt"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/mayswind/ezbookkeeping/pkg/datastore"
	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/mayswind/ezbookkeeping/pkg/settings"
	"github.com/mayswind/ezbookkeeping/pkg/utils"
	"github.com/stretchr/testify/require"
)

func TestFamilySQLiteIndependentQA(t *testing.T) {
	config := &settings.Config{DatabaseConfig: &settings.DatabaseConfig{DatabaseType: settings.Sqlite3DbType, DatabasePath: filepath.Join(t.TempDir(), "family.db")}}
	require.NoError(t, datastore.InitializeDataStore(config))
	db := datastore.Container.UserDataStore.Choose(0)
	t.Cleanup(func() { require.NoError(t, db.NewSession(nil).(*datastore.XOrmSession).Engine().Close()) })
	require.NoError(t, db.SyncStructs(&models.User{}, &models.TokenRecord{}, &models.Account{}, &models.TransactionCategory{}, &models.Transaction{}, &models.TransactionPictureInfo{}))
	for uid := int64(1); uid <= 4; uid++ {
		_, err := db.NewSession(nil).Insert(&models.User{Uid: uid, Username: fmt.Sprint(uid), Email: fmt.Sprintf("%d@test.invalid", uid), Nickname: fmt.Sprint(uid), Disabled: uid == 3, Deleted: uid == 4})
		require.NoError(t, err)
	}
	for _, account := range []*models.Account{{AccountId: 11, Uid: 1, Currency: "CNY", Balance: 123}, {AccountId: 12, Uid: 2, Currency: "CNY", Balance: -456}, {AccountId: 13, Uid: 2, Currency: "USD"}, {AccountId: 14, Uid: 3, Currency: "CNY"}, {AccountId: 15, Uid: 4, Currency: "CNY"}} {
		_, err := db.NewSession(nil).Insert(account)
		require.NoError(t, err)
	}
	for _, category := range []*models.TransactionCategory{{CategoryId: 21, Uid: 0, Type: models.CATEGORY_TYPE_EXPENSE, Name: "退款", ParentCategoryId: 24}, {CategoryId: 22, Uid: 0, Name: "家庭内部", FamilyInternalTransfer: true}, {CategoryId: 23, Uid: 0, Type: models.CATEGORY_TYPE_INCOME, Name: "工资"}, {CategoryId: 24, Uid: 0, Type: models.CATEGORY_TYPE_EXPENSE, Name: "消费", Icon: 5}, {CategoryId: 25, Uid: 0, Type: models.CATEGORY_TYPE_EXPENSE, Name: "旧分类", ParentCategoryId: 24}} {
		_, err := db.NewSession(nil).Insert(category)
		require.NoError(t, err)
	}
	start, end, err := familyMonthRange("2026-11")
	require.NoError(t, err)
	stamp := utils.GetMinTransactionTimeFromUnixTime(start.Unix())
	rows := []*models.Transaction{
		{TransactionId: 1, Uid: 1, AccountId: 11, CategoryId: 23, Type: models.TRANSACTION_DB_TYPE_INCOME, Amount: 10000, TransactionTime: stamp},
		{TransactionId: 2, Uid: 2, AccountId: 12, CategoryId: 21, Type: models.TRANSACTION_DB_TYPE_EXPENSE, Amount: -500, TransactionTime: stamp},
		{TransactionId: 3, Uid: 2, AccountId: 13, CategoryId: 21, Type: models.TRANSACTION_DB_TYPE_EXPENSE, Amount: 999999, TransactionTime: stamp},
		{TransactionId: 4, Uid: 2, AccountId: 12, CategoryId: 22, Type: models.TRANSACTION_DB_TYPE_EXPENSE, Amount: 888888, TransactionTime: stamp},
		{TransactionId: 5, Uid: 3, AccountId: 14, CategoryId: 21, Type: models.TRANSACTION_DB_TYPE_INCOME, Amount: 777777, TransactionTime: stamp},
		{TransactionId: 6, Uid: 4, AccountId: 15, CategoryId: 21, Type: models.TRANSACTION_DB_TYPE_INCOME, Amount: 777777, TransactionTime: stamp},
		{TransactionId: 7, Uid: 1, AccountId: 11, CategoryId: 21, Type: models.TRANSACTION_DB_TYPE_MODIFY_BALANCE, Amount: 666666, TransactionTime: stamp},
		{TransactionId: 8, Uid: 1, AccountId: 11, CategoryId: 21, Type: models.TRANSACTION_DB_TYPE_TRANSFER_OUT, Amount: 666666, TransactionTime: stamp},
		{TransactionId: 9, Uid: 1, AccountId: 11, CategoryId: 23, Type: models.TRANSACTION_DB_TYPE_INCOME, Amount: 900, TransactionTime: stamp - 1},
		{TransactionId: 10, Uid: 1, AccountId: 11, CategoryId: 23, Type: models.TRANSACTION_DB_TYPE_INCOME, Amount: 900, TransactionTime: utils.GetMinTransactionTimeFromUnixTime(end.Unix())},
	}
	for _, row := range rows {
		if row.TransactionId > 2 && row.TransactionId < 9 {
			row.TransactionTime += row.TransactionId
		}
	}
	_, err = db.NewSession(nil).Insert(rows)
	require.NoError(t, err)
	overview, err := Family.Overview(nil, 1, "2026-11")
	require.NoError(t, err)
	require.Equal(t, models.FamilyAmounts{IncomeMinor: "10000", ExpenseMinor: "-500", NetMinor: "10500"}, overview.Totals)
	require.Len(t, overview.Members, 2)
	require.Len(t, overview.Accounts, 3)
	require.Len(t, overview.Trend, 12)
	require.Equal(t, "900", overview.Trend[10].IncomeMinor)
	require.Equal(t, "-500", overview.Categories[0].ExpenseMinor)
	require.Equal(t, 1, overview.ExcludedForeignTransactions)
	require.True(t, overview.HasExcludedForeignCurrency)
	report, err := Family.Report(nil, 1, "2026-11", false, 0)
	require.NoError(t, err)
	require.Equal(t, "10000", report.IncomeMinor)
	require.Equal(t, "-500", report.ExpenseMinor)
	require.Equal(t, "10500", report.NetMinor)
	require.Equal(t, "-17", report.AverageExpenseMinor)
	require.Equal(t, "-500", report.Categories[0].AmountMinor)
	require.Equal(t, &models.FamilyReportCategoryParent{Id: 24, Name: "消费", Icon: 5}, report.Categories[0].Parent)
	require.Equal(t, "900", report.Categories[1].PreviousAmountMinor)
	require.NotEmpty(t, report.Days)
	require.True(t, report.ReadOnly)
	memberReport, err := Family.Report(nil, 1, "2026-11", false, 2)
	require.NoError(t, err)
	require.Equal(t, "0", memberReport.IncomeMinor)
	require.Equal(t, "-500", memberReport.ExpenseMinor)
	detail, err := Family.ReportTransactions(nil, 1, "2026-11", false, 2, 21, 0, "2026-11-01", "", 50)
	require.NoError(t, err)
	require.Len(t, detail.Items, 1)
	require.Equal(t, int64(2), detail.Items[0].Id)
	require.Equal(t, int64(-500), detail.Items[0].AmountMinor)
	annual, err := Family.Report(nil, 1, "2026", true, 0)
	require.NoError(t, err)
	require.Len(t, annual.Months, 3)
	require.Equal(t, "2026-11", annual.Months[1].Month)
	require.Equal(t, "-500", annual.Months[1].ExpenseMinor)
	require.Equal(t, "-167", annual.AverageExpenseMinor)
	yearlyPage, err := Family.TransactionsForPeriod(nil, 1, "2026", true, 0, "", 2)
	require.NoError(t, err)
	require.Len(t, yearlyPage.Items, 2)
	require.Equal(t, int64(10), yearlyPage.Items[0].Id)
	_, err = Family.TransactionsForPeriod(nil, 1, "2026", true, 2, yearlyPage.NextCursor, 2)
	require.Error(t, err)
	yearlyRest, err := Family.TransactionsForPeriod(nil, 1, "2026", true, 0, yearlyPage.NextCursor, 20)
	require.NoError(t, err)
	require.Len(t, yearlyRest.Items, 6)
	require.Equal(t, int64(9), yearlyRest.Items[len(yearlyRest.Items)-1].Id)
	_, err = db.NewSession(nil).Insert(&models.Transaction{TransactionId: 101, Uid: 2, AccountId: 12, CategoryId: 25, Type: models.TRANSACTION_DB_TYPE_EXPENSE, Amount: 100, TransactionTime: stamp - 1})
	require.NoError(t, err)
	priorOnly, err := Family.Report(nil, 2, "2026-11", false, 2)
	require.NoError(t, err)
	require.Len(t, priorOnly.PriorOnlyCategories, 1)
	require.Equal(t, int64(25), priorOnly.PriorOnlyCategories[0].Id)
	require.Equal(t, "0", priorOnly.PriorOnlyCategories[0].AmountMinor)
	require.Equal(t, "100", priorOnly.PriorOnlyCategories[0].PreviousAmountMinor)

	daylightTime, err := time.Parse(time.RFC3339, "1991-06-09T15:30:00Z")
	require.NoError(t, err)
	_, err = db.NewSession(nil).Insert(&models.Transaction{TransactionId: 100, Uid: 1, AccountId: 11, CategoryId: 23, Type: models.TRANSACTION_DB_TYPE_INCOME, Amount: 100, TransactionTime: utils.GetMinTransactionTimeFromUnixTime(daylightTime.Unix())})
	require.NoError(t, err)
	historical, err := Family.Report(nil, 1, "1991", true, 0)
	require.NoError(t, err)
	require.Len(t, historical.Days, 1)
	require.Equal(t, "1991-06-10", historical.Days[0].Date)
	historicalDetail, err := Family.ReportTransactions(nil, 1, "1991", true, 0, 0, 0, "1991-06-10", "", 50)
	require.NoError(t, err)
	require.Len(t, historicalDetail.Items, 1)
	boundCursor := base64.RawURLEncoding.EncodeToString([]byte(strings.Join([]string{"2026-11", "false", "1", "0", "0", "", "0", "1"}, "\x1f")))
	_, err = Family.ReportTransactions(nil, 1, "2026-11", false, 2, 0, 0, "", boundCursor, 1)
	require.Error(t, err, "a report cursor must not be reusable for another member scope")
	for _, uid := range []int64{0, 3, 4, 999} {
		_, err := Family.Overview(nil, uid, "2026-11")
		require.Error(t, err)
	}
	var gotIDs []int64
	cursor := ""
	for {
		page, err := Family.Transactions(nil, 1, "2026-11", 0, cursor, 2)
		require.NoError(t, err)
		for _, item := range page.Items {
			gotIDs = append(gotIDs, item.Id)
			require.Equal(t, item.OwnerId == 1, item.Editable)
		}
		cursor = page.NextCursor
		if cursor == "" {
			break
		}
	}
	require.Equal(t, []int64{8, 7, 4, 3, 2, 1}, gotIDs)
	filtered, err := Family.Transactions(nil, 1, "2026-11", 2, "", 100)
	require.NoError(t, err)
	require.Len(t, filtered.Items, 3)
	// Shared reads do not broaden existing personal writes.
	require.Error(t, Transactions.DeleteTransaction(nil, 1, 2))
	require.Error(t, Accounts.HideAccount(nil, 1, []int64{12}, true))
	otherAccount, err := Accounts.GetAccountByAccountId(nil, 2, 12)
	require.NoError(t, err)
	require.False(t, otherAccount.Hidden)
	otherTransaction, err := Transactions.GetTransactionByTransactionId(nil, 2, 2)
	require.NoError(t, err)
	require.Equal(t, int64(-500), otherTransaction.Amount)
	_, err = Family.Transactions(nil, 1, "2026-11", 3, "", 100)
	require.Error(t, err)
	for _, picture := range []*models.TransactionPictureInfo{{PictureId: 100, Uid: 2, TransactionId: 0}, {PictureId: 101, Uid: 2, TransactionId: 1}, {PictureId: 102, Uid: 3, TransactionId: 5}, {PictureId: 103, Uid: 2, TransactionId: 999}} {
		_, err := db.NewSession(nil).Insert(picture)
		require.NoError(t, err)
		_, err = Family.Picture(nil, 1, picture.PictureId, "png")
		require.Error(t, err, "orphan, mismatched owner, disabled owner and missing transaction must all reject")
	}
	_, err = db.NewSession(nil).Insert(&models.TokenRecord{UserTokenId: 1, Uid: 2})
	require.NoError(t, err)
	require.NoError(t, Users.DisableUser(nil, "2"))
	count, err := db.NewSession(nil).Where("uid=?", 2).Count(&models.TokenRecord{})
	require.NoError(t, err)
	require.Zero(t, count)
	overview, err = Family.Overview(nil, 1, "2026-11")
	require.NoError(t, err)
	require.Len(t, overview.Members, 1)
	require.Equal(t, "0", overview.Totals.ExpenseMinor)
	_, err = Family.Transactions(nil, 2, "2026-11", 0, "", 50)
	require.Error(t, err)
}
