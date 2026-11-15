package models

// TransactionCategoryRevision is the revision of the shared category collection.
type TransactionCategoryRevision struct {
	Id       int64 `xorm:"PK"`
	Revision int64 `xorm:"NOT NULL"`
}
