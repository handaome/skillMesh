package cmd

import "testing"

// 声明名来自包里的 SKILL.md，是发布方写的：它会拼进 Agent 目录的路径里，
// 不校验就等于让发布方决定往哪写。
func Test声明名不能逃出目录(t *testing.T) {
	坏的 := []string{"", ".", "..", "../evil", "a/b", `a\b`, "C:evil", "/abs/path"}
	for _, name := range 坏的 {
		if err := checkDirName(name); err == nil {
			t.Fatalf("名字 %q 应该被拒", name)
		}
	}
	好的 := []string{"my-skill", "skill.name", "技能"}
	for _, name := range 好的 {
		if err := checkDirName(name); err != nil {
			t.Fatalf("名字 %q 不该被拒: %v", name, err)
		}
	}
}
