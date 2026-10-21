// Package packager 把本地 Skill 目录打成可上传的制品。
// 产出的是 zip —— 服务端 content_type 是 application/zip，
// 客户端 installer.ExtractZip 解的也是 zip，两边对得上。
package packager

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
)

// Entry 是 manifest 里的一项，对应 OpenAPI 的 ManifestEntry。
type Entry struct {
	Path       string `json:"path"`
	SHA256     string `json:"sha256"`
	SizeBytes  int64  `json:"size_bytes"`
	Executable bool   `json:"executable"`
}

// Artifact 是打包结果。
type Artifact struct {
	// Zip 是可直接 PUT 到对象存储的字节。
	Zip []byte
	// SHA256 是整包的摘要。
	SHA256 string
	// SizeBytes 是整包大小。
	SizeBytes int64
	// Manifest 是逐文件的清单。
	Manifest []Entry
}

// Pack 把 dir 打成 zip。SKILL.md 放在包的根目录，
// 这样安装端解包后一步就能定位到 Skill 根。
func Pack(dir string) (Artifact, error) {
	info, err := os.Stat(dir)
	if err != nil || !info.IsDir() {
		return Artifact{}, fmt.Errorf("目录不存在: %s", dir)
	}

	ignore, err := skillscan.LoadIgnore(dir)
	if err != nil {
		return Artifact{}, err
	}

	var buf bytes.Buffer
	writer := zip.NewWriter(&buf)
	var manifest []Entry

	walkErr := filepath.Walk(dir, func(path string, fi os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(dir, path)
		if err != nil {
			return err
		}
		if rel == "." {
			return nil
		}
		if fi.IsDir() {
			// 忽略目录与隐藏目录整目录跳过：打包的文件集必须与算摘要、扫密钥的是同一批。
			// 否则作者在 update --dry-run 里看不到的文件会跟着发上去，
			// 审核页列出来的文件列表与作者的 diff 对不上。
			if path != dir && skillscan.IsSkippedDir(fi.Name()) {
				return filepath.SkipDir
			}
			return nil
		}
		if !fi.Mode().IsRegular() {
			return nil // 跳过符号链接等非普通文件
		}
		if strings.HasPrefix(fi.Name(), ".") {
			// 隐藏文件不进包：.skillmesh-install 是本机状态标记，
			// .git 之类是上游自己的东西，都不该发到云端。
			return nil
		}
		if skillscan.IsSensitiveFile(fi.Name()) || ignore.Match(filepath.ToSlash(rel)) {
			// .skillmeshignore 排掉的和凭据类文件都不进包（设计文档 §6.1）。
			return nil
		}

		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		header := &zip.FileHeader{
			Name:   filepath.ToSlash(rel),
			Method: zip.Deflate,
		}
		header.SetMode(fi.Mode())
		entry, err := writer.CreateHeader(header)
		if err != nil {
			return err
		}
		if _, err := entry.Write(data); err != nil {
			return err
		}
		sum := sha256.Sum256(data)
		manifest = append(manifest, Entry{
			Path:      filepath.ToSlash(rel),
			SHA256:    hex.EncodeToString(sum[:]),
			SizeBytes: fi.Size(),
			// Windows 上没有 Unix 可执行位，Go 一律报 0666，
			// 所以从 Windows 发布的包会丢掉脚本的可执行标记。
			Executable: fi.Mode()&0o111 != 0,
		})
		return nil
	})
	if walkErr != nil {
		writer.Close()
		return Artifact{}, walkErr
	}
	if err := writer.Close(); err != nil {
		return Artifact{}, err
	}
	if len(manifest) == 0 {
		return Artifact{}, fmt.Errorf("目录里没有可打包的文件: %s", dir)
	}
	sort.Slice(manifest, func(i, j int) bool { return manifest[i].Path < manifest[j].Path })

	sum := sha256.Sum256(buf.Bytes())
	return Artifact{
		Zip:       buf.Bytes(),
		SHA256:    hex.EncodeToString(sum[:]),
		SizeBytes: int64(buf.Len()),
		Manifest:  manifest,
	}, nil
}

// Write 把包写到文件，用来 --dry-run 时让人看看产物。
func (a Artifact) Write(path string) error {
	return os.WriteFile(path, a.Zip, 0o644)
}

var _ = io.Discard
