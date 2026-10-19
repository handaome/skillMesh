//go:build windows

package config

import (
	"os"
	"path/filepath"
)

// DefaultPaths 按详细设计 §3.1.1：配置与缓存分开放。
func DefaultPaths() (configDir, cacheDir string) {
	return filepath.Join(os.Getenv("APPDATA"), "SkillMesh"),
		filepath.Join(os.Getenv("LOCALAPPDATA"), "SkillMesh", "Cache")
}

func homeDir() string {
	if v := os.Getenv("USERPROFILE"); v != "" {
		return v
	}
	return os.Getenv("HOME")
}

// codexHome 尊重 CODEX_HOME，Agent 目录约定可能随版本变化（设计文档 §8 注）。
func codexHome() string {
	if v := os.Getenv("CODEX_HOME"); v != "" {
		return v
	}
	return filepath.Join(homeDir(), ".codex")
}
