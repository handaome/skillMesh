<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, listAuditEvents } from '../api'
import type { AuditEvent } from '../types'
import { formatDateTime } from '../utils/format'
import EmptyState from '../components/EmptyState.vue'

const events = ref<AuditEvent[]>([])
const error = ref('')
const loading = ref(false)

/**
 * 动作的中文读法。
 *
 * 服务端给的是稳定的标识符（access.denied、version.approve），
 * 界面上要给人看——这里做一层映射，没列到的原样显示，
 * 免得新增动作在页面上变成一片空白。
 */
const ACTION_TEXT: Record<string, string> = {
  'access.denied': '访问被拒',
  'skill.publish': '发布版本',
  'skill.publish_rejected': '发布被拒',
  'skill.publish_quarantined': '发布被隔离',
  'skill.submit_for_review': '提交审核',
  'version.approve': '审核放行',
  'version.reject': '审核拒绝',
  'version.revoke': '撤回版本',
  'artifact.download': '下载制品',
  'installation.register': '登记安装',
  'installation.update': '更新安装记录',
  'installation.refresh': '巡检安装',
  'installation.unregister': '注销安装',
  'sync.commit': '同步提交',
  'sync.commit_conflict': '同步冲突',
  'organization.create': '创建组织',
  'organization.membership.update': '改组织角色',
  'organization.membership.status': '改成员状态',
  'team.create': '创建团队',
  'team.membership.grant': '加入团队',
  'team.membership.update': '改团队角色',
  'team.membership.remove': '移出团队',
  'invitation.create': '发邀请',
  'invitation.accept': '接受邀请',
  'invitation.revoke': '撤销邀请',
  'device.revoke': '撤销设备',
}

function actionText(action: string): string {
  return ACTION_TEXT[action] ?? action
}

/** 结果的读法。denied 是被拒，failure 是失败，success 是成功。 */
function outcomeText(outcome: string): string {
  if (outcome === 'success') return '成功'
  if (outcome === 'denied') return '被拒'
  if (outcome === 'failure') return '失败'
  return outcome
}

/** 目标的可读写法。skill 类事件指向具体 Skill，其余指向请求路径。 */
function targetText(event: AuditEvent): string {
  if (event.target_type === 'skill') return `Skill ${event.target_id ?? '—'}`
  if (event.target_type === 'request') return event.target_id ?? '—'
  if (event.target_type === 'organization') return `组织 ${event.target_id ?? '—'}`
  if (event.target_type === 'team') return `团队 ${event.target_id ?? '—'}`
  return event.target_id ?? '—'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    events.value = await listAuditEvents()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <div class="sm-title-row">
      <div>
        <h1 class="sm-title">审计流水</h1>
        <p class="sm-sub">你可见的 Skill 上发生过的动作：发布、审批、下载、安装、同步与权限变更。</p>
      </div>
      <span class="sm-muted sm-sm sm-num">{{ events.length }} 条</span>
    </div>

    <p v-if="error" class="sm-note sm-note--error sm-mb-16" data-test="error">
      {{ error }}
    </p>

    <div class="sm-panel">
      <div class="sm-panel-head">
        <h2 class="sm-section">最近动作</h2>
        <span class="sm-meta">按时间倒序</span>
      </div>

      <div v-if="loading" class="sm-empty">加载中…</div>

      <EmptyState
        v-else-if="!error && !events.length"
        text="还没有审计记录。发布、审核、安装这些动作发生后会出现在这里。"
      />

      <div v-else class="sm-list">
        <div
          v-for="(event, index) in events"
          :key="`${event.occurred_at}-${index}`"
          class="sm-item"
          data-test="audit-item"
        >
          <div class="sm-item-body">
            <div class="sm-item-head">
              <strong class="sm-item-title">{{ actionText(event.action) }}</strong>
              <span
                class="sm-pill"
                :class="event.outcome === 'success'
                  ? 'sm-pill-pos'
                  : event.outcome === 'denied' ? 'sm-pill-neg' : ''"
              >
                {{ outcomeText(event.outcome) }}
              </span>
            </div>
            <p class="sm-muted sm-meta">
              {{ targetText(event) }}
              · {{ formatDateTime(event.occurred_at) }}
            </p>
            <p v-if="event.action === 'access.denied'" class="sm-muted sm-meta sm-mono">
              {{ event.details?.method }} {{ event.details?.status }}
            </p>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
