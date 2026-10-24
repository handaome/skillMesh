package cmd

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/packager"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
)

// 设计文档 §6.3 的冲突表：两边都改时能按文件合就合，合不了才报冲突。
func Test按文件合并决策(t *testing.T) {
	base := map[string]string{"a.md": "A", "b.md": "B", "c.md": "C"}
	local := map[string]string{"a.md": "A-LOCAL", "b.md": "B-LOCAL", "c.md": "C"}
	cloud := map[string]string{"a.md": "A-CLOUD", "b.md": "B", "c.md": "C"}

	plan := mergePlan(base, local, cloud)
	if plan["a.md"] != actionMergeLocal {
		t.Errorf("同一个文件两边都改应尝试合并，得到 %s", plan["a.md"])
	}
	if plan["b.md"] != actionKeepLocal {
		t.Errorf("只有本机改过应保留本机，得到 %s", plan["b.md"])
	}
	if _, touched := plan["c.md"]; touched {
		t.Errorf("两边都没动的文件不该进计划：%v", plan)
	}
}

func Test云端新增的文件会被拉下来(t *testing.T) {
	base := map[string]string{"a.md": "A"}
	local := map[string]string{"a.md": "A"}
	cloud := map[string]string{"a.md": "A", "new.md": "N"}

	plan := mergePlan(base, local, cloud)
	if plan["new.md"] != actionTakeCloud {
		t.Fatalf("云端新增的文件该被拉下来，得到 %s", plan["new.md"])
	}
}

func Test两边改成一样不进计划(t *testing.T) {
	base := map[string]string{"a.md": "A"}
	same := map[string]string{"a.md": "SAME"}
	if plan := mergePlan(base, same, same); len(plan) != 0 {
		t.Fatalf("两边一致不该产生动作：%v", plan)
	}
}

// 设计文档 §6.3：本地删除、云端仍存在 → 走删除待确认，不能自动拉回来。
func Test本机删掉的文件不从云端拉回(t *testing.T) {
	base := map[string]string{"a.md": "A", "gone.md": "G"}
	local := map[string]string{"a.md": "A-LOCAL"}
	cloud := map[string]string{"a.md": "A", "gone.md": "G-CLOUD"}

	plan := mergePlan(base, local, cloud)
	if plan["gone.md"] == actionTakeCloud {
		t.Fatalf("本机删掉的文件不该从云端拉回来：%v", plan)
	}
	if plan["gone.md"] != actionDeleted {
		t.Fatalf("本机删掉的文件该标成待确认，得到 %s", plan["gone.md"])
	}
}

// 真跑一遍三方合并：同一文件、改的是不同行 → 自动合；改同一行 → 判冲突。
func Test同一文件的三方合并(t *testing.T) {
	root := t.TempDir()
	baseDir := filepath.Join(root, "base")
	cloudDir := filepath.Join(root, "cloud")
	localDir := filepath.Join(root, "local")
	for _, dir := range []string{baseDir, cloudDir, localDir} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	write := func(dir, content string) {
		if err := os.WriteFile(filepath.Join(dir, "SKILL.md"), []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	// 基线
	write(baseDir, "line1\nline2\nline3\n")
	write(cloudDir, "line1\nline2\nline3\n云端加的\n")
	write(localDir, "line1\n本机改的\nline2\nline3\n")

	ok, err := mergeFile(baseDir, localDir, cloudDir, "SKILL.md")
	if err != nil || !ok {
		t.Fatalf("改的是不同位置，应该合得上：%v %v", ok, err)
	}
	merged, err := os.ReadFile(filepath.Join(localDir, "SKILL.md"))
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"本机改的", "云端加的"} {
		if !strings.Contains(string(merged), want) {
			t.Errorf("合并结果应含 %q：\n%s", want, merged)
		}
	}

	// 同一个位置改成不同内容 → 冲突，不写盘
	write(cloudDir, "line1\nline2\n云端改的\nline3\n")
	write(localDir, "line1\nline2\n本机改的\nline3\n")
	before, _ := os.ReadFile(filepath.Join(localDir, "SKILL.md"))
	ok, err = mergeFile(baseDir, localDir, cloudDir, "SKILL.md")
	if err != nil {
		t.Fatal(err)
	}
	if ok {
		t.Fatal("同一行改成不同内容应判冲突")
	}
	after, _ := os.ReadFile(filepath.Join(localDir, "SKILL.md"))
	if string(before) != string(after) {
		t.Fatal("判冲突时不该改动本地文件")
	}
}

func Test基线文件缺失时不硬合(t *testing.T) {
	root := t.TempDir()
	local := filepath.Join(root, "local")
	cloud := filepath.Join(root, "cloud")
	for _, dir := range []string{local, cloud} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	content := []byte("a\nb\n")
	if err := os.WriteFile(filepath.Join(local, "SKILL.md"), content, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cloud, "SKILL.md"), []byte("x\ny\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	ok, err := mergeFile(filepath.Join(root, "no-such-base"), local, cloud, "SKILL.md")
	if err != nil {
		t.Fatalf("基线缺失不该报错：%v", err)
	}
	if ok {
		t.Fatal("没有基线可比，合不了才对")
	}
}

// 合并中途失败不能留下「改了一半」的目录：基线没更新，下一轮又判冲突。
// 失败时本机目录必须还是原样，错误也得如实报出来。
func Test合并失败不动本机目录(t *testing.T) {
	root := t.TempDir()
	local := filepath.Join(root, "local")
	cloud := filepath.Join(root, "cloud")
	for _, dir := range []string{local, cloud} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	write := func(dir, name, content string) {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	write(local, "SKILL.md", "# 本机\n")
	write(cloud, "SKILL.md", "# 本机\n")
	write(cloud, "a.md", "云端的 a\n")

	// 云端清单说有 b.md，制品里却没有：写到 b.md 时必然失败。
	artifact, err := packager.Pack(cloud)
	if err != nil {
		t.Fatal(err)
	}
	localHashes, err := skillscan.DirHashes(local)
	if err != nil {
		t.Fatal(err)
	}
	hashes := toHashMap(localHashes)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/skills/skill-1/versions":
			w.Write([]byte(`{"items":[{"version":"1.0.0","status":"published","manifest":[` +
				`{"path":"SKILL.md","sha256":"` + hashes["SKILL.md"] + `"},` +
				`{"path":"a.md","sha256":"ca"},{"path":"b.md","sha256":"cb"}],` +
				`"artifact":{"id":"art-1"}}],"has_more":false}`))
		case r.Method == http.MethodGet && r.URL.Path == "/artifacts/art-1/download":
			w.Write(artifact.Zip)
		default:
			w.WriteHeader(http.StatusInternalServerError)
		}
	}))
	defer srv.Close()

	target := syncTarget{Name: "demo", Agent: "claude-code", Path: local, SkillID: "skill-1"}
	entry := syncstate.Entry{Name: "demo", Agent: "claude-code",
		BaseFiles: map[string]string{"SKILL.md": hashes["SKILL.md"]}}
	ok, _, err := mergeConflict(context.Background(), apiclient.New(srv.URL, "tok", 0),
		target, entry, artifact, apiclient.Skill{ID: "skill-1"})
	// 本机目录保持原样：前面合上的 a.md 不能落到本机目录里。
	if _, statErr := os.Stat(filepath.Join(local, "a.md")); !os.IsNotExist(statErr) {
		t.Fatalf("合并失败不该往本机目录写文件：%v", statErr)
	}
	content, readErr := os.ReadFile(filepath.Join(local, "SKILL.md"))
	if readErr != nil {
		t.Fatal(readErr)
	}
	if string(content) != "# 本机\n" {
		t.Fatalf("本机文件被改了：%q", content)
	}
	if err == nil {
		t.Fatal("合并中途失败该把错报出来")
	}
	if ok {
		t.Fatal("没合完不该说合成了")
	}
}
