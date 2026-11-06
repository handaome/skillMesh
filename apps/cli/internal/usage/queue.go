// Package usage 保存本机待上报的 Skill 调用记录。
//
// 上报失败不丢：先记在这里，等网络好了再补报（skillmesh usage flush）。
// 格式一行一条 JSON：机器读得动（jq、grep 都行），人也能直接看。
package usage

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
)

// FileName 是队列文件名。
const FileName = "usage-queue.jsonl"

// Path 返回队列文件路径。
func Path(cacheDir string) string {
	return filepath.Join(cacheDir, FileName)
}

// Load 读出队列里的全部记录。
//
// 坏行直接跳过：写坏的半截 JSON 已经没用了，
// 留着只会让后面的补报一直失败。
func Load(cacheDir string) ([]apiclient.SkillUsageInput, error) {
	file, err := os.Open(Path(cacheDir))
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("读待重试队列失败: %w", err)
	}
	defer file.Close()
	var records []apiclient.SkillUsageInput
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		var record apiclient.SkillUsageInput
		if err := json.Unmarshal([]byte(line), &record); err != nil {
			continue
		}
		records = append(records, record)
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("读待重试队列失败: %w", err)
	}
	return records, nil
}

// Append 往队列尾部追加一条记录。
func Append(cacheDir string, record apiclient.SkillUsageInput) error {
	path := Path(cacheDir)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return fmt.Errorf("建缓存目录失败: %w", err)
	}
	line, err := json.Marshal(record)
	if err != nil {
		return fmt.Errorf("序列化待重试记录失败: %w", err)
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return fmt.Errorf("开待重试队列失败: %w", err)
	}
	defer file.Close()
	if _, err := file.Write(append(line, '\n')); err != nil {
		return fmt.Errorf("写待重试队列失败: %w", err)
	}
	return nil
}

// Replace 用给定的记录整体替换队列文件。
//
// 先写临时文件再改名：直接改原文件时中途崩了，队列就成半截 JSON，
// 补报时整条都读不出来。
func Replace(cacheDir string, records []apiclient.SkillUsageInput) error {
	path := Path(cacheDir)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return fmt.Errorf("建缓存目录失败: %w", err)
	}
	var buf bytes.Buffer
	for _, record := range records {
		line, err := json.Marshal(record)
		if err != nil {
			return fmt.Errorf("序列化待重试记录失败: %w", err)
		}
		buf.Write(line)
		buf.WriteByte('\n')
	}
	temp, err := os.CreateTemp(filepath.Dir(path), "usage-queue-*.tmp")
	if err != nil {
		return fmt.Errorf("建临时队列文件失败: %w", err)
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if _, err := temp.Write(buf.Bytes()); err != nil {
		temp.Close()
		return fmt.Errorf("写临时队列文件失败: %w", err)
	}
	if err := temp.Close(); err != nil {
		return fmt.Errorf("写临时队列文件失败: %w", err)
	}
	if err := os.Chmod(tempName, 0o644); err != nil {
		return fmt.Errorf("设临时队列文件权限失败: %w", err)
	}
	if err := os.Rename(tempName, path); err != nil {
		return fmt.Errorf("替换待重试队列失败: %w", err)
	}
	return nil
}
