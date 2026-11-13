//go:build cgo

package services

import (
	"path/filepath"
	"testing"

	"github.com/mayswind/ezbookkeeping/pkg/datastore"
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
	require.NoError(t, db.SyncStructs(&models.Account{}, &models.TransactionCategory{}, &models.TransactionTag{}, &models.TransactionTagGroup{}, &models.TransactionTagIndex{}, &models.Transaction{}, &models.TransactionTemplate{}, &models.TransactionPictureInfo{}))
	require.NoError(t, uuid.InitializeUuidGenerator(&settings.Config{UuidGeneratorType: settings.InternalUuidGeneratorType}))
	require.NoError(t, EnsureSharedDictionary(nil))
	require.NoError(t, EnsureSharedDictionary(nil))
	var systemCategories []*models.TransactionCategory
	require.NoError(t, db.NewSession(nil).Where("family_internal_transfer=?", true).Find(&systemCategories))
	require.Len(t, systemCategories, 2)
	protected := systemCategories[0]

	parent := &models.TransactionCategory{CategoryId: 10, Uid: models.SharedDictionaryOwnerUID, Type: models.CATEGORY_TYPE_EXPENSE, Name: "parent", Color: "000000"}
	child := &models.TransactionCategory{CategoryId: 11, Uid: models.SharedDictionaryOwnerUID, Type: models.CATEGORY_TYPE_EXPENSE, ParentCategoryId: 10, Name: "child", Color: "000000"}
	tag := &models.TransactionTag{TagId: 20, Uid: models.SharedDictionaryOwnerUID, Name: "shared"}
	_, err := db.NewSession(nil).Insert(parent, child, tag)
	require.NoError(t, err)

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
	require.NoError(t, TransactionCategories.CreateCategory(nil, created))
	created.Uid = 2
	created.Name = "renamed"
	require.NoError(t, TransactionCategories.ModifyCategory(nil, created))
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
	require.Error(t, TransactionCategories.HideCategory(nil, 1, []int64{protected.ParentCategoryId}, true))
	require.Error(t, TransactionCategories.DeleteCategory(nil, 1, protected.CategoryId))

	account := &models.Account{AccountId: 40, Uid: 2, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Name: "account"}
	_, err = db.NewSession(nil).Insert(account)
	require.NoError(t, err)
	transaction := &models.Transaction{Uid: 2, AccountId: account.AccountId, CategoryId: child.CategoryId, Type: models.TRANSACTION_DB_TYPE_EXPENSE, TransactionTime: 1000000, Amount: 125}
	require.NoError(t, Transactions.isCategoryValid(db.NewSession(nil), transaction))
	require.NoError(t, Transactions.isTagsValid(db.NewSession(nil), 2, []*models.TransactionTagIndex{{TagId: tag.TagId}}, []int64{tag.TagId}))
	require.NoError(t, Transactions.CreateTransaction(nil, transaction, []int64{tag.TagId}, nil))
	require.Error(t, TransactionCategories.DeleteCategory(nil, 1, child.CategoryId))
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
	require.Error(t, TransactionCategories.DeleteCategory(nil, 1, child.CategoryId))
	require.Error(t, TransactionTags.DeleteTag(nil, 1, tag.TagId))

	require.NoError(t, TransactionTemplates.DeleteAllTemplates(nil, 2))
	require.NoError(t, Transactions.DeleteAllTransactions(nil, 2, false))
	_, err = TransactionCategories.GetCategoryByCategoryId(nil, 1, child.CategoryId)
	require.NoError(t, err)
	_, err = TransactionTags.GetTagByTagId(nil, 1, tag.TagId)
	require.NoError(t, err)
}
