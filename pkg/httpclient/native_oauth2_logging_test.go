package httpclient

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/mayswind/ezbookkeeping/pkg/core"
)

type nativeLogTransport struct{}

func (nativeLogTransport) RoundTrip(*http.Request) (*http.Response, error) {
	return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader("native-sensitive-response-sentinel"))}, nil
}
func TestNativeProviderResponseNeverEntersDebugHandler(t *testing.T) {
	for _, native := range []bool{false, true} {
		g, _ := gin.CreateTestContext(httptest.NewRecorder())
		g.Request = httptest.NewRequest("GET", "/", nil)
		c := core.WrapWebContext(g, nil)
		if native {
			c.Set(core.NativeOAuth2ContextKey, "synthetic-flow")
		}
		logged := false
		ctx := CustomHttpResponseLog(c, func(body []byte) { logged = true })
		request := httptest.NewRequest("GET", "https://issuer.example/userinfo", nil).WithContext(ctx)
		transport := &defaultTransport{enableHttpResponseLog: true, baseTransport: nativeLogTransport{}}
		response, err := transport.RoundTrip(request)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(response.Body)
		response.Body.Close()
		if string(body) != "native-sensitive-response-sentinel" {
			t.Fatal("response parser lost body")
		}
		if logged == native {
			t.Fatal("native response leaked, or existing web response logging changed")
		}
	}
}
