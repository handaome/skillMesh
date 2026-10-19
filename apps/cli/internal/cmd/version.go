package cmd

import (
	"fmt"
	"strconv"
	"strings"
)

// orDefaultVersion 用显式指定的版本号，没指定就是首个版本 1.0.0。
func orDefaultVersion(version string) string {
	if version == "" {
		return "1.0.0"
	}
	return version
}

// nextVersion 在已有版本上累加 patch 位。
// 第一个版本是 1.0.0。已有版本不是语义化版本时报错，让人用 --version 显式指定。
func nextVersion(existing []string) (string, error) {
	if len(existing) == 0 {
		return "1.0.0", nil
	}
	latest, ok := highestSemver(existing)
	if !ok {
		return "", fmt.Errorf(
			"已有版本都不是语义化版本（%s），请用 --version 显式指定版本号",
			strings.Join(existing, ", "))
	}
	major, minor, patch, prefix, _ := parseSemver(latest)
	return fmt.Sprintf("%s%d.%d.%d", prefix, major, minor, patch+1), nil
}

// highestSemver 找出版本号最大的那个。
// 返回 false 表示没有一个能按语义化版本解析。
func highestSemver(versions []string) (string, bool) {
	best := ""
	bestMajor, bestMinor, bestPatch := -1, -1, -1
	for _, version := range versions {
		major, minor, patch, _, ok := parseSemver(version)
		if !ok {
			continue
		}
		if major > bestMajor ||
			(major == bestMajor && minor > bestMinor) ||
			(major == bestMajor && minor == bestMinor && patch > bestPatch) {
			best, bestMajor, bestMinor, bestPatch = version, major, minor, patch
		}
	}
	return best, best != ""
}

// parseSemver 解析 "1.2.3" 或 "v1.2.3"。
// 语义化版本不允许前导零，所以 "2024-01-01" 不是合法版本。
func parseSemver(version string) (major, minor, patch int, prefix string, ok bool) {
	trimmed := strings.TrimSpace(version)
	if strings.HasPrefix(trimmed, "v") || strings.HasPrefix(trimmed, "V") {
		prefix = "v"
		trimmed = trimmed[1:]
	}
	parts := strings.Split(trimmed, ".")
	if len(parts) != 3 {
		return 0, 0, 0, "", false
	}
	values := make([]int, 3)
	for i, part := range parts {
		value, valid := parseNumericIdentifier(part)
		if !valid {
			return 0, 0, 0, "", false
		}
		values[i] = value
	}
	return values[0], values[1], values[2], prefix, true
}

// parseNumericIdentifier 解析语义化版本里的数字段：不允许前导零。
func parseNumericIdentifier(part string) (int, bool) {
	if part == "" {
		return 0, false
	}
	for _, r := range part {
		if r < '0' || r > '9' {
			return 0, false
		}
	}
	if len(part) > 1 && part[0] == '0' {
		return 0, false
	}
	value, err := strconv.Atoi(part)
	if err != nil {
		return 0, false
	}
	return value, true
}
