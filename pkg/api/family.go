package api

import (
	"bytes"
	"encoding/csv"
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
		MemberID int64  `form:"member_id,string"`
		Cursor   string `form:"cursor"`
		Count    int    `form:"count,default=50" binding:"min=1,max=200"`
	}
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	result, err := services.Family.Transactions(c, uid, request.Month, request.MemberID, request.Cursor, request.Count)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
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
