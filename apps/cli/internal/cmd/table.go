package cmd

import (
	"io"
	"text/tabwriter"
)

// newTable 造一个 CLI 表格的 writer。
//
// 所有命令的表格共用同一套列宽参数：最小列宽 0、tab 宽 0、单元格左右各留 2 个空格、
// 填充用空格、不做特殊对齐。参数原先散在十几个命令里各写一遍，
// 想统一改样式要改十几处，漏掉一处就会让各命令的表格长得不一样。
func newTable(out io.Writer) *tabwriter.Writer {
	return tabwriter.NewWriter(out, 0, 0, 2, ' ', 0)
}
