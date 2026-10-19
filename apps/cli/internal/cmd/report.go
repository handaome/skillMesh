package cmd

import (
	"fmt"

	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
)

// warnRisks 把安装内容里的风险摊开给用户看（设计文档 §3.2 第 6 步）。
//
// 只提示不阻断：装到本地是用户自己的机器，密钥进了别人的机器才有理由硬拦，
// 那是 publish 的事。
func warnRisks(skillDir string) {
	findings, err := skillscan.ScanSecrets(skillDir)
	if err != nil || len(findings) == 0 {
		return
	}
	fmt.Printf("风险提示：\n%s\n", skillscan.FormatFindings(findings))
}
