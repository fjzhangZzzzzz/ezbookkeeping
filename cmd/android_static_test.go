package cmd

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

func androidTestRouter(root string) *gin.Engine {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	registerAndroidDistribution(router, root)
	return router
}

func androidTestRequest(router http.Handler, method, path string, headers map[string]string) *httptest.ResponseRecorder {
	request := httptest.NewRequest(method, path, nil)
	for key, value := range headers {
		request.Header.Set(key, value)
	}
	response := httptest.NewRecorder()
	router.ServeHTTP(response, request)
	return response
}

func TestAndroidDistributionAnonymousCacheAndRange(t *testing.T) {
	root := t.TempDir()
	directory := filepath.Join(root, "android", "releases", "0.1.1")
	if err := os.MkdirAll(directory, 0700); err != nil {
		t.Fatal(err)
	}
	manifest := `{"schemaVersion":1,"versionCode":3}`
	apk := "PK\x03\x04synthetic-apk-bytes"
	if err := os.WriteFile(filepath.Join(root, "android", "update.json"), []byte(manifest), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(directory, "ezbookkeeping-android-0.1.1.apk"), []byte(apk), 0600); err != nil {
		t.Fatal(err)
	}
	router := androidTestRouter(root)
	for _, item := range []struct{ path, body, mime, cache string }{
		{"/android/update.json", manifest, "application/json", "no-cache, max-age=0, must-revalidate"},
		{"/android/releases/0.1.1/ezbookkeeping-android-0.1.1.apk", apk, "application/vnd.android.package-archive", "public, max-age=31536000, immutable"},
	} {
		t.Run(item.path, func(t *testing.T) {
			get := androidTestRequest(router, http.MethodGet, item.path, nil)
			if get.Code != 200 || get.Body.String() != item.body {
				t.Fatalf("GET status/body mismatch: %d", get.Code)
			}
			if get.Header().Get("Content-Type") != item.mime || get.Header().Get("Cache-Control") != item.cache || get.Header().Get("X-Content-Type-Options") != "nosniff" {
				t.Fatal("MIME/cache/nosniff contract mismatch")
			}
			modified := get.Header().Get("Last-Modified")
			if modified == "" {
				t.Fatal("Last-Modified absent")
			}
			head := androidTestRequest(router, http.MethodHead, item.path, nil)
			if head.Code != 200 || head.Body.Len() != 0 || head.Header().Get("Content-Type") != item.mime {
				t.Fatal("HEAD mismatch")
			}
			conditional := androidTestRequest(router, http.MethodGet, item.path, map[string]string{"If-Modified-Since": modified})
			if conditional.Code != http.StatusNotModified || conditional.Body.Len() != 0 {
				t.Fatal("Conditional GET mismatch")
			}
		})
	}
	ranged := androidTestRequest(router, http.MethodGet, "/android/releases/0.1.1/ezbookkeeping-android-0.1.1.apk", map[string]string{"Range": "bytes=0-3"})
	if ranged.Code != http.StatusPartialContent || ranged.Body.String() != apk[:4] {
		t.Fatal("APK range mismatch")
	}
}

func TestAndroidDistributionRejectsUnpublishedPaths(t *testing.T) {
	root := t.TempDir()
	directory := filepath.Join(root, "android", "releases", "0.1.1")
	if err := os.MkdirAll(directory, 0700); err != nil {
		t.Fatal(err)
	}
	for _, file := range []string{"private.txt", ".hidden.apk", "directory.apk/index.html"} {
		p := filepath.Join(directory, file)
		if err := os.MkdirAll(filepath.Dir(p), 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte("must never be served"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	router := androidTestRouter(root)
	for _, path := range []string{"/android", "/android/", "/android/missing.json", "/android/releases/", "/android/releases/0.1.1/", "/android/releases/0.1.1/private.txt", "/android/releases/0.1.1/.hidden.apk", "/android/releases/0.1.1/directory.apk", "/android/releases/../private.apk", "/android/releases/%2e%2e/private.apk", "/android/releases/0.1.1/a/b.apk", "/android/.env"} {
		response := androidTestRequest(router, http.MethodGet, path, nil)
		if response.Code != 404 || strings.Contains(response.Body.String(), "must never") {
			t.Errorf("Unpublished path was not rejected: %s (%d)", path, response.Code)
		}
	}
	response := androidTestRequest(router, http.MethodPost, "/android/update.json", nil)
	if response.Code == 200 {
		t.Fatal("POST accepted")
	}
	missing := androidTestRequest(androidTestRouter(t.TempDir()), http.MethodGet, "/android/update.json", nil)
	if missing.Code != 404 {
		t.Fatal("Unmounted distribution should be 404")
	}
}

func TestAndroidDistributionSymlinkBoundary(t *testing.T) {
	root := t.TempDir()
	release := filepath.Join(root, "android", "releases", "0.1.1")
	if err := os.MkdirAll(release, 0700); err != nil {
		t.Fatal(err)
	}
	manifest := filepath.Join(release, "update.json")
	if err := os.WriteFile(manifest, []byte(`{"versionCode":3}`), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join("releases", "0.1.1"), filepath.Join(root, "android", "current")); err != nil {
		t.Skipf("Symlink creation unavailable: %v", err)
	}
	if err := os.Symlink(filepath.Join("current", "update.json"), filepath.Join(root, "android", "update.json")); err != nil {
		t.Fatal(err)
	}
	router := androidTestRouter(root)
	if androidTestRequest(router, http.MethodGet, "/android/update.json", nil).Code != 200 {
		t.Fatal("In-root manifest selection rejected")
	}
	outside := filepath.Join(t.TempDir(), "outside.apk")
	if err := os.WriteFile(outside, []byte("private-outside"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(release, "escape.apk")); err != nil {
		t.Fatal(err)
	}
	response := androidTestRequest(router, http.MethodGet, "/android/releases/0.1.1/escape.apk", nil)
	if response.Code != 404 || strings.Contains(response.Body.String(), "private-outside") {
		t.Fatal("Escaping symlink was served")
	}
}
