package errs

import "net/http"

var (
	ErrNativeOAuth2InvalidRequest = NewNormalError(NormalSubcategoryOAuth2, 16, http.StatusBadRequest, "invalid native oauth2 request")
	ErrNativeOAuth2Unavailable    = NewNormalError(NormalSubcategoryOAuth2, 17, http.StatusBadRequest, "native oauth2 flow unavailable; start sign-in again")
	ErrNativeOAuth2Limit          = NewNormalError(NormalSubcategoryOAuth2, 18, http.StatusTooManyRequests, "native oauth2 sign-in temporarily busy; try later")
	ErrNativeOAuth2Failed         = NewNormalError(NormalSubcategoryOAuth2, 19, http.StatusBadRequest, "native oauth2 authorization failed; start sign-in again")
)
