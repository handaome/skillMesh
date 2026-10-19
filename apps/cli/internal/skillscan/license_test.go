package skillscan

import (
	"path/filepath"
	"strings"
	"testing"
)

const mitText = `MIT License

Copyright (c) 2026 someone

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction.
`

func Test识别MIT许可证(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "LICENSE"), mitText)
	if got := DetectLicense(dir); got != "MIT" {
		t.Fatalf("应识别为 MIT，得到 %q", got)
	}
}

func Test没有许可证文件返回空(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "SKILL.md"), "# demo\n")
	if got := DetectLicense(dir); got != "" {
		t.Fatalf("没有许可证文件应返回空串，得到 %q", got)
	}
	if line := LicenseLine(dir); !strings.Contains(line, "未检测到") {
		t.Fatalf("说明里应说清未检测到，得到 %q", line)
	}
}
