//go:build cgo

package services

import (
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/mayswind/ezbookkeeping/pkg/datastore"
	"github.com/mayswind/ezbookkeeping/pkg/errs"
	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/mayswind/ezbookkeeping/pkg/settings"
	"github.com/mayswind/ezbookkeeping/pkg/utils"
	"github.com/mayswind/ezbookkeeping/pkg/uuid"
	"github.com/stretchr/testify/require"
)

func TestTransactionCategoryRevisionSQLite(t *testing.T) {
	config := &settings.Config{DatabaseConfig: &settings.DatabaseConfig{DatabaseType: settings.Sqlite3DbType, DatabasePath: filepath.Join(t.TempDir(), "ledger.db")}}
	require.NoError(t, datastore.InitializeDataStore(config))
	db := datastore.Container.UserDataStore.Choose(0)
	t.Cleanup(func() { require.NoError(t, db.NewSession(nil).(*datastore.XOrmSession).Engine().Close()) })
	require.NoError(t, db.SyncStructs(&models.Account{}, &models.TransactionCategory{}, &models.TransactionCategoryRevision{}, &models.Transaction{}, &models.TransactionRequest{}, &models.TransactionTagIndex{}, &models.TransactionPictureInfo{}))
	require.NoError(t, uuid.InitializeUuidGenerator(&settings.Config{UuidGeneratorType: settings.InternalUuidGeneratorType}))
	_, err := db.NewSession(nil).Insert(&models.Account{AccountId: 1, Uid: 1, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Currency: "CNY"}, &models.TransactionCategory{CategoryId: 10, Uid: 0, Type: models.CATEGORY_TYPE_EXPENSE, Name: "parent", Color: "000000"}, &models.TransactionCategory{CategoryId: 11, Uid: 0, Type: models.CATEGORY_TYPE_EXPENSE, ParentCategoryId: 10, Name: "child", Color: "000000"}, &models.TransactionCategoryRevision{Id: 1, Revision: 1})
	require.NoError(t, err)
	revision := int64(1)
	makeTransaction := func(requestID string, revision *int64) *models.Transaction {
		transaction := &models.Transaction{Uid: 1, AccountId: 1, CategoryId: 11, Type: models.TRANSACTION_DB_TYPE_EXPENSE, Amount: 100, TransactionTime: utils.GetMinTransactionTimeFromUnixTime(time.Now().Unix()), ExpectedCategoryRevision: revision}
		if requestID != "" {
			transaction.ClientRequest = &models.TransactionRequest{Uid: 1, RequestId: requestID, Fingerprint: "stable"}
		}
		return transaction
	}
	original := makeTransaction("request", &revision)
	require.NoError(t, Transactions.CreateTransaction(nil, original, nil, nil))
	changed := &models.TransactionCategory{CategoryId: 11, Uid: 1, Type: models.CATEGORY_TYPE_EXPENSE, ParentCategoryId: 10, Name: "renamed", Color: "000000"}
	require.NoError(t, TransactionCategories.ModifyCategory(nil, changed, 1))
	replay := makeTransaction("request", &revision)
	require.NoError(t, Transactions.CreateTransaction(nil, replay, nil, nil))
	require.Equal(t, original.TransactionId, replay.TransactionId)
	require.ErrorIs(t, Transactions.CreateTransaction(nil, makeTransaction("new-request", &revision), nil, nil), errs.ErrTransactionCategoryRevisionConflict)
	update := *original
	update.Amount = 200
	update.ExpectedCategoryRevision = &revision
	require.ErrorIs(t, Transactions.ModifyTransaction(nil, &update, false, 0, nil, nil, nil, nil), errs.ErrTransactionCategoryRevisionConflict)
	account, err := Accounts.GetAccountByAccountId(nil, 1, 1)
	require.NoError(t, err)
	require.EqualValues(t, -100, account.Balance)
	// Old clients omit the field and can still edit their existing transaction.
	update.ExpectedCategoryRevision = nil
	require.NoError(t, Transactions.ModifyTransaction(nil, &update, false, 0, nil, nil, nil, nil))
	account, err = Accounts.GetAccountByAccountId(nil, 1, 1)
	require.NoError(t, err)
	require.EqualValues(t, -200, account.Balance)
	current := int64(2)
	contender := makeTransaction("", &current)
	changed.Name = "concurrent"
	var categoryErr, transactionErr error
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); categoryErr = TransactionCategories.ModifyCategory(nil, changed, current) }()
	go func() { defer wg.Done(); transactionErr = Transactions.CreateTransaction(nil, contender, nil, nil) }()
	wg.Wait()
	require.NoError(t, categoryErr)
	if transactionErr != nil {
		require.ErrorIs(t, transactionErr, errs.ErrTransactionCategoryRevisionConflict)
	}
	count, err := db.NewSession(nil).Where("uid=? AND deleted=?", 1, false).Count(&models.Transaction{})
	require.NoError(t, err)
	if transactionErr == nil {
		require.EqualValues(t, 2, count)
	} else {
		require.EqualValues(t, 1, count)
	}
}
