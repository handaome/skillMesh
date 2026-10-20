<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  ApiError,
  createInvitation,
  listInvitations,
  listOrgMembers,
  listTeamMembers,
  listTeams,
  revokeInvitation,
  updateOrgRole,
  updateTeamRole,
} from '../api'
import { currentOrg } from '../auth/currentOrg'
import { meError } from '../auth/session'
import type { Invitation, Member, Team } from '../types'

const org = currentOrg
const teams = ref<Team[]>([])
const members = ref<Member[]>([])
/** 成员表正在读。切范围时旧行已经清空，这时更不能显示「还没有成员」。 */
const membersLoading = ref(false)
/** 当前表格里的行属于哪个范围（组织/团队）。切范围时旧行得先清掉。 */
const membersScope = ref('')
const invitations = ref<Invitation[]>([])
const loading = ref(false)
/** 成员区的错（改角色、读成员、读团队） */
const memberError = ref('')
/** 邀请区的错（生成、撤销、读邀请） */
const inviteError = ref('')
const notice = ref('')
const inviteLink = ref('')
const inviteToken = ref('')
const selectedTeam = ref('')
const inviteRole = ref('member')
const inviteOrgRole = ref('member')

/** 组织角色有 owner 一档，团队没有——服务端 400 挡着，别放进下拉。 */
const ORG_ROLE_OPTIONS = ['owner', 'admin', 'publisher', 'reviewer', 'member', 'viewer']
const TEAM_ROLE_OPTIONS = ['admin', 'publisher', 'reviewer', 'member', 'viewer']

/** 角色下拉的选项：选了团队就是团队角色，没选就是组织角色。 */
const roleOptions = computed(() => (selectedTeam.value ? TEAM_ROLE_OPTIONS : ORG_ROLE_OPTIONS))

function errorText(e: unknown): string {
  return e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
}

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

async function load() {
  // 记下发起时的组织：响应回来时组织已经变了就丢弃，否则旧组织那轮慢响应
  // 会盖掉新组织的数据（页面顶上写着组织 B，列表里却是组织 A 的）。
  const orgId = org.value?.id
  if (!orgId) {
    loading.value = false
    return
  }
  loading.value = true
  memberError.value = ''
  inviteError.value = ''
  try {
    const list = await listTeams(orgId)
    if (org.value?.id !== orgId) return
    teams.value = list
    await loadMembers(orgId, selectedTeam.value)
  } catch (e) {
    if (org.value?.id !== orgId) return
    memberError.value = errorText(e)
  }
  try {
    const list = await listInvitations(orgId)
    if (org.value?.id !== orgId) return
    invitations.value = list
  } catch (e) {
    if (org.value?.id !== orgId) return
    inviteError.value = errorText(e)
  }
  if (org.value?.id === orgId) loading.value = false
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

// 换组织：团队、成员、邀请都是按组织拉的，得重取一遍。
watch(() => org.value?.id, async () => {
  selectedTeam.value = ''
  teams.value = []
  members.value = []
  invitations.value = []
  inviteLink.value = ''
  inviteToken.value = ''
  notice.value = ''
  await load()
})

onMounted(load)
</script>

<template>
  <section>
    <h1 class="sm-h1">团队</h1>

    <el-empty
      v-if="!org"
      :description="meError ? '加载用户信息失败，还不知道你有没有组织。' : '你还没有加入任何组织。'"
    />

    <template v-else>
      <p class="sm-muted">
        组织 {{ org.name }} · 我的角色 {{ org.role }}
      </p>

      <el-card shadow="never" class="sm-mb-16">
        <h2 class="sm-h2">团队</h2>
        <div class="sm-search">
          <el-select
            v-model="selectedTeam"
            placeholder="（不指定团队）"
            data-test="team-select"
            class="sm-grow"
            @change="refreshMembers"
          >
            <el-option label="（不指定团队）" value="" />
            <el-option v-for="team in teams" :key="team.id" :label="team.name" :value="team.id" />
          </el-select>
        </div>

        <el-alert
          v-if="memberError"
          data-test="error"
          :title="memberError"
          type="error"
          :closable="false"
          show-icon
          class="sm-mb-12"
        />

        <!--
          表格只在有数据时渲染：Element Plus 自带的「No Data」也是一句谎话
          （读失败不是没有成员）。改角色失败时列表仍然有效，表格照常显示。
        -->
        <el-table v-if="!loading && members.length > 0" :data="members" size="small">
          <el-table-column label="成员" min-width="140">
            <template #default="scope">
              <span data-test="member-row">{{ scope.row.display_name }}</span>
            </template>
          </el-table-column>
          <el-table-column label="邮箱" min-width="160">
            <template #default="scope">{{ scope.row.email ?? '—' }}</template>
          </el-table-column>
          <el-table-column :label="selectedTeam ? '团队角色' : '组织角色'" width="170">
            <template #default="scope">
              <el-select
                :model-value="scope.row.role"
                size="small"
                data-test="role-edit"
                @change="(value: string) => changeRole(scope.row, value)"
              >
                <el-option v-for="role in roleOptions" :key="role" :label="role" :value="role" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="加入时间" width="120">
            <template #default="scope">
              <span class="sm-text-sm">{{ scope.row.joined_at.slice(0, 10) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty
          v-if="!loading && !membersLoading && !memberError && !members.length"
          description="还没有成员"
          :image-size="60"
        />
      </el-card>

      <el-card shadow="never" class="sm-mb-16">
        <h2 class="sm-h2">邀请</h2>
        <div class="sm-search">
          <el-select v-model="inviteOrgRole" data-test="org-role-select" class="sm-grow">
            <el-option label="组织角色 member" value="member" />
            <el-option label="组织角色 publisher" value="publisher" />
            <el-option label="组织角色 reviewer" value="reviewer" />
            <el-option label="组织角色 admin" value="admin" />
          </el-select>
          <el-select v-model="inviteRole" data-test="role-select" class="sm-grow" :disabled="!selectedTeam">
            <el-option label="团队角色 member" value="member" />
            <el-option label="团队角色 publisher" value="publisher" />
            <el-option label="团队角色 reviewer" value="reviewer" />
            <el-option label="团队角色 admin" value="admin" />
          </el-select>
          <el-button type="primary" data-test="invite" @click="invite">生成邀请链接</el-button>
        </div>

        <el-alert
          v-if="inviteLink"
          data-test="invite-link"
          :title="inviteLink"
          type="success"
          :closable="false"
          show-icon
          class="sm-mb-12"
        />
        <p v-if="inviteToken" class="sm-muted sm-mb-12">
          原始 token（链接指向本机时，把这个发给对方）：<code class="sm-mono" data-test="invite-token">{{ inviteToken }}</code>
        </p>
        <el-alert
          v-if="notice"
          :title="notice"
          type="info"
          :closable="false"
          class="sm-mb-12"
        />
        <el-alert
          v-if="inviteError"
          data-test="invite-error"
          :title="inviteError"
          type="error"
          :closable="false"
          show-icon
          class="sm-mb-12"
        />

        <!-- 邀请区同理：读失败不是「没有发出过邀请」。 -->
        <el-table v-if="!loading && invitations.length > 0" :data="invitations" size="small">
          <el-table-column label="团队" min-width="120">
            <template #default="scope">
              <span data-test="invitation-row">{{ scope.row.team_name ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="组织角色" width="110">
            <template #default="scope">
              <span data-test="invitation-org-role">{{ scope.row.org_role }}</span>
            </template>
          </el-table-column>
          <el-table-column label="团队角色" width="110">
            <template #default="scope">
              <span data-test="invitation-team-role">{{ scope.row.team_name ? scope.row.team_role : '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="scope">
              <el-tag
                size="small"
                :type="scope.row.status === 'pending' ? 'warning' : 'info'"
              >{{ scope.row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="过期" width="120">
            <template #default="scope">
              <span class="sm-text-sm">{{ scope.row.expires_at.slice(0, 10) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="" width="90">
            <template #default="scope">
              <el-button
                v-if="scope.row.status === 'pending'"
                text
                type="danger"
                @click="revoke(scope.row.id)"
              >撤销</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty
          v-if="!loading && !inviteError && !invitations.length"
          description="还没有发出过邀请"
          :image-size="60"
        />
      </el-card>
    </template>
  </section>
</template>
