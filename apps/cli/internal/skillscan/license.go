package skillscan

import (
	"os"
	"path/filepath"
	"regexp"
)

// licenseFiles 是常见的许可证文件名，按惯例顺序排。
var licenseFiles = []string{
	"LICENSE", "LICENSE.md", "LICENSE.txt", "LICENCE", "LICENCE.md", "COPYING", "COPYING.md",
}

// licenseNames 是从许可证正文里认标识用的特征。
// 顺序即优先级：Apache 的许可里也会提到 MIT-compatible，所以具体的排前面。
var licenseNames = []struct {
	id      string
	pattern *regexp.Regexp
}{
	{"AGPL-3.0", regexp.MustCompile(`GNU AFFERO GENERAL PUBLIC LICENSE\s+Version 3`)},
	{"LGPL-3.0", regexp.MustCompile(`GNU LESSER GENERAL PUBLIC LICENSE\s+Version 3`)},
	{"GPL-3.0", regexp.MustCompile(`GNU GENERAL PUBLIC LICENSE\s+Version 3`)},
	{"GPL-2.0", regexp.MustCompile(`GNU GENERAL PUBLIC LICENSE\s+Version 2`)},
	{"Apache-2.0", regexp.MustCompile(`Apache License[\s\S]{0,80}Version 2\.0`)},
	{"MPL-2.0", regexp.MustCompile(`Mozilla Public License[\s\S]{0,40}Version 2\.0`)},
	{"BSD-3-Clause", regexp.MustCompile(`(?i)redistribution and use in source and binary forms[\s\S]{0,400}neither the name`)},
	{"BSD-2-Clause", regexp.MustCompile(`(?i)redistribution and use in source and binary forms`)},
	{"Unlicense", regexp.MustCompile(`This is free and unencumbered software released into the public domain`)},
	{"CC0-1.0", regexp.MustCompile(`CC0 1\.0 Universal`)},
	{"MIT", regexp.MustCompile(`(?i)Permission is hereby granted, free of charge`)},
}

// DetectLicense 按顺序在这些目录里找许可证文件，返回识别到的标识。
// 找到文件但认不出正文时返回「未识别」；一个都没有则返回空串。
//
// 只做粗识别，不做法律判断——识别结果仅供参考，
// Skill 能不能分发还得看具体条款和所在仓库的约定（设计文档 §7.1、§7.3）。
func DetectLicense(dirs ...string) string {
	for _, dir := range dirs {
		if dir == "" {
			continue
		}
		for _, name := range licenseFiles {
			path := filepath.Join(dir, name)
			data, err := os.ReadFile(path)
			if err != nil || len(data) == 0 {
				continue
			}
			return matchLicense(string(data))
		}
	}
	return ""
}

func matchLicense(text string) string {
	head := text
	if len(head) > 8192 {
		head = head[:8192]
	}
	for _, candidate := range licenseNames {
		if candidate.pattern.MatchString(head) {
			return candidate.id
		}
	}
	return "未识别"
}

// LicenseLine 拼一行给人看的许可证说明；没检测到也要说清楚「不是就等于没有」。
func LicenseLine(dirs ...string) string {
	license := DetectLicense(dirs...)
	if license == "" {
		return "许可证：未检测到（再分发前请自行确认来源授权）"
	}
	return "许可证：" + license + "（自动识别，仅供参考）"
}
