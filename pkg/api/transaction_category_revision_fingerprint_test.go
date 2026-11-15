package api

import (
	"encoding/json/v2"
	"strings"
	"testing"

	"github.com/mayswind/ezbookkeeping/pkg/models"
)

func TestTransactionCategoryRevisionFingerprintCompatibility(t *testing.T) {
	request := &models.TransactionCreateRequest{ClientSessionId: "request"}
	raw, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), "expectedCategoryRevision") {
		t.Fatal("legacy request encoding must remain unchanged")
	}
	legacy, err := transactionRequestRecord(1, request)
	if err != nil {
		t.Fatal(err)
	}
	one := int64(1)
	request.ExpectedCategoryRevision = &one
	first, err := transactionRequestRecord(1, request)
	if err != nil {
		t.Fatal(err)
	}
	two := int64(2)
	request.ExpectedCategoryRevision = &two
	second, err := transactionRequestRecord(1, request)
	if err != nil {
		t.Fatal(err)
	}
	if legacy.Fingerprint == first.Fingerprint || first.Fingerprint == second.Fingerprint {
		t.Fatal("revision must participate in exact-request fingerprint")
	}
}
