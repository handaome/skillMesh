<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  createInvitation,
  createOrganization,
  createTeam,
  listInvitations,
  listOrgMembers,
  listTeamMembers,
  listTeams,
  revokeInvitation,
  updateMemberStatus,
  updateOrgRole,
  updateTeamRole,
} from '../api'
import { currentOrg } from '../auth/currentOrg'
import { loadMe, meError, session } from '../auth/session'
import { usePagedList } from '../composables/usePagedList'
import {
  loadPermissions,
  memberPermissionList,
  orgPermissionList,
} from '../composables/usePermissions'
import type { Invitation, Member, Team } from '../types'
import { errorText } from '../utils/errorText'
import EmptyState from '../components/EmptyState.vue'

const org = currentOrg
const teams = ref<Team[]>([])
/** 成员表正在读。切范围时旧行已经清空，这时更不能显示「还没有成员」。 */
const membersLoading = ref(true)
/** 当前表格里的行属于哪个范围（组织/团队）。切范围时旧行得先清掉。 */
const membersScope = ref('')
const members = ref<Member[]>([])
/**
 * 邀请列表。服务端按游标翻页，超过一页的这里能接着往下取。
 *
 * 组织 id 是这次查询的条件：翻页时用同一个组织接着查——
 * 换了组织就重新 load，拿旧组织的游标查新组织，页面对不上。
 */
const {
  items: invitations,
  loading: invitationsLoading,
  loadingMore: invitationsLoadingMore,
  error: invitationsError,
  hasMore: invitationsHasMore,
  countText: invitationCountText,
  load: loadInvitations,
  loadMore: loadMoreInvitations,
  reset: resetInvitations,
} = usePagedList<Invitation, string>((orgId, cursor) => listInvitations(orgId, cursor), {
  unit: '个',
  initialLoading: true,
})
/** 成员区的错（改角色、读成员、读团队） */
const memberError = ref('')
/** 权限清单没拉到的错 */
const permissionError = ref('')
/** 邀请区的错（生成、撤销、读邀请） */
const inviteError = ref('')
const notice = ref('')
const inviteLink = ref('')
const inviteToken = ref('')
const selectedTeam = ref('')
const inviteRole = ref('member')
const inviteOrgRole = ref('member')
// 绑定的成员（OIDC sub）：邀请只对他有效。
const inviteSubject = ref('')

// 新建组织 / 新建团队
const newOrgSlug = ref('')
const newOrgName = ref('')
const newTeamSlug = ref('')
const newTeamName = ref('')
const showNewTeam = ref(false)
const creating = ref(false)
const createError = ref('')

/** 组织角色有 owner 一档，团队没有——服务端 400 挡着，别放进下拉。 */
const ORG_ROLE_OPTIONS = ['owner', 'admin', 'publisher', 'reviewer', 'member', 'viewer']
const TEAM_ROLE_OPTIONS = ['admin', 'publisher', 'reviewer', 'member', 'viewer']

/**
 * 我在这个组织里的权限。
 *
 * 清单没拉到之前一律按没有算：按钮先不显示，等清单到了再显示——
 * 显示一个点了必然 403 的按钮比不显示更糟。
 */
const orgPermissions = computed(() => orgPermissionList(org.value?.id))
const canInvite = computed(() => orgPermissions.value.includes('ORG_INVITE'))
const canManageMembers = computed(() => orgPermissions.value.includes('ORG_MANAGE_MEMBERS'))
const canManageTeams = computed(() => orgPermissions.value.includes('ORG_MANAGE_TEAMS'))
const canGrantOwner = computed(() => orgPermissions.value.includes('ORG_GRANT_OWNER'))
const canRevokeOwner = computed(() => orgPermissions.value.includes('ORG_REVOKE_OWNER'))
/** 团队成员管理权：团队角色与组织角色的并集。 */
const teamMemberPermissions = computed(() =>
  memberPermissionList(org.value?.id, selectedTeam.value),
)
const canManageTeamMembers = computed(() =>
  teamMemberPermissions.value.includes('TEAM_MANAGE_MEMBERS'),
)
/** 当前范围能不能改别人的角色。 */
const canChangeRole = computed(() =>
  selectedTeam.value ? canManageTeamMembers.value : canManageMembers.value,
)

/**
 * 角色下拉的选项：选了团队就是团队角色，没选就是组织角色。
 *
 * 组织角色里的 owner 只有 owner 本人能给：没有授予权的人看见这个选项
 * 也只能选出一个 403，下拉里就不该出现。
 */
const roleOptions = computed(() => {
  if (selectedTeam.value) return TEAM_ROLE_OPTIONS
  return canGrantOwner.value
    ? ORG_ROLE_OPTIONS
    : ORG_ROLE_OPTIONS.filter((role) => role !== 'owner')
})

/**
 * 能不能禁用/启用这个成员。
 *
 * 不能禁自己（把自己的路堵死），也不能禁组织 owner——
 * owner 是组织的最后一道保险，要换人得先降级（两次请求）。
 */
function canToggleStatus(member: Member): boolean {
  if (!canManageMembers.value) return false
  if (member.user_id === session.user?.id) return false
  if (member.role === 'owner' && !canRevokeOwner.value) return false
  return true
}

/**
 * 角色是什么意思。表头只说「角色」时没人知道改的是哪一层——
 * 界面上必须把这层语义说出来。
 */
const roleScopeLabel = computed(() =>
  selectedTeam.value
    ? `团队角色（${teams.value.find((t) => t.id === selectedTeam.value)?.name ?? ''}）`
    : '组织角色',
)

/** 当前角色管的是哪件事。两层角色各管各的：发布权看组织，审核放行看团队。 */
const roleMeaning = computed(() =>
  selectedTeam.value
    ? '管审核放行权：能不能放行这个团队待审的版本。'
    : '管发布权：能不能发布到组织、能不能建团队与邀请成员。',
)

/**
 * 列表里的成员。没选团队就是组织成员；选了团队就是该团队的人。
 *
 * 角色列显示的就是列表给的那个角色：团队视图给的是团队角色（管审核放行权），
 * 叠上组织角色会让人改错地方——团队角色才是这里能改的那一层。
 *
 * 响应回来时对不上发起时的组织/团队就丢掉：换组织后旧组织那轮慢响应
 * 落地会把新组织的数据盖掉（页面顶上写着组织 B，列表里却是组织 A 的）。
 */
async function loadMembers(orgId: string, teamId: string) {
  const list = teamId
    ? await listTeamMembers(orgId, teamId)
    : await listOrgMembers(orgId)
  if (org.value?.id !== orgId || selectedTeam.value !== teamId) return
  members.value = list
  membersScope.value = `${orgId}/${teamId}`
}

/** 重读成员表。切团队、改角色之后都走这里。 */
async function refreshMembers() {
  const orgId = org.value?.id
  if (!orgId) return
  memberError.value = ''
  // 切范围时旧行必须先走：表格里的下拉还能改角色，而 PATCH 打到的是新范围——
  // 改错了人。响应回来再填新的。
  if (membersScope.value !== `${orgId}/${selectedTeam.value}`) members.value = []
  membersLoading.value = true
  try {
    await loadMembers(orgId, selectedTeam.value)
  } catch (e) {
    if (org.value?.id !== orgId) return
    // 读失败就把上一份名单清掉：那是上一个团队的成员，配上错误提示页面在骗人。
    members.value = []
    memberError.value = errorText(e)
  } finally {
    membersLoading.value = false
  }
}

/**
 * 读组织的三块数据：团队、成员、邀请。
 *
 * 三块并行而不是串行：邀请表在这个环境的远程 PG 上要 3 秒多，串着等的话
 * 成员表早回来了却要陪着一起空着——「列表在转圈」看起来像没数据。
 *
 * 每块自己管自己的 loading 与错误：一块挂了不该把另外两块也拉下水。
 */
async function load() {
  // 记下发起时的组织：响应回来时组织已经变了就丢弃，否则旧组织那轮慢响应
  // 会盖掉新组织的数据（页面顶上写着组织 B，列表里却是组织 A 的）。
  const orgId = org.value?.id
  if (!orgId) {
    membersLoading.value = false
    resetInvitations()
    return
  }
  memberError.value = ''
  inviteError.value = ''
  permissionError.value = ''

  // 权限清单决定哪些按钮显示，先拿到它再渲染操作区。
  // 放在后面的并行块里的话，首屏按钮会先缺席再出现——用户看见的是按钮在跳。
  try {
    await loadPermissions(orgId)
  } catch (e) {
    if (org.value?.id !== orgId) return
    permissionError.value = `权限清单没拉到，操作按钮可能显示不全：${errorText(e)}`
  }

  await Promise.all([
    (async () => {
      try {
        const list = await listTeams(orgId)
        if (org.value?.id !== orgId) return
        teams.value = list
      } catch (e) {
        if (org.value?.id !== orgId) return
        memberError.value = errorText(e)
      }
    })(),
    (async () => {
      membersLoading.value = true
      try {
        await loadMembers(orgId, selectedTeam.value)
      } catch (e) {
        if (org.value?.id !== orgId) return
        members.value = []
        memberError.value = errorText(e)
      } finally {
        membersLoading.value = false
      }
    })(),
  ])

  // 邀请列表单独拉：它在远程库上要好几秒，跟成员表并行会把整页拖住，
  // 让人先看到半张空页面。成员表先落地，邀请后到。
  void loadInvitations(orgId)
}

async function invite() {
  if (!org.value) return
  inviteError.value = ''
  notice.value = ''
  try {
    const created = await createInvitation(org.value.id, {
      team_id: selectedTeam.value || undefined,
      org_role: inviteOrgRole.value,
      // 服务端要求：给了团队角色就必须给团队，否则 400。没选团队时别发。
      team_role: selectedTeam.value ? inviteRole.value : undefined,
      // 绑到具体某个人：链接只对他有效。链接是要转发出去的，
      // 不绑人的话，拿到链接的人（甚至截图里的人）都能以被邀请的角色进来。
      subject: inviteSubject.value || undefined,
    })
    inviteLink.value = created.invite_url
    inviteToken.value = created.token
    notice.value = '邀请已生成，把链接发给对方。'
    await load()
  } catch (e) {
    inviteError.value = errorText(e)
  }
}

async function revoke(id: string) {
  if (!org.value) return
  inviteError.value = ''
  try {
    await revokeInvitation(org.value.id, id)
    await load()
  } catch (e) {
    inviteError.value = errorText(e)
  }
}

/**
 * 改角色。选了团队改团队角色（管审核放行权），没选团队改组织角色（管发布权）——
 * 两层是分开的，改错层就白改。
 */
async function changeRole(member: Member, role: string) {
  if (!org.value || role === member.role) return
  memberError.value = ''
  try {
    if (selectedTeam.value) {
      await updateTeamRole(org.value.id, selectedTeam.value, member.user_id, role)
    } else {
      await updateOrgRole(org.value.id, member.user_id, role)
    }
  } catch (e) {
    memberError.value = errorText(e)
    return
  }
  await refreshMembers()
}

/**
 * 禁用或启用一个成员。
 *
 * 只在组织层有这回事：团队角色管不了账号状态。
 * 禁用的是账号而不是移出花名册——人还在，恢复后不用重新邀请。
 */
async function toggleMemberStatus(member: Member) {
  if (!org.value) return
  memberError.value = ''
  const next = member.status === 'disabled' ? 'active' : 'disabled'
  try {
    await updateMemberStatus(org.value.id, member.user_id, next)
  } catch (e) {
    memberError.value = errorText(e)
    return
  }
  await refreshMembers()
}

/**
 * 建组织。组织只能被邀请进，但自己也能建——建完组织列表要重拉：
 * 顶栏与团队页都从 session.organizations 算出来的（currentOrg 是它的 computed）。
 */
async function createOrg() {
  creating.value = true
  createError.value = ''
  try {
    const slug = newOrgSlug.value.trim()
    await createOrganization(slug, newOrgName.value.trim() || slug)
    await loadMe()
    newOrgSlug.value = ''
    newOrgName.value = ''
  } catch (e) {
    createError.value = errorText(e)
  } finally {
    creating.value = false
  }
}

/** 建团队。建完直接出现在上面的下拉里。 */
async function createTeamNow() {
  if (!org.value) return
  creating.value = true
  createError.value = ''
  try {
    const slug = newTeamSlug.value.trim()
    const team = await createTeam(org.value.id, slug, newTeamName.value.trim() || slug)
    teams.value = [...teams.value, team]
    newTeamSlug.value = ''
    newTeamName.value = ''
    showNewTeam.value = false
    notice.value = `团队 ${team.name} 已创建`
  } catch (e) {
    createError.value = errorText(e)
  } finally {
    creating.value = false
  }
}

// 换组织：团队、成员、邀请都是按组织拉的，得重取一遍。
watch(() => org.value?.id, async () => { selectedTeam.value = ''
  teams.value = []
  members.value = []
  resetInvitations()
  inviteLink.value = ''
  inviteToken.value = ''
  notice.value = ''
  await load()
})

onMounted(load)
</script>

<template>
  <section>
    <div class="sm-title-row">
      <div>
        <h1 class="sm-title">团队</h1>
        <p class="sm-sub">组织是团队与 Skill 的归属地。这里管成员、角色与邀请。</p>
      </div>
    </div>

    <EmptyState
      v-if="!org"
      :text="meError ? '加载用户信息失败，还不知道你有没有组织。' : '你还没有加入任何组织。'"
    />

    <!-- 还没有组织时先建一个：组织只能被邀请进，但自己也能建。 -->
    <div v-if="!org" class="sm-panel">
      <div class="sm-panel-head">
        <h2 class="sm-section">新建组织</h2>
      </div>
      <p class="sm-muted sm-mb-16">组织是团队与 Skill 归属的地方。建好后邀请同事进来。</p>
      <div class="sm-toolbar">
        <input
          v-model="newOrgSlug"
          class="sm-input"
          placeholder="组织 slug（如 my-company）"
          data-test="org-slug"
        />
        <input
          v-model="newOrgName"
          class="sm-input"
          placeholder="组织名称（如 某某科技）"
          data-test="org-name"
        />
        <button
          class="sm-btn sm-btn--primary"
          data-test="create-org"
          :disabled="creating"
          @click="createOrg"
        >
          创建组织
        </button>
      </div>
      <p v-if="createError" class="sm-note sm-note--error sm-mt-12" data-test="create-error">
        {{ createError }}
      </p>
    </div>

    <template v-if="org">
      <div class="sm-panel sm-mb-16">
        <div class="sm-panel-head">
          <h2 class="sm-section">成员</h2>
          <span class="sm-meta">组织 {{ org.name }} · 我的角色 {{ org.role }}</span>
        </div>

        <!--
          范围切换：这个下拉不只换名单，还换掉「角色」两个字的意思。
          所以标题要跟着说清楚现在改的是哪一层，否则改错了地方。
        -->
        <div class="sm-toolbar">
          <span class="sm-muted sm-meta">范围</span>
          <select
            v-model="selectedTeam"
            class="sm-select sm-scope-select"
            data-test="team-select"
            @change="refreshMembers"
          >
            <option value="">整个组织</option>
            <option v-for="team in teams" :key="team.id" :value="team.id">
              {{ team.name }}
            </option>
          </select>
          <span class="sm-spacer" />
          <button
            v-if="canManageTeams"
            class="sm-btn sm-btn--sm"
            data-test="create-team-toggle"
            @click="showNewTeam = !showNewTeam"
          >
            新建团队
          </button>
        </div>

        <p class="sm-muted sm-meta sm-mb-16">
          正在改「{{ roleScopeLabel }}」——{{ roleMeaning }}
        </p>

        <div v-if="showNewTeam" class="sm-toolbar">
          <input
            v-model="newTeamSlug"
            class="sm-input"
            placeholder="团队 slug（如 platform）"
            data-test="team-slug"
          />
          <input
            v-model="newTeamName"
            class="sm-input"
            placeholder="团队名称（如 Platform）"
            data-test="team-name"
          />
          <button
            class="sm-btn sm-btn--primary"
            data-test="create-team"
            :disabled="creating"
            @click="createTeamNow"
          >
            创建团队
          </button>
        </div>

        <p v-if="createError" class="sm-note sm-note--error sm-mb-12" data-test="create-error">
          {{ createError }}
        </p>

        <p v-if="memberError" class="sm-note sm-note--error sm-mb-12" data-test="error">
          {{ memberError }}
        </p>

        <!-- 权限清单没拉到：按钮按「没有权限」显示，说清楚为什么少了东西。 -->
        <p
          v-if="permissionError"
          class="sm-note sm-note--error sm-mb-12"
          data-test="permission-error"
        >
          {{ permissionError }}
        </p>

        <!--
          表格只在有数据时渲染：读失败不是没有成员，
          两个空态并排会把人读歪。
        -->
        <div v-if="members.length" class="sm-table-scroll">
          <table class="sm-table sm-table--flush sm-table--stack">
            <thead>
              <tr>
                <th>成员</th>
                <th>邮箱</th>
                <th>{{ roleScopeLabel }}</th>
                <th v-if="!selectedTeam">账号</th>
                <th class="sm-num">加入时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="member in members" :key="member.user_id">
                <td>
                  <div class="sm-row">
                    <span data-test="member-row">{{ member.display_name }}</span>
                    <span
                      v-if="member.status === 'disabled'"
                      class="sm-pill sm-pill-neg"
                      data-test="member-disabled"
                      >已禁用</span
                    >
                  </div>
                </td>
                <td class="sm-muted sm-cell-sub">{{ member.email ?? '—' }}</td>
                <td>
                  <!-- 没有改角色的权限就只显示角色，不给一个点了必然 403 的下拉。 -->
                  <select
                    v-if="canChangeRole"
                    class="sm-select sm-role-select"
                    :value="member.role"
                    data-test="role-edit"
                    @change="changeRole(member, ($event.target as HTMLSelectElement).value)"
                  >
                    <option v-for="role in roleOptions" :key="role" :value="role">
                      {{ role }}
                    </option>
                  </select>
                  <span v-else class="sm-muted" data-test="role-readonly">{{ member.role }}</span>
                </td>
                <td v-if="!selectedTeam">
                  <button
                    v-if="canToggleStatus(member)"
                    class="sm-btn sm-btn--quiet sm-btn--sm"
                    data-test="member-status-toggle"
                    @click="toggleMemberStatus(member)"
                  >
                    {{ member.status === 'disabled' ? '启用' : '禁用' }}
                  </button>
                </td>
                <td class="sm-num sm-muted sm-cell-sub">
                  加入于 {{ member.joined_at.slice(0, 10) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="membersLoading && !members.length" class="sm-muted sm-meta">正在读成员…</div>
        <EmptyState
          v-if="!membersLoading && !memberError && !members.length"
          text="还没有成员"
        />
      </div>

      <div class="sm-panel">
        <div class="sm-panel-head">
          <h2 class="sm-section">邀请</h2>
          <span class="sm-meta">链接 7 天内有效，只显示一次</span>
        </div>
        <!-- 没邀请权的人不显示这些：邀请是 owner/admin 的事，成员看见只会白点一次。 -->
        <div v-if="canInvite" class="sm-field-grid sm-mb-16">
          <div class="sm-field">
            <label for="invite-org-role">组织角色</label>
            <select id="invite-org-role" v-model="inviteOrgRole" class="sm-select" data-test="org-role-select">
              <option value="member">member</option>
              <option value="publisher">publisher</option>
              <option value="reviewer">reviewer</option>
              <option value="admin">admin</option>
            </select>
          </div>
          <div class="sm-field">
            <label for="invite-team-role">团队角色</label>
            <select
              id="invite-team-role"
              v-model="inviteRole"
              class="sm-select"
              data-test="role-select"
              :disabled="!selectedTeam"
            >
              <option value="member">member</option>
              <option value="publisher">publisher</option>
              <option value="reviewer">reviewer</option>
              <option value="admin">admin</option>
            </select>
          </div>
          <div class="sm-field">
            <label for="invite-subject">绑定到</label>
            <select id="invite-subject" v-model="inviteSubject" class="sm-select" data-test="invite-subject">
              <option value="">任何拿到链接的人</option>
              <option
                v-for="m in members"
                :key="m.user_id"
                :value="m.subject"
              >
                {{ m.display_name }}
              </option>
            </select>
          </div>
        </div>
        <div v-if="canInvite" class="sm-toolbar">
          <button class="sm-btn sm-btn--primary" data-test="invite" @click="invite">
            生成邀请链接
          </button>
        </div>

        <p v-if="inviteLink" class="sm-note sm-note--success sm-mb-12 sm-break" data-test="invite-link">
          {{ inviteLink }}
        </p>
        <p v-if="inviteToken" class="sm-muted sm-mb-12">
          原始 token（链接指向本机时，把这个发给对方）：<code class="sm-mono" data-test="invite-token">{{ inviteToken }}</code>
        </p>
        <p v-if="notice" class="sm-note sm-mb-12">
          {{ notice }}
        </p>
        <p v-if="inviteError" class="sm-note sm-note--error sm-mb-12" data-test="invite-error">
          {{ inviteError }}
        </p>

        <!-- 邀请列表读失败要说出来：读失败不是「没有发出过邀请」。 -->
        <p v-if="invitationsError" class="sm-note sm-note--error sm-mb-12" data-test="invitations-error">
          {{ invitationsError }}
        </p>

        <!-- 邀请区同理：读失败不是「没有发出过邀请」。 -->
        <div v-if="invitations.length" class="sm-table-scroll">
          <table class="sm-table sm-table--flush sm-table--stack">
            <thead>
              <tr>
                <th>团队</th>
                <th>组织角色</th>
                <th>团队角色</th>
                <th>状态</th>
                <th class="sm-num">过期</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in invitations" :key="row.id">
                <td><span data-test="invitation-row">{{ row.team_name ?? '—' }}</span></td>
                <td><span data-test="invitation-org-role">{{ row.org_role }}</span></td>
                <td class="sm-drop">
                  <span>{{ row.team_name ? row.team_role : '—' }}</span>
                </td>
                <td>
                  <span
                    class="sm-pill"
                    :class="row.status === 'pending' ? 'sm-pill-warn' : ''"
                  >
                    {{ row.status === 'pending' ? '待接受' : row.status }}
                  </span>
                </td>
                <td class="sm-num sm-muted sm-cell-sub">
                  过期于 {{ row.expires_at.slice(0, 10) }}
                  <template v-if="row.team_name">
                    · 团队角色
                    <span class="sm-mono" data-test="invitation-team-role">{{ row.team_role }}</span>
                  </template>
                  <template v-else>
                    <span data-test="invitation-team-role" class="sm-hidden">—</span>
                  </template>
                </td>
                <td class="sm-actions">
                  <button
                    v-if="row.status === 'pending' && canInvite"
                    class="sm-btn sm-btn--quiet sm-btn--sm"
                    @click="revoke(row.id)"
                  >
                    撤销
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="invitationsLoading && !invitations.length" class="sm-muted sm-meta">正在读邀请…</div>
        <EmptyState
          v-if="!invitationsLoading && !invitationsError && !invitations.length"
          text="还没有发出过邀请"
        />
        <p v-if="invitations.length" class="sm-muted sm-meta sm-mt-12">
          {{ invitationCountText }}
        </p>

        <!-- 还有下一页：说清还有，不假装这就是全部。 -->
        <div v-if="invitationsHasMore" class="sm-row sm-mt-16">
          <button
            class="sm-btn sm-btn--quiet sm-btn--sm"
            data-test="load-more-invitations"
            :disabled="invitationsLoadingMore"
            @click="loadMoreInvitations"
          >
            {{ invitationsLoadingMore ? '加载中…' : '加载更多' }}
          </button>
        </div>
      </div>
    </template>
  </section>
</template>
