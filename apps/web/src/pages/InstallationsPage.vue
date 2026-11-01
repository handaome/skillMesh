<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  listInstallations,
  registerDevice,
  registerInstallation,
  revokeDevice,
  searchSkills,
  unregisterInstallation,
  updateInstallation,
} from '../api'
import {
  INSTALL_SCOPE_TEXT,
  INSTALLATION_STATUS_TEXT,
  UPDATE_POLICY_TEXT,
} from '../constants/status'
import type { Device, Installation, InstallationPatch, Skill } from '../types'
import { errorText } from '../utils/errorText'
import { formatDateTime } from '../utils/format'
import EmptyState from '../components/EmptyState.vue'

/** 取值与服务端一致：多给一个服务端会 400。 */
const STATUS_OPTIONS = ['active', 'missing', 'conflict', 'uninstalled', 'blocked'] as const
const POLICY_OPTIONS = ['manual', 'notify', 'auto_safe'] as const
const SCOPE_OPTIONS = ['user', 'project', 'custom'] as const

/** 设备行。服务端只给设备本身的几件事，这里把两处来源合成一行。 */
interface DeviceRow {
  id: string
  /** 设备名。安装记录里引用到的设备拿不到名字，这里是空的。 */
  name: string
  device_key: string
  platform: string
  cli_version: string
  last_seen_at: string
  revoked: boolean
}

const installations = ref<Installation[]>([])
/** 本页登记过的设备。服务端没有「列出我的设备」端点，只能这样攒着。 */
const registered = ref<Device[]>([])
/** 撤销过的设备 id：设备表列不出来，撤销了得自己记着。 */
const revokedIds = ref<string[]>([])
/** 可见的 Skill：给安装记录配名字，也给登记表单选 Skill。 */
const skills = ref<Skill[]>([])

const loading = ref(false)
const error = ref('')
const notice = ref('')
const deviceError = ref('')
const installError = ref('')
const busy = ref(false)
const statusFilter = ref('')

const deviceForm = reactive({
  device_key: '',
  display_name: '',
  platform: '',
  cli_version: '',
})

const installForm = reactive({
  skill_id: '',
  device_id: '',
  agent: '',
  install_scope: 'user' as Installation['install_scope'],
  target_path: '',
  installed_version: '',
})

const skillNames = computed(() => {
  const map: Record<string, string> = {}
  for (const skill of skills.value) map[skill.id] = skill.name || skill.slug
  return map
})

/**
 * 设备行：两处来源合成一处。
 *
 * 服务端只有 POST /devices 与 DELETE /devices/{id}，没有列表端点。
 * 能看到的设备只有两处来源——本页登记过的，和安装记录里引用到的
 * （只有 id：名字、平台、最近使用时间都拿不到）。
 */
const deviceRows = computed<DeviceRow[]>(() => {
  const rows = new Map<string, DeviceRow>()
  for (const device of registered.value) {
    rows.set(device.id, {
      id: device.id,
      name: device.display_name || device.device_key,
      device_key: device.device_key,
      platform: device.platform,
      cli_version: device.cli_version,
      last_seen_at: device.last_seen_at,
      revoked: device.revoked || revokedIds.value.includes(device.id),
    })
  }
  for (const inst of installations.value) {
    if (!inst.device_id) continue
    const existing = rows.get(inst.device_id)
    if (existing) {
      if (inst.last_checked_at > existing.last_seen_at) {
        existing.last_seen_at = inst.last_checked_at
      }
      continue
    }
    rows.set(inst.device_id, {
      id: inst.device_id,
      name: '',
      device_key: '',
      platform: '',
      cli_version: '',
      // 设备的「最近使用」拿不到：安装记录里的巡检时间是这台机器上最接近的信号。
      last_seen_at: inst.last_checked_at,
      revoked: revokedIds.value.includes(inst.device_id),
    })
  }
  return [...rows.values()]
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    installations.value = await listInstallations(statusFilter.value || undefined)
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

/** Skill 名字只是锦上添花：拉不到就显示 id，不影响别的。 */
async function loadSkills() {
  try {
    skills.value = (await searchSkills('')).items
  } catch {
    skills.value = []
  }
}

function skillName(id: string): string {
  return skillNames.value[id] || '未知 Skill'
}

function statusPill(status: Installation['status']): string {
  if (status === 'active') return 'sm-pill-pos'
  if (status === 'missing') return 'sm-pill-warn'
  if (status === 'conflict' || status === 'blocked') return 'sm-pill-neg'
  return ''
}

/** 改一条安装记录。改完就地换掉那一行，不用重拉整页。 */
async function patch(inst: Installation, body: InstallationPatch) {
  error.value = ''
  try {
    const updated = await updateInstallation(inst.id, body)
    const index = installations.value.findIndex((item) => item.id === inst.id)
    if (index >= 0) installations.value[index] = updated
  } catch (e) {
    error.value = errorText(e)
    // 改失败就把下拉框拨回服务端的真值：留着用户刚选的那个值是假的。
    await load()
  }
}

function onPolicyChange(inst: Installation, event: Event) {
  const value = (event.target as HTMLSelectElement).value
  void patch(inst, { update_policy: value as Installation['update_policy'] })
}

function onStatusChange(inst: Installation, event: Event) {
  const value = (event.target as HTMLSelectElement).value
  void patch(inst, { status: value as Installation['status'] })
}

function onScopeChange(event: Event) {
  installForm.install_scope = (event.target as HTMLSelectElement)
    .value as Installation['install_scope']
}

/**
 * 注销一条安装登记。
 *
 * 不可逆：删了这条记录，云端就不再记得这台机器上装过它。
 * 本机的文件不动——删文件是本机的事，要在机器上卸载。
 */
async function unregister(inst: Installation) {
  const ok = window.confirm(
    `注销「${skillNames.value[inst.skill_id] || inst.skill_id}」这条安装登记？\n` +
      '注销不可逆：云端不再记得这台机器上装过它，要回来得重新登记。\n' +
      '本机的文件不会被删——删文件要在本机上卸载。',
  )
  if (!ok) return
  error.value = ''
  notice.value = ''
  try {
    await unregisterInstallation(inst.id)
    installations.value = installations.value.filter((item) => item.id !== inst.id)
    notice.value = '已注销这条安装登记。'
  } catch (e) {
    error.value = errorText(e)
  }
}

/**
 * 撤销一台设备。
 *
 * 撤销后它不能再向服务端登记或同步（CLI 那边要重新登录）。
 * 已经登记的安装实例留着——那是历史事实，不因为设备没了就消失。
 */
async function revoke(row: DeviceRow) {
  const ok = window.confirm(
    `撤销设备「${row.name || row.id}」？\n` +
      '撤销不可逆：这台设备不能再向服务端登记或同步，CLI 那边要重新登录。\n' +
      '已经登记的安装实例仍留着。',
  )
  if (!ok) return
  error.value = ''
  notice.value = ''
  try {
    await revokeDevice(row.id)
    if (!revokedIds.value.includes(row.id)) {
      revokedIds.value = [...revokedIds.value, row.id]
    }
    notice.value = `已撤销设备「${row.name || row.id}」。`
  } catch (e) {
    error.value = errorText(e)
  }
}

/** 注册一台设备。同一个 device_key 重复注册是同一台，不新建设备。 */
async function submitDevice() {
  deviceError.value = ''
  const key = deviceForm.device_key.trim()
  if (!key) {
    deviceError.value = '设备标识不能为空'
    return
  }
  busy.value = true
  try {
    const device = await registerDevice({
      device_key: key,
      display_name: deviceForm.display_name.trim() || undefined,
      platform: deviceForm.platform.trim() || undefined,
      cli_version: deviceForm.cli_version.trim() || undefined,
    })
    const index = registered.value.findIndex((item) => item.id === device.id)
    if (index >= 0) registered.value[index] = device
    else registered.value = [...registered.value, device]
    deviceForm.display_name = ''
    notice.value = `已登记设备「${device.display_name || device.device_key}」。`
  } catch (e) {
    deviceError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

/** 手工登记一条安装：装好了却没登记上时用。 */
async function submitInstallation() {
  installError.value = ''
  if (!installForm.skill_id) {
    installError.value = '选一个 Skill'
    return
  }
  if (!installForm.device_id) {
    installError.value = '选一台设备'
    return
  }
  if (!installForm.agent.trim()) {
    installError.value = 'Agent 不能为空'
    return
  }
  if (!installForm.target_path.trim()) {
    installError.value = '安装路径不能为空'
    return
  }
  busy.value = true
  try {
    const inst = await registerInstallation({
      skill_id: installForm.skill_id,
      device_id: installForm.device_id,
      agent: installForm.agent.trim(),
      install_scope: installForm.install_scope,
      target_path: installForm.target_path.trim(),
      installed_version: installForm.installed_version.trim() || undefined,
    })
    installations.value = [inst, ...installations.value]
    installForm.skill_id = ''
    installForm.target_path = ''
    installForm.installed_version = ''
    notice.value = '已登记这条安装。'
  } catch (e) {
    installError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void load()
  void loadSkills()
})
</script>

<template>
  <section>
    <div class="sm-title-row">
      <div>
        <h1 class="sm-title">安装登记与设备</h1>
        <p class="sm-sub">
          我在哪些机器上装了什么、装的是哪一版。装与卸由 CLI 在本机完成，
          这里管的是登记：改更新策略、注销记录、撤销设备。
        </p>
      </div>
    </div>

    <p v-if="error" class="sm-note sm-note--error sm-mb-16" data-test="error">
      {{ error }}
    </p>

    <p v-if="notice" class="sm-note sm-note--success sm-mb-16" data-test="notice">
      {{ notice }}
    </p>

    <!-- 安装实例 -->
    <div class="sm-panel sm-mb-16">
      <div class="sm-panel-head">
        <h2 class="sm-section">安装实例</h2>
        <select
          v-model="statusFilter"
          class="sm-select sm-scope-select"
          aria-label="按状态筛选安装实例"
          data-test="status-filter"
          @change="load"
        >
          <option value="">全部状态</option>
          <option v-for="status in STATUS_OPTIONS" :key="status" :value="status">
            {{ INSTALLATION_STATUS_TEXT[status] }}
          </option>
        </select>
      </div>

      <div v-if="loading" class="sm-empty">加载中…</div>

      <EmptyState
        v-else-if="!error && !installations.length"
        text="还没有安装记录。用 skillmesh install 装一个，或在下面手工登记。"
      />

      <div v-else class="sm-list">
        <div
          v-for="inst in installations"
          :key="inst.id"
          class="sm-item sm-item-static"
          data-test="installation-row"
        >
          <div class="sm-item-body">
            <div class="sm-item-head">
              <strong class="sm-item-title">{{ skillName(inst.skill_id) }}</strong>
              <code class="sm-mono sm-muted sm-meta">{{ inst.skill_id }}</code>
              <span class="sm-pill" :class="statusPill(inst.status)">
                {{ INSTALLATION_STATUS_TEXT[inst.status] || inst.status }}
              </span>
            </div>
            <p class="sm-muted sm-meta sm-break">
              {{ inst.agent }} · {{ INSTALL_SCOPE_TEXT[inst.install_scope] || inst.install_scope }}
              · <code class="sm-mono">{{ inst.target_path }}</code>
            </p>
            <p class="sm-muted sm-meta">
              版本 {{ inst.installed_version || '—' }}
              <template v-if="inst.local_modified"> · 本地改过</template>
              · 最近巡检 {{ formatDateTime(inst.last_checked_at) }}
            </p>
          </div>

          <div class="sm-row">
            <select
              class="sm-select sm-mini-select"
              :value="inst.update_policy"
              data-test="policy-select"
              aria-label="更新策略"
              @change="onPolicyChange(inst, $event)"
            >
              <option v-for="policy in POLICY_OPTIONS" :key="policy" :value="policy">
                {{ UPDATE_POLICY_TEXT[policy] }}
              </option>
            </select>
            <select
              class="sm-select sm-mini-select"
              :value="inst.status"
              data-test="status-select"
              aria-label="安装状态"
              @change="onStatusChange(inst, $event)"
            >
              <option v-for="status in STATUS_OPTIONS" :key="status" :value="status">
                {{ INSTALLATION_STATUS_TEXT[status] }}
              </option>
            </select>
            <button
              class="sm-btn sm-btn--quiet sm-btn--sm sm-danger-text"
              data-test="unregister"
              @click="unregister(inst)"
            >
              注销
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 设备 -->
    <div class="sm-panel sm-mb-16">
      <div class="sm-panel-head">
        <h2 class="sm-section">设备</h2>
        <span class="sm-meta">登记过的 CLI 实例</span>
      </div>

      <p class="sm-note sm-mb-16">
        服务端还没有「列出我的设备」的接口：这里只显示本页登记过的设备，
        以及安装记录里引用到的设备（只有 id，名字与最近使用时间拿不到）。
      </p>

      <div v-if="!deviceRows.length" class="sm-empty">还没有设备。</div>

      <div v-else class="sm-list">
        <div
          v-for="row in deviceRows"
          :key="row.id"
          class="sm-item sm-item-static"
          data-test="device-row"
        >
          <div class="sm-item-body">
            <div class="sm-item-head">
              <strong class="sm-item-title">{{ row.name || '未命名设备' }}</strong>
              <code class="sm-mono sm-muted sm-meta">{{ row.id }}</code>
              <span v-if="row.revoked" class="sm-pill sm-pill-neg">已撤销</span>
            </div>
            <p class="sm-muted sm-meta sm-break">
              <template v-if="row.device_key"><code class="sm-mono">{{ row.device_key }}</code> · </template>
              <template v-if="row.platform">{{ row.platform }} · </template>
              <template v-if="row.cli_version">CLI {{ row.cli_version }} · </template>
              最近活动 {{ formatDateTime(row.last_seen_at) }}
            </p>
          </div>
          <button
            class="sm-btn sm-btn--quiet sm-btn--sm sm-danger-text"
            data-test="revoke-device"
            :disabled="row.revoked"
            @click="revoke(row)"
          >
            撤销
          </button>
        </div>
      </div>

      <!-- 注册设备 -->
      <div class="sm-field-grid sm-mt-16">
        <div class="sm-field">
          <label for="device-key">设备标识</label>
          <input
            id="device-key"
            v-model="deviceForm.device_key"
            class="sm-input sm-input--mono"
            placeholder="work-laptop"
            data-test="device-key"
          />
          <span class="sm-hint">同一个标识重复登记是同一台设备</span>
        </div>
        <div class="sm-field">
          <label for="device-name">设备名</label>
          <input
            id="device-name"
            v-model="deviceForm.display_name"
            class="sm-input"
            placeholder="公司笔记本"
            data-test="device-name"
          />
          <span class="sm-hint">名字只在第一次登记时定下</span>
        </div>
        <div class="sm-field">
          <label for="device-platform">平台</label>
          <input
            id="device-platform"
            v-model="deviceForm.platform"
            class="sm-input"
            placeholder="darwin"
            data-test="device-platform"
          />
        </div>
        <div class="sm-field">
          <label for="device-cli-version">CLI 版本</label>
          <input
            id="device-cli-version"
            v-model="deviceForm.cli_version"
            class="sm-input"
            placeholder="0.1.0"
            data-test="device-cli-version"
          />
        </div>
      </div>

      <p v-if="deviceError" class="sm-note sm-note--error sm-mb-12" data-test="device-error">
        {{ deviceError }}
      </p>

      <div class="sm-toolbar sm-mt-16">
        <button
          class="sm-btn sm-btn--primary"
          data-test="register-device"
          :disabled="busy"
          @click="submitDevice"
        >
          注册设备
        </button>
      </div>
    </div>

    <!-- 登记安装 -->
    <div class="sm-panel">
      <div class="sm-panel-head">
        <h2 class="sm-section">登记安装</h2>
        <span class="sm-meta">装好了却没登记上时，补一条</span>
      </div>

      <div class="sm-field-grid sm-mb-16">
        <div class="sm-field">
          <label for="install-skill">Skill</label>
          <select
            id="install-skill"
            v-model="installForm.skill_id"
            class="sm-select"
            data-test="install-skill"
          >
            <option value="">选一个 Skill</option>
            <option v-for="skill in skills" :key="skill.id" :value="skill.id">
              {{ skill.name || skill.slug }}
            </option>
          </select>
        </div>
        <div class="sm-field">
          <label for="install-device">设备</label>
          <select
            id="install-device"
            v-model="installForm.device_id"
            class="sm-select"
            data-test="install-device"
          >
            <option value="">选一台设备</option>
            <option v-for="row in deviceRows" :key="row.id" :value="row.id">
              {{ row.name || row.id }}
            </option>
          </select>
        </div>
        <div class="sm-field">
          <label for="install-agent">Agent</label>
          <input
            id="install-agent"
            v-model="installForm.agent"
            class="sm-input"
            placeholder="claude-code"
            data-test="install-agent"
          />
        </div>
        <div class="sm-field">
          <label for="install-scope">作用域</label>
          <select
            id="install-scope"
            class="sm-select"
            :value="installForm.install_scope"
            data-test="install-scope"
            @change="onScopeChange"
          >
            <option v-for="scope in SCOPE_OPTIONS" :key="scope" :value="scope">
              {{ INSTALL_SCOPE_TEXT[scope] }}
            </option>
          </select>
        </div>
        <div class="sm-field">
          <label for="install-path">安装路径</label>
          <input
            id="install-path"
            v-model="installForm.target_path"
            class="sm-input sm-input--mono"
            placeholder="~/.claude/skills/demo"
            data-test="install-path"
          />
        </div>
        <div class="sm-field">
          <label for="install-version">版本</label>
          <input
            id="install-version"
            v-model="installForm.installed_version"
            class="sm-input"
            placeholder="1.2.0"
            data-test="install-version"
          />
        </div>
      </div>

      <p v-if="installError" class="sm-note sm-note--error sm-mb-12" data-test="install-error">
        {{ installError }}
      </p>

      <div class="sm-toolbar">
        <button
          class="sm-btn sm-btn--primary"
          data-test="register-installation"
          :disabled="busy"
          @click="submitInstallation"
        >
          登记安装
        </button>
      </div>
    </div>
  </section>
</template>
