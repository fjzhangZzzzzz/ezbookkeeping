package services

import (
	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/mayswind/ezbookkeeping/pkg/datastore"
	"github.com/mayswind/ezbookkeeping/pkg/errs"
	"github.com/mayswind/ezbookkeeping/pkg/models"
)

func findTransactionRequest(sess datastore.DatabaseSession, uid int64, requestID string) (*models.TransactionRequest, error) {
	request := &models.TransactionRequest{}
	has, err := sess.Where("uid=? AND request_id=?", uid, requestID).Get(request)
	if err != nil || !has {
		return nil, err
	}
	return request, nil
}

func (s *TransactionService) GetTransactionRequest(c core.Context, uid int64, requestID string) (*models.TransactionRequest, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}
	if requestID == "" || len(requestID) > 128 {
		return nil, errs.ErrTransactionRequestConflict
	}
	return findTransactionRequest(s.UserDataDB(uid).NewSession(c), uid, requestID)
}
