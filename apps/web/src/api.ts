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

async function request<T>(path: string, token: string): Promise<T> {
  const response = await fetch(BASE + path, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!response.ok) {
    let code = `HTTP_${response.status}`
    let message = response.statusText
    try {
      const body = await response.json()
      code = body.code ?? code
      message = body.detail ?? message
    } catch {
      // 非 JSON 错误体，沿用状态码
    }
    throw new ApiError(code, response.status, message)
  }
  return (await response.json()) as T
}

export function searchSkills(q: string, token: string): Promise<SkillPage> {
  const params = new URLSearchParams()
  if (q) params.set('q', q)
  const suffix = params.toString() ? `?${params}` : ''
  return request<SkillPage>(`/skills${suffix}`, token)
}

export function getSkill(id: string, token: string): Promise<Skill> {
  return request<Skill>(`/skills/${id}`, token)
}

export function getVersions(id: string, token: string): Promise<VersionPage> {
  return request<VersionPage>(`/skills/${id}/versions`, token)
}
