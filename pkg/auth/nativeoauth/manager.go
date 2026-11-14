// Package nativeoauth holds bounded, ephemeral PKCE handoffs to the native app.
// Nothing survives restart; no user session token is placed in a redirect URI.
package nativeoauth

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"
)

const RedirectURI = "top.fjzhang233.books://oauth2/callback"
const FlowTTL = 5 * time.Minute
const CodeTTL = time.Minute
const MaxFlows = 128
const MaxPeerFlows = 8
const maxVerifierFailures = 5

var ErrInvalid = errors.New("invalid native oauth2 request")
var ErrUnavailable = errors.New("native oauth2 flow unavailable")
var ErrLimit = errors.New("native oauth2 flow limit")
var verifierPattern = regexp.MustCompile(`^[A-Za-z0-9._~-]{43,128}$`)
var statePattern = regexp.MustCompile(`^[A-Za-z0-9_-]{43,128}$`)

type Request struct {
	Challenge, Method, Redirect, State, Peer string
	UID                                      int64
}
type Flow struct {
	ID, State string
	UID       int64
	ExpiresAt time.Time
}
type Payload struct {
	CallbackToken    string
	RequiresPassword bool
	ErrorCode        int32
}
type pending struct {
	Flow
	challenge, peer, providerState, codeHash string
	started, claimed                         bool
	codeExpires                              time.Time
	failures                                 int
	payload                                  Payload
}
type Manager struct {
	mu    sync.Mutex
	flows map[string]*pending
	codes map[string]string
	now   func() time.Time
}

func NewManager() *Manager {
	return &Manager{flows: map[string]*pending{}, codes: map[string]string{}, now: time.Now}
}
func randomString() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}
func challenge(verifier string) string {
	sum := sha256.Sum256([]byte(verifier))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}
func codeKey(code string) string { sum := sha256.Sum256([]byte(code)); return string(sum[:]) }
func validChallenge(s string) bool {
	b, e := base64.RawURLEncoding.DecodeString(s)
	return e == nil && len(s) == 43 && len(b) == 32 && base64.RawURLEncoding.EncodeToString(b) == s
}
func (m *Manager) remove(id string) {
	if f := m.flows[id]; f != nil {
		delete(m.codes, f.codeHash)
	}
	delete(m.flows, id)
}
func (m *Manager) prune() {
	now := m.now()
	for id, f := range m.flows {
		if !now.Before(f.ExpiresAt) || f.codeHash != "" && !now.Before(f.codeExpires) {
			m.remove(id)
		}
	}
}

func (m *Manager) Create(r Request) (Flow, error) {
	if r.Redirect != RedirectURI || r.Method != "S256" || !validChallenge(r.Challenge) || !statePattern.MatchString(r.State) || r.UID < 0 {
		return Flow{}, ErrInvalid
	}
	id, err := randomString()
	if err != nil {
		return Flow{}, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.prune()
	peers := 0
	for _, f := range m.flows {
		if f.peer == r.Peer {
			peers++
		}
	}
	if len(m.flows) >= MaxFlows || peers >= MaxPeerFlows {
		return Flow{}, ErrLimit
	}
	flow := Flow{ID: "native_" + id, State: r.State, UID: r.UID, ExpiresAt: m.now().Add(FlowTTL)}
	m.flows[flow.ID] = &pending{Flow: flow, challenge: r.Challenge, peer: r.Peer}
	return flow, nil
}
func (m *Manager) Begin(id string) (Flow, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.prune()
	f := m.flows[id]
	if f == nil || f.started {
		return Flow{}, ErrUnavailable
	}
	f.started = true
	return f.Flow, nil
}
func (m *Manager) SetProviderState(id, state string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.prune()
	f := m.flows[id]
	if f == nil || !f.started || f.providerState != "" || state == "" {
		return ErrUnavailable
	}
	f.providerState = state
	return nil
}
func IsNativeState(state string) bool {
	parts := strings.Split(state, "|")
	return len(parts) == 3 && strings.HasPrefix(parts[1], "native_")
}

// Attach claims the exact state once, before any provider response is logged.
func (m *Manager) Attach(state string) (string, error) {
	parts := strings.Split(state, "|")
	if len(parts) != 3 || !strings.HasPrefix(parts[1], "native_") {
		return "", ErrUnavailable
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.prune()
	f := m.flows[parts[1]]
	if f == nil || !f.started || f.claimed || f.providerState == "" || subtle.ConstantTimeCompare([]byte(state), []byte(f.providerState)) != 1 {
		return "", ErrUnavailable
	}
	f.claimed = true
	return f.ID, nil
}
func (m *Manager) Publish(id string, payload Payload) (string, error) {
	code, err := randomString()
	if err != nil {
		return "", err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.prune()
	f := m.flows[id]
	if f == nil || !f.claimed || f.codeHash != "" {
		return "", ErrUnavailable
	}
	f.codeHash = codeKey(code)
	f.codeExpires = m.now().Add(CodeTTL)
	f.payload = payload
	m.codes[f.codeHash] = id
	u, _ := url.Parse(RedirectURI)
	q := url.Values{"code": {code}, "state": {f.State}}
	u.RawQuery = q.Encode()
	return u.String(), nil
}
func (m *Manager) Exchange(id, code, verifier string) (Payload, error) {
	if !verifierPattern.MatchString(verifier) || !validChallenge(code) {
		return Payload{}, ErrUnavailable
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.prune()
	f := m.flows[id]
	if f == nil || m.codes[codeKey(code)] != id || f.codeHash != codeKey(code) {
		return Payload{}, ErrUnavailable
	}
	if subtle.ConstantTimeCompare([]byte(challenge(verifier)), []byte(f.challenge)) != 1 {
		f.failures++
		if f.failures >= maxVerifierFailures {
			m.remove(id)
		}
		return Payload{}, ErrUnavailable
	}
	payload := f.payload
	m.remove(id)
	return payload, nil
}

// Cancel requires the same verifier even before the browser has completed.
func (m *Manager) Cancel(id, verifier string) (Payload, error) {
	if !verifierPattern.MatchString(verifier) {
		return Payload{}, ErrUnavailable
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.prune()
	f := m.flows[id]
	if f == nil || subtle.ConstantTimeCompare([]byte(challenge(verifier)), []byte(f.challenge)) != 1 {
		return Payload{}, ErrUnavailable
	}
	payload := f.payload
	m.remove(id)
	return payload, nil
}

// Abort drops a server-owned flow after browser-start preparation fails.
// Public cancellation must use Cancel with its verifier instead.
func (m *Manager) Abort(id string) { m.mu.Lock(); defer m.mu.Unlock(); m.remove(id) }
