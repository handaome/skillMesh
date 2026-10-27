package redact

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPathHidesHome(t *testing.T) {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		t.Skip("取不到家目录")
	}
	got := Path(filepath.Join(home, "AppData", "Roaming", "SkillMesh"))
	if !strings.HasPrefix(got, "~") {
		t.Fatalf("家目录下的路径应变成 ~ 开头，实际 %q", got)
	}
	if strings.Contains(got, filepath.Base(home)) {
		t.Fatalf("结果里不该出现家目录名，实际 %q", got)
	}
}

func TestPathKeepsOtherPathsShort(t *testing.T) {
	got := Path(filepath.Join(string(filepath.Separator), "opt", "skills", "demo"))
	if strings.HasPrefix(got, string(filepath.Separator)) {
		t.Fatalf("家目录外的路径不该再是完整绝对路径，实际 %q", got)
	}
	if !strings.Contains(got, "skills") || !strings.Contains(got, "demo") {
		t.Fatalf("末两段要保留，否则定位不到，实际 %q", got)
	}
}

func TestTextRedacts(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{"URL 里的账号密码", "clone https://alice:secret@git.example.com/repo.git", "clone https://***@git.example.com/repo.git"},
		{"GitHub 令牌", "token ghp_abcdefghijklmnopqrst", "token ghp_***"},
		{"邮箱", "写给 wuss@example.com 的", "写给 wuss***@example.com 的"},
		{"私钥块", "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----", "[私钥已脱敏]"},
		{"普通文本不动", "已安装 demo@1.0.0", "已安装 demo@1.0.0"},
		{"夹在文本里的绝对路径", "装到 /home/someone/skills/demo 了", "装到 …/skills/demo 了"},
		{"Windows 绝对路径", "失败于 C:\\Users\\someone\\skills\\demo", "失败于 …/skills/demo"},
		{"斜杠不是路径", "版本/commit 不该被动", "版本/commit 不该被动"},
		{"版本号里的点", "1.0.0 保持原样", "1.0.0 保持原样"},
		{"紧贴中文的盘符路径", "失败于C:\\Users\\someone\\skills\\demo", "失败于…/skills/demo"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := Text(c.in); got != c.want {
				t.Fatalf("Text(%q) = %q，期望 %q", c.in, got, c.want)
			}
		})
	}
}

func TestFieldPicksByKey(t *testing.T) {
	if got := Field("path", filepath.Join("/opt", "skills", "demo")); strings.HasPrefix(got, "/") {
		t.Fatalf("path 字段应走路径脱敏，实际 %q", got)
	}
	if got := Field("detail", "https://u:p@example.com/x"); strings.Contains(got, "u:p@") {
		t.Fatalf("普通字段应走文本脱敏，实际 %q", got)
	}
}
