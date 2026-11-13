package api

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json/v2"

	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/mayswind/ezbookkeeping/pkg/errs"
	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/mayswind/ezbookkeeping/pkg/utils"
)

func transactionRequestRecord(uid int64, request *models.TransactionCreateRequest) (*models.TransactionRequest, error) {
	if request.ClientSessionId == "" {
		return nil, nil
	}
	if len(request.ClientSessionId) > 128 {
		return nil, errs.ErrTransactionRequestConflict
	}
	data, err := json.Marshal(request)
	if err != nil {
		return nil, err
	}
	hash := sha256.Sum256(data)
	return &models.TransactionRequest{Uid: uid, RequestId: request.ClientSessionId, Fingerprint: hex.EncodeToString(hash[:])}, nil
}

// Replay before validating unused pictures: a committed request has already bound them.
func (a *TransactionsApi) replayTransactionRequest(c *core.WebContext, request *models.TransactionRequest) (any, *errs.Error, bool) {
	if request == nil {
		return nil, nil, false
	}
	previous, err := a.transactions.GetTransactionRequest(c, request.Uid, request.RequestId)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed), true
	}
	if previous == nil {
		return nil, nil, false
	}
	if previous.Fingerprint != request.Fingerprint {
		return nil, errs.ErrTransactionRequestConflict, true
	}
	query := c.Request.URL.Query()
	query.Set("id", utils.Int64ToString(previous.TransactionId))
	query.Set("with_pictures", "true")
	originalQuery := c.Request.URL.RawQuery
	c.Request.URL.RawQuery = query.Encode()
	defer func() { c.Request.URL.RawQuery = originalQuery }()
	response, responseErr := a.TransactionGetHandler(c)
	return response, responseErr, true
}
