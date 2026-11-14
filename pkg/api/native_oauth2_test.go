package api

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/mayswind/ezbookkeeping/pkg/auth/nativeoauth"
	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/mayswind/ezbookkeeping/pkg/errs"
	"github.com/mayswind/ezbookkeeping/pkg/settings"
)

func nativeContext(method, target string, body any) (*core.WebContext, *httptest.ResponseRecorder) {
	data, _ := json.Marshal(body)
	w := httptest.NewRecorder()
	g, _ := gin.CreateTestContext(w)
	g.Request = httptest.NewRequest(method, target, bytes.NewReader(data))
	g.Request.Header.Set("Content-Type", "application/json")
	return core.WrapWebContext(g, nil), w
}
func nativeAPI() *NativeOAuth2Api {
	return &NativeOAuth2Api{ApiUsingConfig: ApiUsingConfig{container: settings.NewConfigContainer(&settings.Config{EnableOAuth2Login: true, OAuth2UsePKCE: true, RootUrl: "https://books.example/"})}, flows: nativeoauth.NewManager()}
}
func nativeRequest() nativeOAuth2StartRequest {
	sum := sha256.Sum256([]byte(strings.Repeat("v", 43)))
	return nativeOAuth2StartRequest{CodeChallenge: base64.RawURLEncoding.EncodeToString(sum[:]), CodeChallengeMethod: "S256", RedirectURI: nativeoauth.RedirectURI, State: strings.Repeat("s", 43)}
}
func nativePending(t *testing.T, a *NativeOAuth2Api) (nativeoauth.Flow, string) {
	t.Helper()
	r := nativeRequest()
	f, e := a.flows.Create(nativeoauth.Request{Challenge: r.CodeChallenge, Method: r.CodeChallengeMethod, Redirect: r.RedirectURI, State: r.State})
	if e != nil {
		t.Fatal(e)
	}
	if _, e = a.flows.Begin(f.ID); e != nil {
		t.Fatal(e)
	}
	state := "mobile|" + f.ID + "|generated-provider-state"
	if e = a.flows.SetProviderState(f.ID, state); e != nil {
		t.Fatal(e)
	}
	return f, state
}
func assertPrivateHeaders(t *testing.T, w *httptest.ResponseRecorder) {
	t.Helper()
	if w.Header().Get("Cache-Control") != "no-store" || w.Header().Get("Referrer-Policy") != "no-referrer" {
		t.Fatal("handoff can be cached or referred")
	}
}

func TestNativeStartRejectsRedirectAndInvalidProof(t *testing.T) {
	for _, mutate := range []func(*nativeOAuth2StartRequest){func(r *nativeOAuth2StartRequest) { r.RedirectURI = "https://attacker.invalid/" }, func(r *nativeOAuth2StartRequest) { r.RedirectURI = nativeoauth.RedirectURI + "?token=anything" }, func(r *nativeOAuth2StartRequest) { r.CodeChallengeMethod = "plain" }, func(r *nativeOAuth2StartRequest) { r.State = "short" }} {
		a := nativeAPI()
		r := nativeRequest()
		mutate(&r)
		c, w := nativeContext("POST", "/api/native/oauth2/start.json", r)
		_, e := a.StartHandler(c)
		if e != errs.ErrNativeOAuth2InvalidRequest {
			t.Fatal("bad native input accepted")
		}
		assertPrivateHeaders(t, w)
	}
}
func TestNativeStartContract(t *testing.T) {
	a := nativeAPI()
	c, w := nativeContext("POST", "/api/native/oauth2/start.json", nativeRequest())
	v, e := a.StartHandler(c)
	if e != nil {
		t.Fatal(e)
	}
	r := v.(*nativeOAuth2StartResponse)
	u, _ := url.Parse(r.AuthorizationURL)
	if u.Host != "books.example" || u.Path != "/oauth2/native/start" || u.Query().Get("flow_id") != r.FlowID || r.ExpiresAt <= 0 {
		t.Fatal("incorrect browser start contract")
	}
	assertPrivateHeaders(t, w)
}
func TestNativeCallbackOnlyReturnsCodeAndState(t *testing.T) {
	old := NativeOAuth2
	NativeOAuth2 = nativeAPI()
	defer func() { NativeOAuth2 = old }()
	f, state := nativePending(t, NativeOAuth2)
	if _, e := NativeOAuth2.flows.Attach(state); e != nil {
		t.Fatal(e)
	}
	c, w := nativeContext("GET", "/oauth2/callback", nil)
	c.Set(nativeFlowContextKey, f.ID)
	a := &OAuth2AuthenticationApi{}
	location, e := a.redirectToVerifyCallbackPage(c, "mobile", core.USER_EXTERNAL_AUTH_TYPE_OAUTH2_OIDC, "private-name-sentinel", "private-callback-token-sentinel")
	if e != nil {
		t.Fatal(e)
	}
	u, _ := url.Parse(location)
	if len(u.Query()) != 2 || u.Query().Get("code") == "" || u.Query().Get("state") != f.State || strings.Contains(location, "sentinel") {
		t.Fatal("callback leaked identity or token")
	}
	assertPrivateHeaders(t, w)
	exchange, xw := nativeContext("POST", "/api/native/oauth2/exchange.json", nativeOAuth2ExchangeRequest{FlowID: f.ID, Code: u.Query().Get("code"), CodeVerifier: strings.Repeat("v", 43)})
	value, e := NativeOAuth2.ExchangeHandler(exchange)
	if e != nil {
		t.Fatal(e)
	}
	result := value.(*nativeOAuth2ExchangeResponse)
	if !result.RequiresPassword || result.CallbackToken != "private-callback-token-sentinel" {
		t.Fatal("scoped password seam not preserved")
	}
	assertPrivateHeaders(t, xw)
	again, _ := nativeContext("POST", "/api/native/oauth2/exchange.json", nativeOAuth2ExchangeRequest{FlowID: f.ID, Code: u.Query().Get("code"), CodeVerifier: strings.Repeat("v", 43)})
	if _, e = NativeOAuth2.ExchangeHandler(again); e != errs.ErrNativeOAuth2Unavailable {
		t.Fatal("ticket replay accepted")
	}
}
func TestNativeProviderFailureIsNormalizedAndReturnedToApp(t *testing.T) {
	old := NativeOAuth2
	NativeOAuth2 = nativeAPI()
	defer func() { NativeOAuth2 = old }()
	f, state := nativePending(t, NativeOAuth2)
	query := url.Values{"state": {state}, "error": {"access_denied"}, "error_description": {"sensitive-provider-response-sentinel"}}
	c, w := nativeContext("GET", "/oauth2/callback?"+query.Encode(), nil)
	location, e := OAuth2Authentications.CallbackHandler(c)
	if e != nil {
		t.Fatal(e)
	}
	u, _ := url.Parse(location)
	if u.Scheme != "top.fjzhang233.books" || len(u.Query()) != 2 || strings.Contains(location, "sentinel") {
		t.Fatal("provider error leaked or stayed on web SPA")
	}
	assertPrivateHeaders(t, w)
	x, _ := nativeContext("POST", "/api/native/oauth2/exchange.json", nativeOAuth2ExchangeRequest{FlowID: f.ID, Code: u.Query().Get("code"), CodeVerifier: strings.Repeat("v", 43)})
	if _, e = NativeOAuth2.ExchangeHandler(x); e != errs.ErrNativeOAuth2Failed {
		t.Fatal("provider error was not normalized")
	}
}
func TestForgedNativeStateAndWebRedirectRegression(t *testing.T) {
	old := NativeOAuth2
	NativeOAuth2 = nativeAPI()
	defer func() { NativeOAuth2 = old }()
	_, state := nativePending(t, NativeOAuth2)
	bad, _ := nativeContext("GET", "/oauth2/callback?state="+url.QueryEscape(state+"bad")+"&code=synthetic", nil)
	if location, e := OAuth2Authentications.CallbackHandler(bad); location != "" || e != errs.ErrNativeOAuth2Unavailable {
		t.Fatal("unknown native state reflected a redirect")
	}
	a := &OAuth2AuthenticationApi{ApiUsingConfig: ApiUsingConfig{container: settings.NewConfigContainer(&settings.Config{RootUrl: "https://books.example/"})}}
	web, _ := nativeContext("GET", "/oauth2/callback", nil)
	location, e := a.redirectToSuccessCallbackPage(web, "desktop", core.USER_EXTERNAL_AUTH_TYPE_OAUTH2_OIDC, "synthetic-web-token")
	if e != nil || location != "https://books.example/desktop#/oauth2_callback?platform=desktop&provider=oidc&token=synthetic-web-token" {
		t.Fatal("existing web callback changed")
	}
	if e := NativeOAuth2.attachCallback(web, "desktop|ordinary-session|digest"); e != nil || nativeFlowID(web) != "" {
		t.Fatal("ordinary web state treated as native")
	}
}
func TestCancelCallbackNeverRevokesNormalSession(t *testing.T) {
	a := nativeAPI()
	c, w := nativeContext("POST", "/api/oauth2/cancel.json", nil)
	c.SetTokenClaims(&core.UserTokenClaims{Uid: 42, Type: core.USER_TOKEN_TYPE_NORMAL})
	if _, e := a.CancelCallbackHandler(c); e != errs.ErrInvalidToken {
		t.Fatal("normal session accepted by temporary cancel")
	}
	assertPrivateHeaders(t, w)
}

func TestNativeOversizeJSONRejected(t *testing.T) {
	for _, handler := range []func(*NativeOAuth2Api, *core.WebContext) (any, *errs.Error){func(a *NativeOAuth2Api, c *core.WebContext) (any, *errs.Error) { return a.StartHandler(c) }, func(a *NativeOAuth2Api, c *core.WebContext) (any, *errs.Error) { return a.ExchangeHandler(c) }, func(a *NativeOAuth2Api, c *core.WebContext) (any, *errs.Error) { return a.CancelHandler(c) }} {
		a := nativeAPI()
		c, w := nativeContext("POST", "/api/native/oauth2/oversize.json", map[string]string{"state": strings.Repeat("x", 5000)})
		if _, e := handler(a, c); e == nil {
			t.Fatal("oversized authentication JSON accepted")
		}
		assertPrivateHeaders(t, w)
		c.Request.ContentLength = -1
		c, w = nativeContext("POST", "/api/native/oauth2/oversize.json", map[string]string{"codeVerifier": strings.Repeat("x", 5000)})
		c.Request.ContentLength = -1
		if _, e := handler(a, c); e == nil {
			t.Fatal("oversized chunked authentication JSON accepted")
		}
		assertPrivateHeaders(t, w)
	}
}
func TestNativeBrowserStartFailureAbortsFlow(t *testing.T) {
	for _, provider := range []func(*core.WebContext, string, int64) (string, error){func(*core.WebContext, string, int64) (string, error) { return "", nativeoauth.ErrUnavailable }, func(*core.WebContext, string, int64) (string, error) { return "%", nil }, func(*core.WebContext, string, int64) (string, error) { return "https://issuer.example/authorize", nil }} {
		a := nativeAPI()
		a.authorizeURL = provider
		c, _ := nativeContext("POST", "/api/native/oauth2/start.json", nativeRequest())
		v, e := a.StartHandler(c)
		if e != nil {
			t.Fatal(e)
		}
		id := v.(*nativeOAuth2StartResponse).FlowID
		browser, w := nativeContext("GET", "/oauth2/native/start?flow_id="+url.QueryEscape(id), nil)
		if location, e := a.BrowserStartHandler(browser); location != "" || e != errs.ErrNativeOAuth2Unavailable {
			t.Fatal("failed preparation redirected")
		}
		assertPrivateHeaders(t, w)
		if _, e := a.flows.Begin(id); e != nativeoauth.ErrUnavailable {
			t.Fatal("failed browser preparation retained flow")
		}
	}
}
func TestNativeAnonymousStartCannotInjectUID(t *testing.T) {
	a := nativeAPI()
	request := nativeRequest()
	b, _ := json.Marshal(request)
	var body map[string]any
	_ = json.Unmarshal(b, &body)
	body["uid"] = 999
	c, _ := nativeContext("POST", "/api/native/oauth2/start.json", body)
	v, e := a.StartHandler(c)
	if e != nil {
		t.Fatal(e)
	}
	flow, beginErr := a.flows.Begin(v.(*nativeOAuth2StartResponse).FlowID)
	if beginErr != nil || flow.UID != 0 {
		t.Fatal("untrusted UID captured")
	}
}

func TestNativeChunkedTrailingBodyRejected(t *testing.T) {
	for _, suffix := range []string{strings.Repeat(" ", 5000), "{}"} {
		c, _ := nativeContext("POST", "/", nativeRequest())
		original, _ := json.Marshal(nativeRequest())
		c.Request.Body = io.NopCloser(strings.NewReader(string(original) + suffix))
		c.Request.ContentLength = -1
		if _, err := nativeAPI().StartHandler(c); err != errs.ErrNativeOAuth2InvalidRequest {
			t.Fatal("trailing chunked body accepted")
		}
	}
}
