package models

// TransactionRequest commits in the same SQLite transaction as its ledger entry.
// Keeping the key after a transaction is deleted prevents a late retry from recreating it.
type TransactionRequest struct {
	Uid           int64  `xorm:"PK NOT NULL"`
	RequestId     string `xorm:"PK VARCHAR(128) NOT NULL"`
	Fingerprint   string `xorm:"VARCHAR(64) NOT NULL"`
	TransactionId int64  `xorm:"NOT NULL"`
}
