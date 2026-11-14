package nativeoauth

import (
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func request(peer string) Request {
	return Request{Challenge: challenge(strings.Repeat("v", 43)), Method: "S256", Redirect: RedirectURI, State: strings.Repeat("s", 43), Peer: peer, UID: 42}
}
func issued(t *testing.T, m *Manager, peer string) (Flow, string) {
	t.Helper()
	f, e := m.Create(request(peer))
	if e != nil {
		t.Fatal(e)
	}
	b, e := m.Begin(f.ID)
	if e != nil || b.UID != 42 {
		t.Fatal("captured identity lost")
	}
	state := "mobile|" + f.ID + "|state-digest"
	if e = m.SetProviderState(f.ID, state); e != nil {
		t.Fatal(e)
	}
	if _, e = m.Attach(state); e != nil {
		t.Fatal(e)
	}
	uri, e := m.Publish(f.ID, Payload{CallbackToken: "synthetic-callback-only", RequiresPassword: true})
	if e != nil {
		t.Fatal(e)
	}
	u, e := url.Parse(uri)
	if e != nil || u.Scheme != "top.fjzhang233.books" || u.Host != "oauth2" || u.Path != "/callback" || len(u.Query()) != 2 || u.Query().Get("state") != f.State || strings.Contains(uri, "synthetic-callback") {
		t.Fatal("unsafe redirect")
	}
	return f, u.Query().Get("code")
}
func TestSingleUsePKCEAndCrossFlow(t *testing.T) {
	m := NewManager()
	f, code := issued(t, m, "one")
	other, otherCode := issued(t, m, "two")
	if _, e := m.Exchange(other.ID, code, strings.Repeat("v", 43)); e != ErrUnavailable {
		t.Fatal("cross-flow accepted")
	}
	if _, e := m.Exchange(f.ID, code, strings.Repeat("x", 43)); e != ErrUnavailable {
		t.Fatal("wrong verifier accepted")
	}
	p, e := m.Exchange(f.ID, code, strings.Repeat("v", 43))
	if e != nil || !p.RequiresPassword || p.CallbackToken != "synthetic-callback-only" {
		t.Fatal("legitimate verifier must work after one wrong attempt")
	}
	if _, e = m.Exchange(f.ID, code, strings.Repeat("v", 43)); e != ErrUnavailable {
		t.Fatal("replay accepted")
	}
	if _, e = m.Exchange(other.ID, otherCode, strings.Repeat("v", 43)); e != nil {
		t.Fatal("cross-flow attempt damaged other flow")
	}
}
func TestConcurrentConsume(t *testing.T) {
	m := NewManager()
	f, code := issued(t, m, "one")
	var successes atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 32; i++ {
		wg.Go(func() {
			if _, e := m.Exchange(f.ID, code, strings.Repeat("v", 43)); e == nil {
				successes.Add(1)
			}
		})
	}
	wg.Wait()
	if successes.Load() != 1 {
		t.Fatal("code was not atomically single-use")
	}
}
func TestExpiryCleanupAndRestart(t *testing.T) {
	m := NewManager()
	now := time.Unix(1800000000, 0)
	m.now = func() time.Time { return now }
	f, code := issued(t, m, "one")
	now = now.Add(CodeTTL)
	if _, e := m.Exchange(f.ID, code, strings.Repeat("v", 43)); e != ErrUnavailable {
		t.Fatal("expired ticket accepted")
	}
	if len(m.flows) != 0 || len(m.codes) != 0 {
		t.Fatal("expired state retained")
	}
	f, code = issued(t, m, "two")
	if _, e := NewManager().Exchange(f.ID, code, strings.Repeat("v", 43)); e != ErrUnavailable {
		t.Fatal("restart retained handoff")
	}
	pending, e := m.Create(request("three"))
	if e != nil {
		t.Fatal(e)
	}
	now = now.Add(FlowTTL)
	if _, e = m.Begin(pending.ID); e != ErrUnavailable {
		t.Fatal("expired pending flow accepted")
	}
}
func TestValidationAndLimits(t *testing.T) {
	for _, mutate := range []func(*Request){func(r *Request) { r.Redirect = "https://attacker.invalid/callback" }, func(r *Request) { r.Redirect = RedirectURI + "?next=attacker" }, func(r *Request) { r.Method = "plain" }, func(r *Request) { r.Challenge = strings.Repeat("a", 42) }, func(r *Request) { r.Challenge = strings.Repeat("a", 43) + "=" }, func(r *Request) { r.State = "weak" }, func(r *Request) { r.State = strings.Repeat("s", 42) + "|" }} {
		r := request("one")
		mutate(&r)
		if _, e := NewManager().Create(r); e != ErrInvalid {
			t.Fatal("invalid start accepted")
		}
	}
	m := NewManager()
	for i := 0; i < MaxPeerFlows; i++ {
		if _, e := m.Create(request("same")); e != nil {
			t.Fatal(e)
		}
	}
	if _, e := m.Create(request("same")); e != ErrLimit {
		t.Fatal("peer quota missing")
	}
	m = NewManager()
	for i := 0; i < MaxFlows; i++ {
		r := request(strings.Repeat("p", i+1))
		if _, e := m.Create(r); e != nil {
			t.Fatal(e)
		}
	}
	if _, e := m.Create(request("new")); e != ErrLimit {
		t.Fatal("global quota missing")
	}
}
func TestCallbackClaimAndCancel(t *testing.T) {
	m := NewManager()
	f, e := m.Create(request("one"))
	if e != nil {
		t.Fatal(e)
	}
	if _, e = m.Begin(f.ID); e != nil {
		t.Fatal(e)
	}
	if _, e = m.Begin(f.ID); e != ErrUnavailable {
		t.Fatal("browser flow restarted")
	}
	state := "mobile|" + f.ID + "|digest"
	_ = m.SetProviderState(f.ID, state)
	if _, e = m.Attach(state + "wrong"); e != ErrUnavailable {
		t.Fatal("forged state accepted")
	}
	if _, e = m.Attach(state); e != nil {
		t.Fatal(e)
	}
	if _, e = m.Attach(state); e != ErrUnavailable {
		t.Fatal("callback replay accepted")
	}
	if _, e = m.Cancel(f.ID, strings.Repeat("x", 43)); e != ErrUnavailable {
		t.Fatal("foreign cancellation accepted")
	}
	if _, e = m.Cancel(f.ID, strings.Repeat("v", 43)); e != nil {
		t.Fatal(e)
	}
	if _, e = m.Publish(f.ID, Payload{}); e != ErrUnavailable {
		t.Fatal("cancelled flow completed")
	}
}
func TestVerifierBoundariesAndAttemptLimit(t *testing.T) {
	for _, v := range []string{strings.Repeat("v", 42), strings.Repeat("v", 129), strings.Repeat("v", 42) + "=", strings.Repeat("v", 42) + "é"} {
		m := NewManager()
		f, code := issued(t, m, "one")
		if _, e := m.Exchange(f.ID, code, v); e != ErrUnavailable {
			t.Fatal("invalid verifier accepted")
		}
	}
	m := NewManager()
	f, code := issued(t, m, "one")
	for i := 0; i < maxVerifierFailures; i++ {
		_, _ = m.Exchange(f.ID, code, strings.Repeat("x", 43))
	}
	if _, e := m.Exchange(f.ID, code, strings.Repeat("v", 43)); e != ErrUnavailable {
		t.Fatal("attempt bound ignored")
	}
}
