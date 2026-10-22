<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  ApiError,
  createSkill,
  getVersions,
  revokeVersion,
  searchMySkills,
  updateSkill,
} from '../api'
import type { Skill, SkillVersion } from '../types'
import { formatDateTime } from '../utils/format'
import EmptyState from '../components/EmptyState.vue'
import FileBrowser from '../components/FileBrowser.vue'
import ModalDialog from '../components/ModalDialog.vue'
import TagInput from '../components/TagInput.vue'

/** 服务端收 slug 的规则：小写字母数字开头，后跟小写字母数字或连字符，总长 2–64。 */
const SLUG_PATTERN = /^[a-z0-9][a-z0-9-]{1,63}$/

const query = ref('')
const skills = ref<Skill[]>([])
const loading = ref(false)
const listError = ref('')

/** 选中的 Skill。右侧详情面板显示的就是它。 */
const selected = ref<Skill | null>(null)
const versions = ref<SkillVersion[]>([])
const versionsLoading = ref(false)
const versionsError = ref('')
const actionError = ref('')
const notice = ref('')

/** 正在浏览哪个版本的文件。空串表示没展开。 */
const browseVersion = ref('')

/** 编辑表单。改的是展示性元数据：slug 与可见性服务端不收。 */
const editName = ref('')
const editDescription = ref('')
const editTags = ref<string[]>([])
const saving = ref(false)

/** 新建表单 */
const creating = ref(false)
const createError = ref('')
const newSlug = ref('')
const newName = ref('')
const newDescription = ref('')
const newVisibility = ref<'private' | 'public'>('private')
const newTags = ref<string[]>([])

const dirty = computed(
  () =>
    selected.value !== null &&
    (editName.value !== selected.value.name ||
      editDescription.value !== selected.value.description ||
      editTags.value.join(',') !== selected.value.tags.join(',')),
)

function errorText(e: unknown): string {
  return e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
}

/** 可见性 → 药丸的颜色。private/public 是个人空间的常态，其余颜色区分。 */
function visibilityPill(visibility: string): string {
  if (visibility === 'public') return 'sm-pill sm-pill-data'
  if (visibility === 'organization' || visibility === 'team') return 'sm-pill sm-pill-warn'
  return 'sm-pill'
}

/** 版本状态 → 药丸的颜色。 */
function versionPill(status: string): string {
  if (status === 'published') return 'sm-pill sm-pill-pos'
  if (status === 'rejected' || status === 'revoked') return 'sm-pill sm-pill-neg'
  if (status === 'pending_review') return 'sm-pill sm-pill-warn'
  return 'sm-pill'
}

const VISIBILITY_TEXT: Record<string, string> = {
  private: '私有',
  public: '公开',
  team: '团队可见',
  organization: '组织可见',
}

const STATUS_TEXT: Record<string, string> = {
  draft: '草稿',
  scanning: '扫描中',
  pending_review: '待审核',
  published: '已发布',
  rejected: '已拒绝',
  revoked: '已撤回',
}

/**
 * 列出我名下的 Skill。
 *
 * 响应回来时输入框里的词已经变了，这份结果就丢掉——不丢的话列表显示的是旧词的
 * 结果，用户会打开错的 Skill。
 */
async function load() {
  const q = query.value
  loading.value = true
  listError.value = ''
  try {
    const page = await searchMySkills(q)
    if (query.value !== q) return
    skills.value = page.items
    // 选中的那个可能已经不在列表里了（被改名或撤销），跟着刷新一下。
    if (selected.value) {
      const hit = page.items.find((s) => s.id === selected.value?.id)
      if (hit) select(hit)
      else clearSelection()
    }
  } catch (e) {
    if (query.value !== q) return
    skills.value = []
    listError.value = errorText(e)
  } finally {
    if (query.value === q) loading.value = false
  }
}

/** 打开某个 Skill 的详情。 */
async function select(skill: Skill) {
  selected.value = skill
  editName.value = skill.name
  editDescription.value = skill.description
  editTags.value = [...skill.tags]
  notice.value = ''
  actionError.value = ''
  // 换 Skill 就收起文件浏览：展开的是上一个 Skill 的文件。
  browseVersion.value = ''
  versions.value = []
  versionsLoading.value = true
  versionsError.value = ''
  try {
    const page = await getVersions(skill.id)
    if (selected.value?.id !== skill.id) return
    versions.value = page.items
  } catch (e) {
    if (selected.value?.id !== skill.id) return
    versionsError.value = errorText(e)
  } finally {
    if (selected.value?.id === skill.id) versionsLoading.value = false
  }
}

function clearSelection() {
  selected.value = null
  versions.value = []
  versionsError.value = ''
  actionError.value = ''
  notice.value = ''
  browseVersion.value = ''
}

/**
 * 编辑文件产出了新版本：把版本列表刷新。
 *
 * 抽屉里显示的还是源版本（那才是被改的），列表里多出来的那一版才是结果——
 * 两边都得对上，不能只刷一半。
 */
async function onEdited(version?: string) {
  if (!selected.value) return
  const skill = selected.value
  try {
    const page = await getVersions(skill.id)
    if (selected.value?.id !== skill.id) return
    versions.value = page.items
    // 抽屉跟着新版本走：编辑产出的是新版本，接着改得基于它。
    if (version) browseVersion.value = version
  } catch (e) {
    versionsError.value = errorText(e)
  }
}

/** 保存展示性元数据。 */
async function save() {
  const skill = selected.value
  if (!skill) return
  saving.value = true
  actionError.value = ''
  notice.value = ''
  try {
    const updated = await updateSkill(skill.id, {
      name: editName.value.trim(),
      description: editDescription.value.trim(),
      tags: editTags.value,
    })
    selected.value = updated
    skills.value = skills.value.map((s) => (s.id === updated.id ? updated : s))
    notice.value = '已保存。'
  } catch (e) {
    actionError.value = errorText(e)
  } finally {
    saving.value = false
  }
}

/**
 * 撤回一个版本。撤了不可逆——恢复得发新版本，所以先问一句。
 */
async function revoke(version: SkillVersion) {
  const skill = selected.value
  if (!skill) return
  if (!window.confirm(`撤回版本 ${version.version}？撤回不可逆，要恢复只能发新版本。`)) return
  actionError.value = ''
  notice.value = ''
  try {
    await revokeVersion(skill.id, version.version, '在控制台撤回')
    notice.value = `版本 ${version.version} 已撤回。`
    const page = await getVersions(skill.id)
    if (selected.value?.id !== skill.id) return
    versions.value = page.items
  } catch (e) {
    actionError.value = errorText(e)
  }
}

/** 新建 Skill 的元数据。内容（版本）仍由 CLI 发布。 */
async function create() {
  createError.value = ''
  const slug = newSlug.value.trim()
  if (!SLUG_PATTERN.test(slug)) {
    createError.value = 'slug 只能是小写字母、数字或连字符，长度 2–64，且以字母或数字开头。'
    return
  }
  creating.value = true
  try {
    const created = await createSkill({
      slug,
      name: newName.value.trim() || slug,
      description: newDescription.value.trim(),
      visibility: newVisibility.value,
      tags: newTags.value,
    })
    newSlug.value = ''
    newName.value = ''
    newDescription.value = ''
    newTags.value = []
    await load()
    select(created)
  } catch (e) {
    createError.value = errorText(e)
  } finally {
    creating.value = false
  }
}

watch(() => query.value, load)

onMounted(load)
</script>

<template>
  <section>
    <div class="sm-title-row">
      <div>
        <h1 class="sm-title">我的 Skill</h1>
        <p class="sm-sub">你名下的 Skill：个人私有空间里的备份，以及你建的团队 Skill。</p>
      </div>
      <button class="sm-btn sm-btn--primary" data-test="new-skill" @click="creating = true">
        新建 Skill
      </button>
    </div>

    <p v-if="listError" class="sm-note sm-note--error sm-mb-16" data-test="error">
      {{ listError }}
    </p>

    <!--
      没选中时列表占满整屏：这一屏问的是「我名下有什么」，
      列表就是答案，右边留一栏空白反而像缺了东西。
      选中之后才分出详情栏。
    -->
    <div class="sm-mine" :class="{ 'sm-mine-split': selected }">
      <div class="sm-mine-list">
        <div class="sm-toolbar">
          <input
            v-model="query"
            class="sm-input sm-search"
            type="search"
            placeholder="按名称、slug 或标签过滤"
            data-test="mine-search"
          />
          <span class="sm-spacer" />
          <span class="sm-muted sm-sm sm-num">{{ skills.length }} 个</span>
        </div>

        <div v-if="loading" class="sm-empty">加载中…</div>

        <EmptyState
          v-else-if="!listError && !skills.length"
          text="你还没有 Skill。用 CLI 发布一个，或点右上角新建元数据。"
        />

        <div v-else class="sm-list">
          <button
            v-for="skill in skills"
            :key="skill.id"
            class="sm-item sm-item-btn"
            :class="{ 'sm-item-on': selected?.id === skill.id }"
            data-test="mine-item"
            @click="select(skill)"
          >
            <div class="sm-item-body">
              <div class="sm-item-head">
                <strong class="sm-item-title sm-truncate">{{ skill.name }}</strong>
                <!-- slug 与名字常常一样，重复两遍只是噪声。 -->
                <code v-if="skill.slug !== skill.name" class="sm-mono sm-muted sm-meta sm-truncate">
                  {{ skill.slug }}
                </code>
              </div>
              <p class="sm-muted sm-meta sm-truncate">{{ skill.description || '（没有描述）' }}</p>
            </div>
            <div class="sm-row sm-mine-meta">
              <span :class="visibilityPill(skill.visibility)">
                {{ VISIBILITY_TEXT[skill.visibility] ?? skill.visibility }}
              </span>
              <span class="sm-muted sm-meta sm-num">
                <span class="sm-faint">最新</span> {{ skill.latest_version ?? '—' }}
              </span>
            </div>
          </button>
        </div>
      </div>

      <div v-if="selected" class="sm-mine-detail">
        <template v-if="selected">
          <div class="sm-panel">
            <div class="sm-panel-head">
              <h2 class="sm-section">元数据</h2>
              <span class="sm-meta">
                {{ selected.slug }} · 更新于 {{ selected.updated_at.slice(0, 10) }}
              </span>
            </div>

            <p v-if="actionError" class="sm-note sm-note--error sm-mb-12" data-test="action-error">
              {{ actionError }}
            </p>
            <p v-if="notice" class="sm-note sm-note--success sm-mb-12" data-test="notice">
              {{ notice }}
            </p>

            <div class="sm-field sm-mb-12">
              <label for="edit-name">名称</label>
              <input id="edit-name" v-model="editName" class="sm-input" data-test="edit-name" />
            </div>
            <div class="sm-field sm-mb-12">
              <label for="edit-desc">描述</label>
              <textarea
                id="edit-desc"
                v-model="editDescription"
                class="sm-textarea"
                rows="3"
                data-test="edit-desc"
              />
            </div>
            <div class="sm-field sm-mb-16">
              <label>标签</label>
              <TagInput v-model="editTags" data-test="edit-tags" />
            </div>

            <div class="sm-row">
              <button
                class="sm-btn sm-btn--primary"
                data-test="save"
                :disabled="!dirty || saving"
                @click="save"
              >
                保存
              </button>
              <span class="sm-muted sm-sm">slug 与可见性不可改：会影响别人已装的内容。</span>
            </div>
          </div>

          <div class="sm-panel">
            <div class="sm-panel-head">
              <h2 class="sm-section">版本</h2>
              <span class="sm-meta">{{ versions.length }} 个</span>
            </div>

            <p v-if="versionsError" class="sm-note sm-note--error sm-mb-12" data-test="versions-error">
              {{ versionsError }}
            </p>

            <div v-if="versionsLoading" class="sm-empty">加载中…</div>

            <EmptyState
              v-else-if="!versionsError && !versions.length"
              text="还没有版本。用 CLI 发布：skillmesh publish <目录>"
            />

            <ul v-else class="sm-list">
              <li v-for="version in versions" :key="version.id" class="sm-item sm-item-static">
                <div class="sm-grow">
                  <div class="sm-item-head">
                    <strong class="sm-mono">{{ version.version }}</strong>
                    <span :class="versionPill(version.status)">
                      {{ STATUS_TEXT[version.status] ?? version.status }}
                    </span>
                    <span class="sm-muted sm-sm">{{ version.channel }}</span>
                  </div>
                  <p class="sm-muted sm-sm sm-mono sm-break">
                    {{ version.artifact?.sha256.slice(0, 16) ?? '—' }}
                  </p>
                  <p class="sm-muted sm-sm" data-test="version-time">
                    创建于 {{ formatDateTime(version.created_at) }}
                    <template v-if="version.published_at">
                      · 发布于 {{ formatDateTime(version.published_at) }}
                    </template>
                  </p>
                </div>
                <div class="sm-row">
                  <button
                    v-if="version.artifact"
                    class="sm-btn sm-btn--quiet sm-btn--sm"
                    data-test="browse-files"
                    @click="browseVersion = browseVersion === version.version ? '' : version.version"
                  >
                    {{ browseVersion === version.version ? '收起文件' : '浏览文件' }}
                  </button>
                  <button
                    v-if="version.status !== 'revoked'"
                    class="sm-btn sm-btn--quiet sm-btn--sm"
                    data-test="revoke"
                    @click="revoke(version)"
                  >
                    撤回
                  </button>
                </div>
              </li>
            </ul>
          </div>
        </template>
      </div>
    </div>

    <!--
      文件浏览做成抽屉：点「浏览文件」就滑出来，不用在一屏内容里找它。
      组件挂上即打开，关掉即卸载。
    -->
    <FileBrowser
      v-if="browseVersion && selected"
      :skill-id="selected.id"
      :version="browseVersion"
      :label="`${selected.slug} · ${browseVersion}`"
      @close="browseVersion = ''"
      @edited="onEdited"
    />

    <ModalDialog
      :open="creating"
      title="新建 Skill"
      data-test="create-dialog"
      @close="creating = false"
    >
      <p v-if="createError" class="sm-note sm-note--error sm-mb-12" data-test="create-error">
        {{ createError }}
      </p>

      <div class="sm-field sm-mb-12">
        <label for="new-slug">slug</label>
        <input
          id="new-slug"
          v-model="newSlug"
          class="sm-input sm-input--mono"
          placeholder="如 java-debug"
          data-test="new-slug"
        />
        <span class="sm-hint">只建元数据。内容与版本仍由 CLI 发布到它上面。</span>
      </div>
      <div class="sm-field sm-mb-12">
        <label for="new-name">名称</label>
        <input id="new-name" v-model="newName" class="sm-input" placeholder="留空就用 slug" data-test="new-name" />
      </div>
      <div class="sm-field sm-mb-12">
        <label for="new-desc">描述</label>
        <textarea
          id="new-desc"
          v-model="newDescription"
          class="sm-textarea"
          rows="3"
          data-test="new-desc"
        />
      </div>
      <div class="sm-field sm-mb-12">
        <label>可见性</label>
        <div class="sm-radio-row" data-test="new-visibility">
          <label class="sm-check">
            <input v-model="newVisibility" type="radio" value="private" />
            私有
          </label>
          <label class="sm-check">
            <input v-model="newVisibility" type="radio" value="public" />
            公开
          </label>
        </div>
        <span class="sm-hint">团队与组织可见的 Skill 由 CLI 发布时带 --org / --team 建立。</span>
      </div>
      <div class="sm-field">
        <label>标签</label>
        <TagInput v-model="newTags" data-test="new-tags" />
      </div>

      <template #footer>
        <button class="sm-btn" @click="creating = false">取消</button>
        <button class="sm-btn sm-btn--primary" data-test="create-submit" @click="create">
          创建
        </button>
      </template>
    </ModalDialog>
  </section>
</template>
