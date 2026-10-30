import { ensureFresh, loadMe, logout, session } from './auth/session'
import type { OrgMembership } from './auth/session'
import { API_BASE } from './constants/api'
import router from './router'
import type { Skill, SkillPage, SkillVersion, VersionPage, VersionFiles, VersionFileContent, EditFileResult, Team, Member, OrgPermissions, InvitationPage, CreatedInvitation, InvitationSummary, AcceptedInvitation, PendingReview, PendingReviewPage, ReviewDetail, AuditEventPage, RecycleFile, DeletedSkillPage, TagCount, BatchDecideResult, BatchSkillResult, Group, UserAccount, SkillSource, SourceUpdateCheck, SourceUpdateResult, ResolvedSource, ImportedSkill } from './types'
import { authHeaders } from './utils/auth'

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

/**
 * 发请求，管会话过期。
 *
 * 返回原始响应，不解析内容：JSON 与二进制流都从这里出去——制品下载是
 * 二进制流，按 JSON 解析会炸。
 */
async function fetchWithAuth(
  path: string,
  method = 'GET',
  body?: unknown,
  token?: string,
  retried = false,
): Promise<Response> {
  const bearer = token ?? session.accessToken
  const response = await fetch(API_BASE + path, {
    method,
    headers: {
      ...authHeaders(bearer),
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
      return fetchWithAuth(path, method, body, token, true)
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
  return response
}

async function request<T>(
  path: string,
  method = 'GET',
  body?: unknown,
  token?: string,
): Promise<T> {
  const response = await fetchWithAuth(path, method, body, token)
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
export function searchSkills(
  q: string, cursor?: string, filters?: Record<string, string>,
): Promise<SkillPage> {
  const params = new URLSearchParams({ limit: '100' })
  if (q) params.set('q', q)
  if (cursor) params.set('cursor', cursor)
  for (const [key, value] of Object.entries(filters ?? {})) {
    if (value) params.set(key, value)
  }
  return request<SkillPage>(`/skills?${params}`, 'GET')
}

/**
 * 我名下的 Skill（个人空间 + 我建的团队 Skill）。
 * 服务端按属主过滤，可见性条件对它不生效——属主本来就看得见自己的东西。
 *
 * 分页与 searchSkills 一样：mine=true 只改属主过滤，游标照样要接。
 */
export function searchMySkills(
  q: string, cursor?: string, filters?: Record<string, string>,
): Promise<SkillPage> {
  const params = new URLSearchParams({ mine: 'true', limit: '100' })
  if (q) params.set('q', q)
  if (cursor) params.set('cursor', cursor)
  for (const [key, value] of Object.entries(filters ?? {})) {
    if (value) params.set(key, value)
  }
  return request<SkillPage>(`/skills?${params}`, 'GET')
}

/**
 * 我可见的标签与各自的数量。
 *
 * 标签清单要单独取，不能从一页列表里汇总：列表是游标分页的，汇总出来的
 * 只是这一页出现过的标签——用户想按一个只出现在第二页的标签筛选时，
 * 根本没有那个选项可点。
 */
export function fetchTags(mine = false): Promise<TagCount[]> {
  const params = new URLSearchParams({ mine: String(mine) })
  return request<TagCount[]>(`/tags?${params}`, 'GET')
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

/**
 * 某个 Skill 的版本列表，按游标翻页。
 *
 * 版本只增不减（每发一版 +1），一次全量返回迟早把响应撑爆：
 * 客户端只取前 N 条，后面的版本谁都看不见。
 */
export function getVersions(id: string, token?: string, cursor?: string): Promise<VersionPage> {
  const params = new URLSearchParams({ limit: '50' })
  if (cursor) params.set('cursor', cursor)
  return request<VersionPage>(`/skills/${id}/versions?${params}`, 'GET', undefined, token)
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

/**
 * 某个 Skill 的 Git 来源。
 *
 * 没有 Git 来源的 Skill 服务端返回 404——那是「这个 Skill 不是从 Git 来的」，
 * 不是「出错了」，调用方按 404 隐藏入口即可。
 */
export function getSkillSource(skillId: string): Promise<SkillSource> {
  return request<SkillSource>(`/skills/${skillId}/source`)
}

/** 查 Git 来源有没有新提交。只查 ref，不下载内容。 */
export function checkSkillSourceUpdates(skillId: string): Promise<SourceUpdateCheck> {
  return request<SourceUpdateCheck>(`/skills/${skillId}/source/check-updates`, 'POST')
}

/**
 * 从 Git 来源拉新提交，产出新版本。
 *
 * 走的是和 CLI 发布一样的链路：服务端扫描 → 个人与公开直接发布，
 * 团队与组织进待审队列。
 */
export function updateSkillFromSource(skillId: string): Promise<SourceUpdateResult> {
  return request<SourceUpdateResult>(`/skills/${skillId}/source/update`, 'POST')
}

/** 解析仓库里的 Skill 候选目录。只读，不装任何东西。 */
export function resolveGitSource(body: {
  repository_url: string
  path?: string
  ref?: string
}): Promise<ResolvedSource> {
  return request<ResolvedSource>('/sources/resolve', 'POST', body)
}

/**
 * 从 Git 仓库导入一个 Skill 到云端。
 *
 * 给的是那些「从 GitHub 下载了、还没进 SkillMesh」的手工 Skill：
 * 导入后它在云端有来源记录，之后就能在网页上查更新、拉新版。
 */
export function importSkillFromGit(body: {
  repository_url: string
  path?: string
  ref?: string
  slug: string
  name: string
  description: string
  visibility: 'private' | 'public'
  tags?: string[]
}): Promise<ImportedSkill> {
  return request<ImportedSkill>('/sources/import', 'POST', body)
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

/**
 * 我在这个组织下的权限清单。
 *
 * 前端按它决定按钮显隐：没有的权限点了也是 403，不如别显示。
 * 服务端仍然是最终裁决者，这份清单只决定界面长什么样。
 */
export function getOrgPermissions(organizationId: string): Promise<OrgPermissions> {
  return request<OrgPermissions>(`/organizations/${organizationId}/permissions`, 'GET')
}

/**
 * 组织的邀请列表（一页）。
 *
 * 服务端按 (created_at, id) 倒序翻页：has_more 说清还有没有，next_cursor 是
 * 下一页的起点。只拉一页就当成全部，页面说的就是假话——把「还有更多」接出去。
 */
export function listInvitations(organizationId: string, cursor?: string): Promise<InvitationPage> {
  const params = new URLSearchParams({ limit: '20' })
  if (cursor) params.set('cursor', cursor)
  return request<InvitationPage>(`/organizations/${organizationId}/invitations?${params}`, 'GET')
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

/**
 * 禁用或启用一个成员。
 *
 * 禁用的是账号（users.status），不是移出花名册：人还在组织里，
 * 只是登不进来、也不能发布。被禁用的人仍占着成员位置，恢复后不用重新邀请。
 */
export function updateMemberStatus(
  organizationId: string, userId: string, status: 'active' | 'disabled',
): Promise<void> {
  return request<void>(`/organizations/${organizationId}/members/${userId}`, 'PATCH', { status })
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

/**
 * 待审队列，按游标翻页。
 *
 * 待审数没有上限：团队一直发版，队列就一直长。只给一页的话，
 * 排在窗口外的待审版本永远没人看得见。
 */
export function listReviews(status = 'pending_review', cursor?: string): Promise<PendingReviewPage> {
  const params = new URLSearchParams({ status, limit: '50' })
  if (cursor) params.set('cursor', cursor)
  return request<PendingReviewPage>(`/reviews?${params}`, 'GET')
}

/** 最近放行或被拒的版本。审核页要看得见历史，不只是当下的待办。 */
export function listRecentReviews(
  status: 'published' | 'rejected', cursor?: string,
): Promise<PendingReviewPage> {
  const params = new URLSearchParams({ status, limit: '20' })
  if (cursor) params.set('cursor', cursor)
  return request<PendingReviewPage>(`/reviews?${params}`, 'GET')
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
 * 删除一个 Skill。
 *
 * 软删除：数据留着，status 变成 archived，slug 让出来——同名能重建。
 * 各端同步时据此把本地那份一起删掉。
 */
/**
 * 批量审核。
 *
 * 逐条独立：一条过了另一条没过，两边都报清楚。服务端要是共用一个事务，
 * 第一条失败会把已经放行的也一起撤回来。
 *
 * 权限不放宽：每条照样走单条审核的那几道闸（reviewer 角色、
 * 不能审自己发布的版本）。批量只是省了逐条点开详情页。
 */
export function batchDecide(
  items: { skill_id: string; version: string }[],
  action: 'approve' | 'reject',
  comment = '',
): Promise<BatchDecideResult> {
  return request<BatchDecideResult>(
    '/reviews/batch-decide', 'POST', { action, items, comment },
  )
}

/**
 * 批量改标签。
 *
 * 逐条独立：一条没权限不该把已经改好的也一起撤回来。权限与单条更新一致
 * （只有属主能改）。
 */
export function batchUpdateTags(ids: string[], tags: string[]): Promise<BatchSkillResult> {
  return request<BatchSkillResult>('/skills/batch/tags', 'PATCH', { ids, tags })
}

/**
 * 批量归档（软删除）。
 *
 * 数据留着，可以还原；各端同步后本地那份也会被删掉。
 */
export function batchArchive(ids: string[]): Promise<BatchSkillResult> {
  return request<BatchSkillResult>('/skills/batch/archive', 'POST', { ids })
}

/**
 * 我可见的技能集合。
 *
 * 徽标只算「我装了几个」——本人可见的数据。
 */
export function listGroups(): Promise<Group[]> {
  return request<Group[]>('/groups', 'GET')
}

export function deleteSkill(id: string): Promise<void> {
  return request<void>(`/skills/${id}`, 'DELETE')
}

/**
 * 还原一个被删除的 Skill。
 *
 * 还原完它就回到删除前的样子，重新出现在「我的 Skill」里。
 */
export function restoreSkill(id: string): Promise<Skill> {
  return request<Skill>(`/skills/${id}/restore`, 'POST')
}

/**
 * 我删掉过哪些 Skill。
 *
 * 服务端只返回当前用户自己删掉的那些。分页与别的列表一个口径：
 * has_more 说清还有没有，next_cursor 是下一页的起点。
 *
 * 路径是 /deleted-skills 而不是 /skills/deleted：后者会被
 * GET /skills/{skillId} 抢走——skillId 那一段是变量，deleted 被当 UUID 解析，
 * 报出来的是「参数格式不对」，看不出是路由撞了。
 */
export function listDeletedSkills(cursor?: string): Promise<DeletedSkillPage> {
  const params = new URLSearchParams({ limit: '100' })
  if (cursor) params.set('cursor', cursor)
  return request<DeletedSkillPage>(`/deleted-skills?${params}`, 'GET')
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

/**
 * 制品下载地址。
 *
 * 服务端先授权再给地址——不返回含长期权限的公开 URL（设计文档 §12）。
 * 带令牌取认证流，避免把带权限的地址留在浏览器历史里。
 */
export async function downloadArtifact(artifactId: string): Promise<Blob> {
  // 不能走 request()：它总是按 JSON 解析响应，制品是二进制流。
  // 但 401 的处理要走同一条路：令牌过期时静默续期再重试一次，
  // 否则用户点下载只看到一句「HTTP 401」，下载不动。
  // 续期后重发的是一次全新的请求，下载流不会被读坏。
  const response = await fetchWithAuth(
    `/artifacts/${artifactId}/download?disposition=stream`)
  if (!response.ok) {
    throw new Error(`下载制品失败：HTTP ${response.status}`)
  }
  return response.blob()
}

/**
 * 我可见的审计流水（设计文档 §4 审计）。
 *
 * 服务端只返回我能看见的 Skill 的事件——审计不是所有人都能翻的。
 *
 * 按游标翻页：流水只增不减，只拉一页的话旧的就再也翻不到了。
 */
export function listAuditEvents(action?: string, cursor?: string): Promise<AuditEventPage> {
  const params = new URLSearchParams({ limit: '50' })
  if (action) params.set('action', action)
  if (cursor) params.set('cursor', cursor)
  return request<AuditEventPage>(`/audit-events?${params}`, 'GET')
}

/**
 * 某个 Skill 的回收站：被删掉的文件。
 *
 * 回收期内能恢复（恢复会产出新版本）——设计文档 §6.4「删除传播：软删除、回收期、支持恢复」。
 */
export function listRecycleFiles(skillId: string): Promise<RecycleFile[]> {
  return request<RecycleFile[]>(`/skills/${skillId}/recycle`, 'GET')
}

/**
 * 恢复一个被删掉的文件。
 *
 * 恢复不改旧版本——它产出**新版本**（版本不可变，设计文档 §2）。
 */
export function restoreRecycleFile(
  skillId: string, path: string,
): Promise<EditFileResult> {
  return request<EditFileResult>(
    `/skills/${skillId}/recycle/restore`, 'POST', { path })
}

/**
 * 账号列表。只有站点管理员拿得到，别人调这一下会吃 403。
 *
 * 自助注册关掉后，账号只能从管理员这儿来，这个列表就是花名册。
 */
export function listUsers(): Promise<UserAccount[]> {
  return request<UserAccount[]>('/users', 'GET')
}

/**
 * 建一个内置账号。
 *
 * 建出来的固定是普通用户（role=user），管理员名额不开放给这个接口。
 */
export function createUser(body: {
  username: string
  password: string
  display_name?: string
  email?: string
}): Promise<UserAccount> {
  return request<UserAccount>('/users', 'POST', body)
}

/**
 * 禁用或启用一个账号。
 *
 * 禁用后登不进来，账号和它名下的数据都留着，恢复后不用重建。
 * 外部 IdP 账号（username 为空）也归这里管——它们的账号在本系统里。
 */
export function updateUserStatus(
  userId: string, status: 'active' | 'disabled',
): Promise<UserAccount> {
  return request<UserAccount>(
    `/users/${encodeURIComponent(userId)}/status`, 'PATCH', { status })
}
