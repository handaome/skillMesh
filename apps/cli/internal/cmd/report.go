package cmd

import (
	"fmt"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
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

// printVersionRisks 打印服务端对这一份制品的扫描结论。
// 没扫过就说没扫过——空着会让人误以为是「安全」。
func printVersionRisks(version apiclient.SkillVersion) {
	if version.Scan == nil {
		fmt.Printf("%s 未经服务端扫描\n", version.Version)
		return
	}
	if version.Scan.Status == "passed" {
		fmt.Printf("%s 服务端扫描通过（%s %s）\n",
			version.Version, version.Scan.ScannerName, version.Scan.ScannerVersion)
		return
	}
	fmt.Printf("%s 服务端扫描结论：%s\n", version.Version, version.Scan.Status)
	for _, finding := range version.Scan.Findings {
		fmt.Printf("  %s:%d %s —— %s\n", finding.Path, finding.Line, finding.Rule, finding.Detail)
	}
}
