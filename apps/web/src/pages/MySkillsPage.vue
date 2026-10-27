<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  createSkill,
  deleteSkill,
  getVersions,
  listDeletedSkills,
  listRecycleFiles,
  restoreRecycleFile,
  restoreSkill,
  revokeVersion,
  searchMySkills,
  updateSkill,
} from '../api'
import { usePagedList } from '../composables/usePagedList'
import { VERSION_STATUS_TEXT, VISIBILITY_TEXT } from '../constants/status'
import type { DeletedSkill, RecycleFile, Skill, SkillVersion } from '../types'
import { errorText } from '../utils/errorText'
import { formatDateTime } from '../utils/format'
import EmptyState from '../components/EmptyState.vue'
import FileBrowser from '../components/FileBrowser.vue'
import ModalDialog from '../components/ModalDialog.vue'
import TagInput from '../components/TagInput.vue'

/** 服务端收 slug 的规则：小写字母数字开头，后跟小写字母数字或连字符，总长 2–64。 */
const SLUG_PATTERN = /^[a-z0-9][a-z0-9-]{1,63}$/

/** 输入防抖。击键一次发一次搜索，绝大多数结果落地时已经被下一次输入作废。 */
const SEARCH_DEBOUNCE_MS = 300

const query = ref('')

/**
 * 列表。服务端按游标翻页，超过一页的这里能接着往下取。
 *
 * 查询词是这次查询的条件：翻页时用同一个词接着查，
 * 词变了就重新 load——拿旧词的游标查新词，页面对不上。
 */
const {
  items: skills,
  loading,
  loadingMore,
  error: listError,
  hasMore,
  countText,
  load: loadPage,
  loadMore,
} = usePagedList<Skill, string>((q, cursor) => searchMySkills(q, cursor), { unit: '个' })

/** 选中的 Skill。右侧详情面板显示的就是它。 */
const selected = ref<Skill | null>(null)

/**
 * 版本列表。
 *
 * 版本只增不减（每发一版 +1），一次全量返回迟早把响应撑爆：
 * 后面的版本谁都看不见，所以要能一页一页往下翻。
 *
 * 换 Skill 就重置：旧 Skill 的版本还挂在屏幕上会让人以为看的是新的。
 */
const {
  items: versions,
  loading: versionsLoading,
  loadingMore: versionsLoadingMore,
  error: versionsError,
  hasMore: versionsHasMore,
  countText: versionsCountText,
  load: loadVersions,
  loadMore: loadMoreVersions,
  reset: resetVersions,
} = usePagedList<SkillVersion, string>((skillId, cursor) =>
  getVersions(skillId, undefined, cursor), { unit: '个' })

/**
 * 已删除的 Skill（云端归档的）。
 *
 * 客户端靠它判断「云端删过的、本地也要跟着删」——
 * 只有服务端明确记下过删除，本地才敢动。
 */
const {
  items: deleted,
  loading: deletedLoading,
  error: deletedError,
  hasMore: deletedHasMore,
  countText: deletedCountText,
  load: loadDeleted,
  loadMore: loadMoreDeleted,
} = usePagedList<DeletedSkill>((_params, cursor) => listDeletedSkills(cursor), { unit: '个' })

const actionError = ref('')
const notice = ref('')
/** 已删除区块展开与否。第一次展开才去拉数据——没人看就别打这个请求。 */
const deletedOpen = ref(false)
/** 拉过没有。拉过就不在每次展开时重拉。 */
const deletedLoaded = ref(false)
/** 列表级动作（删除、还原）的提示。放在页面顶部：列表里没有详情面板可依附。 */
const listNotice = ref('')
const listActionError = ref('')

/** 回收站里的文件。空数组表示没查过或没有。 */
const recycle = ref<RecycleFile[]>([])
const recycleLoading = ref(false)
const recycleError = ref('')

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

/** 可见性 → 药丸的颜色。private/public 是个人空间的常态，其余颜色区分。 */
function visibilityPill(visibility: string): string {
  if (visibility === 'public') return 'sm-pill sm-pill-data'
  if (visibility === 'organization' || visibility === 'team') return 'sm-pill sm-pill-warn'
  return 'sm-pill'
}

/** 版本状态 → 药丸的颜色。 */
function versionPill(status: string): string {
  if (status === 'published') return 'sm-pill sm-pill-pos'
  if (status === 'rejected' || status === 'revoked' || status === 'quarantined') {
    return 'sm-pill sm-pill-neg'
  }
  if (status === 'pending_review') return 'sm-pill sm-pill-warn'
  return 'sm-pill'
}

/**
 * 列出我名下的 Skill。
 *
 * 响应回来时输入框里的词已经变了，这份结果就丢掉——不丢的话列表显示的是旧词的
 * 结果，用户会打开错的 Skill。
 */
async function load() {
  const q = query.value
  await loadPage(q)
  if (query.value !== q || listError.value) return
  // 选中的那个可能已经不在列表里了（被改名或撤销），跟着清掉。
  // 这里不重拉版本：刷新列表是筛选，不是「换个 Skill」——每次击键都多打一次
  // 版本列表，请求量直接翻倍。
  if (selected.value && !skills.value.some((s) => s.id === selected.value?.id)) {
    clearSelection()
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
  // 旧 Skill 的版本还挂在屏幕上会让人以为看的是新的，先清掉。
  resetVersions()
  await loadVersions(skill.id)
  await loadRecycle(skill.id)
}

/**
 * 读回收站。
 *
 * 回收站是按 Skill 查的（/skills/{id}/recycle）——被删掉的文件属于那个 Skill。
 */
async function loadRecycle(skillId: string) {
  recycleLoading.value = true
  recycleError.value = ''
  try {
    const files = await listRecycleFiles(skillId)
    if (selected.value?.id !== skillId) return
    recycle.value = files
  } catch (e) {
    if (selected.value?.id !== skillId) return
    recycleError.value = errorText(e)
  } finally {
    if (selected.value?.id === skillId) recycleLoading.value = false
  }
}

/**
 * 恢复一个被删掉的文件。
 *
 * 恢复不改旧版本——它产出新版本（版本不可变）。
 */
async function restoreFile(file: RecycleFile) {
  const skill = selected.value
  if (!skill) return
  actionError.value = ''
  try {
    await restoreRecycleFile(skill.id, file.path)
  } catch (e) {
    actionError.value = errorText(e)
    return
  }
  notice.value = `${file.path} 已恢复，发出了新版本。`
  await loadRecycle(skill.id)
  await loadVersions(skill.id)
}

function clearSelection() {
  selected.value = null
  resetVersions()
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
  await loadVersions(skill.id)
  // 抽屉跟着新版本走：编辑产出的是新版本，接着改得基于它。
  if (version) browseVersion.value = version
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
    await loadVersions(skill.id)
  } catch (e) {
    actionError.value = errorText(e)
  }
}

/** 展开/收起已删除区块。第一次展开才去拉数据：没人看就别打这个请求。 */
async function toggleDeleted() {
  deletedOpen.value = !deletedOpen.value
  if (deletedOpen.value && !deletedLoaded.value) {
    await loadDeleted()
    deletedLoaded.value = true
  }
}

/**
 * 删除一个 Skill。
 *
 * 软删除：云端的数据留着，可以从「已删除」里还原回来；但各端同步后本地那份
 * 也会被删掉——这步不可逆（本地那份删了就是删了），所以先问一句。
 */
async function remove(skill: Skill) {
  const ok = window.confirm(
    `删除「${skill.name}」？\n删除后可在「已删除」里还原；各端同步后本地那份也会被删掉。`,
  )
  if (!ok) return
  listActionError.value = ''
  listNotice.value = ''
  try {
    await deleteSkill(skill.id)
  } catch (e) {
    listActionError.value = errorText(e)
    return
  }
  listNotice.value = `已删除「${skill.name}」，可在「已删除」里还原。`
  await load()
  await loadDeleted()
}

/**
 * 还原一个被删除的 Skill。
 *
 * 还原完它回到删除前的样子，重新出现在「我的 Skill」里——两个列表都得刷新。
 */
async function restore(item: DeletedSkill) {
  listActionError.value = ''
  listNotice.value = ''
  try {
    await restoreSkill(item.id)
  } catch (e) {
    listActionError.value = errorText(e)
    return
  }
  listNotice.value = `已还原「${item.name}」。`
  await load()
  await loadDeleted()
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

/**
 * 输入防抖。
 *
 * 原来每敲一个字符就打一次搜索：请求量跟击键速度成正比，而绝大多数响应
 * 落地时输入框里的词已经变了，结果直接被丢弃——白花带宽也白占服务端。
 */
let searchTimer: ReturnType<typeof setTimeout> | undefined

watch(query, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(load, SEARCH_DEBOUNCE_MS)
})

onMounted(load)

onUnmounted(() => {
  if (searchTimer) clearTimeout(searchTimer)
})
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

    <!-- 删除、还原的提示。列表级动作没有详情面板可依附，放在页面顶部。 -->
    <p v-if="listActionError" class="sm-note sm-note--error sm-mb-16" data-test="list-error">
      {{ listActionError }}
    </p>
    <p v-if="listNotice" class="sm-note sm-note--success sm-mb-16" data-test="list-notice">
      {{ listNotice }}
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
          <span class="sm-muted sm-sm sm-num" data-test="count">{{ countText }}</span>
        </div>

        <div v-if="loading" class="sm-empty">加载中…</div>

        <EmptyState
          v-else-if="!listError && !skills.length"
          text="你还没有 Skill。用 CLI 发布一个，或点右上角新建元数据。"
        />

        <div v-else-if="skills.length" class="sm-list">
          <!--
            行本身不再整行是按钮：行里要放「删除」，按钮套按钮浏览器会把它拆开。
            左边那块仍然是按钮，点它打开详情。
          -->
          <div
            v-for="skill in skills"
            :key="skill.id"
            class="sm-item"
            :class="{ 'sm-item-on': selected?.id === skill.id }"
          >
            <button class="sm-item-btn sm-grow" data-test="mine-item" @click="select(skill)">
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
            </button>
            <div class="sm-row sm-mine-meta">
              <span :class="visibilityPill(skill.visibility)">
                {{ VISIBILITY_TEXT[skill.visibility] ?? skill.visibility }}
              </span>
              <span class="sm-muted sm-meta sm-num">
                <span class="sm-faint">最新</span> {{ skill.latest_version ?? '—' }}
              </span>
              <button
                class="sm-btn sm-btn--danger sm-btn--sm"
                data-test="delete-skill"
                @click="remove(skill)"
              >
                删除
              </button>
            </div>
          </div>
        </div>

        <!-- 还有下一页：说清还有，不假装这就是全部。 -->
        <div v-if="hasMore" class="sm-row sm-mt-12">
          <button
            class="sm-btn sm-btn--quiet sm-btn--sm"
            data-test="load-more"
            :disabled="loadingMore"
            @click="loadMore"
          >
            {{ loadingMore ? '加载中…' : '加载更多' }}
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
              <span class="sm-meta">{{ versionsCountText }}</span>
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
                      {{ VERSION_STATUS_TEXT[version.status] ?? version.status }}
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

            <!-- 还有下一页：说清还有，不假装这就是全部。 -->
            <div v-if="versionsHasMore" class="sm-row sm-mt-12">
              <button
                class="sm-btn sm-btn--quiet sm-btn--sm"
                data-test="load-more-versions"
                :disabled="versionsLoadingMore"
                @click="loadMoreVersions"
              >
                {{ versionsLoadingMore ? '加载中…' : '加载更多版本' }}
              </button>
            </div>
          </div>

          <!--
            回收站：发新版时被删掉的文件。删掉不是抹掉——
            回收期内能找回来（设计文档 §6.4）。
          -->
          <div class="sm-panel">
            <div class="sm-panel-head">
              <h2 class="sm-section">回收站</h2>
              <span class="sm-meta">{{ recycle.length }} 个文件</span>
            </div>

            <p v-if="recycleError" class="sm-note sm-note--error sm-mb-12" data-test="recycle-error">
              {{ recycleError }}
            </p>

            <div v-if="recycleLoading" class="sm-empty">加载中…</div>

            <EmptyState
              v-else-if="!recycleError && !recycle.length"
              text="回收站是空的。发新版时删掉的文件会先放到这儿，过期才清掉。"
            />

            <ul v-else class="sm-list">
              <li v-for="file in recycle" :key="file.path" class="sm-item sm-item-static">
                <div class="sm-grow">
                  <div class="sm-item-head">
                    <strong class="sm-mono sm-break">{{ file.path }}</strong>
                  </div>
                  <p class="sm-muted sm-sm">
                    来自 {{ file.source_version }}
                    · {{ file.size_bytes }} 字节
                    · {{ formatDateTime(file.created_at) }} 删除
                  </p>
                </div>
                <button
                  class="sm-btn sm-btn--quiet sm-btn--sm"
                  data-test="restore-file"
                  @click="restoreFile(file)"
                >
                  恢复
                </button>
              </li>
            </ul>
          </div>
        </template>
      </div>
    </div>

    <!--
      已删除：云端归档的 Skill。删掉的先在这儿躺着，可以还原回去。
      默认收起：多数人没删过东西，摊开一屏空列表只是噪声。
    -->
    <div class="sm-panel sm-mt-24">
      <div class="sm-panel-head">
        <h2 class="sm-section">已删除</h2>
        <div class="sm-row">
          <span v-if="deletedLoaded" class="sm-meta">{{ deletedCountText }}</span>
          <button
            class="sm-btn sm-btn--quiet sm-btn--sm"
            data-test="toggle-deleted"
            @click="toggleDeleted"
          >
            {{ deletedOpen ? '收起' : '展开' }}
          </button>
        </div>
      </div>

      <template v-if="deletedOpen">
        <p v-if="deletedError" class="sm-note sm-note--error sm-mb-12" data-test="deleted-error">
          {{ deletedError }}
        </p>

        <div v-if="deletedLoading" class="sm-empty">加载中…</div>

        <EmptyState
          v-else-if="!deletedError && !deleted.length"
          text="还没有删除过 Skill。删掉的会先放到这儿，随时能还原回去。"
        />

        <ul v-else class="sm-list">
          <li v-for="item in deleted" :key="item.id" class="sm-item sm-item-static">
            <div class="sm-grow">
              <div class="sm-item-head">
                <strong class="sm-item-title sm-truncate">{{ item.name }}</strong>
                <code class="sm-mono sm-muted sm-meta sm-truncate">{{ item.slug }}</code>
              </div>
              <p class="sm-muted sm-sm">删除于 {{ formatDateTime(item.deleted_at) }}</p>
            </div>
            <button
              class="sm-btn sm-btn--quiet sm-btn--sm"
              data-test="restore-skill"
              @click="restore(item)"
            >
              还原
            </button>
          </li>
        </ul>

        <!-- 还有下一页：说清还有，不假装这就是全部。 -->
        <div v-if="deletedHasMore" class="sm-row sm-mt-12">
          <button
            class="sm-btn sm-btn--quiet sm-btn--sm"
            data-test="deleted-more"
            @click="loadMoreDeleted"
          >
            加载更多
          </button>
        </div>
      </template>
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
