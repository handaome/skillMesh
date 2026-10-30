package skillscan

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestValidateSpec 只测真正会挡住发布的几种情况。
// 每种不合规都要有话说清楚缺什么——用户照着改一遍就能过。
func TestValidateSpec(t *testing.T) {
	cases := []struct {
		name string
		body string
		want string
	}{
		{"缺 frontmatter", "# 只是正文\n", "frontmatter"},
		{"frontmatter 没闭合", "---\nname: x\ndescription: y\n", "没有闭合"},
		{"缺 name", "---\ndescription: y\n---\n正文\n", "缺 name"},
		{"缺 description", "---\nname: x\n---\n正文\n", "缺 description"},
		{"正文为空", "---\nname: x\ndescription: y\n---\n\n   \n", "没有正文"},
		{"name 太长", "---\nname: " + strings.Repeat("a", 65) +
			"\ndescription: y\n---\n正文\n", "name"},
		{"description 太长", "---\nname: x\ndescription: " + strings.Repeat("a", 1025) +
			"\n---\n正文\n", "description"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			dir := t.TempDir()
			if err := os.WriteFile(filepath.Join(dir, SkillFile), []byte(c.body), 0o644); err != nil {
				t.Fatal(err)
			}
			err := ValidateSpec(dir)
			if err == nil {
				t.Fatalf("应该不合规，实际通过了")
			}
			if !strings.Contains(err.Error(), c.want) {
				t.Fatalf("报错里没有 %q：%v", c.want, err)
			}
		})
	}
}

func TestValidateSpec通过合规的Skill(t *testing.T) {
	dir := t.TempDir()
	body := "---\nname: demo\ndescription: 一个演示用的 skill\n---\n\n# 演示\n\n正文。\n"
	if err := os.WriteFile(filepath.Join(dir, SkillFile), []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := ValidateSpec(dir); err != nil {
		t.Fatalf("不该拦：%v", err)
	}
}

// frontmatter 里的分隔线不能把 frontmatter 提前截断：正文里出现 --- 是常事。
func TestValidateSpec正文里的分隔线(t *testing.T) {
	dir := t.TempDir()
	body := "---\nname: demo\ndescription: 演示\n---\n\n# 标题\n\n---\n\n后面还有正文。\n"
	if err := os.WriteFile(filepath.Join(dir, SkillFile), []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := ValidateSpec(dir); err != nil {
		t.Fatalf("不该拦：%v", err)
	}
}

// 声明了豁免的 skill 不按规范校验：改不了源文件的第三方 skill 得有个出口。
// 豁免声明写在 metadata.skillmesh.spec_exempt 里，与服务端同一套口径。
func TestValidateSpec豁免(t *testing.T) {
	dir := t.TempDir()
	body := "---\nmetadata:\n  skillmesh:\n    spec_exempt: true\n---\n\n正文。\n"
	if err := os.WriteFile(filepath.Join(dir, SkillFile), []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := ValidateSpec(dir); err != nil {
		t.Fatalf("豁免的不该拦：%v", err)
	}
}

// 豁免写错了不算豁免：值必须是 true。
func TestValidateSpec豁免写错了不算豁免(t *testing.T) {
	dir := t.TempDir()
	body := "---\nmetadata:\n  skillmesh:\n    spec_exempt: nope\n---\n\n正文。\n"
	if err := os.WriteFile(filepath.Join(dir, SkillFile), []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := ValidateSpec(dir); err == nil {
		t.Fatal("不该放行")
	}
}
