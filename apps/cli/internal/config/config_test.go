package config

import (
	"os"
	"path/filepath"
	"testing"
)

func Test缺文件时用默认值(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("APPDATA", filepath.Join(dir, "AppData", "Roaming"))
	t.Setenv("LOCALAPPDATA", filepath.Join(dir, "AppData", "Local"))
	t.Setenv("USERPROFILE", filepath.Join(dir, "home"))
	os.Unsetenv("SKILLMESH_SERVER")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load 返回错误: %v", err)
	}
	if cfg.Server != defaultServer {
		t.Fatalf("默认 server 不对: %s", cfg.Server)
	}
	if cfg.ClientID != defaultClientID {
		t.Fatalf("默认 client_id 不对: %s", cfg.ClientID)
	}
	if cfg.ConfigDir != filepath.Join(dir, "AppData", "Roaming", "SkillMesh") {
		t.Fatalf("Windows 配置目录不对: %s", cfg.ConfigDir)
	}
	if cfg.CacheDir != filepath.Join(dir, "AppData", "Local", "SkillMesh", "Cache") {
		t.Fatalf("Windows 缓存目录不对: %s", cfg.CacheDir)
	}
	if cfg.Agents["claude-code"].UserSkillsDir == "" {
		t.Fatal("claude-code 适配器目录为空")
	}
	if !cfg.Agents["claude-code"].Enabled {
		t.Fatal("claude-code 默认应启用")
	}
}

func Test环境变量覆盖server(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("APPDATA", filepath.Join(dir, "roaming"))
	t.Setenv("LOCALAPPDATA", filepath.Join(dir, "local"))
	t.Setenv("SKILLMESH_SERVER", "https://skills.example.com/api/v1")

	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Server != "https://skills.example.com/api/v1" {
		t.Fatalf("环境变量未生效: %s", cfg.Server)
	}
}

func Test配置文件覆盖默认值(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("APPDATA", dir)
	t.Setenv("LOCALAPPDATA", dir)
	os.Unsetenv("SKILLMESH_SERVER")

	cfgDir := filepath.Join(dir, "SkillMesh")
	if err := os.MkdirAll(cfgDir, 0o700); err != nil {
		t.Fatal(err)
	}
	body := "server: https://from-file.example.com\nclient_id: other-cli\n"
	if err := os.WriteFile(filepath.Join(cfgDir, "config.yaml"), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}

	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Server != "https://from-file.example.com" {
		t.Fatalf("配置文件未生效: %s", cfg.Server)
	}
	if cfg.ClientID != "other-cli" {
		t.Fatalf("配置文件未覆盖 client_id: %s", cfg.ClientID)
	}
}

func Test配置文件里的钉住公钥要读进来(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("APPDATA", dir)
	t.Setenv("LOCALAPPDATA", dir)
	cfgDir := filepath.Join(dir, "SkillMesh")
	if err := os.MkdirAll(cfgDir, 0o700); err != nil {
		t.Fatal(err)
	}
	body := "verify:\n  pinned_key_id: local-dev\n  pinned_public_key: cHVibGlja2V5\n"
	if err := os.WriteFile(filepath.Join(cfgDir, "config.yaml"), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}

	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	// 漏读这一段，cfg.Verify 恒为空，Pinned() 恒为 false——
	// 注册中心换把钥匙照样验签通过，信任根就没了。
	if !cfg.Verify.Pinned() {
		t.Fatal("配置文件里的钉住公钥没读进来")
	}
	if cfg.Verify.PinnedKeyID != "local-dev" || cfg.Verify.PinnedPublicKey != "cHVibGlja2V5" {
		t.Fatalf("钉住公钥内容不对: %+v", cfg.Verify)
	}
}
