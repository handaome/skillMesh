package config_test

import (
	config "github.com/handaome/skillmesh/apps/cli/internal/config"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func Test缺文件时用默认值(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("APPDATA", filepath.Join(dir, "AppData", "Roaming"))
	t.Setenv("LOCALAPPDATA", filepath.Join(dir, "AppData", "Local"))
	t.Setenv("USERPROFILE", filepath.Join(dir, "home"))
	os.Unsetenv("SKILLMESH_SERVER")

	Cfg, err := config.Load()
	if err != nil {
		t.Fatalf("Load 返回错误: %v", err)
	}
	if Cfg.Server != config.DefaultServer {
		t.Fatalf("默认 server 不对: %s", Cfg.Server)
	}
	if Cfg.ClientID != config.DefaultClientID {
		t.Fatalf("默认 client_id 不对: %s", Cfg.ClientID)
	}
	if Cfg.ConfigDir != filepath.Join(dir, "AppData", "Roaming", "SkillMesh") {
		t.Fatalf("Windows 配置目录不对: %s", Cfg.ConfigDir)
	}
	if Cfg.CacheDir != filepath.Join(dir, "AppData", "Local", "SkillMesh", "Cache") {
		t.Fatalf("Windows 缓存目录不对: %s", Cfg.CacheDir)
	}
	if Cfg.Agents["claude-code"].UserSkillsDir == "" {
		t.Fatal("claude-code 适配器目录为空")
	}
	if !Cfg.Agents["claude-code"].Enabled {
		t.Fatal("claude-code 默认应启用")
	}
	// Cursor 目录约定还没在真实安装上验证过（设计文档 §4 把它列在后续增强），
	// 默认关着——要用就在 config.yaml 里打开。
	if Cfg.Agents["cursor"].Enabled {
		t.Fatal("cursor 默认应关着")
	}
	if !Cfg.Agents["codex"].Enabled {
		t.Fatal("codex 默认应启用")
	}
}

func Test环境变量覆盖server(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("APPDATA", filepath.Join(dir, "roaming"))
	t.Setenv("LOCALAPPDATA", filepath.Join(dir, "local"))
	t.Setenv("SKILLMESH_SERVER", "https://skills.example.com/api/v1")

	Cfg, err := config.Load()
	if err != nil {
		t.Fatal(err)
	}
	if Cfg.Server != "https://skills.example.com/api/v1" {
		t.Fatalf("环境变量未生效: %s", Cfg.Server)
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

	Cfg, err := config.Load()
	if err != nil {
		t.Fatal(err)
	}
	if Cfg.Server != "https://from-file.example.com" {
		t.Fatalf("配置文件未生效: %s", Cfg.Server)
	}
	if Cfg.ClientID != "other-cli" {
		t.Fatalf("配置文件未覆盖 client_id: %s", Cfg.ClientID)
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

	Cfg, err := config.Load()
	if err != nil {
		t.Fatal(err)
	}
	// 漏读这一段，Cfg.Verify 恒为空，Pinned() 恒为 false——
	// 注册中心换把钥匙照样验签通过，信任根就没了。
	if !Cfg.Verify.Pinned() {
		t.Fatal("配置文件里的钉住公钥没读进来")
	}
	if Cfg.Verify.PinnedKeyID != "local-dev" || Cfg.Verify.PinnedPublicKey != "cHVibGlja2V5" {
		t.Fatalf("钉住公钥内容不对: %+v", Cfg.Verify)
	}
}

// 额外扫描的目录要读进来，并且展开 ~（设计文档 §6.1：项目级与自定义目录）。
func Test配置文件里的额外扫描目录要读进来(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("APPDATA", dir)
	t.Setenv("LOCALAPPDATA", dir)
	t.Setenv("USERPROFILE", filepath.Join(dir, "home"))
	cfgDir := filepath.Join(dir, "SkillMesh")
	if err := os.MkdirAll(cfgDir, 0o700); err != nil {
		t.Fatal(err)
	}
	body := "sync:\n  extra_dirs:\n    - \"~/projects/app/.claude/skills\"\n    - /srv/shared/skills\n"
	if err := os.WriteFile(filepath.Join(cfgDir, "config.yaml"), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}

	cfg, err := config.Load()
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Sync.ExtraDirs) != 2 {
		t.Fatalf("该读到 2 个额外目录，实际 %d 个：%v", len(cfg.Sync.ExtraDirs), cfg.Sync.ExtraDirs)
	}
	if !strings.HasPrefix(cfg.Sync.ExtraDirs[0], filepath.Join(dir, "home")) {
		t.Fatalf("~ 该展开成用户目录，实际 %q", cfg.Sync.ExtraDirs[0])
	}
	if cfg.Sync.ExtraDirs[1] != "/srv/shared/skills" {
		t.Fatalf("绝对路径不该被改，实际 %q", cfg.Sync.ExtraDirs[1])
	}
}
