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
import type { Invitation, Member, Team } from '../types'

const org = currentOrg
const teams = ref<Team[]>([])
const members = ref<Member[]>([])
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
 */
async function loadMembers() {
  if (!org.value) return
  if (!selectedTeam.value) {
    members.value = await listOrgMembers(org.value.id)
    return
  }
  members.value = await listTeamMembers(org.value.id, selectedTeam.value)
}

/** 重读成员表。切团队、改角色之后都走这里。 */
async function refreshMembers() {
  memberError.value = ''
  try {
    await loadMembers()
  } catch (e) {
    memberError.value = errorText(e)
  }
}

async function load() {
  if (!org.value) return
  loading.value = true
  memberError.value = ''
  inviteError.value = ''
  try {
    teams.value = await listTeams(org.value.id)
    await loadMembers()
  } catch (e) {
    memberError.value = errorText(e)
  }
  try {
    invitations.value = await listInvitations(org.value.id)
  } catch (e) {
    inviteError.value = errorText(e)
  }
  loading.value = false
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

    <el-empty v-if="!org" description="你还没有加入任何组织。" />

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

        <el-table v-if="!loading" :data="members" size="small">
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
        <el-empty v-if="!loading && !members.length" description="还没有成员" :image-size="60" />
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

        <el-table v-if="!loading" :data="invitations" size="small">
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
        <el-empty v-if="!loading && !invitations.length" description="还没有发出过邀请" :image-size="60" />
      </el-card>
    </template>
  </section>
</template>
