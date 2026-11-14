package services

import (
	"time"

	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/mayswind/ezbookkeeping/pkg/models"
)

// CreateNativeOAuth2CallbackToken is scoped to the existing password/2FA seam;
// it cannot access ordinary APIs and is never placed in the native return URI.
func (s *TokenService) CreateNativeOAuth2CallbackToken(c *core.WebContext, user *models.User, context string, requiresPassword bool) (string, *core.UserTokenClaims, error) {
	tokenType := core.USER_TOKEN_TYPE_OAUTH2_CALLBACK
	if requiresPassword {
		tokenType = core.USER_TOKEN_TYPE_OAUTH2_CALLBACK_REQUIRE_VERIFY
	}
	token, claims, _, err := s.createToken(c, user, tokenType, s.getUserAgent(c), context, 2*time.Minute)
	return token, claims, err
}
