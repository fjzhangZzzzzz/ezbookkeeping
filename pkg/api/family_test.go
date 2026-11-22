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

func TestFamilyReportCSVUsesSelectedScopeAndSignedAmounts(t *testing.T) {
	amounts := models.FamilyAmounts{IncomeMinor: "1000", ExpenseMinor: "-250", NetMinor: "1250"}
	data, err := buildFamilyReportCSV(&models.FamilyReport{
		Period: "2026", Yearly: true, MemberId: 2, Timezone: "Asia/Shanghai", Currency: "CNY",
		IncomeMinor: "1000", ExpenseMinor: "-250", NetMinor: "1250", AverageExpenseMinor: "-250",
		Members:                     []models.FamilyMemberSummary{{Id: 1, Nickname: "其他成员"}, {Id: 2, Nickname: "=某人"}},
		Categories:                  []models.FamilyReportCategory{{Name: "退款", Type: int(models.CATEGORY_TYPE_EXPENSE), AmountMinor: "-250", PreviousAmountMinor: "70"}},
		Months:                      []models.FamilyMonthSummary{{Month: "2026-11", FamilyAmounts: amounts}},
		ExcludedForeignTransactions: 3, PreviousExcludedForeignTransactions: 1,
	})
	assert.NoError(t, err)
	rows, err := csv.NewReader(strings.NewReader(strings.TrimPrefix(string(data), "\xef\xbb\xbf"))).ReadAll()
	if assert.NoError(t, err) && assert.Len(t, rows, 7) {
		assert.Equal(t, []string{"总计", "'=某人", "1000", "-250", "1250", ""}, rows[1][3:])
		assert.Equal(t, []string{"分类", "退款", "", "-250", "", "70"}, rows[2][3:])
		assert.Equal(t, []string{"逐月", "2026-11", "1000", "-250", "1250", ""}, rows[3][3:])
		assert.Contains(t, rows[5][4], "2 笔")
		assert.Contains(t, rows[6][4], "1 笔")
	}
}
