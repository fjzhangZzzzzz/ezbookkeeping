//go:build cgo

package services

import (
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/mayswind/ezbookkeeping/pkg/datastore"
	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/mayswind/ezbookkeeping/pkg/settings"
	"github.com/mayswind/ezbookkeeping/pkg/utils"
	"github.com/mayswind/ezbookkeeping/pkg/uuid"
	"github.com/stretchr/testify/require"
)

func TestTransactionRequestDurableConcurrentSQLiteQA(t *testing.T) {
	config := &settings.Config{DatabaseConfig: &settings.DatabaseConfig{DatabaseType: settings.Sqlite3DbType, DatabasePath: filepath.Join(t.TempDir(), "requests.db")}}
	require.NoError(t, datastore.InitializeDataStore(config))
	db := datastore.Container.UserDataStore.Choose(0)
	t.Cleanup(func() { require.NoError(t, db.NewSession(nil).(*datastore.XOrmSession).Engine().Close()) })
	require.NoError(t, db.SyncStructs(&models.Account{}, &models.TransactionCategory{}, &models.Transaction{}, &models.TransactionRequest{}, &models.TransactionTagIndex{}, &models.TransactionPictureInfo{}))
	require.NoError(t, uuid.InitializeUuidGenerator(&settings.Config{UuidGeneratorType: settings.InternalUuidGeneratorType}))
	_, err := db.NewSession(nil).Insert(&models.Account{AccountId: 1, Uid: 1, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Currency: "CNY"}, &models.Account{AccountId: 2, Uid: 2, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Currency: "CNY"})
	require.NoError(t, err)
	_, err = db.NewSession(nil).Insert(&models.TransactionCategory{CategoryId: 10, Uid: 0, Type: models.CATEGORY_TYPE_EXPENSE}, &models.TransactionCategory{CategoryId: 11, Uid: 0, ParentCategoryId: 10, Type: models.CATEGORY_TYPE_EXPENSE})
	require.NoError(t, err)
	newRequest := func(uid int64, fingerprint string) *models.Transaction {
		return &models.Transaction{Uid: uid, AccountId: uid, CategoryId: 11, Type: models.TRANSACTION_DB_TYPE_EXPENSE, Amount: 1234, TransactionTime: utils.GetMinTransactionTimeFromUnixTime(time.Now().Unix()), ClientRequest: &models.TransactionRequest{Uid: uid, RequestId: "same-request-key", Fingerprint: fingerprint}}
	}
	const count = 8
	transactions := make([]*models.Transaction, count)
	errors := make([]error, count)
	var workers sync.WaitGroup
	for i := range transactions {
		transactions[i] = newRequest(1, "same-content")
		workers.Add(1)
		go func(i int) {
			defer workers.Done()
			errors[i] = Transactions.CreateTransaction(nil, transactions[i], nil, nil)
		}(i)
	}
	workers.Wait()
	for i := range transactions {
		require.NoError(t, errors[i])
		require.Equal(t, transactions[0].TransactionId, transactions[i].TransactionId)
	}
	rowCount, err := db.NewSession(nil).Where("uid=?", 1).Count(&models.Transaction{})
	require.NoError(t, err)
	require.EqualValues(t, 1, rowCount)
	account, err := Accounts.GetAccountByAccountId(nil, 1, 1)
	require.NoError(t, err)
	require.EqualValues(t, -1234, account.Balance)
	require.Error(t, Transactions.CreateTransaction(nil, newRequest(1, "different-content"), nil, nil))
	otherMember := newRequest(2, "same-content")
	require.NoError(t, Transactions.CreateTransaction(nil, otherMember, nil, nil))
	require.NotEqual(t, transactions[0].TransactionId, otherMember.TransactionId)

	// Close every SQLite connection and recreate the service container: no in-memory
	// duplicate checker or existing connection can supply the replay result.
	require.NoError(t, db.NewSession(nil).(*datastore.XOrmSession).Engine().Close())
	require.NoError(t, datastore.InitializeDataStore(config))
	db = datastore.Container.UserDataStore.Choose(0)
	replayed := newRequest(1, "same-content")
	require.NoError(t, Transactions.CreateTransaction(nil, replayed, nil, nil))
	require.Equal(t, transactions[0].TransactionId, replayed.TransactionId)
	account, err = Accounts.GetAccountByAccountId(nil, 1, 1)
	require.NoError(t, err)
	require.EqualValues(t, -1234, account.Balance)
	require.NoError(t, Transactions.DeleteTransaction(nil, 1, replayed.TransactionId))
	require.Error(t, Transactions.CreateTransaction(nil, newRequest(1, "same-content"), nil, nil), "late retry must not resurrect a deleted entry")
	rowCount, err = db.NewSession(nil).Where("uid=? AND deleted=?", 1, false).Count(&models.Transaction{})
	require.NoError(t, err)
	require.Zero(t, rowCount)
}
