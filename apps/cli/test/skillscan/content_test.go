package skillscan_test

import (
	skillscan "github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"os"
	"path/filepath"
	"testing"
)

func Test目录摘要只跟内容有关(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "references"), 0o755); err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(dir, "SKILL.md"), "# demo\n")
	writeFile(t, filepath.Join(dir, "references", "a.md"), "a\n")

	before, err := skillscan.DirDigest(dir)
	if err != nil {
		t.Fatal(err)
	}
	// 本机状态标记不该影响摘要
	writeFile(t, filepath.Join(dir, ".skillmesh-install"), "repo\n")
	after, err := skillscan.DirDigest(dir)
	if err != nil {
		t.Fatal(err)
	}
	if before != after {
		t.Fatalf("隐藏文件不该影响摘要：%s vs %s", before, after)
	}
}

func Test内容变了摘要就变(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "SKILL.md"), "# demo\n")
	before, err := skillscan.DirDigest(dir)
	if err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(dir, "SKILL.md"), "# demo 改过了\n")
	after, err := skillscan.DirDigest(dir)
	if err != nil {
		t.Fatal(err)
	}
	if before == after {
		t.Fatal("内容变了摘要必须跟着变")
	}
}

func Test文件顺序不影响摘要(t *testing.T) {
	files := []skillscan.FileDigest{{Path: "b.md", SHA256: "22"}, {Path: "a.md", SHA256: "11"}}
	reversed := []skillscan.FileDigest{{Path: "a.md", SHA256: "11"}, {Path: "b.md", SHA256: "22"}}
	if skillscan.Digest(files) != skillscan.Digest(reversed) {
		t.Fatal("摘要不能依赖遍历顺序")
	}
}

// 摘要按码点排序（Go 的字符串比较就是 UTF-8 字节序，等于码点序）。
//
// 服务端那边必须用同一套规则：Java 的 String.compareTo 是 UTF-16 码元序，
// 增补平面字符（emoji 之类）的代理对会被排到 U+E000..U+FFFF 之前，
// 两边排序不同，同一份内容的摘要就对不上——基线校验会一直失败。
//
// 这个值服务端也有同样的断言（ContentDigestTest）。
func Test含增补平面字符的路径按码点排序(t *testing.T) {
	files := []skillscan.FileDigest{
		{Path: "\U0001F600.md", SHA256: "aa"},
		{Path: "�.md", SHA256: "bb"},
		{Path: "a.md", SHA256: "cc"},
	}
	want := "aae24b34af6740df839ceeb3417c6b681ffb76450022ac72bf4c714e0ca3bb81"
	if got := skillscan.Digest(files); got != want {
		t.Fatalf("摘要应按码点排序：得到 %s，期望 %s", got, want)
	}
}
