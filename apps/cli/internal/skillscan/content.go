package skillscan

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
)

// FileDigest 是参与内容摘要的一项。
type FileDigest struct {
	Path   string
	SHA256 string
}

// Digest 是内容的规范化摘要：对「路径 + 文件摘要」排序后再哈希。
//
// 不用整包 zip 的 sha256——同一份内容重新打包，压缩实现或文件权限位一变，
// 摘要就跟着变，会把「内容没变」误判成变更（设计文档 §6.2、§7.1）。
func Digest(files []FileDigest) string {
	sorted := append([]FileDigest(nil), files...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].Path < sorted[j].Path })
	hash := sha256.New()
	for _, file := range sorted {
		fmt.Fprintf(hash, "%s\x00%s\n", file.Path, file.SHA256)
	}
	return hex.EncodeToString(hash.Sum(nil))
}

// DirHashes 逐文件算摘要，规则与打包一致：跳过隐藏文件和忽略目录。
func DirHashes(dir string) ([]FileDigest, error) {
	ignore, err := LoadIgnore(dir)
	if err != nil {
		return nil, err
	}
	var files []FileDigest
	err = filepath.Walk(dir, func(path string, fi os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if fi.IsDir() {
			if path != dir && IsSkippedDir(fi.Name()) {
				return filepath.SkipDir
			}
			return nil
		}
		if !fi.Mode().IsRegular() || IsHiddenFile(fi.Name()) {
			return nil
		}
		rel, err := filepath.Rel(dir, path)
		if err != nil {
			rel = path
		}
		rel = filepath.ToSlash(rel)
		if ignore.Match(rel) || IsSensitiveFile(fi.Name()) {
			return nil
		}
		sum, err := fileSHA256(path)
		if err != nil {
			return err
		}
		files = append(files, FileDigest{Path: rel, SHA256: sum})
		return nil
	})
	if err != nil {
		return nil, err
	}
	return files, nil
}

// DirDigest 直接算一个目录的内容摘要。
func DirDigest(dir string) (string, error) {
	files, err := DirHashes(dir)
	if err != nil {
		return "", err
	}
	return Digest(files), nil
}

func fileSHA256(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}
