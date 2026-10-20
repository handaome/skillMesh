<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ApiError,
  createInvitation,
  listInvitations,
  listOrgMembers,
  listTeamMembers,
  listTeams,
  revokeInvitation,
  updateOrgRole,
} from '../api'
import { session } from '../auth/session'
import type { Invitation, Member, Team } from '../types'

const org = computed(() => session.organizations[0] ?? null)
const teams = ref<Team[]>([])
const members = ref<Member[]>([])
const invitations = ref<Invitation[]>([])
const error = ref('')
const notice = ref('')
const inviteLink = ref('')
const selectedTeam = ref('')
const inviteRole = ref('member')
const inviteOrgRole = ref('member')

function errorText(e: unknown): string {
  return e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
}

/**
 * 列表里的成员。没选团队就是组织成员；选了团队就是该团队的人。
 * 角色列改的始终是组织角色（服务端只提供改组织角色这一个入口），
 * 所以团队视图里也显示组织角色，免得改完看着没生效。
 */
async function loadMembers() {
  if (!org.value) return
  if (!selectedTeam.value) {
    members.value = await listOrgMembers(org.value.id)
    return
  }
  const [teamMembers, orgMembers] = await Promise.all([
    listTeamMembers(org.value.id, selectedTeam.value),
    listOrgMembers(org.value.id),
  ])
  const roles = new Map(orgMembers.map((m) => [m.user_id, m.role]))
  members.value = teamMembers.map((m) => ({ ...m, role: roles.get(m.user_id) ?? m.role }))
}

async function load() {
  if (!org.value) return
  error.value = ''
  try {
    teams.value = await listTeams(org.value.id)
    await loadMembers()
    invitations.value = await listInvitations(org.value.id)
  } catch (e) {
    error.value = errorText(e)
  }
}

/** 重读成员表。切团队、改角色之后都走这里。 */
async function refreshMembers() {
  error.value = ''
  try {
    await loadMembers()
  } catch (e) {
    error.value = errorText(e)
  }
}

async function invite() {
  if (!org.value) return
  error.value = ''
  notice.value = ''
  try {
    const created = await createInvitation(org.value.id, {
      team_id: selectedTeam.value || undefined,
      org_role: inviteOrgRole.value,
      // 服务端要求：给了团队角色就必须给团队，否则 400。没选团队时别发。
      team_role: selectedTeam.value ? inviteRole.value : undefined,
    })
    inviteLink.value = created.invite_url
    notice.value = '邀请已生成，把链接发给对方。'
    await load()
  } catch (e) {
    error.value = errorText(e)
  }
}

async function revoke(id: string) {
  if (!org.value) return
  error.value = ''
  try {
    await revokeInvitation(org.value.id, id)
    await load()
  } catch (e) {
    error.value = errorText(e)
  }
}

/** 改组织角色。被邀请进来的人默认是 member，要往团队发布得有 publisher 以上。 */
async function changeRole(member: Member, role: string) {
  if (!org.value || role === member.role) return
  error.value = ''
  try {
    await updateOrgRole(org.value.id, member.user_id, role)
    await refreshMembers()
  } catch (e) {
    error.value = errorText(e)
  }
}

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

        <el-table :data="members" size="small">
          <el-table-column label="成员" min-width="140">
            <template #default="scope">
              <span data-test="member-row">{{ scope.row.display_name }}</span>
            </template>
          </el-table-column>
          <el-table-column label="邮箱" min-width="160">
            <template #default="scope">{{ scope.row.email ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="角色" width="170">
            <template #default="scope">
              <el-select
                :model-value="scope.row.role"
                size="small"
                data-test="role-edit"
                @change="(value: string) => changeRole(scope.row, value)"
              >
                <el-option label="owner" value="owner" />
                <el-option label="admin" value="admin" />
                <el-option label="publisher" value="publisher" />
                <el-option label="reviewer" value="reviewer" />
                <el-option label="member" value="member" />
                <el-option label="viewer" value="viewer" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="加入时间" width="120">
            <template #default="scope">
              <span class="sm-text-sm">{{ scope.row.joined_at.slice(0, 10) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!members.length" description="还没有成员" :image-size="60" />
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
        <el-alert
          v-if="notice"
          :title="notice"
          type="info"
          :closable="false"
          class="sm-mb-12"
        />

        <el-table :data="invitations" size="small">
          <el-table-column label="团队" min-width="120">
            <template #default="scope">
              <span data-test="invitation-row">{{ scope.row.team_name ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="team_role" label="角色" width="110" />
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
        <el-empty v-if="!invitations.length" description="还没有发出过邀请" :image-size="60" />
      </el-card>

      <el-alert
        v-if="error"
        data-test="error"
        :title="error"
        type="error"
        :closable="false"
        show-icon
      />
    </template>
  </section>
</template>
