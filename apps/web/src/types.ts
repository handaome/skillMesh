// 与 api-spec/openapi.yaml 的 schemas 对应，字段名保持 snake_case。
export interface Skill {
  id: string
  slug: string
  name: string
  description: string
  visibility: 'private' | 'team' | 'organization' | 'public'
  status: string
  tags: string[]
  latest_version: string | null
  created_at: string
  updated_at: string
  /** 属主用户。团队/组织归属另看下面两项。 */
  owner_user_id?: string | null
  organization_id?: string | null
  owner_team_id?: string | null
  /** 描述之外的自定义键值。 */
  metadata?: Record<string, unknown>
}

/**
 * 游标分页的一页。
 *
 * 服务端所有分页端点都是这个形状：条目、下一页游标、还有没有更多。
 * 各端点只差 items 里的元素类型，所以只在这里定义一次。
 */
export interface Page<T> {
  items: T[]
  next_cursor: string | null
  has_more: boolean
}

export type SkillPage = Page<Skill>

/**
 * 一个标签及其数量。
 *
 * 数量给筛选器显示：用户点之前就知道大概会筛出多少条。
 */
export interface TagCount {
  tag: string
  count: number
}

/**
 * 已删除（归档）的 Skill。
 *
 * 服务端只返回当前用户自己删掉的那些——别人删的不在这儿。
 * slug 让了出来，所以同名能重建；这条记录留着是为了让各端知道
 * 「云端删过了，本地那份也跟着删」。
 */
export interface DeletedSkill {
  id: string
  slug: string
  name: string
  /** 删除时间。ISO 串，带时区。 */
  deleted_at: string
}

/** 已删除 Skill 的一页。与 SkillPage 同一个游标口径。 */
export type DeletedSkillPage = Page<DeletedSkill>

/**
 * 服务端给的公开配置。
 *
 * 登录前浏览器要知道 OIDC 的 issuer 与 client_id：写死在控制台源码里的话，
 * 换企业 IdP 就得改代码重新打包——部署文档写的「只认 OIDC issuer，代码不用改」
 * 对网页端并不成立。
 */
export interface PublicConfig {
  issuer: string
  client_id: string
  /** 服务端支持内置账号（用户名 + 密码）。 */
  local_auth: boolean
  /** 能不能自助注册。 */
  register_open: boolean
}

export interface ArtifactSummary {
  id: string
  sha256: string
  size_bytes: number
  content_type: string
}

export interface SkillVersion {
  id: string
  skill_id: string
  version: string
  channel: string
  status: string
  source_commit: string | null
  artifact: ArtifactSummary | null
  published_at: string | null
  created_at: string
  /** 最近一次扫描结论：装之前给用户看的风险来源（设计文档 §4「来源与风险展示」）。 */
  scan: ScanSummary | null
}

/** 扫描结论。 */
export interface ScanSummary {
  scanner_name: string
  scanner_version: string
  status: string
  summary: string
  completed_at: string | null
  findings: {
    path: string
    line: number
    rule: string
    detail: string
  }[]
}

export type VersionPage = Page<SkillVersion>

/** 一个 Skill 的 Git 来源。 */
export interface SkillSource {
  source_type: string
  provider: string | null
  repository_url: string | null
  subpath: string | null
  ref_type: string | null
  ref_value: string | null
  /** 已经落到云端的提交。 */
  resolved_commit: string | null
}

/** 检查 Git 来源有没有新提交的结果。 */
export interface SourceUpdateCheck {
  repository_url: string
  ref_value: string
  current_commit: string
  remote_commit: string
  has_update: boolean
}

/** 从 Git 来源更新出来的版本。 */
export interface SourceUpdateResult {
  version: string
  source_commit: string
  sha256: string
  file_count: number
  /** published / pending_review / quarantined。 */
  status: string
}

/** 某版本里的文件清单。服务端从真 zip 算出来的那份。 */
export interface VersionFiles {
  version: string
  file_count: number
  files: {
    path: string
    size_bytes: number
    sha256: string
  }[]
}

/** 某个文件的正文。二进制文件 content 为 null。 */
export interface VersionFileContent {
  version: string
  path: string
  size_bytes: number
  sha256: string
  binary: boolean
  content: string | null
}

/** 编辑文件的结果：新版本的信息与状态。 */
export interface EditFileResult {
  skill_id: string
  /** 新版本号。编辑不改旧版本。 */
  version: string
  file_count: number
  sha256: string
  /** published / pending_review / rejected / quarantined。 */
  status: string
}

export interface Team {
  id: string
  organization_id: string
  slug: string
  name: string
  status: string
}

export interface Member {
  user_id: string
  display_name: string
  email: string | null
  role: string
  joined_at: string
  /** OIDC 的 sub——邀请要绑到某个人时填的就是它。 */
  subject: string
  /** 账号状态：active / disabled。被禁用的人还挂在花名册上，只是登不进来了。 */
  status: string
}

export interface Invitation {
  id: string
  organization_name: string
  team_name: string | null
  org_role: string
  team_role: string
  status: string
  expires_at: string
  created_at: string
}

// 邀请列表的一页：与服务端的 InvitationPage 同形状，跟 SkillPage 一个口径。
export type InvitationPage = Page<Invitation>

export interface CreatedInvitation {
  id: string
  organization_slug: string
  organization_name: string
  team_slug: string | null
  team_name: string | null
  org_role: string
  team_role: string
  expires_at: string
  invite_url: string
  token: string
}

// 公开邀请摘要：与服务端的 InvitationSummary 是同一个 DTO，
// 列表端点与公开端点返回的都是它。别复制一份——将来加字段要改两处。
export type InvitationSummary = Invitation

// 接受邀请的结果：告诉前端落进了哪个组织、哪个团队。
export interface AcceptedInvitation {
  invitation_id: string
  organization_id: string
  team_id: string | null
  org_role: string
  team_role: string
  message: string
}

// 审核相关：字段名与 review/dto、diff/FileChange 逐字段对齐。
export interface ReviewScan {
  status: string
  summary: string
}

export interface ReviewSource {
  provider: string | null
  repository: string | null
  ref: string | null
  commit: string | null
}

export interface PendingReview {
  skill_id: string
  skill_slug: string | null
  skill_name: string | null
  version: string
  channel: string
  submitted_by: string | null
  submitted_at: string | null
  scan: ReviewScan
  source: ReviewSource
  /** 版本自己的状态：draft / scanning / pending_review / published / rejected / quarantined。 */
  status: string
}

export interface FileChange {
  path: string
  kind: 'added' | 'modified' | 'removed'
  added: number
  removed: number
  truncated: boolean
}

/** 扫描命中项。键与 UploadService.toJson 写进 findings_json 的一致。 */
export interface ReviewFinding {
  path: string
  line: number
  rule: string
  detail: string
}

export interface ReviewDetail {
  item: PendingReview
  skill_md: string
  changelog: string | null
  findings: ReviewFinding[]
  diff: {
    base_version: string
    truncated: boolean
    files: FileChange[]
  }
}

/** 一条审计事件（设计文档 §4 审计）。 */
export interface AuditEvent {
  occurred_at: string
  action: string
  target_type: string
  target_id: string | null
  outcome: 'success' | 'denied' | 'failure'
  details: Record<string, unknown>
}

/**
 * 审计流水的一页。
 *
 * 流水只增不减——只给最近 N 条的话，旧的就再也翻不到了，
 * 「上个月那次改动是谁做的」这种问题就答不上来。
 */
export type AuditEventPage = Page<AuditEvent>

/** 待审队列的一页。待审数没有上限，同样要能往下翻。 */
export type PendingReviewPage = Page<PendingReview>

/**
 * 批量审核的结果。
 *
 * 逐条独立：过了的和没过的分开报。没过的那几条带原因，
 * 用户能知道是哪一条、为什么。
 */
export interface BatchDecideResult {
  succeeded: { skill_id: string; version: string }[]
  failed: {
    skill_id: string
    version: string
    code: string
    message: string
  }[]
}

/**
 * 批量操作（改标签、归档）的结果。
 *
 * 与批量审核同一个形状：逐条独立，两边都报清楚。
 */
export interface BatchSkillResult {
  succeeded: { id: string }[]
  failed: { id: string; code: string; message: string }[]
}

/** 回收站里被删掉的文件（设计文档 §6.4：软删除、回收期内能找回）。 */
/**
 * 技能集合：命名的一组 Skill。
 *
 * 徽标只算「我装了几个」——本人可见的数据。团队级的覆盖率要另开权限，
 * 不在这里混进来。
 */
export interface Group {
  id: string
  slug: string
  name: string
  description: string
  visibility: string
  member_count: number
  installed_count: number
  members: GroupMember[]
}

/** 集合里的一个 Skill。 */
export interface GroupMember {
  id: string
  slug: string
  name: string
  latest_version: string | null
  installed: boolean
}

export interface RecycleFile {
  path: string
  sha256: string
  size_bytes: number
  source_version: string
  created_at: string
  expires_at: string
}

/** 账号。username 为空的是外部 IdP 账号，只能禁用，不能改密码。 */
export interface UserAccount {
  id: string
  username: string | null
  display_name: string | null
  email: string | null
  status: string
  role: string
  created_at: string
}
