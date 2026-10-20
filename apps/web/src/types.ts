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
}

export interface SkillPage {
  items: Skill[]
  next_cursor: string | null
  has_more: boolean
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
}

export interface VersionPage {
  items: SkillVersion[]
  next_cursor: string | null
  has_more: boolean
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
  /** 版本自己的状态：draft / scanning / pending_review / published / rejected。 */
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
