//go:build cgo

package services

import (
	"path/filepath"
	"sync"
	"testing"

	"github.com/mayswind/ezbookkeeping/pkg/datastore"
	"github.com/mayswind/ezbookkeeping/pkg/errs"
	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/mayswind/ezbookkeeping/pkg/settings"
	"github.com/mayswind/ezbookkeeping/pkg/utils"
	"github.com/mayswind/ezbookkeeping/pkg/uuid"
	"github.com/stretchr/testify/require"
)

func TestSharedDictionarySQLite(t *testing.T) {
	config := &settings.Config{DatabaseConfig: &settings.DatabaseConfig{DatabaseType: settings.Sqlite3DbType, DatabasePath: filepath.Join(t.TempDir(), "test.db")}}
	require.NoError(t, datastore.InitializeDataStore(config))
	db := datastore.Container.UserDataStore.Choose(0)
	t.Cleanup(func() { require.NoError(t, db.NewSession(nil).(*datastore.XOrmSession).Engine().Close()) })
	require.NoError(t, db.SyncStructs(&models.User{}, &models.Account{}, &models.TransactionCategory{}, &models.TransactionCategoryRevision{}, &models.TransactionTag{}, &models.TransactionTagGroup{}, &models.TransactionTagIndex{}, &models.Transaction{}, &models.TransactionTemplate{}, &models.TransactionPictureInfo{}))
	require.NoError(t, uuid.InitializeUuidGenerator(&settings.Config{UuidGeneratorType: settings.InternalUuidGeneratorType}))
	require.NoError(t, EnsureSharedDictionary(nil))
	initialRevision, err := TransactionCategories.GetCategoryRevision(nil, 1)
	require.NoError(t, err)
	require.NoError(t, EnsureSharedDictionary(nil))
	unchangedRevision, err := TransactionCategories.GetCategoryRevision(nil, 1)
	require.NoError(t, err)
	require.Equal(t, initialRevision, unchangedRevision)
	var wg sync.WaitGroup
	results := make(chan error, 2)
	for _, name := range []string{"concurrent-one", "concurrent-two"} {
		wg.Add(1)
		go func(name string) {
			defer wg.Done()
			results <- TransactionCategories.CreateCategory(nil, &models.TransactionCategory{Uid: 1, Type: models.CATEGORY_TYPE_EXPENSE, Name: name, Color: "000000"}, initialRevision)
		}(name)
	}
	wg.Wait()
	close(results)
	successes, conflicts := 0, 0
	for err := range results {
		if err == nil {
			successes++
		} else if err == errs.ErrTransactionCategoryRevisionConflict {
			conflicts++
		} else {
			require.NoError(t, err)
		}
	}
	require.Equal(t, 1, successes)
	require.Equal(t, 1, conflicts)
	afterConcurrent, err := TransactionCategories.GetCategoryRevision(nil, 1)
	require.NoError(t, err)
	require.Equal(t, initialRevision+1, afterConcurrent)
	var systemCategories []*models.TransactionCategory
	require.NoError(t, db.NewSession(nil).Where("family_internal_transfer=?", true).Find(&systemCategories))
	require.Len(t, systemCategories, 2)
	protected := systemCategories[0]

	parent := &models.TransactionCategory{CategoryId: 10, Uid: models.SharedDictionaryOwnerUID, Type: models.CATEGORY_TYPE_EXPENSE, Name: "parent", Color: "000000"}
	child := &models.TransactionCategory{CategoryId: 11, Uid: models.SharedDictionaryOwnerUID, Type: models.CATEGORY_TYPE_EXPENSE, ParentCategoryId: 10, Name: "child", Color: "000000"}
	tag := &models.TransactionTag{TagId: 20, Uid: models.SharedDictionaryOwnerUID, Name: "shared"}
	_, err = db.NewSession(nil).Insert(parent, child, tag)
	require.NoError(t, err)
	before, err := TransactionCategories.GetCategoryRevision(nil, 1)
	require.NoError(t, err)
	wrongChild := &models.TransactionCategory{Uid: 1, Type: models.CATEGORY_TYPE_INCOME, ParentCategoryId: parent.CategoryId, Name: "wrong", Color: "000000"}
	require.ErrorIs(t, TransactionCategories.CreateCategory(nil, wrongChild, before), errs.ErrTransactionCategoryTypeInvalid)
	wrongParent := &models.TransactionCategory{Uid: 1, Type: models.CATEGORY_TYPE_EXPENSE, Name: "batch parent", Color: "000000"}
	wrongBatchChild := &models.TransactionCategory{Uid: 1, Type: models.CATEGORY_TYPE_INCOME, Name: "batch child", Color: "000000"}
	_, err = TransactionCategories.CreateCategories(nil, 1, map[*models.TransactionCategory][]*models.TransactionCategory{nil: {wrongParent}, wrongParent: {wrongBatchChild}}, before)
	require.ErrorIs(t, err, errs.ErrTransactionCategoryTypeInvalid)
	_, err = TransactionCategories.GetCategoryByCategoryId(nil, 1, wrongParent.CategoryId)
	require.ErrorIs(t, err, errs.ErrTransactionCategoryNotFound)
	incomeParent := &models.TransactionCategory{CategoryId: 12, Uid: models.SharedDictionaryOwnerUID, Type: models.CATEGORY_TYPE_INCOME, Name: "income", Color: "000000"}
	_, err = db.NewSession(nil).Insert(incomeParent)
	require.NoError(t, err)
	movedChild := *child
	movedChild.Uid = 1
	movedChild.ParentCategoryId = incomeParent.CategoryId
	require.ErrorIs(t, TransactionCategories.ModifyCategory(nil, &movedChild, before), errs.ErrTransactionCategoryTypeInvalid)
	unchanged, err := TransactionCategories.GetCategoryRevision(nil, 1)
	require.NoError(t, err)
	require.Equal(t, before, unchanged)
	stale := before - 1
	require.ErrorIs(t, TransactionCategories.CreateCategory(nil, &models.TransactionCategory{Uid: 1, Color: "000000"}, 0), errs.ErrTransactionCategoryRevisionRequired)
	_, err = TransactionCategories.CreateCategories(nil, 1, map[*models.TransactionCategory][]*models.TransactionCategory{}, stale)
	require.ErrorIs(t, err, errs.ErrTransactionCategoryRevisionConflict)
	require.ErrorIs(t, TransactionCategories.HideCategory(nil, 1, []int64{parent.CategoryId}, true, stale), errs.ErrTransactionCategoryRevisionConflict)
	require.ErrorIs(t, TransactionCategories.ModifyCategoryDisplayOrders(nil, 1, []*models.TransactionCategory{{CategoryId: parent.CategoryId}}, stale), errs.ErrTransactionCategoryRevisionConflict)
	require.ErrorIs(t, TransactionCategories.DeleteCategory(nil, 1, child.CategoryId, stale), errs.ErrTransactionCategoryRevisionConflict)
	afterStale, err := TransactionCategories.GetCategoryRevision(nil, 1)
	require.NoError(t, err)
	require.Equal(t, before, afterStale)
	require.NoError(t, TransactionCategories.HideCategory(nil, 1, []int64{parent.CategoryId}, true, before))
	require.NoError(t, TransactionCategories.HideCategory(nil, 1, []int64{parent.CategoryId}, false, before+1))
	require.NoError(t, TransactionCategories.ModifyCategoryDisplayOrders(nil, 1, []*models.TransactionCategory{{CategoryId: parent.CategoryId, DisplayOrder: 5}}, before+2))

	for _, uid := range []int64{1, 2} {
		got, err := TransactionCategories.GetCategoryByCategoryId(nil, uid, child.CategoryId)
		require.NoError(t, err)
		require.Equal(t, child.Name, got.Name)
		_, err = TransactionTags.GetTagByTagId(nil, uid, tag.TagId)
		require.NoError(t, err)
	}
	_, err = TransactionCategories.GetCategoryByCategoryId(nil, 0, child.CategoryId)
	require.Error(t, err)
	created := &models.TransactionCategory{Uid: 1, Type: models.CATEGORY_TYPE_EXPENSE, Name: "created", Color: "000000"}
	revision, err := TransactionCategories.GetCategoryRevision(nil, 1)
	require.NoError(t, err)
	require.NoError(t, TransactionCategories.CreateCategory(nil, created, revision))
	created.Uid = 2
	created.Name = "renamed"
	revision, err = TransactionCategories.GetCategoryRevision(nil, 1)
	require.NoError(t, err)
	require.NoError(t, TransactionCategories.ModifyCategory(nil, created, revision))
	afterRevision, err := TransactionCategories.GetCategoryRevision(nil, 1)
	require.NoError(t, err)
	require.Equal(t, revision+1, afterRevision)
	created.Name = "stale"
	require.ErrorIs(t, TransactionCategories.ModifyCategory(nil, created, revision), errs.ErrTransactionCategoryRevisionConflict)
	actualRevision, err := TransactionCategories.GetCategoryRevision(nil, 1)
	require.NoError(t, err)
	require.Equal(t, afterRevision, actualRevision)
	sharedCreated, err := TransactionCategories.GetCategoryByCategoryId(nil, 1, created.CategoryId)
	require.NoError(t, err)
	require.Equal(t, "renamed", sharedCreated.Name)
	require.EqualValues(t, models.SharedDictionaryOwnerUID, sharedCreated.Uid)
	createdTag := &models.TransactionTag{Uid: 1, Name: "created-tag"}
	require.NoError(t, TransactionTags.CreateTag(nil, createdTag))
	createdTag.Uid = 2
	createdTag.Name = "renamed-tag"
	require.NoError(t, TransactionTags.ModifyTag(nil, createdTag, true))
	sharedTag, err := TransactionTags.GetTagByTagId(nil, 1, createdTag.TagId)
	require.NoError(t, err)
	require.Equal(t, "renamed-tag", sharedTag.Name)
	group := &models.TransactionTagGroup{Uid: 1, Name: "group"}
	require.NoError(t, TransactionTagGroups.CreateTagGroup(nil, group))
	createdTag.TagGroupId = group.TagGroupId
	require.NoError(t, TransactionTags.ModifyTag(nil, createdTag, false))
	require.NoError(t, TransactionTagGroups.HideTagGroup(nil, 2, group.TagGroupId, true))
	require.Error(t, Transactions.isTagsValid(db.NewSession(nil), 1, []*models.TransactionTagIndex{{TagId: createdTag.TagId}}, []int64{createdTag.TagId}))
	require.NoError(t, TransactionTagGroups.HideTagGroup(nil, 1, group.TagGroupId, false))
	require.NoError(t, Transactions.isTagsValid(db.NewSession(nil), 1, []*models.TransactionTagIndex{{TagId: createdTag.TagId}}, []int64{createdTag.TagId}))
	require.Error(t, TransactionCategories.HideCategory(nil, 1, []int64{protected.ParentCategoryId}, true, revision+1))
	require.Error(t, TransactionCategories.DeleteCategory(nil, 1, protected.CategoryId, revision+1))

	account := &models.Account{AccountId: 40, Uid: 2, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Name: "account"}
	_, err = db.NewSession(nil).Insert(account)
	require.NoError(t, err)
	transaction := &models.Transaction{Uid: 2, AccountId: account.AccountId, CategoryId: child.CategoryId, Type: models.TRANSACTION_DB_TYPE_EXPENSE, TransactionTime: 1000000, Amount: 125}
	require.NoError(t, Transactions.isCategoryValid(db.NewSession(nil), transaction))
	require.NoError(t, Transactions.isTagsValid(db.NewSession(nil), 2, []*models.TransactionTagIndex{{TagId: tag.TagId}}, []int64{tag.TagId}))
	require.NoError(t, Transactions.CreateTransaction(nil, transaction, []int64{tag.TagId}, nil))
	_, err = db.NewSession(nil).Insert(
		&models.User{Uid: 1, Username: "one", Email: "one@example.invalid"},
		&models.User{Uid: 2, Username: "two", Email: "two@example.invalid"},
		&models.User{Uid: 3, Username: "disabled", Email: "disabled@example.invalid", Disabled: true},
	)
	require.NoError(t, err)
	_, err = db.NewSession(nil).Insert(&models.Transaction{TransactionId: 999, Uid: 3, CategoryId: child.CategoryId, TransactionTime: 999999})
	require.NoError(t, err)
	_, err = db.NewSession(nil).Insert(
		&models.Transaction{TransactionId: 1000, Uid: 2, CategoryId: child.CategoryId, Type: models.TRANSACTION_DB_TYPE_TRANSFER_OUT, TransactionTime: 1000001},
		&models.Transaction{TransactionId: 1001, Uid: 2, CategoryId: child.CategoryId, Type: models.TRANSACTION_DB_TYPE_TRANSFER_IN, TransactionTime: 1000002},
	)
	require.NoError(t, err)

	impactRevision, count, err := TransactionCategories.CategoryImpact(nil, 1, parent.CategoryId)
	require.NoError(t, err)
	require.Equal(t, revision+1, impactRevision)
	require.EqualValues(t, 2, count)
	_, _, err = TransactionCategories.CategoryImpact(nil, 3, parent.CategoryId)
	require.ErrorIs(t, err, errs.ErrNotPermittedToPerformThisAction)
	require.Error(t, TransactionCategories.DeleteCategory(nil, 1, child.CategoryId, revision+1))
	require.Error(t, TransactionTags.DeleteTag(nil, 1, tag.TagId))
	validTemplate := &models.TransactionTemplate{Uid: 2, TemplateType: models.TRANSACTION_TEMPLATE_TYPE_NORMAL, Type: models.TRANSACTION_TYPE_EXPENSE, CategoryId: child.CategoryId, AccountId: account.AccountId, TagIds: "20"}
	require.NoError(t, TransactionTemplates.isTemplateValid(db.NewSession(nil), validTemplate))
	require.NoError(t, TransactionTemplates.CreateTemplate(nil, validTemplate))
	require.NoError(t, TransactionTagGroups.HideTagGroup(nil, 1, group.TagGroupId, true))
	validTemplate.TagIds = utils.Int64ToString(createdTag.TagId)
	require.Error(t, TransactionTemplates.isTemplateValid(db.NewSession(nil), validTemplate))

	template := &models.TransactionTemplate{TemplateId: 30, Uid: 2, TemplateType: models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE, CategoryId: child.CategoryId, TagIds: "20"}
	_, err = db.NewSession(nil).Insert(template)
	require.NoError(t, err)
	require.Error(t, TransactionCategories.DeleteCategory(nil, 1, child.CategoryId, revision+1))
	require.Error(t, TransactionTags.DeleteTag(nil, 1, tag.TagId))

	require.NoError(t, TransactionTemplates.DeleteAllTemplates(nil, 2))
	require.NoError(t, Transactions.DeleteAllTransactions(nil, 2, false))
	_, err = TransactionCategories.GetCategoryByCategoryId(nil, 1, child.CategoryId)
	require.NoError(t, err)
	_, err = TransactionTags.GetTagByTagId(nil, 1, tag.TagId)
	require.NoError(t, err)
}

func TestSharedDictionaryRenamesLegacyInternalCategories(t *testing.T) {
	config := &settings.Config{DatabaseConfig: &settings.DatabaseConfig{DatabaseType: settings.Sqlite3DbType, DatabasePath: filepath.Join(t.TempDir(), "test.db")}}
	require.NoError(t, datastore.InitializeDataStore(config))
	db := datastore.Container.UserDataStore.Choose(0)
	t.Cleanup(func() { require.NoError(t, db.NewSession(nil).(*datastore.XOrmSession).Engine().Close()) })
	require.NoError(t, db.SyncStructs(&models.TransactionCategory{}, &models.TransactionCategoryRevision{}))
	require.NoError(t, uuid.InitializeUuidGenerator(&settings.Config{UuidGeneratorType: settings.InternalUuidGeneratorType}))
	require.NoError(t, EnsureSharedDictionary(nil))

	var internalCategories []*models.TransactionCategory
	require.NoError(t, db.NewSession(nil).Where("uid=? AND deleted=? AND family_internal_transfer=?", models.SharedDictionaryOwnerUID, false, true).Find(&internalCategories))
	require.Len(t, internalCategories, 2)
	ids := make(map[models.TransactionCategoryType]int64, len(internalCategories))
	for _, category := range internalCategories {
		ids[category.Type] = category.CategoryId
		legacyName := "家庭内部转入"
		if category.Type == models.CATEGORY_TYPE_EXPENSE {
			legacyName = "家庭内部转出"
		}
		_, err := db.NewSession(nil).ID(category.CategoryId).Cols("name").Update(&models.TransactionCategory{Name: legacyName})
		require.NoError(t, err)
		_, err = db.NewSession(nil).ID(category.ParentCategoryId).Cols("name").Update(&models.TransactionCategory{Name: "家庭内部往来"})
		require.NoError(t, err)
	}

	before, err := TransactionCategories.GetCategoryRevision(nil, 1)
	require.NoError(t, err)
	require.NoError(t, EnsureSharedDictionary(nil))
	after, err := TransactionCategories.GetCategoryRevision(nil, 1)
	require.NoError(t, err)
	require.Equal(t, before+1, after)

	var migrated []*models.TransactionCategory
	require.NoError(t, db.NewSession(nil).Where("uid=? AND deleted=? AND family_internal_transfer=?", models.SharedDictionaryOwnerUID, false, true).Find(&migrated))
	require.Len(t, migrated, 2)
	for _, category := range migrated {
		require.Equal(t, ids[category.Type], category.CategoryId)
		require.True(t, category.FamilyInternalTransfer)
		if category.Type == models.CATEGORY_TYPE_INCOME {
			require.Equal(t, "家庭转入", category.Name)
		} else {
			require.Equal(t, "家庭转出", category.Name)
		}
		parent := &models.TransactionCategory{}
		found, err := db.NewSession(nil).ID(category.ParentCategoryId).Get(parent)
		require.NoError(t, err)
		require.True(t, found)
		require.Equal(t, "家庭往来", parent.Name)
	}

	require.NoError(t, EnsureSharedDictionary(nil))
	unchanged, err := TransactionCategories.GetCategoryRevision(nil, 1)
	require.NoError(t, err)
	require.Equal(t, after, unchanged)

	income := &models.TransactionCategory{}
	found, err := db.NewSession(nil).Where("uid=? AND deleted=? AND family_internal_transfer=? AND type=?", models.SharedDictionaryOwnerUID, false, true, models.CATEGORY_TYPE_INCOME).Get(income)
	require.NoError(t, err)
	require.True(t, found)
	_, err = db.NewSession(nil).ID(income.CategoryId).Cols("name").Update(&models.TransactionCategory{Name: "自定义转入"})
	require.NoError(t, err)
	require.NoError(t, EnsureSharedDictionary(nil))
	unchangedCustom, err := TransactionCategories.GetCategoryRevision(nil, 1)
	require.NoError(t, err)
	require.Equal(t, unchanged, unchangedCustom)
	gotCustom := &models.TransactionCategory{}
	found, err = db.NewSession(nil).ID(income.CategoryId).Get(gotCustom)
	require.NoError(t, err)
	require.True(t, found)
	require.Equal(t, "自定义转入", gotCustom.Name)
}
