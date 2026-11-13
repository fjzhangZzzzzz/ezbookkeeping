package services

import (
	"testing"
	"time"

	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/stretchr/testify/assert"
)

func TestFamilyMonthRangeShanghai(t *testing.T) {
	start, end, err := familyMonthRange("2026-11")
	assert.NoError(t, err)
	assert.Equal(t, "2026-10-31T16:00:00Z", start.UTC().Format(time.RFC3339))
	assert.Equal(t, "2026-11-30T16:00:00Z", end.UTC().Format(time.RFC3339))
	for _, invalid := range []string{"2026-1", "2026-13", "1969-12", "2026-11-01", "bad"} {
		_, _, err := familyMonthRange(invalid)
		assert.Error(t, err, invalid)
	}
}

func TestFamilyAmountsPreserveRefundsAndLargeTotals(t *testing.T) {
	var amounts familyAmountAccumulator
	for _, transaction := range []*models.Transaction{
		{Type: models.TRANSACTION_DB_TYPE_INCOME, Amount: 999999999999999},
		{Type: models.TRANSACTION_DB_TYPE_INCOME, Amount: 999999999999999},
		{Type: models.TRANSACTION_DB_TYPE_EXPENSE, Amount: 10000},
		{Type: models.TRANSACTION_DB_TYPE_EXPENSE, Amount: -30000},
		{Type: models.TRANSACTION_DB_TYPE_TRANSFER_OUT, Amount: 50000},
		{Type: models.TRANSACTION_DB_TYPE_MODIFY_BALANCE, Amount: 70000},
	} {
		amounts.add(transaction)
	}
	assert.Equal(t, models.FamilyAmounts{IncomeMinor: "1999999999999998", ExpenseMinor: "-20000", NetMinor: "2000000000019998"}, amounts.response())
}
