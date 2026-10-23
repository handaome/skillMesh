import { ensureFresh, loadMe, logout, session } from './auth/session'
import type { OrgMembership } from './auth/session'
import { API_BASE } from './constants/api'
import router from './router'
import type { Skill, SkillPage, SkillVersion, VersionPage, VersionFiles, VersionFileContent, EditFileResult, Team, Member, Invitation, CreatedInvitation, InvitationSummary, AcceptedInvitation, PendingReview, ReviewDetail } from './types'

/** 服务端错误。按 code 分支，不解析 detail 文本（详细设计 §2.1）。 */
export class ApiError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
    message: string,
  ) {
    super(message)
  }
}

async function request<T>(
  path: string,
  method = 'GET',
  body?: unknown,
  token?: string,
  retried = false,
): Promise<T> {
  const bearer = token ?? session.accessToken
  const response = await fetch(API_BASE + path, {
    method,
    headers: {
      ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  // 会话过期：静默续期后重试一次；续不上就登出并送回登录页。
  // 显式传了 token 的调用没有会话，不走这条路；retried 保证只重试一次
  // （refresh token 本身失效时否则会无限循环）。
  if (response.status === 401 && token === undefined && bearer) {
    // 第一次 401：续期后重试一次。
    if (!retried && (await ensureFresh(true))) {
      // 静默续期只换令牌，从不重拉 /me：挂载时那一次拉失败的话组织列表
      // 会一直空着，团队页对真正的组织成员说「你还没有加入任何组织」。
      // 只在列表空着时补拉——正常情况下没必要多打一次请求。
      if (!session.organizations.length) void loadMe().catch(() => {})
      return request<T>(path, method, body, token, true)
    }
    // 续不上，或续期后重试仍是 401（令牌被服务端拒了：签发方/受众不符、被吊销、
    // 时钟偏差）：续期救不了这个会话，按续期失败处理——清会话并送回登录页，
    // 否则每个页面都渲染「UNAUTHORIZED」，用户只能自己点顶栏的登出。
    logout()
    // 跳不动也不该盖掉 401 本身（下面的 ApiError 才是调用方要的东西）。
    await router.push({
      path: '/login',
      query: { redirect: router.currentRoute.value.fullPath },
    }).catch(() => {})
  }
  if (!response.ok) {
    let code = `HTTP_${response.status}`
    let message = response.statusText
    try {
      const parsed = await response.json()
      code = parsed.code ?? code
      message = parsed.detail ?? message
    } catch {
      // 非 JSON 错误体，沿用状态码
    }
    throw new ApiError(code, response.status, message)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

/**
 * 搜索当前用户可见的 Skill。
 *
 * limit 给到服务端上限（100）：默认 20 会把结果静默截断。
 * 超过 100 条时服务端在 next_cursor 里给出下一页的起点，has_more 说清还有没有——
 * 调用方得把它接上，只拉一页就显示「N 个」的话，那个数是假的。
 */
export function searchSkills(q: string, cursor?: string): Promise<SkillPage> {
  const params = new URLSearchParams({ limit: '100' })
  if (q) params.set('q', q)
  if (cursor) params.set('cursor', cursor)
  return request<SkillPage>(`/skills?${params}`, 'GET')
}

/**
 * 我名下的 Skill（个人空间 + 我建的团队 Skill）。
 * 服务端按属主过滤，可见性条件对它不生效——属主本来就看得见自己的东西。
 *
 * 分页与 searchSkills 一样：mine=true 只改属主过滤，游标照样要接。
 */
export function searchMySkills(q: string, cursor?: string): Promise<SkillPage> {
  const params = new URLSearchParams({ mine: 'true', limit: '100' })
  if (q) params.set('q', q)
  if (cursor) params.set('cursor', cursor)
  return request<SkillPage>(`/skills?${params}`, 'GET')
}

/**
 * 新建 Skill 的元数据。
 * 可见性只有 private / public：带组织的 Skill 要 publisher 及以上，
 * 那种由 CLI publish 负责（它顺带建元数据），网页端不重复一套归属选择。
 */
export function createSkill(body: {
  slug: string
  name: string
  description: string
  visibility: 'private' | 'public'
  tags?: string[]
}): Promise<Skill> {
  return request<Skill>('/skills', 'POST', body)
}

/**
 * 改展示性元数据。slug 与可见性改不了（服务端不收那两项），
 * 改了会影响别人已经装的东西。
 */
export function updateSkill(
  id: string,
  body: { name?: string; description?: string; tags?: string[] },
): Promise<Skill> {
  return request<Skill>(`/skills/${id}`, 'PATCH', body)
}

/** 撤回一个版本。只有属主能撤，撤了不可逆：恢复得发新版本。 */
export function revokeVersion(skillId: string, version: string, reason = ''): Promise<unknown> {
  return request<unknown>(
    `/skills/${skillId}/versions/${encodeURIComponent(version)}/revoke`,
    'POST',
    { reason },
  )
}

export function getSkill(id: string, token?: string): Promise<Skill> {
  return request<Skill>(`/skills/${id}`, 'GET', undefined, token)
}

export function getVersions(id: string, token?: string): Promise<VersionPage> {
  return request<VersionPage>(`/skills/${id}/versions`, 'GET', undefined, token)
}

/** 某版本里的文件清单。服务端从真 zip 算出来的那份，不是发布方自填的。 */
export function listVersionFiles(
  skillId: string,
  version: string,
): Promise<VersionFiles> {
  return request<VersionFiles>(
    `/skills/${skillId}/versions/${encodeURIComponent(version)}/files`,
  )
}

/**
 * 读某个文件的正文。
 *
 * 二进制文件服务端不返回正文（content 为 null，binary 为 true）——把乱码塞给
 * 浏览器既没意义，也可能被当成脚本渲染。
 */
export function readVersionFile(
  skillId: string,
  version: string,
  path: string,
): Promise<VersionFileContent> {
  const params = new URLSearchParams({ path })
  return request<VersionFileContent>(
    `/skills/${skillId}/versions/${encodeURIComponent(version)}/file?${params}`,
  )
}

/**
 * 改一个文件，产出新版本。
 *
 * 版本不可变：这不是就地改，而是服务端基于源版本重打包出一个新版本，
 * 再走一遍正常的发布流程（扫描 → 发布或送审）。
 */
export function editVersionFile(
  skillId: string,
  version: string,
  body: {
    path: string
    content?: string
    /** 二进制内容（base64）。上传图片这类文件时用它，content 只能装文本。 */
    content_base64?: string
    /** replace（改内容，默认）/ create（新建）/ delete（删除）。 */
    operation?: 'replace' | 'create' | 'delete'
    /** 要不要可执行位。不给就沿用原来的。 */
    executable?: boolean
    changelog?: string
  },
): Promise<EditFileResult> {
  return request<EditFileResult>(
    `/skills/${skillId}/versions/${encodeURIComponent(version)}/file`,
    'PUT',
    body,
  )
}

export function listTeams(organizationId: string): Promise<Team[]> {
  return request<Team[]>(`/organizations/${organizationId}/teams`)
}

/** 建组织。建完组织列表要重拉——session 里的 organizations 决定顶栏与团队页。 */
export function createOrganization(slug: string, name: string): Promise<OrgMembership> {
  return request<OrgMembership>('/organizations', 'POST', { slug, name })
}

/** 建团队。团队名与组织名可以只给 slug 一个。 */
export function createTeam(organizationId: string, slug: string, name: string): Promise<Team> {
  return request<Team>(`/organizations/${organizationId}/teams`, 'POST', { slug, name })
}

export function listOrgMembers(organizationId: string): Promise<Member[]> {
  return request<Member[]>(`/organizations/${organizationId}/members`)
}

export function listTeamMembers(organizationId: string, teamId: string): Promise<Member[]> {
  return request<Member[]>(`/organizations/${organizationId}/teams/${teamId}/members`)
}

export function listInvitations(organizationId: string): Promise<Invitation[]> {
  return request<Invitation[]>(`/organizations/${organizationId}/invitations`)
}

export function createInvitation(organizationId: string, body: {
  team_id?: string
  org_role?: string
  team_role?: string
  /** 绑到某个已有成员的 OIDC sub：填了就只有他能接受这份邀请。 */
  subject?: string
}): Promise<CreatedInvitation> {
  return request<CreatedInvitation>(
    `/organizations/${organizationId}/invitations`, 'POST', body)
}

export function revokeInvitation(organizationId: string, invitationId: string): Promise<void> {
  return request<void>(`/organizations/${organizationId}/invitations/${invitationId}`, 'DELETE')
}

export function updateOrgRole(organizationId: string, userId: string, role: string): Promise<void> {
  return request<void>(`/organizations/${organizationId}/members/${userId}`, 'PATCH', { role })
}

/** 公开：没登录的人也要看得懂这份邀请是什么。 */
export function getInvitation(token: string): Promise<InvitationSummary> {
  return request<InvitationSummary>(`/invitations/${encodeURIComponent(token)}`)
}

export function acceptInvitation(token: string): Promise<AcceptedInvitation> {
  // method 在第二位：写成 body 位会发成 GET，接受了也不算数。
  return request<AcceptedInvitation>(
    `/invitations/${encodeURIComponent(token)}/accept`, 'POST')
}

/** 待审队列。服务端默认 limit=50。 */
export function listReviews(status = 'pending_review'): Promise<PendingReview[]> {
  return request<PendingReview[]>(`/reviews?status=${encodeURIComponent(status)}`)
}

/** 最近放行或被拒的版本。审核页要看得见历史，不只是当下的待办。 */
export function listRecentReviews(status: 'published' | 'rejected'): Promise<PendingReview[]> {
  return request<PendingReview[]>(`/reviews?status=${encodeURIComponent(status)}&limit=20`)
}

export function getReviewDetail(skillId: string, version: string): Promise<ReviewDetail> {
  return request<ReviewDetail>(`/reviews/${skillId}/${encodeURIComponent(version)}`)
}

export function approveVersion(skillId: string, version: string, comment = ''): Promise<unknown> {
  return request<unknown>(
    `/skills/${skillId}/versions/${encodeURIComponent(version)}/approve`, 'POST', { comment })
}

export function rejectVersion(skillId: string, version: string, comment = ''): Promise<unknown> {
  return request<unknown>(
    `/skills/${skillId}/versions/${encodeURIComponent(version)}/reject`, 'POST', { comment })
}

/**
 * 改团队角色。
 *
 * 与 updateOrgRole 是两件事：团队角色管审核放行权，组织角色管发布权。
 * 团队里没有 owner 这一档，服务端会 400。
 */
export function updateTeamRole(organizationId: string, teamId: string, userId: string, role: string): Promise<void> {
  return request<void>(
    `/organizations/${organizationId}/teams/${teamId}/members/${userId}`, 'PATCH', { role })
}
