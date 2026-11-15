package api

import (
	"github.com/gin-gonic/gin/binding"
	"github.com/go-playground/validator/v10"
	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/mayswind/ezbookkeeping/pkg/errs"
	"github.com/mayswind/ezbookkeeping/pkg/validators"
	"net/http"
	"testing"
)

func TestLegacyCategoryManagementRequiresRevision(t *testing.T) {
	v := binding.Validator.Engine().(*validator.Validate)
	if err := v.RegisterValidation("notBlank", validators.NotBlank); err != nil {
		t.Fatal(err)
	}
	if err := v.RegisterValidation("validHexRGBColor", validators.ValidHexRGBColor); err != nil {
		t.Fatal(err)
	}
	tests := []struct {
		name   string
		body   any
		handle func(*core.WebContext) (any, *errs.Error)
	}{
		{"add", map[string]any{"name": "food", "type": 2, "parentId": "0", "icon": "1", "color": "000000"}, TransactionCategories.CategoryCreateHandler},
		{"add_batch", map[string]any{"categories": []any{}}, TransactionCategories.CategoryCreateBatchHandler},
		{"modify", map[string]any{"id": "1", "name": "food", "parentId": "0", "icon": "1", "color": "000000"}, TransactionCategories.CategoryModifyHandler},
		{"hide", map[string]any{"id": "1", "hidden": true}, TransactionCategories.CategoryHideHandler},
		{"move", map[string]any{"newDisplayOrders": []any{map[string]any{"id": "1", "displayOrder": 0}}}, TransactionCategories.CategoryMoveHandler},
		{"delete", map[string]any{"id": "1"}, TransactionCategories.CategoryDeleteHandler},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			c, _ := nativeContext(http.MethodPost, "/", tc.body)
			_, err := tc.handle(c)
			if err != errs.ErrTransactionCategoryRevisionRequired {
				t.Fatalf("got %v; expected revision required", err)
			}
			if err.Code() != 206012 || err.HttpStatusCode != http.StatusUpgradeRequired {
				t.Fatal("unexpected error contract")
			}
		})
	}
}
