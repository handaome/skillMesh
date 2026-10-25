package cmd_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	cmd "github.com/handaome/skillmesh/apps/cli/internal/cmd"
	"github.com/handaome/skillmesh/apps/cli/internal/config"
	"github.com/handaome/skillmesh/apps/cli/internal/packager"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
)

// writeSkill 写一个最小的 Skill 目录，返回它的路径。
func writeSkill(t *testing.T, dir, content string) string {
	t.Helper()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "SKILL.md"), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return dir
}

// 基线只在双方认可同一份内容时才能前进。
// 一次「无变化」的同步就把逐文件摘要清掉的话，下一次真冲突就再也合不了。
func Test无变化的同步也留下逐文件基线(t *testing.T) {
	skill := writeSkill(t, filepath.Join(t.TempDir(), "demo"), "# demo\n")

	old := cmd.Cfg.ConfigDir
	cmd.Cfg.ConfigDir = t.TempDir()
	defer func() { cmd.Cfg.ConfigDir = old }()

	artifact, err := packager.Pack(skill)
	if err != nil {
		t.Fatal(err)
	}
	target := cmd.SyncTarget{Name: "demo", Agent: "claude-code", Path: skill}
	result, entry, err := cmd.ApplyDecision(context.Background(), nil, target, syncstate.UpToDate,
		artifact, cmd.ManifestDigest(artifact.Manifest), apiclient.Skill{ID: "skill-1"}, true,
		cmd.SyncOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if result.Outcome != cmd.Skipped {
		t.Fatalf("应判无变化，得到 %s", result.Outcome)
	}
	if len(entry.BaseFiles) == 0 {
		t.Fatalf("无变化的同步不该把逐文件基线清掉：%+v", entry)
	}
	if entry.BaseSnapshot == "" {
		t.Fatalf("基线该留一份内容副本：%+v", entry)
	}
}

// 本地领先云端、这次只下载时，基线不能被写成本地当前内容：
// 写歪了下一轮就会判成「本地落后」，把用户本地的改动下载掉。
func Test方向过滤的同步不改基线(t *testing.T) {
	skill := writeSkill(t, filepath.Join(t.TempDir(), "demo"), "# demo\n")

	artifact, err := packager.Pack(skill)
	if err != nil {
		t.Fatal(err)
	}
	target := cmd.SyncTarget{Name: "demo", Agent: "claude-code", Path: skill,
		BaseHash: "cloud-base", SkillID: "skill-1",
		BaseFiles: map[string]string{"SKILL.md": "sha"}, BaseSnapshot: "/base/snapshot"}
	_, entry, err := cmd.ApplyDecision(context.Background(), nil, target, syncstate.Upload,
		artifact, cmd.ManifestDigest(artifact.Manifest), apiclient.Skill{ID: "skill-1"}, true,
		cmd.SyncOptions{Direction: "download"})
	if err != nil {
		t.Fatal(err)
	}
	if entry.BaseHash != "cloud-base" {
		t.Fatalf("跳过上传不该动基线摘要，得到 %s", entry.BaseHash)
	}
	if entry.BaseSnapshot != "/base/snapshot" {
		t.Fatalf("跳过上传不该动基线副本，得到 %s", entry.BaseSnapshot)
	}
	if len(entry.BaseFiles) == 0 {
		t.Fatalf("跳过上传不该把逐文件基线清掉：%+v", entry)
	}
	// 下一轮不能判成「本地落后」——那会把用户本地的改动下载掉。
	next := syncstate.Decide(entry.BaseHash, cmd.ManifestDigest(artifact.Manifest), "cloud-base")
	if next != syncstate.Upload {
		t.Fatalf("本地领先时不该判成下载，得到 %s", next)
	}
}

// 服务端明确判冲突才算冲突；服务端自己出错是失败——失败才进重试队列，
// 报成冲突会让用户去 --resolve，白折腾一轮。
func Test服务端出错不当成冲突(t *testing.T) {
	target := cmd.SyncTarget{Name: "demo", Path: filepath.Join(t.TempDir(), "demo")}
	conflict := cmd.OutcomeForError(target, fmt.Errorf("%w（云端已变）", cmd.ErrConflict))
	if conflict.Outcome != cmd.Conflicted {
		t.Fatalf("服务端判的冲突该报冲突，得到 %s", conflict.Outcome)
	}
	failure := cmd.OutcomeForError(target, errors.New("INTERNAL (500): 服务端崩了"))
	if failure.Outcome != cmd.Failed {
		t.Fatalf("服务端出错该报失败，得到 %s", failure.Outcome)
	}
}

// 幂等键必须带 skill id：同一台设备上两个 Skill 的内容摘要与基线可能一模一样
// （同一份模板装到两个目录），不带 id 服务端会把第二个当重复提交吞掉。
func Test幂等键带skillID(t *testing.T) {
	hash := strings.Repeat("a", 64)
	base := strings.Repeat("b", 64)
	if cmd.IdempotencyKey("skill-1", hash, base) == cmd.IdempotencyKey("skill-2", hash, base) {
		t.Fatal("两个不同的 Skill 不该撞幂等键")
	}
}

// 同步下来的 Skill 也要登记安装实例：不然 installations 里看不到任何同步来的 Skill。
func Test下载后登记安装实例(t *testing.T) {
	cloud := writeSkill(t, filepath.Join(t.TempDir(), "demo"), "# demo\n")
	artifact, err := packager.Pack(cloud)
	if err != nil {
		t.Fatal(err)
	}
	var got apiclient.RegisterInstallationInput
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/skills/skill-1/versions":
			w.Write([]byte(`{"items":[{"version":"1.0.0","status":"published",` +
				`"artifact":{"id":"art-1","sha256":"` + artifact.SHA256 + `"}}],"has_more":false}`))
		case r.Method == http.MethodGet && r.URL.Path == "/artifacts/art-1/download":
			w.Write(artifact.Zip)
		case r.Method == http.MethodPost && r.URL.Path == "/devices":
			w.Write([]byte(`{"id":"dev-1"}`))
		case r.Method == http.MethodPost && r.URL.Path == "/installations":
			if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
				t.Error(err)
			}
			w.Write([]byte(`{"id":"inst-1"}`))
		default:
			w.WriteHeader(http.StatusInternalServerError)
		}
	}))
	defer srv.Close()

	target := cmd.SyncTarget{Name: "demo", Agent: "claude-code",
		Path: filepath.Join(t.TempDir(), "demo"), SkillID: "skill-1"}
	version, err := cmd.DownloadInto(context.Background(), apiclient.New(srv.URL, "tok", 0), target,
		apiclient.Skill{ID: "skill-1"})
	if err != nil {
		t.Fatal(err)
	}
	if version != "1.0.0" {
		t.Fatalf("该装的是 1.0.0，得到 %s", version)
	}
	if got.SkillID != "skill-1" || got.Agent != "claude-code" || got.InstallScope != "user" {
		t.Fatalf("登记的安装实例不对: %+v", got)
	}
	if got.TargetPath != target.Path {
		t.Fatalf("登记的安装路径不对: %+v", got)
	}
	if got.InstalledVersion != "1.0.0" || got.InstalledSHA256 != artifact.SHA256 {
		t.Fatalf("登记的版本或摘要不对: %+v", got)
	}
}

// 本地删掉的文件不能静默跟着新版本传到云端：没确认就不上传。
func Test本地删除不静默上传(t *testing.T) {
	old := cmd.Cfg.ConfigDir
	cmd.Cfg.ConfigDir = t.TempDir()
	defer func() { cmd.Cfg.ConfigDir = old }()

	skill := writeSkill(t, filepath.Join(t.TempDir(), "demo"), "# demo\n")
	artifact, err := packager.Pack(skill)
	if err != nil {
		t.Fatal(err)
	}
	var requests atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	// 基线里有、现在本地没有的文件 = 本机删掉了。
	target := cmd.SyncTarget{Name: "demo", Agent: "claude-code", Path: skill,
		BaseFiles: map[string]string{"SKILL.md": "sha", "删掉的.md": "sha2"}}
	result, _, err := cmd.ApplyDecision(context.Background(), apiclient.New(srv.URL, "tok", 0),
		target, syncstate.Upload, artifact, cmd.ManifestDigest(artifact.Manifest),
		apiclient.Skill{ID: "skill-1"}, true, cmd.SyncOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if result.Outcome != cmd.PendingDelete {
		t.Fatalf("本地删了文件该等着确认，得到 %s（%s）", result.Outcome, result.Detail)
	}
	if requests.Load() != 0 {
		t.Fatalf("没确认就不该上传，却发了 %d 个请求", requests.Load())
	}

	// 给了 --allow-delete 才真传。
	result, _, err = cmd.ApplyDecision(context.Background(), apiclient.New(srv.URL, "tok", 0),
		target, syncstate.Upload, artifact, cmd.ManifestDigest(artifact.Manifest),
		apiclient.Skill{ID: "skill-1"}, true, cmd.SyncOptions{AllowDelete: true})
	if err != nil {
		t.Fatal(err)
	}
	if result.Outcome == cmd.PendingDelete {
		t.Fatal("确认过了还拦着不让传")
	}
	if requests.Load() == 0 {
		t.Fatal("确认过了就该去上传")
	}
}

// 额外配置的目录（项目级、自定义目录）也要被扫到
// （设计文档 §6.1：支持用户级目录、项目级目录和自定义目录）。
func Test额外目录里的Skill也被扫到(t *testing.T) {
	skillsDir := t.TempDir()
	extraDir := t.TempDir()
	writeSkill(t, filepath.Join(skillsDir, "agent-one"), "# agent\n")
	writeSkill(t, filepath.Join(extraDir, "project-one"), "# project\n")

	old := cmd.Cfg
	cmd.Cfg.ConfigDir = t.TempDir()
	cmd.Cfg.Agents = map[string]config.Agent{
		"claude-code": {Enabled: true, UserSkillsDir: skillsDir},
	}
	cmd.Cfg.Sync.ExtraDirs = []string{extraDir}
	defer func() { cmd.Cfg = old }()

	targets, err := cmd.SyncTargets(cmd.SyncOptions{})
	if err != nil {
		t.Fatal(err)
	}
	names := map[string]string{}
	for _, target := range targets {
		names[target.Name] = target.Agent
	}
	if names["agent-one"] != "claude-code" {
		t.Fatalf("Agent 目录里的 Skill 该被扫到：%v", names)
	}
	if names["project-one"] != "custom" {
		t.Fatalf("额外目录里的 Skill 该被扫到（agent 记 custom）：%v", names)
	}
}
