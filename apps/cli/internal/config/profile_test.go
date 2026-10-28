package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestReadProfileMissing(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nope.yaml")
	if _, err := readProfile(path); err == nil {
		t.Fatal("配置档不存在时应该报错")
	}
}

func TestMergeProfile(t *testing.T) {
	base := Config{
		Server:   "http://base/api/v1",
		ClientID: "base-client",
		Network:  NetworkConfig{TimeoutSeconds: 30, MaxRetries: 5},
		Agents: map[string]Agent{
			"claude-code": {Enabled: true, UserSkillsDir: "/home/u/.claude/skills"},
			"cursor":      {Enabled: false},
		},
		Verify: VerifyConfig{PinnedKeyID: "base-key"},
		Sync:   SyncConfig{ExtraDirs: []string{"/base/dir"}},
	}
	off := false
	profile := profileFile{
		Server:  "http://profile/api/v1",
		Network: NetworkConfig{TimeoutSeconds: 60},
		Agents: map[string]profileAgent{
			"claude-code": {UserSkillsDir: "/profile/skills"},
			"cursor":      {Enabled: &off},
		},
		Sync: SyncConfig{ExtraDirs: []string{"/profile/dir"}, PropagateDelete: true},
	}

	merged := mergeProfile(base, profile)
	if merged.Server != "http://profile/api/v1" {
		t.Fatalf("server = %q, want 配置档里的", merged.Server)
	}
	if merged.ClientID != "base-client" {
		t.Fatalf("client_id = %q, want 沿用基础配置", merged.ClientID)
	}
	if merged.Network.TimeoutSeconds != 60 || merged.Network.MaxRetries != 5 {
		t.Fatalf("network = %+v, want 超时取配置档、重试次数沿用基础配置", merged.Network)
	}
	claude := merged.Agents["claude-code"]
	if claude.UserSkillsDir != "/profile/skills" {
		t.Fatalf("claude-code 目录 = %q, want 配置档里的", claude.UserSkillsDir)
	}
	if !claude.Enabled {
		t.Fatal("配置档没写 enabled 时应沿用基础配置")
	}
	cursor := merged.Agents["cursor"]
	if cursor.Enabled {
		t.Fatal("配置档显式写 enabled: false 时应关掉")
	}
	if len(merged.Sync.ExtraDirs) != 1 || merged.Sync.ExtraDirs[0] != "/profile/dir" {
		t.Fatalf("额外目录 = %v, want 配置档里的", merged.Sync.ExtraDirs)
	}
	if !merged.Sync.PropagateDelete {
		t.Fatal("配置档里的 propagate_delete 应生效")
	}
	if merged.Verify.PinnedKeyID != "base-key" {
		t.Fatalf("验签公钥 = %q, want 沿用基础配置", merged.Verify.PinnedKeyID)
	}
}

func TestMergeProfilePinsVerify(t *testing.T) {
	base := Config{Verify: VerifyConfig{PinnedKeyID: "base-key"}}
	profile := profileFile{Verify: VerifyConfig{PinnedKeyID: "profile-key", PinnedPublicKey: "pub"}}
	merged := mergeProfile(base, profile)
	if merged.Verify.PinnedKeyID != "profile-key" || merged.Verify.PinnedPublicKey != "pub" {
		t.Fatalf("验签公钥 = %+v, want 配置档里的", merged.Verify)
	}
}

func TestReadProfileParses(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "staging.yaml")
	raw := "server: http://staging/api/v1\nagents:\n  cursor:\n    enabled: false\n"
	if err := os.WriteFile(path, []byte(raw), 0o644); err != nil {
		t.Fatal(err)
	}
	profile, err := readProfile(path)
	if err != nil {
		t.Fatalf("读配置档失败: %v", err)
	}
	if profile.Server != "http://staging/api/v1" {
		t.Fatalf("server = %q", profile.Server)
	}
	cursor, ok := profile.Agents["cursor"]
	if !ok || cursor.Enabled == nil || *cursor.Enabled {
		t.Fatalf("cursor 的 enabled = %v, want 显式 false", cursor.Enabled)
	}
}
