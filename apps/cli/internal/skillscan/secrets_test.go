package skillscan

import (
	"os"
	"path/filepath"
	"testing"
)

func Test扫出真实凭据但不误伤占位符(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "SKILL.md"), "# 演示\n\nexport AWS_KEY=AKIAIOSFODNN7EXAMPLE\n")
	writeFile(t, filepath.Join(dir, "config.yaml"), "api_key: your-key-here\ntoken: \"<your-token>\"\n")
	writeFile(t, filepath.Join(dir, "assets.bin"), []byte("head\x00\x01AKIAIOSFODNN7EXAMPLE"))

	findings, err := ScanSecrets(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(findings) != 1 {
		t.Fatalf("应只命中 SKILL.md 里那一处，得到 %+v", findings)
	}
	if findings[0].Path != "SKILL.md" || findings[0].Line != 3 {
		t.Fatalf("位置不对: %+v", findings[0])
	}
	if findings[0].Rule != "AWS Access Key" {
		t.Fatalf("规则不对: %s", findings[0].Rule)
	}
}

func Test干净目录没有命中(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "SKILL.md"), "# 正常 Skill\n\n用法：见 README。\n")
	findings, err := ScanSecrets(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(findings) != 0 {
		t.Fatalf("不该有命中，得到 %+v", findings)
	}
}

func writeFile(t *testing.T, path string, content any) {
	t.Helper()
	var data []byte
	switch value := content.(type) {
	case string:
		data = []byte(value)
	case []byte:
		data = value
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
}
