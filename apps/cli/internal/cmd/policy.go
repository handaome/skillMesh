package cmd

// 更新策略的取值（设计文档 §7.2）。
//
// 写进锁文件的只有这三个；命令行的 --policy 还多收 auto-safe 这个连字符写法，
// 那只是当次的开关，不落盘。
const (
	policyManual   = "manual"
	policyNotify   = "notify"
	policyAutoSafe = "auto_safe"
)

// policyAutoSafeAlias 是 auto_safe 的连字符写法：配置里的 sync.default_update_policy
// 用的是下划线，命令行两种都收。
const policyAutoSafeAlias = "auto-safe"

// storedPolicies 是锁文件里能存的策略取值。
var storedPolicies = []string{policyManual, policyNotify, policyAutoSafe}

// updatePolicies 是命令行 --policy 接受的取值：存盘的三个，外加连字符写法。
var updatePolicies = []string{policyManual, policyNotify, policyAutoSafeAlias, policyAutoSafe}
