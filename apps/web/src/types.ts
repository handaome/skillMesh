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
