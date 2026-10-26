/**
 * 状态词表：可见性、版本状态、扫描结论。
 *
 * 集中在这里，不在页面里各写一份：同一个状态词在几个页面各翻一次，
 * 迟早翻出两种说法（同一个扫描结论，市场页说「通过」、待审页说「扫描通过」）。
 *
 * 查不到就退回服务端给的状态词——词表是给人看的，不是用来拦人的。
 */

/** 可见性。 */
export const VISIBILITY_TEXT: Record<string, string> = {
  private: '私有',
  public: '公开',
  team: '团队可见',
  organization: '组织可见',
}

/**
 * 版本状态。
 *
 * 与服务端的状态枚举一一对应（draft / scanning / pending_review /
 * published / rejected / quarantined / revoked）。
 */
export const VERSION_STATUS_TEXT: Record<string, string> = {
  draft: '草稿',
  scanning: '扫描中',
  pending_review: '待审核',
  published: '已发布',
  rejected: '已拒绝',
  quarantined: '已隔离',
  revoked: '已撤回',
}

/**
 * 扫描结论。
 *
 * 与服务端的状态枚举一一对应（passed / warning / failed / error）。
 */
export const SCAN_STATUS_TEXT: Record<string, string> = {
  passed: '通过',
  warning: '有告警',
  failed: '未通过',
  error: '出错',
}

/**
 * 文件浏览器里的版本状态。
 *
 * 与 VERSION_STATUS_TEXT 分开一张表：这里的 rejected / quarantined 要说清
 * 是服务端的制品扫描拦下的，不是普通拒绝——用户在文件浏览器里看到的是
 * 自己刚改的文件被拦，得知道原因。
 */
export const FILE_BROWSER_STATUS_TEXT: Record<string, string> = {
  draft: '草稿',
  scanning: '扫描中',
  published: '已发布',
  pending_review: '已提交审核',
  rejected: '被服务端的制品扫描拦下',
  quarantined: '被服务端的制品扫描隔离',
}
