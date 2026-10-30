import { ref } from 'vue'
import { getOrgPermissions } from '../api'
import type { OrgPermissions, Permission } from '../types'

/**
 * 权限清单。按钮显隐按它来：没有的权限点了也是 403，不如别显示。
 *
 * 放模块级而不是组件里：团队页与审核页都要用同一份，
 * 不该拉两遍。缓存跟着页面刷新走——刷新一次重新拉，
 * 权限刚被人改掉时不用等缓存过期。
 */
const cache = ref<Record<string, OrgPermissions>>({})
/** 正在拉的组织。同一个组织并发拉只发一次请求。 */
const inflight = new Map<string, Promise<OrgPermissions>>()

/**
 * 拉某个组织的权限清单。
 *
 * 已经在拉就等那一次，不重复发请求；已经在缓存里就直接给。
 * 拉失败会往外抛——调用方要处理，权限清单没有就当作什么权限都没有。
 */
export function loadPermissions(organizationId: string): Promise<OrgPermissions> {
  const cached = cache.value[organizationId]
  if (cached) return Promise.resolve(cached)
  const running = inflight.get(organizationId)
  if (running) return running
  const task = getOrgPermissions(organizationId)
    .then((data) => {
      cache.value[organizationId] = data
      return data
    })
    .finally(() => inflight.delete(organizationId))
  inflight.set(organizationId, task)
  return task
}

/** 这个组织下的权限清单；没拉过就是 null。 */
export function permissionsOf(organizationId?: string): OrgPermissions | null {
  if (!organizationId) return null
  return cache.value[organizationId] ?? null
}

/**
 * 组织域的权限点。只看组织角色。
 *
 * 没拉到清单就是空集——按钮先不显示，清单到了再显示。
 */
export function orgPermissionList(organizationId?: string): Permission[] {
  return permissionsOf(organizationId)?.organization_permissions ?? []
}

/**
 * 某个团队的范围权限：团队成员行优先，没有才回落组织角色。审核权看这个。
 */
export function scopePermissionList(organizationId?: string, teamId?: string): Permission[] {
  const team = permissionsOf(organizationId)?.teams.find((t) => t.team_id === teamId)
  return team?.scope_permissions ?? []
}

/**
 * 某个团队的成员管理权限：团队角色与组织角色的并集。管团队成员看这个。
 *
 * 与上面的范围权限会不一样：既是某团队的普通成员、又是组织管理员的人，
 * 审核权按团队角色算（不能审），管成员按并集算（能管）。
 */
export function memberPermissionList(organizationId?: string, teamId?: string): Permission[] {
  const team = permissionsOf(organizationId)?.teams.find((t) => t.team_id === teamId)
  return team?.member_permissions ?? []
}
