import { ensureFresh, logout, session } from './auth/session'
import router from './router'
import type { Skill, SkillPage, SkillVersion, VersionPage, Team, Member, Invitation, CreatedInvitation, InvitationSummary, AcceptedInvitation, PendingReview, ReviewDetail } from './types'

const BASE = '/api/v1'

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
  const response = await fetch(BASE + path, {
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
  if (response.status === 401 && token === undefined && bearer && !retried) {
    if (await ensureFresh(true)) {
      return request<T>(path, method, body, token, true)
    }
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

export function searchSkills(q: string, token?: string): Promise<SkillPage> {
  const params = new URLSearchParams()
  if (q) params.set('q', q)
  const suffix = params.toString() ? `?${params}` : ''
  return request<SkillPage>(`/skills${suffix}`, 'GET', undefined, token)
}

export function getSkill(id: string, token?: string): Promise<Skill> {
  return request<Skill>(`/skills/${id}`, 'GET', undefined, token)
}

export function getVersions(id: string, token?: string): Promise<VersionPage> {
  return request<VersionPage>(`/skills/${id}/versions`, 'GET', undefined, token)
}

export function listTeams(organizationId: string): Promise<Team[]> {
  return request<Team[]>(`/organizations/${organizationId}/teams`)
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
