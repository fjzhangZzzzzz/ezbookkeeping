package api

import (
	"bytes"
	"encoding/csv"
	"slices"
	"strconv"
	"strings"

	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/mayswind/ezbookkeeping/pkg/errs"
	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/mayswind/ezbookkeeping/pkg/services"
	"github.com/mayswind/ezbookkeeping/pkg/utils"
)

type FamilyApi struct{}

var Family = &FamilyApi{}

func familySessionUID(c *core.WebContext) (int64, *errs.Error) {
	claims := c.GetTokenClaims()
	if claims == nil || claims.Type != core.USER_TOKEN_TYPE_NORMAL {
		return 0, errs.ErrNotPermittedToPerformThisAction
	}
	return claims.Uid, nil
}

func (a *FamilyApi) OverviewHandler(c *core.WebContext) (any, *errs.Error) {
	uid, authErr := familySessionUID(c)
	if authErr != nil {
		return nil, authErr
	}
	result, err := services.Family.Overview(c, uid, c.Query("month"))
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return result, nil
}

func (a *FamilyApi) TransactionsHandler(c *core.WebContext) (any, *errs.Error) {
	uid, authErr := familySessionUID(c)
	if authErr != nil {
		return nil, authErr
	}
	var request struct {
		Month    string `form:"month"`
		Period   string `form:"period"`
		Yearly   bool   `form:"yearly"`
		MemberID int64  `form:"member_id,string"`
		Cursor   string `form:"cursor"`
		Count    int    `form:"count,default=50" binding:"min=1,max=200"`
	}
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	var result *models.FamilyTransactionPage
	var err error
	if request.Period != "" || request.Yearly {
		result, err = services.Family.TransactionsForPeriod(c, uid, request.Period, request.Yearly, request.MemberID, request.Cursor, request.Count)
	} else {
		result, err = services.Family.Transactions(c, uid, request.Month, request.MemberID, request.Cursor, request.Count)
	}
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return result, nil
}

func familyReportForRequest(c *core.WebContext) (*models.FamilyReport, *errs.Error) {
	uid, authErr := familySessionUID(c)
	if authErr != nil {
		return nil, authErr
	}
	yearly := c.Query("yearly") == "true"
	memberID := int64(0)
	if rawMemberID := c.Query("member_id"); rawMemberID != "" {
		var err error
		memberID, err = utils.StringToInt64(rawMemberID)
		if err != nil {
			return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
		}
	}
	result, serviceErr := services.Family.Report(c, uid, c.Query("period"), yearly, memberID)
	if serviceErr != nil {
		return nil, errs.Or(serviceErr, errs.ErrOperationFailed)
	}
	return result, nil
}

func (a *FamilyApi) ReportHandler(c *core.WebContext) (any, *errs.Error) {
	return familyReportForRequest(c)
}

func (a *FamilyApi) ReportTransactionsHandler(c *core.WebContext) (any, *errs.Error) {
	uid, authErr := familySessionUID(c)
	if authErr != nil {
		return nil, authErr
	}
	var request struct {
		Period     string `form:"period"`
		Yearly     bool   `form:"yearly"`
		MemberID   int64  `form:"member_id,string"`
		CategoryID int64  `form:"category_id,string"`
		AccountID  int64  `form:"account_id,string"`
		Date       string `form:"date"`
		Cursor     string `form:"cursor"`
		Count      int    `form:"count,default=30" binding:"min=1,max=200"`
	}
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	result, serviceErr := services.Family.ReportTransactions(c, uid, request.Period, request.Yearly, request.MemberID, request.CategoryID, request.AccountID, request.Date, request.Cursor, request.Count)
	if serviceErr != nil {
		return nil, errs.Or(serviceErr, errs.ErrOperationFailed)
	}
	return result, nil
}

// CSV labels are untrusted dictionary/member names. Quoting alone does not stop spreadsheet formulas.
func familyCSVText(text string) string {
	trimmed := strings.TrimLeft(text, " \t\r\n")
	if trimmed != "" && strings.ContainsAny(trimmed[:1], "=+-@") {
		return "'" + text
	}
	return text
}

func buildFamilyCSV(overview *models.FamilyOverview) ([]byte, error) {
	var buffer bytes.Buffer
	buffer.WriteString("\xef\xbb\xbf")
	writer := csv.NewWriter(&buffer)
	rows := [][]string{
		{"月份", "时区", "币种", "维度", "名称", "收入（分）", "支出（分）", "结余（分）"},
		{overview.Month, overview.Timezone, overview.Currency, "总计", "家庭", overview.Totals.IncomeMinor, overview.Totals.ExpenseMinor, overview.Totals.NetMinor},
	}
	for _, member := range overview.Members {
		rows = append(rows, []string{overview.Month, overview.Timezone, overview.Currency, "成员", familyCSVText(member.Nickname), member.IncomeMinor, member.ExpenseMinor, member.NetMinor})
	}
	for _, category := range overview.Categories {
		rows = append(rows, []string{overview.Month, overview.Timezone, overview.Currency, "分类", familyCSVText(category.Name), category.IncomeMinor, category.ExpenseMinor, ""})
	}
	for _, day := range overview.Daily {
		rows = append(rows, []string{overview.Month, overview.Timezone, overview.Currency, "每日", day.Date, day.IncomeMinor, day.ExpenseMinor, day.NetMinor})
	}
	for _, month := range overview.Trend {
		rows = append(rows, []string{month.Month, overview.Timezone, overview.Currency, "月度趋势", month.Month, month.IncomeMinor, month.ExpenseMinor, month.NetMinor})
	}
	if overview.HasExcludedForeignCurrency {
		rows = append(rows, []string{overview.Month, overview.Timezone, overview.Currency, "提示", "存在未计入的外币交易（含最近十二个月趋势范围）", "", "", ""})
	}
	if err := writer.WriteAll(rows); err != nil {
		return nil, err
	}
	return buffer.Bytes(), writer.Error()
}

func (a *FamilyApi) ExportHandler(c *core.WebContext) ([]byte, string, *errs.Error) {
	uid, authErr := familySessionUID(c)
	if authErr != nil {
		return nil, "", authErr
	}
	result, err := services.Family.Overview(c, uid, c.Query("month"))
	if err != nil {
		return nil, "", errs.Or(err, errs.ErrOperationFailed)
	}
	data, err := buildFamilyCSV(result)
	if err != nil {
		return nil, "", errs.ErrOperationFailed
	}
	c.Header("Cache-Control", "no-store")
	return data, "family-" + result.Month + ".csv", nil
}

func buildFamilyReportCSV(report *models.FamilyReport) ([]byte, error) {
	var buffer bytes.Buffer
	buffer.WriteString("\xef\xbb\xbf")
	writer := csv.NewWriter(&buffer)
	rows := [][]string{{"期间", "时区", "币种", "维度", "名称", "收入（分）", "支出（分）", "结余（分）", "上期分类金额（分）"}}
	appendRow := func(dimension, name string, amounts models.FamilyAmounts, previous string) {
		rows = append(rows, []string{report.Period, report.Timezone, report.Currency, dimension, familyCSVText(name), amounts.IncomeMinor, amounts.ExpenseMinor, amounts.NetMinor, previous})
	}
	scopeName := "全家"
	for _, member := range report.Members {
		if member.Id == report.MemberId {
			scopeName = member.Nickname
		}
	}
	appendRow("总计", scopeName, models.FamilyAmounts{IncomeMinor: report.IncomeMinor, ExpenseMinor: report.ExpenseMinor, NetMinor: report.NetMinor}, "")
	if report.MemberId == 0 {
		for _, member := range report.Members {
			appendRow("成员", member.Nickname, member.FamilyAmounts, "")
		}
	}
	for _, category := range slices.Concat(report.Categories, report.PriorOnlyCategories) {
		var amounts models.FamilyAmounts
		if category.Type == int(models.CATEGORY_TYPE_EXPENSE) {
			amounts.ExpenseMinor = category.AmountMinor
		} else {
			amounts.IncomeMinor = category.AmountMinor
		}
		name := category.Name
		if category.Parent != nil {
			name = category.Parent.Name + " / " + name
		}
		appendRow("分类", name, amounts, category.PreviousAmountMinor)
	}
	if report.Yearly {
		for _, month := range report.Months {
			appendRow("逐月", month.Month, month.FamilyAmounts, "")
		}
		appendRow("平均", "月均", models.FamilyAmounts{IncomeMinor: report.AverageIncomeMinor, ExpenseMinor: report.AverageExpenseMinor, NetMinor: report.AverageNetMinor}, "")
	} else {
		for _, day := range report.Days {
			if day.IncomeMinor != "0" || day.ExpenseMinor != "0" {
				appendRow("逐日", day.Date, day.FamilyAmounts, "")
			}
		}
		appendRow("平均", "日均支出", models.FamilyAmounts{ExpenseMinor: report.AverageExpenseMinor}, "")
	}
	if count := report.ExcludedForeignTransactions - report.PreviousExcludedForeignTransactions; count > 0 {
		appendRow("提示", "本期未计入的外币交易："+strconv.Itoa(count)+" 笔", models.FamilyAmounts{}, "")
	}
	if count := report.PreviousExcludedForeignTransactions; count > 0 {
		appendRow("提示", "上期未计入的外币交易："+strconv.Itoa(count)+" 笔", models.FamilyAmounts{}, "")
	}
	if err := writer.WriteAll(rows); err != nil {
		return nil, err
	}
	return buffer.Bytes(), nil
}

func (a *FamilyApi) ReportExportHandler(c *core.WebContext) ([]byte, string, *errs.Error) {
	report, reportErr := familyReportForRequest(c)
	if reportErr != nil {
		return nil, "", reportErr
	}
	data, err := buildFamilyReportCSV(report)
	if err != nil {
		return nil, "", errs.Or(err, errs.ErrOperationFailed)
	}
	c.Header("Cache-Control", "no-store")
	return data, "family-" + report.Period + ".csv", nil
}

func (a *FamilyApi) PictureHandler(c *core.WebContext) ([]byte, string, *errs.Error) {
	uid, authErr := familySessionUID(c)
	if authErr != nil {
		return nil, "", authErr
	}
	name := c.Param("fileName")
	extension := utils.GetFileNameExtension(name)
	contentType := utils.GetImageContentType(extension)
	if contentType == "" {
		return nil, "", errs.ErrImageTypeNotSupported
	}
	id, err := utils.StringToInt64(utils.GetFileNameWithoutExtension(name))
	if err != nil {
		return nil, "", errs.ErrTransactionPictureIdInvalid
	}
	data, err := services.Family.Picture(c, uid, id, extension)
	if err != nil {
		return nil, "", errs.Or(err, errs.ErrOperationFailed)
	}
	c.Header("Cache-Control", "no-store")
	return data, contentType, nil
}
