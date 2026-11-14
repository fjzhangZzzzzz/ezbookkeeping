package core

import "context"

// NativeOAuth2ContextKey marks sensitive native identity-provider requests.
const NativeOAuth2ContextKey = "NATIVE_OAUTH2_FLOW_ID"

// Context is the base context of ezBookkeeping
type Context interface {
	context.Context
	ClientIP() string
	GetContextId() string
	GetClientLocale() string
}
