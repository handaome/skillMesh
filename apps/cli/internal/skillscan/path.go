package skillscan

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
)

// maxWalkDepth 是遍历的深度上限。链接成环时靠它兜底收场。
const maxWalkDepth = 32

// maxLinkDepth 是解析嵌套链接的层数上限。链接成环时靠它兜底收场。
const maxLinkDepth = 8

// ErrOutsideRoot 表示这个链接指向包根之外。
//
// 链接要跟（跟了链接的内容才算进同步模型），但不能跟出包外：
// 包外的内容不该跟着上传——一个指向 ~/.ssh 的链接会把私钥传上云。
// 收到这个错误的调用方自己决定怎么处理：跳过并报一声，还是直接忽略。
var ErrOutsideRoot = errors.New("链接指向包根之外")

// LinkTarget 取路径指向的真实目标；不是链接就返回 false。
//
// 不依赖 filepath.EvalSymlinks：Windows 上目录链接（junction / 重解析点）
// 在 Go 里是 ModeIrregular，EvalSymlinks 认为它不是链接，原样返回；
// 而 os.Readlink 能拿到目标。
func LinkTarget(path string) (string, bool) {
	if _, err := os.Lstat(path); err != nil {
		return "", false
	}
	target, err := os.Readlink(path)
	if err != nil || target == "" {
		return "", false
	}
	// Windows 上 junction 的目标带 \\?\ 前缀，统一成普通路径：
	// 后面的比较（是不是同一个目录）不能被前缀带偏。
	if plain, err := filepath.Localize(target); err == nil {
		target = plain
	}
	if !filepath.IsAbs(target) {
		target = filepath.Join(filepath.Dir(path), target)
	}
	return target, true
}

// ResolveLink 一层层解析链接，返回最终的真实路径；不是链接就返回原路径。
//
// 多层链接是常事：Agent 目录指向共享目录，共享目录又指向某个技能库。
// 只解一层的话，后面拿它跟别的路径比还是比不出来。
func ResolveLink(path string) string {
	for i := 0; i < maxLinkDepth; i++ {
		target, ok := LinkTarget(path)
		if !ok {
			return path
		}
		path = target
	}
	return path
}

// LinkDir 建一个指向 target 的目录链接（link 是链接本身）。
//
// 多个 Agent 共用一份技能内容时用得上：实体只存一份，
// 各 Agent 的目录里放链接，谁都能读到同一份文件。
func LinkDir(target, link string) error {
	return linkDir(target, link)
}

// WriteTarget 返回内容该写到哪：链接就写它指向的真实目录。
//
// 链接是用户（或 skills 管理器）建的结构，替换成实体目录等于把别人的布局拆了；
// 内容更新应该落在链接指向的地方，链接本身留着。
func WriteTarget(path string) string {
	if target, ok := LinkTarget(path); ok {
		return target
	}
	return path
}

// WalkFollow 遍历目录，判断类型时用 os.Stat（跟随链接）而不是 Lstat。
//
// 技能目录常常是链接（skills 管理器把库里的目录挂进 Agent 目录）。
// Windows 上这类链接被 Lstat 报成「既不是目录也不是符号链接」的重解析点，
// filepath.Walk 第一步就进不去——整份内容被当成空目录，摘要算出来是空哈希，
// 同步就会把空包当成新版本传上去。
//
// 子目录用过 os.Stat 取到的 FileInfo 再决定要不要进去，所以链接目录能正常展开。
// 回调返回 filepath.SkipDir 时跳过该目录，与 filepath.Walk 一致。
//
// 链接要跟，但不能跟出包外：
//   越界的链接（指向包根之外）跳过，并把 ErrOutsideRoot 报给回调——
//     包外的内容不进包，但要让用户知道少了东西；
//   成环的链接（a → b、b → a）按「这个真实目录已经走过」跳过；
//   断链跳过——一个坏链接不该让整次发布失败。
func WalkFollow(root string, fn func(path string, info os.FileInfo, err error) error) error {
	// 根目录本身可能就是链接：链接指向哪儿，根就在哪儿。
	realRoot := ResolveLink(root)
	info, err := os.Stat(root)
	if err != nil {
		return fn(root, nil, err)
	}
	return walkFollow(root, realRoot, realRoot, info, 0, map[string]bool{}, fn)
}

// walkFollow 遍历一个目录。
//
// realPath 是这个目录解完链接后的真实路径；visited 记着已经走过的真实目录。
func walkFollow(path, realRoot, realPath string, info os.FileInfo, depth int, visited map[string]bool,
	fn func(string, os.FileInfo, error) error) error {

	if err := fn(path, info, nil); err != nil {
		if err == filepath.SkipDir {
			return nil
		}
		return err
	}
	if !info.IsDir() {
		return nil
	}
	if depth >= maxWalkDepth {
		// 太深了多半是链接环，停下来比爆栈好。
		return nil
	}
	if visited[realPath] {
		// 这个真实目录已经走过：链接成环，或者是同一份内容的第二个入口。
		// 再走一遍只会把同一批文件重复算进摘要。
		return nil
	}
	visited[realPath] = true
	entries, err := os.ReadDir(path)
	if err != nil {
		return fn(path, info, err)
	}
	for _, entry := range entries {
		child := filepath.Join(path, entry.Name())
		childInfo, statErr := os.Stat(child)
		if statErr != nil {
			// 断链不算错：链接指向的目标没了，跳过就是。
			// Lstat 也失败才是真的错（权限之类的），照旧报出去。
			if _, lstatErr := os.Lstat(child); lstatErr == nil {
				continue
			}
			return fn(child, nil, statErr)
		}
		// 子项的真实路径：父目录的真实路径加上这一项，再解一遍链接。
		childReal := ResolveLink(filepath.Join(realPath, entry.Name()))
		if !within(realRoot, childReal) {
			// 越界的链接：内容在包外，不进包。报一声，让调用方告诉用户。
			if err := fn(child, nil, ErrOutsideRoot); err != nil && err != filepath.SkipDir {
				return err
			}
			continue
		}
		if err := walkFollow(child, realRoot, childReal, childInfo, depth+1, visited, fn); err != nil {
			return err
		}
	}
	return nil
}

// within 判断 path 是不是在 root 之内。
func within(root, path string) bool {
	rel, err := filepath.Rel(root, path)
	if err != nil {
		return false
	}
	return rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}
