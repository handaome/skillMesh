//go:build !windows

package config

import (
	"os"
	"path/filepath"
)

// DefaultPaths 按详细设计 §3.1.1 走 XDG 约定。
func DefaultPaths() (configDir, cacheDir string) {
	if x := os.Getenv("XDG_CONFIG_HOME"); x != "" {
		configDir = filepath.Join(x, "skillmesh")
	} else {
		configDir = filepath.Join(homeDir(), ".config", "skillmesh")
	}
	if x := os.Getenv("XDG_CACHE_HOME"); x != "" {
		cacheDir = filepath.Join(x, "skillmesh")
	} else {
		cacheDir = filepath.Join(homeDir(), ".cache", "skillmesh")
	}
	return configDir, cacheDir
}

func homeDir() string { return os.Getenv("HOME") }

func codexHome() string {
	if v := os.Getenv("CODEX_HOME"); v != "" {
		return v
	}
	return filepath.Join(homeDir(), ".codex")
}
