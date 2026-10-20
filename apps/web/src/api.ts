import { session } from './auth/session'
import type { Skill, SkillPage, SkillVersion, VersionPage } from './types'

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
