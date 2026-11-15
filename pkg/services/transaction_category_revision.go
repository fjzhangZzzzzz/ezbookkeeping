package services

import (
	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/mayswind/ezbookkeeping/pkg/datastore"
	"github.com/mayswind/ezbookkeeping/pkg/errs"
	"github.com/mayswind/ezbookkeeping/pkg/models"
)

// categoryRevisionInSession reads the revision from the caller's database transaction.
func categoryRevisionInSession(sess datastore.DatabaseSession) (int64, error) {
	row := &models.TransactionCategoryRevision{}
	found, err := sess.ID(1).Get(row)
	if err != nil {
		return 0, err
	}
	if !found {
		return 0, errs.ErrOperationFailed
	}
	return row.Revision, nil
}

// advanceCategoryRevision performs the compare-and-swap in the same transaction
// as the category mutation, so a rolled-back mutation also rolls back its revision.
func advanceCategoryRevision(sess datastore.DatabaseSession, expected int64) error {
	if expected < 1 {
		return errs.ErrTransactionCategoryRevisionRequired
	}
	changed, err := sess.ID(1).Where("revision=?", expected).SetExpr("revision", "revision+1").Update(&models.TransactionCategoryRevision{})
	if err != nil {
		return err
	}
	if changed != 1 {
		return errs.ErrTransactionCategoryRevisionConflict
	}
	return nil
}

// GetCategoryRevision reads the global category version.
func (s *TransactionCategoryService) GetCategoryRevision(c core.Context, uid int64) (int64, error) {
	if uid <= 0 {
		return 0, errs.ErrUserIdInvalid
	}
	return categoryRevisionInSession(s.UserDataDB(uid).NewSession(c))
}

// CategoryImpact counts active members' transactions against a consistent SQLite snapshot.
func (s *TransactionCategoryService) CategoryImpact(c core.Context, uid, categoryID int64) (int64, int64, error) {
	if uid <= 0 {
		return 0, 0, errs.ErrUserIdInvalid
	}
	db, ok := s.UserDataDB(uid).(*datastore.XOrmDatabase)
	if !ok || !db.IsSQLite() || s.UserDB() != s.UserDataDB(uid) {
		return 0, 0, errs.ErrOperationFailed
	}
	var revision, count int64
	err := s.UserDataDB(uid).DoTransaction(c, func(sess datastore.DatabaseSession) error {
		var err error
		revision, err = categoryRevisionInSession(sess)
		if err != nil {
			return err
		}
		var members []*models.User
		err = sess.Cols("uid").Where("deleted=? AND disabled=?", false, false).Limit(MaximumFamilyMembers + 1).Find(&members)
		if err != nil {
			return err
		}
		if len(members) > MaximumFamilyMembers {
			return errs.ErrFamilyMemberLimit
		}
		ids := make([]int64, 0, len(members))
		allowed := false
		for _, member := range members {
			ids = append(ids, member.Uid)
			if member.Uid == uid {
				allowed = true
			}
		}
		if !allowed {
			return errs.ErrNotPermittedToPerformThisAction
		}
		var categories []*models.TransactionCategory
		err = sess.Where("uid=? AND deleted=? AND (category_id=? OR parent_category_id=?)", models.SharedDictionaryOwnerUID, false, categoryID, categoryID).Find(&categories)
		if err != nil {
			return err
		}
		if len(categories) == 0 {
			return errs.ErrTransactionCategoryNotFound
		}
		cids := make([]int64, 0, len(categories))
		for _, category := range categories {
			cids = append(cids, category.CategoryId)
		}
		count, err = sess.Where("deleted=? AND type<>?", false, models.TRANSACTION_DB_TYPE_TRANSFER_IN).In("uid", ids).In("category_id", cids).Count(&models.Transaction{})
		return err
	})
	return revision, count, err
}

// checkTransactionCategoryRevision is optional for older clients. SQLite serializes
// this check with the ledger mutation and category writes in the same transaction.
func checkTransactionCategoryRevision(db datastore.Database, sess datastore.DatabaseSession, expected *int64) error {
	if expected == nil {
		return nil
	}
	sqlite, ok := db.(*datastore.XOrmDatabase)
	if !ok || !sqlite.IsSQLite() {
		return errs.ErrOperationFailed
	}
	actual, err := categoryRevisionInSession(sess)
	if err != nil {
		return err
	}
	if *expected < 1 || *expected != actual {
		return errs.ErrTransactionCategoryRevisionConflict
	}
	return nil
}
