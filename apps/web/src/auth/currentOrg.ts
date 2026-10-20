import { computed, reactive } from 'vue'
import { session } from './session'

/** 顶栏选中的组织 id。空表示跟随 session 里的第一个组织。 */
const selection = reactive({ organizationId: '' })

/**
 * 当前组织。组织列表里找不到选中的那个（换账号、权限变了）就退回第一个。
 * 与 session 一样是 reactive 单例——顶栏改它，团队页跟着切。
 */
export const currentOrg = computed(
  () => session.organizations.find((o) => o.id === selection.organizationId)
    ?? session.organizations[0]
    ?? null,
)

export function selectOrganization(organizationId: string) {
  selection.organizationId = organizationId
}
