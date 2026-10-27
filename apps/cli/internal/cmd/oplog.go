package cmd

import (
	"path/filepath"

	"github.com/handaome/skillmesh/apps/cli/internal/oplog"
)

// opLog 返回本机操作日志的写入器。
//
// 放在缓存目录：日志是跑出来的数据，不是配置——清缓存时一起清掉。
func opLog() *oplog.Writer {
	return oplog.New(filepath.Join(Cfg.CacheDir, oplog.FileName))
}

// opLogPath 返回本机操作日志的路径。
func opLogPath() string {
	return filepath.Join(Cfg.CacheDir, oplog.FileName)
}
