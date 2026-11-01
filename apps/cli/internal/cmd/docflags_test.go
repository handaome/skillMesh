package cmd

import (
	"strings"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
)

// 冲突解决方式要翻译成服务端认的词：keep-merged 服务端那边记成 keep-both。
func Test解决方式翻译成服务端的词(t *testing.T) {
	if got := serverResolution("keep-merged"); got != "keep-both" {
		t.Fatalf("keep-merged 该译成 keep-both，实际 %q", got)
	}
	if got := serverResolution("keep-local"); got != "keep-local" {
		t.Fatalf("其余的照原样，实际 %q", got)
	}
}

// 来源过滤：Git、本地导入、云端三类不能串。
func Test按来源筛不出错(t *testing.T) {
	git := apiclient.Skill{Source: &apiclient.SkillSource{SourceType: "git"}}
	local := apiclient.Skill{Source: &apiclient.SkillSource{SourceType: "local_import"}}
	cloud := apiclient.Skill{Source: &apiclient.SkillSource{SourceType: "registry"}}
	none := apiclient.Skill{}

	if !matchesSource(git, "git") || matchesSource(git, "local") || matchesSource(git, "cloud") {
		t.Fatal("Git 来源只该被 --source git 选中")
	}
	if !matchesSource(local, "local") || matchesSource(local, "git") || matchesSource(local, "cloud") {
		t.Fatal("本地导入只该被 --source local 选中")
	}
	if !matchesSource(cloud, "cloud") || matchesSource(cloud, "git") || matchesSource(cloud, "local") {
		t.Fatal("注册中心来源只该被 --source cloud 选中")
	}
	if !matchesSource(none, "cloud") {
		t.Fatal("没有来源摘要的算云端来源")
	}
}

// 补丁要真能看出改了哪几行：一样的文本不产出差异。
func Test统一差异看得出改了什么(t *testing.T) {
	if diff := UnifiedDiff("SKILL.md", "a\nb\n", "a\nb\n"); diff != "" {
		t.Fatalf("一样的文本不该有差异，实际 %q", diff)
	}
	diff := UnifiedDiff("SKILL.md", "a\nb\nc\n", "a\nB\nc\n")
	if !strings.Contains(diff, "-b") || !strings.Contains(diff, "+B") {
		t.Fatalf("差异里该有删掉的行与加上的行，实际 %q", diff)
	}
	if !strings.Contains(diff, "--- a/SKILL.md") || !strings.Contains(diff, "+++ b/SKILL.md") {
		t.Fatalf("补丁要带文件头，实际 %q", diff)
	}
}
