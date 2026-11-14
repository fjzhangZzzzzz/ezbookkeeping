package api

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"

	"github.com/mayswind/ezbookkeeping/pkg/auth/nativeoauth"
	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/mayswind/ezbookkeeping/pkg/errs"
	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/mayswind/ezbookkeeping/pkg/services"
	"github.com/mayswind/ezbookkeeping/pkg/settings"
)

const nativeFlowContextKey = core.NativeOAuth2ContextKey

type NativeOAuth2Api struct {
	ApiUsingConfig
	flows        *nativeoauth.Manager
	authorizeURL func(*core.WebContext, string, int64) (string, error)
}

var NativeOAuth2 = &NativeOAuth2Api{ApiUsingConfig: ApiUsingConfig{container: settings.Container}, flows: nativeoauth.NewManager()}

type nativeOAuth2StartRequest struct {
	CodeChallenge       string `json:"codeChallenge"`
	CodeChallengeMethod string `json:"codeChallengeMethod"`
	RedirectURI         string `json:"redirectUri"`
	State               string `json:"state"`
}
type nativeOAuth2StartResponse struct {
	FlowID           string `json:"flowId"`
	AuthorizationURL string `json:"authorizationUrl"`
	ExpiresAt        int64  `json:"expiresAt"`
}
type nativeOAuth2ExchangeRequest struct {
	FlowID       string `json:"flowId"`
	Code         string `json:"code"`
	CodeVerifier string `json:"codeVerifier"`
}
type nativeOAuth2ExchangeResponse struct {
	CallbackToken    string `json:"callbackToken"`
	RequiresPassword bool   `json:"requiresPassword"`
}

func nativeNoStore(c *core.WebContext) {
	c.Header("Cache-Control", "no-store")
	c.Header("Pragma", "no-cache")
	c.Header("Referrer-Policy", "no-referrer")
}
func nativeBindJSON(c *core.WebContext, target any) bool {
	if c.Request.ContentLength > 4096 {
		return false
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 4096)
	body, err := io.ReadAll(c.Request.Body)
	return err == nil && json.Unmarshal(body, target) == nil
}
func nativeFlowID(c *core.WebContext) string {
	value, ok := c.Get(nativeFlowContextKey)
	if !ok {
		return ""
	}
	id, _ := value.(string)
	return id
}
func (a *NativeOAuth2Api) start(c *core.WebContext, uid int64) (any, *errs.Error) {
	nativeNoStore(c)
	if !a.CurrentConfig().EnableOAuth2Login {
		return nil, errs.ErrOAuth2NotEnabled
	}
	if !a.CurrentConfig().OAuth2UsePKCE {
		return nil, errs.ErrInvalidOAuth2Config
	}
	var request nativeOAuth2StartRequest
	if !nativeBindJSON(c, &request) {
		return nil, errs.ErrNativeOAuth2InvalidRequest
	}
	flow, err := a.flows.Create(nativeoauth.Request{Challenge: request.CodeChallenge, Method: request.CodeChallengeMethod, Redirect: request.RedirectURI, State: request.State, Peer: c.ClientIP(), UID: uid})
	if errors.Is(err, nativeoauth.ErrInvalid) {
		return nil, errs.ErrNativeOAuth2InvalidRequest
	}
	if errors.Is(err, nativeoauth.ErrLimit) {
		return nil, errs.ErrNativeOAuth2Limit
	}
	if err != nil {
		return nil, errs.ErrNativeOAuth2Unavailable
	}
	browserURL := a.CurrentConfig().RootUrl + "oauth2/native/start?flow_id=" + url.QueryEscape(flow.ID)
	return &nativeOAuth2StartResponse{FlowID: flow.ID, AuthorizationURL: browserURL, ExpiresAt: flow.ExpiresAt.Unix()}, nil
}
func (a *NativeOAuth2Api) StartHandler(c *core.WebContext) (any, *errs.Error) { return a.start(c, 0) }
func (a *NativeOAuth2Api) LinkStartHandler(c *core.WebContext) (any, *errs.Error) {
	nativeNoStore(c)
	uid := c.GetCurrentUid()
	user, err := services.Users.GetUserById(c, uid)
	if err != nil {
		return nil, errs.ErrUserNotFound
	}
	if user.Disabled {
		return nil, errs.ErrUserIsDisabled
	}
	if user.FeatureRestriction.Contains(core.USER_FEATURE_RESTRICTION_TYPE_OAUTH2_LOGIN) {
		return nil, errs.ErrNotPermittedToPerformThisAction
	}
	return a.start(c, uid)
}
func (a *NativeOAuth2Api) BrowserStartHandler(c *core.WebContext) (string, *errs.Error) {
	nativeNoStore(c)
	flow, err := a.flows.Begin(c.Query("flow_id"))
	if err != nil {
		return "", errs.ErrNativeOAuth2Unavailable
	}
	var providerURL string
	if a.authorizeURL != nil {
		providerURL, err = a.authorizeURL(c, flow.ID, flow.UID)
	} else {
		providerURL, err = OAuth2Authentications.generateExternalAuthUrl(c, "mobile", flow.ID, flow.UID)
	}
	if err != nil {
		a.flows.Abort(flow.ID)
		return "", errs.ErrNativeOAuth2Unavailable
	}
	parsed, err := url.Parse(providerURL)
	if err != nil {
		a.flows.Abort(flow.ID)
		return "", errs.ErrNativeOAuth2Unavailable
	}
	if a.flows.SetProviderState(flow.ID, parsed.Query().Get("state")) != nil {
		a.flows.Abort(flow.ID)
		return "", errs.ErrNativeOAuth2Unavailable
	}
	return providerURL, nil
}

// Only a state emitted by BrowserStart can opt into native callback handling.
func (a *NativeOAuth2Api) attachCallback(c *core.WebContext, state string) *errs.Error {
	if !nativeoauth.IsNativeState(state) {
		return nil
	}
	nativeNoStore(c)
	id, err := a.flows.Attach(state)
	if err != nil {
		return errs.ErrNativeOAuth2Unavailable
	}
	c.Set(nativeFlowContextKey, id)
	return nil
}
func (a *NativeOAuth2Api) redirect(c *core.WebContext, payload nativeoauth.Payload) (string, *errs.Error) {
	nativeNoStore(c)
	location, err := a.flows.Publish(nativeFlowID(c), payload)
	if err != nil {
		if payload.CallbackToken != "" {
			a.revokeTemporary(c, payload.CallbackToken)
		}
		return "", errs.ErrNativeOAuth2Unavailable
	}
	return location, nil
}
func (a *NativeOAuth2Api) ExchangeHandler(c *core.WebContext) (any, *errs.Error) {
	nativeNoStore(c)
	var request nativeOAuth2ExchangeRequest
	if !nativeBindJSON(c, &request) {
		return nil, errs.ErrNativeOAuth2Unavailable
	}
	payload, err := a.flows.Exchange(request.FlowID, request.Code, request.CodeVerifier)
	if err != nil {
		return nil, errs.ErrNativeOAuth2Unavailable
	}
	if payload.ErrorCode != 0 {
		return nil, nativeAuthorizationError(payload.ErrorCode)
	}
	return &nativeOAuth2ExchangeResponse{CallbackToken: payload.CallbackToken, RequiresPassword: payload.RequiresPassword}, nil
}
func (a *NativeOAuth2Api) CancelHandler(c *core.WebContext) (any, *errs.Error) {
	nativeNoStore(c)
	var request nativeOAuth2ExchangeRequest
	if !nativeBindJSON(c, &request) {
		return nil, errs.ErrNativeOAuth2Unavailable
	}
	payload, err := a.flows.Cancel(request.FlowID, request.CodeVerifier)
	if err != nil {
		return nil, errs.ErrNativeOAuth2Unavailable
	}
	if payload.CallbackToken != "" && !a.revokeTemporary(c, payload.CallbackToken) {
		return nil, errs.ErrOperationFailed
	}
	return true, nil
}
func (a *NativeOAuth2Api) revokeTemporary(c *core.WebContext, token string) bool {
	claims, _, err := services.Tokens.ParseToken(c, token)
	if err != nil {
		return false
	}
	if claims.Type != core.USER_TOKEN_TYPE_OAUTH2_CALLBACK && claims.Type != core.USER_TOKEN_TYPE_OAUTH2_CALLBACK_REQUIRE_VERIFY {
		return false
	}
	return services.Tokens.DeleteTokenByClaims(c, claims) == nil
}
func (a *NativeOAuth2Api) CancelCallbackHandler(c *core.WebContext) (any, *errs.Error) {
	nativeNoStore(c)
	claims := c.GetTokenClaims()
	if claims == nil || (claims.Type != core.USER_TOKEN_TYPE_OAUTH2_CALLBACK && claims.Type != core.USER_TOKEN_TYPE_OAUTH2_CALLBACK_REQUIRE_VERIFY) {
		return nil, errs.ErrInvalidToken
	}
	if services.Tokens.DeleteTokenByClaims(c, claims) != nil {
		return nil, errs.ErrOperationFailed
	}
	return true, nil
}
func nativeAuthorizationError(code int32) *errs.Error {
	// Never relay provider descriptions. These are existing, user-actionable codes.
	known := []*errs.Error{errs.ErrOAuth2AutoRegistrationNotEnabled, errs.ErrOAuth2UserAlreadyBoundToAnotherUser, errs.ErrOAuth2EmailEmpty, errs.ErrOAuth2UserNameEmpty, errs.ErrUserIsDisabled, errs.ErrUserNotFound, errs.ErrNotPermittedToPerformThisAction, errs.ErrNativeOAuth2Failed}
	for _, err := range known {
		if err.Code() == code {
			return err
		}
	}
	return errs.ErrNativeOAuth2Failed
}

// nativeCallbackToken keeps the existing scoped password/2FA authorization seam.
func nativeCallbackToken(c *core.WebContext, user *models.User, context string, requiresPassword bool) (string, error) {
	token, _, err := services.Tokens.CreateNativeOAuth2CallbackToken(c, user, context, requiresPassword)
	return token, err
}
