package services

import (
	"sync"
	"time"

	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/mayswind/ezbookkeeping/pkg/datastore"
	"github.com/mayswind/ezbookkeeping/pkg/errs"
	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/mayswind/ezbookkeeping/pkg/uuid"
)

var sharedDictionaryInitializationLock sync.Mutex

// EnsureSharedDictionary creates the system internal-transfer categories once.
// Their semantic identity is stored on the child rather than inferred from its name.
func EnsureSharedDictionary(c core.Context) error {
	sharedDictionaryInitializationLock.Lock()
	defer sharedDictionaryInitializationLock.Unlock()

	return TransactionCategories.UserDataDB(models.SharedDictionaryOwnerUID).DoTransaction(c, func(sess datastore.DatabaseSession) error {
		for _, item := range []struct {
			typ  models.TransactionCategoryType
			name string
		}{
			{models.CATEGORY_TYPE_INCOME, "家庭内部转入"},
			{models.CATEGORY_TYPE_EXPENSE, "家庭内部转出"},
		} {
			count, err := sess.Where("uid=? AND deleted=? AND family_internal_transfer=? AND type=?", models.SharedDictionaryOwnerUID, false, true, item.typ).Count(&models.TransactionCategory{})
			if err != nil {
				return err
			} else if count > 1 {
				return errs.ErrOperationFailed
			} else if count == 1 {
				continue
			}
			ids := TransactionCategories.GenerateUuids(uuid.UUID_TYPE_CATEGORY, 2)
			if len(ids) != 2 || ids[0] <= 0 || ids[1] <= 0 {
				return errs.ErrSystemIsBusy
			}
			now := time.Now().Unix()
			parent := &models.TransactionCategory{CategoryId: ids[0], Uid: models.SharedDictionaryOwnerUID, Type: item.typ, Name: "家庭内部往来", DisplayOrder: 10000, Icon: 1, Color: "808080", CreatedUnixTime: now, UpdatedUnixTime: now}
			child := &models.TransactionCategory{CategoryId: ids[1], Uid: models.SharedDictionaryOwnerUID, Type: item.typ, ParentCategoryId: ids[0], Name: item.name, Icon: 1, Color: "808080", FamilyInternalTransfer: true, CreatedUnixTime: now, UpdatedUnixTime: now}
			if _, err := sess.Insert(parent, child); err != nil {
				return err
			}
		}
		// A fresh instance needs an ordinary default. Internal transfers must
		// always be an explicit choice rather than the first available expense.
		for _, item := range []struct {
			typ                   models.TransactionCategoryType
			parentName, childName string
		}{
			{models.CATEGORY_TYPE_INCOME, "收入", "其他收入"},
			{models.CATEGORY_TYPE_EXPENSE, "日常支出", "其他支出"},
			{models.CATEGORY_TYPE_TRANSFER, "账户转账", "普通转账"},
		} {
			count, err := sess.Where("uid=? AND deleted=? AND family_internal_transfer=? AND type=? AND parent_category_id>?", models.SharedDictionaryOwnerUID, false, false, item.typ, 0).Count(&models.TransactionCategory{})
			if err != nil {
				return err
			}
			if count > 0 {
				continue
			}
			ids := TransactionCategories.GenerateUuids(uuid.UUID_TYPE_CATEGORY, 2)
			if len(ids) != 2 || ids[0] <= 0 || ids[1] <= 0 {
				return errs.ErrSystemIsBusy
			}
			now := time.Now().Unix()
			parent := &models.TransactionCategory{CategoryId: ids[0], Uid: models.SharedDictionaryOwnerUID, Type: item.typ, Name: item.parentName, Icon: 1, Color: "808080", CreatedUnixTime: now, UpdatedUnixTime: now}
			child := &models.TransactionCategory{CategoryId: ids[1], Uid: models.SharedDictionaryOwnerUID, Type: item.typ, ParentCategoryId: ids[0], Name: item.childName, Icon: 1, Color: "808080", CreatedUnixTime: now, UpdatedUnixTime: now}
			if _, err := sess.Insert(parent, child); err != nil {
				return err
			}
		}
		return nil
	})
}
