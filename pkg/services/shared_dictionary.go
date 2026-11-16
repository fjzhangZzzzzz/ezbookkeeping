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

// EnsureSharedDictionary creates and maintains the system internal-transfer categories.
// Their semantic identity is stored on the child rather than inferred from its name.
func EnsureSharedDictionary(c core.Context) error {
	sharedDictionaryInitializationLock.Lock()
	defer sharedDictionaryInitializationLock.Unlock()

	return TransactionCategories.UserDataDB(models.SharedDictionaryOwnerUID).DoTransaction(c, func(sess datastore.DatabaseSession) error {
		changed := false
		for _, item := range []struct {
			typ              models.TransactionCategoryType
			legacyParentName string
			parentName       string
			legacyName       string
			name             string
		}{
			{models.CATEGORY_TYPE_INCOME, "家庭内部往来", "家庭往来", "家庭内部转入", "家庭转入"},
			{models.CATEGORY_TYPE_EXPENSE, "家庭内部往来", "家庭往来", "家庭内部转出", "家庭转出"},
		} {
			count, err := sess.Where("uid=? AND deleted=? AND family_internal_transfer=? AND type=?", models.SharedDictionaryOwnerUID, false, true, item.typ).Count(&models.TransactionCategory{})
			if err != nil {
				return err
			} else if count > 1 {
				return errs.ErrOperationFailed
			} else if count == 1 {
				internalCategory := &models.TransactionCategory{}
				found, err := sess.Where("uid=? AND deleted=? AND family_internal_transfer=? AND type=?", models.SharedDictionaryOwnerUID, false, true, item.typ).Get(internalCategory)
				if err != nil {
					return err
				} else if !found {
					return errs.ErrOperationFailed
				}

				if internalCategory.Name == item.legacyName {
					_, err = sess.ID(internalCategory.CategoryId).Cols("name", "updated_unix_time").Where("uid=? AND deleted=?", models.SharedDictionaryOwnerUID, false).Update(&models.TransactionCategory{Name: item.name, UpdatedUnixTime: time.Now().Unix()})
					if err != nil {
						return err
					}
					changed = true
				}

				parentCategory := &models.TransactionCategory{}
				found, err = sess.ID(internalCategory.ParentCategoryId).Where("uid=? AND deleted=?", models.SharedDictionaryOwnerUID, false).Get(parentCategory)
				if err != nil {
					return err
				} else if found && parentCategory.Name == item.legacyParentName {
					_, err = sess.ID(parentCategory.CategoryId).Cols("name", "updated_unix_time").Where("uid=? AND deleted=?", models.SharedDictionaryOwnerUID, false).Update(&models.TransactionCategory{Name: item.parentName, UpdatedUnixTime: time.Now().Unix()})
					if err != nil {
						return err
					}
					changed = true
				}

				continue
			}
			ids := TransactionCategories.GenerateUuids(uuid.UUID_TYPE_CATEGORY, 2)
			if len(ids) != 2 || ids[0] <= 0 || ids[1] <= 0 {
				return errs.ErrSystemIsBusy
			}
			now := time.Now().Unix()
			parent := &models.TransactionCategory{CategoryId: ids[0], Uid: models.SharedDictionaryOwnerUID, Type: item.typ, Name: item.parentName, DisplayOrder: 10000, Icon: 1, Color: "808080", CreatedUnixTime: now, UpdatedUnixTime: now}
			child := &models.TransactionCategory{CategoryId: ids[1], Uid: models.SharedDictionaryOwnerUID, Type: item.typ, ParentCategoryId: ids[0], Name: item.name, Icon: 1, Color: "808080", FamilyInternalTransfer: true, CreatedUnixTime: now, UpdatedUnixTime: now}
			changed = true
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
			changed = true
			if _, err := sess.Insert(parent, child); err != nil {
				return err
			}
		}
		row := &models.TransactionCategoryRevision{}
		found, err := sess.ID(1).Get(row)
		if err != nil {
			return err
		}
		if !found {
			_, err = sess.Insert(&models.TransactionCategoryRevision{Id: 1, Revision: 1})
			return err
		}
		if changed {
			_, err = sess.ID(1).SetExpr("revision", "revision+1").Update(&models.TransactionCategoryRevision{})
			return err
		}
		return nil
	})
}
