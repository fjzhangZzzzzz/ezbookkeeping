package models

// FamilyAmounts uses decimal strings so multi-member totals cannot lose integer precision in a browser.
type FamilyAmounts struct {
	IncomeMinor  string `json:"incomeMinor"`
	ExpenseMinor string `json:"expenseMinor"`
	NetMinor     string `json:"netMinor"`
}

type FamilyMemberSummary struct {
	Id       int64  `json:"id,string"`
	Nickname string `json:"nickname"`
	FamilyAmounts
}

type FamilyCategorySummary struct {
	Id           int64  `json:"id,string"`
	Name         string `json:"name"`
	IncomeMinor  string `json:"incomeMinor"`
	ExpenseMinor string `json:"expenseMinor"`
}

type FamilyDaySummary struct {
	Date string `json:"date"`
	FamilyAmounts
}

type FamilyMonthSummary struct {
	Month string `json:"month"`
	FamilyAmounts
}

type FamilyAccount struct {
	Id           int64  `json:"id,string"`
	OwnerId      int64  `json:"ownerId,string"`
	OwnerName    string `json:"ownerName"`
	Name         string `json:"name"`
	Currency     string `json:"currency"`
	BalanceMinor int64  `json:"balanceMinor,string"`
	Hidden       bool   `json:"hidden"`
}

type FamilyOverview struct {
	Month                       string                  `json:"month"`
	Timezone                    string                  `json:"timezone"`
	Currency                    string                  `json:"currency"`
	Totals                      FamilyAmounts           `json:"totals"`
	Members                     []FamilyMemberSummary   `json:"members"`
	Categories                  []FamilyCategorySummary `json:"categories"`
	Daily                       []FamilyDaySummary      `json:"daily"`
	Trend                       []FamilyMonthSummary    `json:"trend"`
	Accounts                    []FamilyAccount         `json:"accounts"`
	HasExcludedForeignCurrency  bool                    `json:"hasExcludedForeignCurrency"`
	ExcludedForeignTransactions int                     `json:"excludedForeignTransactions"`
}

type FamilyPicture struct {
	Id        int64  `json:"id,string"`
	Extension string `json:"extension"`
}

type FamilyTransaction struct {
	Id           int64             `json:"id,string"`
	OwnerId      int64             `json:"ownerId,string"`
	OwnerName    string            `json:"ownerName"`
	Time         int64             `json:"time"`
	Type         TransactionDbType `json:"type"`
	Currency     string            `json:"currency"`
	AmountMinor  int64             `json:"amountMinor,string"`
	CategoryName string            `json:"categoryName"`
	AccountName  string            `json:"accountName"`
	Comment      string            `json:"comment"`
	Editable     bool              `json:"editable"`
	Pictures     []FamilyPicture   `json:"pictures"`
}

type FamilyTransactionPage struct {
	Items      []FamilyTransaction `json:"items"`
	NextCursor string              `json:"nextCursor"`
}

// FamilyReportCategory is the category projection consumed by the Android
// report screen. Ordinary income and expense are positive minor units,
// and refunds reduce those amounts.
type FamilyReportCategoryParent struct {
	Id   int64  `json:"id,string"`
	Name string `json:"name"`
	Icon int64  `json:"icon,string"`
}

type FamilyReportCategory struct {
	Id                  int64                       `json:"id,string"`
	Name                string                      `json:"name"`
	Type                int                         `json:"type"`
	Icon                int64                       `json:"icon,string"`
	IconType            int                         `json:"iconType"`
	Color               string                      `json:"color"`
	AmountMinor         string                      `json:"amountMinor"`
	PreviousAmountMinor string                      `json:"previousAmountMinor"`
	Parent              *FamilyReportCategoryParent `json:"parent,omitempty"`
}

type FamilyReport struct {
	Period                              string                 `json:"period"`
	Yearly                              bool                   `json:"yearly"`
	MemberId                            int64                  `json:"memberId,string"`
	Timezone                            string                 `json:"timezone"`
	Currency                            string                 `json:"currency"`
	IncomeMinor                         string                 `json:"incomeMinor"`
	ExpenseMinor                        string                 `json:"expenseMinor"`
	NetMinor                            string                 `json:"netMinor"`
	AverageExpenseMinor                 string                 `json:"averageExpenseMinor"`
	AverageIncomeMinor                  string                 `json:"averageIncomeMinor"`
	AverageNetMinor                     string                 `json:"averageNetMinor"`
	Days                                []FamilyDaySummary     `json:"days"`
	Months                              []FamilyMonthSummary   `json:"months"`
	Categories                          []FamilyReportCategory `json:"categories"`
	PriorOnlyCategories                 []FamilyReportCategory `json:"priorOnlyCategories,omitempty"`
	Members                             []FamilyMemberSummary  `json:"members"`
	Accounts                            []FamilyAccount        `json:"accounts"`
	HasExcludedForeignCurrency          bool                   `json:"hasExcludedForeignCurrency"`
	ExcludedForeignTransactions         int                    `json:"excludedForeignTransactions"`
	PreviousExcludedForeignTransactions int                    `json:"previousExcludedForeignTransactions"`
	ReadOnly                            bool                   `json:"readOnly"`
}
