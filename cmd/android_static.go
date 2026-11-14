package cmd

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/gin-gonic/gin"
)

// registerAndroidDistribution serves independently published, read-only Android
// artifacts. It does not require an APK to be included in the application image.
func registerAndroidDistribution(router *gin.Engine, staticRoot string) {
	handler := androidDistributionHandler(filepath.Join(staticRoot, "android"))
	router.Match([]string{http.MethodGet, http.MethodHead}, "/android", handler)
	router.Match([]string{http.MethodGet, http.MethodHead}, "/android/*filepath", handler)
}

func androidDistributionHandler(rootPath string) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Header("X-Content-Type-Options", "nosniff")
		c.Header("Cache-Control", "no-store")
		name := strings.TrimPrefix(c.Param("filepath"), "/")
		manifest := name == "update.json"
		parts := strings.Split(name, "/")
		apk := len(parts) == 3 && parts[0] == "releases" && androidArtifactSegment(parts[1]) && androidArtifactSegment(parts[2]) && strings.HasSuffix(parts[2], ".apk")
		if !manifest && !apk {
			c.Status(http.StatusNotFound)
			return
		}

		// os.Root permits an in-root current symlink while rejecting symlinks
		// that escape the published directory. Never expose a directory listing.
		root, err := os.OpenRoot(rootPath)
		if err != nil {
			c.Status(http.StatusNotFound)
			return
		}
		defer root.Close()
		info, err := root.Stat(name)
		if err != nil || !info.Mode().IsRegular() {
			c.Status(http.StatusNotFound)
			return
		}
		file, err := root.Open(name)
		if err != nil {
			c.Status(http.StatusNotFound)
			return
		}
		defer file.Close()
		info, err = file.Stat()
		if err != nil || !info.Mode().IsRegular() {
			c.Status(http.StatusNotFound)
			return
		}
		if manifest {
			c.Header("Content-Type", "application/json")
			c.Header("Cache-Control", "no-cache, max-age=0, must-revalidate")
		} else {
			c.Header("Content-Type", "application/vnd.android.package-archive")
			c.Header("Cache-Control", "public, max-age=31536000, immutable")
		}
		http.ServeContent(c.Writer, c.Request, info.Name(), info.ModTime(), file)
	}
}

func androidArtifactSegment(value string) bool {
	if value == "" || len(value) > 128 || value[0] == '.' {
		return false
	}
	for _, c := range value {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '-' || c == '_') {
			return false
		}
	}
	return true
}
