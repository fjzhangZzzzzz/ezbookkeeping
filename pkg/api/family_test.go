package api

import (
	"encoding/csv"
	"strings"
	"testing"

	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/stretchr/testify/assert"
)

func TestFamilyCSVPreservesNumbersAndNeutralizesLabels(t *testing.T) {
	data, err := buildFamilyCSV(&models.FamilyOverview{
		Month: "2026-11", Timezone: "Asia/Shanghai", Currency: "CNY",
		Totals:     models.FamilyAmounts{IncomeMinor: "10000", ExpenseMinor: "-20000", NetMinor: "30000"},
		Members:    []models.FamilyMemberSummary{{Nickname: " =HYPERLINK(\"x\")", FamilyAmounts: models.FamilyAmounts{IncomeMinor: "10000", ExpenseMinor: "-20000", NetMinor: "30000"}}},
		Categories: []models.FamilyCategorySummary{{Name: "@SUM(1)", ExpenseMinor: "-20000"}},
	})
	assert.NoError(t, err)
	rows, err := csv.NewReader(strings.NewReader(strings.TrimPrefix(string(data), "\xef\xbb\xbf"))).ReadAll()
	assert.NoError(t, err)
	assert.Equal(t, "-20000", rows[1][6])
	assert.Equal(t, "' =HYPERLINK(\"x\")", rows[2][4])
	assert.Equal(t, "'@SUM(1)", rows[3][4])
}
