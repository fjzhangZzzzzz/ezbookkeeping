//go:build cgo

package api

import (
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/require"

	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/mayswind/ezbookkeeping/pkg/datastore"
	"github.com/mayswind/ezbookkeeping/pkg/errs"
	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/mayswind/ezbookkeeping/pkg/settings"
)

func TestRemovingDefaultAccountPreservesUserProfile(t *testing.T) {
	profile := setupAccountRemovalTest(t, []*models.Account{
		{AccountId: 11, Uid: 1, Name: "Default", Currency: "CNY", Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT},
	})
	want := *profile()
	want.DefaultAccountId = 12

	result, err := Accounts.AccountDeleteHandler(accountRemovalRequest(`{"id":"11"}`))
	require.Nil(t, err)
	require.Equal(t, models.AccountDeleteActionDeleted, result.(*models.AccountDeleteResponse).Action)
	actual := profile()
	require.Equal(t, models.TRANSACTION_EDIT_SCOPE_ALL, actual.TransactionEditScope)
	require.Equal(t, want, *actual)
}

func TestRemovingDefaultSubAccountPreservesUserProfile(t *testing.T) {
	for _, test := range []struct {
		name       string
		hasSibling bool
	}{
		{name: "removal switches to fallback", hasSibling: true},
		{name: "rejected removal restores default"},
	} {
		t.Run(test.name, func(t *testing.T) {
			accounts := []*models.Account{
				{AccountId: 10, Uid: 1, Name: "Parent", Currency: "CNY", Type: models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS},
				{AccountId: 11, Uid: 1, Name: "Default", Currency: "CNY", Type: models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS, ParentAccountId: 10},
			}
			if test.hasSibling {
				accounts = append(accounts, &models.Account{AccountId: 13, Uid: 1, Name: "Sibling", Currency: "CNY", Type: models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS, ParentAccountId: 10})
			}
			profile := setupAccountRemovalTest(t, accounts)
			want := *profile()

			result, err := Accounts.SubAccountDeleteHandler(accountRemovalRequest(`{"id":"11"}`))
			if test.hasSibling {
				require.Nil(t, err)
				require.Equal(t, models.AccountDeleteActionDeleted, result.(*models.AccountDeleteResponse).Action)
				want.DefaultAccountId = 12
			} else {
				require.Equal(t, errs.ErrAccountHaveNoSubAccount, err)
			}

			actual := profile()
			require.Equal(t, models.TRANSACTION_EDIT_SCOPE_ALL, actual.TransactionEditScope)
			require.Equal(t, want, *actual)
		})
	}
}

func setupAccountRemovalTest(t *testing.T, accounts []*models.Account) func() *models.UserBasicInfo {
	t.Helper()
	config := &settings.Config{DatabaseConfig: &settings.DatabaseConfig{
		DatabaseType: settings.Sqlite3DbType,
		DatabasePath: filepath.Join(t.TempDir(), "account-removal.db"),
	}}
	previousStores := *datastore.Container
	t.Cleanup(func() { *datastore.Container = previousStores })
	require.NoError(t, datastore.InitializeDataStore(config))
	db := datastore.Container.UserDataStore.Choose(1)
	t.Cleanup(func() { require.NoError(t, db.NewSession(nil).(*datastore.XOrmSession).Engine().Close()) })
	require.NoError(t, db.SyncStructs(&models.User{}, &models.Account{}, &models.Transaction{}, &models.TransactionTemplate{}))
	_, err := db.NewSession(nil).Insert(&models.User{
		Uid:                  1,
		Username:             "account-owner",
		Email:                "account-owner@example.invalid",
		DefaultAccountId:     11,
		DefaultCurrency:      "CNY",
		TransactionEditScope: models.TRANSACTION_EDIT_SCOPE_ALL,
		FirstDayOfWeek:       core.WEEKDAY_MONDAY,
		ExpenseAmountColor:   models.AMOUNT_COLOR_TYPE_BLUE,
	})
	require.NoError(t, err)
	accounts = append(accounts, &models.Account{AccountId: 12, Uid: 1, Name: "Fallback", Currency: "CNY", Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT})
	_, err = db.NewSession(nil).Insert(accounts)
	require.NoError(t, err)
	users := UsersApi{ApiWithUserInfo: *initializeAvatarProviderApiTest(t, config), users: Users.users}
	return func() *models.UserBasicInfo {
		t.Helper()
		result, err := users.UserProfileHandler(accountRemovalRequest(""))
		require.Nil(t, err)
		return result.(*models.UserProfileResponse).UserBasicInfo
	}
}

func accountRemovalRequest(body string) *core.WebContext {
	ctx, _ := gin.CreateTestContext(httptest.NewRecorder())
	if body != "" {
		ctx.Request = httptest.NewRequest(http.MethodPost, "/", strings.NewReader(body))
	}
	c := core.WrapWebContext(ctx, nil)
	c.SetTokenClaims(&core.UserTokenClaims{Type: core.USER_TOKEN_TYPE_NORMAL, Uid: 1})
	return c
}
