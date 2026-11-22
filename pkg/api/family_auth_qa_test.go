package api

import (
	"github.com/gin-gonic/gin"
	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/stretchr/testify/require"
	"testing"
)

func TestFamilyEndpointsRejectNonInteractiveTokensQA(t *testing.T) {
	for _, claims := range []*core.UserTokenClaims{nil, {Type: core.USER_TOKEN_TYPE_API, Uid: 1}, {Type: core.USER_TOKEN_TYPE_MCP, Uid: 1}} {
		c := core.WrapWebContext(&gin.Context{}, nil)
		if claims != nil {
			c.SetTokenClaims(claims)
		}
		_, err := Family.OverviewHandler(c)
		require.NotNil(t, err)
		_, err = Family.TransactionsHandler(c)
		require.NotNil(t, err)
		_, _, err = Family.ExportHandler(c)
		require.NotNil(t, err)
		_, _, err = Family.ReportExportHandler(c)
		require.NotNil(t, err)
		_, _, err = Family.PictureHandler(c)
		require.NotNil(t, err)
	}
}
