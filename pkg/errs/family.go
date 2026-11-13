package errs

import "net/http"

var (
	ErrTransactionRequestConflict = NewNormalError(NormalSubcategoryTransaction, 100, http.StatusConflict, "transaction request id was already used with different data")
	ErrFamilyMonthInvalid         = NewNormalError(NormalSubcategoryGlobal, 100, http.StatusBadRequest, "family month must be YYYY-MM")
	ErrFamilyCursorInvalid        = NewNormalError(NormalSubcategoryGlobal, 101, http.StatusBadRequest, "family cursor is invalid")
	ErrFamilyMemberLimit          = NewNormalError(NormalSubcategoryUser, 100, http.StatusConflict, "family supports at most ten active members")
)
