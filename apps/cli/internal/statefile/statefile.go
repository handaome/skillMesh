// Package statefile 统一读写项目/配置目录下的 YAML 状态文件。
//
// 锁文件（lockfile）、同步基线（syncstate）、同步队列（syncqueue）原本各写了一份
// 几乎逐字相同的 Load/Save：同样的读文件、同样的 yaml.Marshal、同样的 0644 落盘。
// 合成一处以后，改格式或改出错文案不会漏掉另外两处。
package statefile

import (
	"fmt"
	"os"

	"gopkg.in/yaml.v3"
)

// Load 把 path 指向的 YAML 文件读进 T 类型的值。
//
// 文件不存在时返回 def（调用方给的默认值）——锁文件缺省带 version: 1，
// 同步基线与队列缺省是空结构，三者口径不同，所以默认值由调用方给。
// 其余出错（读不了、解析不了）返回类型零值或解析到一半的内容，附带错误。
func Load[T any](path, fileName string, def T) (T, error) {
	var zero T
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return def, nil
	}
	if err != nil {
		return zero, err
	}
	var out T
	if err := yaml.Unmarshal(data, &out); err != nil {
		return out, fmt.Errorf("解析 %s: %w", fileName, err)
	}
	return out, nil
}

// Save 把 v 序列化成 YAML 写到 path（权限 0644）。
func Save(path string, v any) error {
	data, err := yaml.Marshal(v)
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o644)
}
