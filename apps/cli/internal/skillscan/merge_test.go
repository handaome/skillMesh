package skillscan

import (
	"strings"
	"testing"
)

func Test三方合并不同文件各自改动(t *testing.T) {
	base := "line1\nline2\nline3\n"
	local := "line1-CHANGED\nline2\nline3\n" // 本机改了第 1 行
	cloud := "line1\nline2\nline3-REMOTE\n"  // 云端改了第 3 行

	merged, ok := Merge3([]byte(base), []byte(local), []byte(cloud))
	if !ok {
		t.Fatalf("改的是不同位置，应该能自动合：%s", merged)
	}
	text := string(merged)
	for _, want := range []string{"line1-CHANGED", "line3-REMOTE"} {
		if !strings.Contains(text, want) {
			t.Errorf("合并结果应包含 %q，实际：\n%s", want, text)
		}
	}
}

func Test同一处两边改成一样算合得上(t *testing.T) {
	base := "a\nb\nc\n"
	local := "a\nb\nSAME\nc\n"
	cloud := "a\nb\nSAME\nc\n"

	merged, ok := Merge3([]byte(base), []byte(local), []byte(cloud))
	if !ok {
		t.Fatalf("两边改成一样不该算冲突：%s", merged)
	}
	if !strings.Contains(string(merged), "SAME") {
		t.Errorf("合并结果应含 SAME，实际：\n%s", merged)
	}
}

func Test同一处两边改得不同判冲突并留标记(t *testing.T) {
	base := "a\nb\nc\n"
	local := "a\nb\nLOCAL\nc\n"
	cloud := "a\nb\nCLOUD\nc\n"

	merged, ok := Merge3([]byte(base), []byte(local), []byte(cloud))
	if ok {
		t.Fatalf("同一行改成不同内容应判冲突，实际合成了：%s", merged)
	}
	text := string(merged)
	for _, marker := range []string{"<<<<<<< 本地", "=======", ">>>>>>> 云端", "LOCAL", "CLOUD"} {
		if !strings.Contains(text, marker) {
			t.Errorf("冲突结果应含 %q，实际：\n%s", marker, text)
		}
	}
}

func Test一边只加行也能合(t *testing.T) {
	base := "a\nc\n"
	local := "a\nb\nc\n" // 本机插入
	cloud := "a\nc\n"    // 云端没动

	merged, ok := Merge3([]byte(base), []byte(local), []byte(cloud))
	if !ok || !strings.Contains(string(merged), "b") {
		t.Fatalf("单边插入应能合：%s", merged)
	}
}

func Test空基线时两边不同判冲突(t *testing.T) {
	_, ok := Merge3(nil, []byte("local\n"), []byte("cloud\n"))
	if ok {
		t.Fatal("没有基线可比，两边不同就该判冲突")
	}
}
