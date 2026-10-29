<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  ApiError,
  batchArchive,
  batchUpdateTags,
  checkSkillSourceUpdates,
  createSkill,
  deleteSkill,
  fetchTags,
  getSkillSource,
  getVersions,
  importSkillFromGit,
  listDeletedSkills,
  listRecycleFiles,
  resolveGitSource,
  restoreRecycleFile,
  restoreSkill,
  revokeVersion,
  searchMySkills,
  updateSkill,
  updateSkillFromSource,
} from '../api'
import { useMultiSelect } from '../composables/useMultiSelect'
import { usePagedList } from '../composables/usePagedList'
import { VERSION_STATUS_TEXT, VISIBILITY_TEXT } from '../constants/status'
import type {
  BatchSkillResult,
  DeletedSkill,
  RecycleFile,
  Skill,
  SkillSource,
  SkillVersion,
  SourceUpdateCheck,
  TagCount,
} from '../types'
import { errorText } from '../utils/errorText'
import { formatDateTime } from '../utils/format'
import BatchTagDialog from '../components/BatchTagDialog.vue'
import EmptyState from '../components/EmptyState.vue'
import FileBrowser from '../components/FileBrowser.vue'
import ModalDialog from '../components/ModalDialog.vue'
import MultiSelectToolbar from '../components/MultiSelectToolbar.vue'
import TagFilter from '../components/TagFilter.vue'
import TagInput from '../components/TagInput.vue'

/** 服务端收 slug 的规则：小写字母数字开头，后跟小写字母数字或连字符，总长 2–64。 */
const SLUG_PATTERN = /^[a-z0-9][a-z0-9-]{1,63}$/

/** 输入防抖。击键一次发一次搜索，绝大多数结果落地时已经被下一次输入作废。 */
const SEARCH_DEBOUNCE_MS = 300

const query = ref('')
/** 选中的标签。多选是「同时满足」。 */
const selectedTags = ref<string[]>([])
/** 只看没打标的。与选了标签互斥。 */
const untagged = ref(false)
/** 可选的标签与各自数量。只统计我名下的。 */
const tagOptions = ref<TagCount[]>([])

/**
 * 列表。服务端按游标翻页，超过一页的这里能接着往下取。
 *
 * 查询词与筛选条件是这次查询的条件：翻页时用同一组条件接着查，
 * 条件变了就重新 load——拿旧条件的游标查新条件，页面对不上。
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
} = usePagedList<Skill, { q: string; filters: Record<string, string> }>(
  (params, cursor) => searchMySkills(params.q, cursor, params.filters),
  { unit: '个' },
)

/** 选中的 Skill。右侧详情面板显示的就是它。 */
const selected = ref<Skill | null>(null)

/**
 * 勾选状态（批量操作用）。
 *
 * 与右侧详情面板的「选中」是两回事：那个是看详情，这个是圈定
 * 「接下来要一起处理的那几条」。
 */
const filterSignal = computed(
  () => `${query.value}|${selectedTags.value.join(',')}|${untagged.value}`,
)
const scopeSignal = ref('')
const {
  selectedItems: checkedItems,
  selectedCount: checkedCount,
  isSelected: isChecked,
  toggle: toggleChecked,
  clear: clearChecked,
} = useMultiSelect<Skill>({
  items: skills,
  filtered: skills,
  getKey: (skill) => skill.id,
  filterSignal,
  scopeSignal,
})

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
  load: loadDeleted,
  loadMore: loadMoreDeleted,
} = usePagedList<DeletedSkill>((_params, cursor) => listDeletedSkills(cursor), { unit: '个' })

const actionError = ref('')
const notice = ref('')
/** 已删除弹窗开着没有。第一次打开才去拉数据——没人看就别打这个请求。 */
const deletedDialogOpen = ref(false)
/** 拉过没有。拉过就不在每次打开时重拉。 */
const deletedLoaded = ref(false)
/** 列表级动作（删除、还原）的提示。放在页面顶部：列表里没有详情面板可依附。 */
const listNotice = ref('')
const listActionError = ref('')

/** 回收站里的文件。空数组表示没查过或没有。 */
const recycle = ref<RecycleFile[]>([])
/** 回收站展开没有。展开才拉数据——多数 Skill 的回收站是空的，别每次都打一个请求。 */
const recycleOpen = ref(false)
const recycleLoading = ref(false)
const recycleError = ref('')

/** 正在浏览哪个版本的文件。空串表示没展开。 */
const browseVersion = ref('')

/** 编辑表单。改的是展示性元数据：slug 与可见性服务端不收。 */
const editName = ref('')
const editDescription = ref('')
const editTags = ref<string[]>([])
const saving = ref(false)

/**
 * Git 来源与在线更新。
 *
 * 只有从 Git 装过、并且发布到云端的 Skill 才有来源；
 * 没有来源的 Skill 服务端返回 404，这一块整个不显示。
 */
const source = ref<SkillSource | null>(null)
const sourceCheck = ref<SourceUpdateCheck | null>(null)
const sourceError = ref('')
const sourceNotice = ref('')
const sourceBusy = ref(false)

/** 新建表单 */
const creating = ref(false)
const createError = ref('')
const newSlug = ref('')
const newName = ref('')
const newDescription = ref('')
const newVisibility = ref<'private' | 'public'>('private')
const newTags = ref<string[]>([])

/**
 * 从 Git 导入。
 *
 * 给的是那些「从 GitHub 下载了、还没进 SkillMesh」的手工 Skill：
 * 填仓库地址 → 看仓库里有哪些 Skill 目录 → 选一个 → 起个 slug → 导入。
 */
const importing = ref(false)
const importError = ref('')
const importNotice = ref('')
const importRepo = ref('')
const importRef = ref('')
const importPath = ref('')
const importSlug = ref('')
const importName = ref('')
const importDescription = ref('')
const importVisibility = ref<'private' | 'public'>('private')
const importCandidates = ref<{ path: string; name: string }[]>([])
const importResolving = ref(false)
const importBusy = ref(false)

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
  void loadTags()
  const filters: Record<string, string> = {}
  // 标签与「未打标」互斥，界面已经保证了，这里只管把条件传下去。
  if (selectedTags.value.length) filters.tag = selectedTags.value[0]
  if (untagged.value) filters.untagged = 'true'
  await loadPage({ q, filters })
  if (query.value !== q || listError.value) return
  // 选中的那个可能已经不在列表里了（被改名或撤销），跟着清掉。
  // 这里不重拉版本：刷新列表是筛选，不是「换个 Skill」——每次击键都多打一次
  // 版本列表，请求量直接翻倍。
  if (selected.value && !skills.value.some((s) => s.id === selected.value?.id)) {
    clearSelection()
  }
}

/**
 * 拉标签清单。
 *
 * 每次刷新都拉一遍：新建的 Skill 带的标签要能立刻出现在筛选器里，
 * 否则用户建完标签还得刷新页面才看得见。
 */
async function loadTags() {
  try {
    tagOptions.value = await fetchTags(true)
  } catch {
    // 标签拉不到不该挡住列表：筛选器只是没有选项，搜索照常用。
    tagOptions.value = []
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
  // 回收站同理：旧 Skill 的文件还挂在屏幕上会让人以为看的是新的。
  recycleOpen.value = false
  recycle.value = []
  recycleError.value = ''
  // 旧 Skill 的版本还挂在屏幕上会让人以为看的是新的，先清掉。
  resetVersions()
  await loadVersions(skill.id)
  await loadSource(skill.id)
}

/**
 * 读 Git 来源。
 *
 * 没有 Git 来源的 Skill 服务端返回 404——那是「不是从 Git 来的」，
 * 不是出错，入口整个不显示。
 */
async function loadSource(skillId: string) {
  source.value = null
  sourceCheck.value = null
  sourceError.value = ''
  try {
    source.value = await getSkillSource(skillId)
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return
    sourceError.value = errorText(e)
  }
}

/** 查远端有没有新提交。只查 ref，不下载内容。 */
async function checkSourceUpdates() {
  if (!selected.value) return
  sourceBusy.value = true
  sourceError.value = ''
  sourceNotice.value = ''
  try {
    sourceCheck.value = await checkSkillSourceUpdates(selected.value.id)
  } catch (e) {
    sourceError.value = errorText(e)
  } finally {
    sourceBusy.value = false
  }
}

/**
 * 拉远端新提交，生成新版本。
 *
 * 生成的是**云端**的新版本：本机装的那份不会自动变，
 * 拉回本机还得跑 CLI 的 skillmesh update。
 */
async function applySourceUpdate() {
  if (!selected.value) return
  sourceBusy.value = true
  sourceError.value = ''
  sourceNotice.value = ''
  try {
    const result = await updateSkillFromSource(selected.value.id)
    sourceNotice.value = `已拉取 ${shortCommit(result.source_commit)}，生成版本 ${result.version}（${VERSION_STATUS_TEXT[result.status] ?? result.status}）`
    sourceCheck.value = null
    await loadVersions(selected.value.id)
    await loadSource(selected.value.id)
  } catch (e) {
    sourceError.value = errorText(e)
  } finally {
    sourceBusy.value = false
  }
}

/** commit 缩到 8 位显示。 */
function shortCommit(commit: string | null | undefined): string {
  if (!commit) return '—'
  return commit.length <= 8 ? commit : commit.slice(0, 8)
}

/** 展开回收站才去拉文件：折叠着一个空列表，没必要每次换 Skill 都打一次请求。 */
async function toggleRecycle() {
  recycleOpen.value = !recycleOpen.value
  if (recycleOpen.value && selected.value) {
    await loadRecycle(selected.value.id)
  }
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

/** 批量动作的结果提示。 */
const batchResult = ref<BatchSkillResult | null>(null)
/** 批量动作执行中。 */
const batchBusy = ref(false)
/** 批量打标签对话框。 */
const tagDialogOpen = ref(false)
/** 批量删除的确认弹窗。 */
const deleteConfirmOpen = ref(false)

/** 所选 Skill 的标签并集：批量打标签的初值。 */
const checkedTagUnion = computed(() => {
  const tags = new Set<string>()
  for (const skill of checkedItems.value) {
    for (const tag of skill.tags) tags.add(tag)
  }
  return [...tags]
})

/**
 * 批量打标签。
 *
 * 覆盖式：把选中的 Skill 的标签统一改成对话框里那组。逐条独立——
 * 一条没权限不该把已经改好的也一起撤回来。
 */
async function applyBatchTags(tags: string[]) {
  tagDialogOpen.value = false
  batchBusy.value = true
  try {
    batchResult.value = await batchUpdateTags(
      checkedItems.value.map((skill) => skill.id),
      tags,
    )
    clearChecked()
    await load()
  } catch (e) {
    listActionError.value = errorText(e)
  } finally {
    batchBusy.value = false
  }
}

/**
 * 批量归档（删除）。
 *
 * 软删除：云端数据留着，能还原；但各端同步后本地那份也会被删掉。
 */
async function applyBatchDelete() {
  deleteConfirmOpen.value = false
  batchBusy.value = true
  try {
    batchResult.value = await batchArchive(checkedItems.value.map((skill) => skill.id))
    clearChecked()
    await load()
  } catch (e) {
    listActionError.value = errorText(e)
  } finally {
    batchBusy.value = false
  }
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

/** 打开已删除弹窗。第一次打开才去拉数据：没人看就别打这个请求。 */
async function openDeleted() {
  deletedDialogOpen.value = true
  if (!deletedLoaded.value) {
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
  // 没打开过已删除弹窗就不必多打一个请求。
  if (deletedLoaded.value) await loadDeleted()
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
 * 看看仓库里有哪些 Skill 目录。
 *
 * 只读：服务端浅克隆一次、扫出候选、报出来就删掉临时目录。
 * 候选只有一个时直接选中它——多数仓库就装一个 Skill。
 */
async function resolveImport() {
  importError.value = ''
  importNotice.value = ''
  const repo = importRepo.value.trim()
  if (!repo) {
    importError.value = '要填仓库地址。'
    return
  }
  importResolving.value = true
  try {
    const resolved = await resolveGitSource({
      repository_url: repo,
      path: importPath.value.trim() || undefined,
      ref: importRef.value.trim() || undefined,
    })
    importCandidates.value = resolved.candidates
    if (resolved.candidates.length === 0) {
      importError.value = '仓库里没有含 SKILL.md 的目录。'
    } else if (!importPath.value.trim()) {
      importPath.value = resolved.candidates[0].path
      importNotice.value = `仓库里有 ${resolved.candidates.length} 个 Skill 目录，已选第一个。`
    }
  } catch (e) {
    importError.value = errorText(e)
  } finally {
    importResolving.value = false
  }
}

/** 从 Git 导入一个 Skill 到云端。 */
async function runImport() {
  importError.value = ''
  importNotice.value = ''
  const slug = importSlug.value.trim()
  if (!SLUG_PATTERN.test(slug)) {
    importError.value = 'slug 只能是小写字母、数字或连字符，长度 2–64，且以字母或数字开头。'
    return
  }
  if (!importPath.value.trim()) {
    importError.value = '要选一个 Skill 目录。'
    return
  }
  importBusy.value = true
  try {
    const result = await importSkillFromGit({
      repository_url: importRepo.value.trim(),
      path: importPath.value.trim(),
      ref: importRef.value.trim() || undefined,
      slug,
      name: importName.value.trim() || slug,
      description: importDescription.value.trim(),
      visibility: importVisibility.value,
    })
    const status = VERSION_STATUS_TEXT[result.status] ?? result.status
    importNotice.value = `已导入 ${result.slug}，版本 ${result.version}（${status}）。`
    importRepo.value = ''
    importRef.value = ''
    importPath.value = ''
    importSlug.value = ''
    importName.value = ''
    importDescription.value = ''
    importCandidates.value = []
    importing.value = false
    await load()
  } catch (e) {
    importError.value = errorText(e)
  } finally {
    importBusy.value = false
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
      <div class="sm-row">
        <button class="sm-btn sm-btn--quiet" data-test="open-deleted" @click="openDeleted">
          已删除
        </button>
        <button
          class="sm-btn sm-btn--quiet"
          data-test="open-import"
          @click="importing = true"
        >
          从 Git 导入
        </button>
        <button class="sm-btn sm-btn--primary" data-test="new-skill" @click="creating = true">
          新建 Skill
        </button>
      </div>
    </div>

    <p v-if="listError" class="sm-note sm-note--error sm-mb-16" data-test="error">
      {{ listError }}
    </p>

    <!-- 删除、还原的提示。列表级动作没有详情面板可依附，放在页面顶部。 -->
    <p v-if="listActionError" class="sm-note sm-note--error sm-mb-16" data-test="list-error">
      {{ listActionError }}
    </p>

    <!-- 批量结果：过了多少、没过多少、哪一条为什么没过。 -->
    <p
      v-if="batchResult"
      class="sm-note sm-mb-16"
      :class="batchResult.failed.length ? 'sm-note--warning' : 'sm-note--success'"
      data-test="batch-result"
    >
      成功 {{ batchResult.succeeded.length }} 条<template v-if="batchResult.failed.length"
        >，{{ batchResult.failed.length }} 条没过：{{
          batchResult.failed.map((f) => f.message).join('；')
        }}</template
      >
    </p>
    <p v-if="listNotice" class="sm-note sm-note--success sm-mb-16" data-test="list-notice">
      {{ listNotice }}
    </p>

    <!--
      始终左右分栏：左边是列表，右边是详情。
      未选中时右边给一句引导——点一行才开始分栏的话，列表会从整屏宽
      突然缩到一窄条，标题和描述全被截断，像换了个页面。
    -->
    <div class="sm-mine">
      <div class="sm-mine-list">
        <div class="sm-mine-tools">
          <div class="sm-row">
            <input
              v-model="query"
              class="sm-input sm-mine-search"
              type="search"
              placeholder="按名称、slug 或标签过滤"
              aria-label="筛选我的 Skill"
              data-test="mine-search"
            />
            <span class="sm-muted sm-sm sm-num sm-nowrap" data-test="count">{{ countText }}</span>
          </div>

          <!--
            标签筛选。标签清单单独取：列表是游标分页的，从一页数据里汇总标签
            汇总不全，用户想按只出现在第二页的标签筛选时就没有那个选项可点。
          -->
          <TagFilter
            v-model="selectedTags"
            v-model:untagged="untagged"
            class="sm-mine-tags"
            :tags="tagOptions"
            @change="load"
          />
        </div>

        <!--
          批量工具条。批量改标签是覆盖式：统一改成对话框里那组，
          改之前看得见最后会变成什么样。
        -->
        <MultiSelectToolbar :count="checkedCount" :busy="batchBusy" @clear="clearChecked">
          <button
            class="sm-btn sm-btn--quiet sm-btn--sm"
            data-test="batch-tag"
            :disabled="batchBusy"
            @click="tagDialogOpen = true"
          >
            批量打标签
          </button>
          <button
            class="sm-btn sm-btn--danger sm-btn--sm"
            data-test="batch-delete"
            :disabled="batchBusy"
            @click="deleteConfirmOpen = true"
          >
            批量删除
          </button>
        </MultiSelectToolbar>

        <div class="sm-mine-scroll">
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
              class="sm-item sm-mine-row"
              :class="{ 'sm-item-on': selected?.id === skill.id }"
            >
              <input
                class="sm-item-check"
                type="checkbox"
                :checked="isChecked(skill)"
                :aria-label="`选择 ${skill.name}`"
                data-test="mine-check"
                @change="toggleChecked(skill)"
              />
              <button class="sm-item-btn sm-grow" data-test="mine-item" @click="select(skill)">
                <div class="sm-item-body">
                  <div class="sm-item-head">
                    <strong class="sm-item-title sm-truncate">{{ skill.name }}</strong>
                    <span :class="visibilityPill(skill.visibility)" class="sm-pill--sm">
                      {{ VISIBILITY_TEXT[skill.visibility] ?? skill.visibility }}
                    </span>
                  </div>
                  <p class="sm-muted sm-item-desc sm-truncate" :title="skill.description">
                    {{ skill.description || '（没有描述）' }}
                  </p>
                  <!--
                    Git 来源：列表里就摆出来，不用点进详情才知道它从哪来。
                    只显示有仓库地址的——本地导入的来源没什么可显示的。
                  -->
                  <p
                    v-if="skill.source?.repository_url"
                    class="sm-muted sm-sm sm-mono sm-truncate sm-git-line"
                    :title="skill.source.repository_url"
                    data-test="mine-git"
                  >
                    {{ skill.source.repository_url }}
                  </p>
                  <div class="sm-item-foot">
                    <!-- slug 与名字常常一样，重复两遍只是噪声。 -->
                    <code
                      v-if="skill.slug !== skill.name"
                      class="sm-mono sm-faint sm-truncate"
                    >{{ skill.slug }}</code>
                    <span class="sm-spacer" />
                    <span class="sm-num">
                      {{ skill.latest_version ? `v${skill.latest_version}` : '未发布' }}
                    </span>
                  </div>
                </div>
              </button>
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
      </div>

      <div class="sm-mine-detail">
        <!-- 没选中：右边说清这里是什么，不摆一块空面板。 -->
        <div v-if="!selected" class="sm-mine-placeholder">
          <p class="sm-muted">从左侧选一个 Skill，查看元数据、版本与回收站。</p>
        </div>

        <template v-else>
          <div class="sm-panel">
            <div class="sm-panel-head">
              <div>
                <h2 class="sm-section">元数据</h2>
                <span class="sm-meta">
                  {{ selected.slug }} · 更新于 {{ selected.updated_at.slice(0, 10) }}
                </span>
              </div>
              <button
                class="sm-btn sm-btn--quiet sm-btn--sm sm-danger-text"
                data-test="delete-skill"
                @click="remove(selected)"
              >
                删除
              </button>
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

          <!--
            Git 来源：从 Git 装过并发布到云端的 Skill，可以在这里直接查更新、
            拉新提交。拉的是**云端**的新版本——本机装的那份不会自动变，
            拉回本机还得跑 CLI 的 skillmesh update。
          -->
          <div v-if="source" class="sm-panel">
            <div class="sm-panel-head">
              <h2 class="sm-section">Git 来源</h2>
              <span class="sm-meta">{{ source.provider ?? 'git' }}</span>
            </div>

            <p class="sm-muted sm-sm sm-mono sm-break" data-test="source-repo">
              {{ source.repository_url }}
            </p>
            <p class="sm-muted sm-sm">
              {{ source.ref_value || '默认分支' }} · commit
              <span class="sm-mono">{{ shortCommit(source.resolved_commit) }}</span>
            </p>

            <p v-if="sourceError" class="sm-note sm-note--error sm-mb-12" data-test="source-error">
              {{ sourceError }}
            </p>
            <p v-if="sourceNotice" class="sm-note sm-note--success sm-mb-12" data-test="source-notice">
              {{ sourceNotice }}
            </p>

            <div class="sm-row">
              <button
                class="sm-btn sm-btn--quiet sm-btn--sm"
                data-test="check-source"
                :disabled="sourceBusy"
                @click="checkSourceUpdates"
              >
                {{ sourceBusy ? '处理中…' : '检查更新' }}
              </button>
              <button
                v-if="sourceCheck?.has_update"
                class="sm-btn sm-btn--primary sm-btn--sm"
                data-test="apply-source"
                :disabled="sourceBusy"
                @click="applySourceUpdate"
              >
                更新到 {{ shortCommit(sourceCheck.remote_commit) }}
              </button>
            </div>
            <p
              v-if="sourceCheck && !sourceCheck.has_update"
              class="sm-muted sm-sm sm-mt-12"
              data-test="source-latest"
            >
              已是最新（远端 {{ shortCommit(sourceCheck.remote_commit) }}）。
            </p>
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
            默认收起：多数 Skill 的回收站是空的，展开才拉数据。
          -->
          <div class="sm-panel">
            <div class="sm-panel-head">
              <div>
                <h2 class="sm-section">回收站</h2>
                <span class="sm-meta">
                  {{ recycleOpen ? `${recycle.length} 个文件` : '发新版时删掉的文件，过期前能恢复' }}
                </span>
              </div>
              <button
                class="sm-btn sm-btn--quiet sm-btn--sm"
                data-test="toggle-recycle"
                @click="toggleRecycle"
              >
                {{ recycleOpen ? '收起' : '展开' }}
              </button>
            </div>

            <template v-if="recycleOpen">
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
            </template>
          </div>
        </template>
      </div>
    </div>

    <!--
      已删除：云端归档的 Skill。删掉的先在这儿躺着，可以还原回去。
      做成弹窗：多数人没删过东西，页面底部常驻一块空卡片只是噪声。
    -->
    <ModalDialog
      :open="deletedDialogOpen"
      title="已删除的 Skill"
      @close="deletedDialogOpen = false"
    >
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

      <template #footer>
        <button class="sm-btn" @click="deletedDialogOpen = false">关闭</button>
      </template>
    </ModalDialog>

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
      :open="importing"
      title="从 Git 导入"
      data-test="import-dialog"
      @close="importing = false"
    >
      <p class="sm-note sm-mb-12">
        把从 GitHub 下载、还没进 SkillMesh 的 Skill 纳管进来：填仓库地址，
        看仓库里有哪些 Skill 目录，选一个导入。导入后它有来源记录，
        之后就能在这里查更新、拉新版。
      </p>

      <p v-if="importError" class="sm-note sm-note--error sm-mb-12" data-test="import-error">
        {{ importError }}
      </p>
      <p v-if="importNotice" class="sm-note sm-note--success sm-mb-12" data-test="import-notice">
        {{ importNotice }}
      </p>

      <div class="sm-field sm-mb-12">
        <label for="import-repo">仓库地址</label>
        <input
          id="import-repo"
          v-model="importRepo"
          class="sm-input sm-input--mono"
          placeholder="https://github.com/owner/agent-skills.git"
          data-test="import-repo"
        />
      </div>
      <div class="sm-field sm-mb-12">
        <label for="import-ref">分支 / 标签</label>
        <input
          id="import-ref"
          v-model="importRef"
          class="sm-input"
          placeholder="留空用默认分支"
          data-test="import-ref"
        />
      </div>

      <div class="sm-field sm-mb-12">
        <label>仓库里的 Skill 目录</label>
        <div class="sm-row sm-mb-12">
          <button
            class="sm-btn sm-btn--quiet sm-btn--sm"
            data-test="import-resolve"
            :disabled="importResolving"
            @click="resolveImport"
          >
            {{ importResolving ? '正在看仓库…' : '看看仓库里有什么' }}
          </button>
        </div>
        <div v-if="importCandidates.length" class="sm-radio-row" data-test="import-candidates">
          <label v-for="candidate in importCandidates" :key="candidate.path" class="sm-check">
            <input v-model="importPath" type="radio" :value="candidate.path" />
            {{ candidate.name }}
            <span class="sm-muted sm-sm sm-mono">{{ candidate.path }}</span>
          </label>
        </div>
        <input
          v-model="importPath"
          class="sm-input sm-input--mono"
          placeholder="仓库内的子目录，如 skills/java-debug"
          data-test="import-path"
        />
        <span class="sm-hint">手填也行：填的是仓库里的相对路径。</span>
      </div>

      <div class="sm-field sm-mb-12">
        <label for="import-slug">slug</label>
        <input
          id="import-slug"
          v-model="importSlug"
          class="sm-input sm-input--mono"
          placeholder="如 java-debug"
          data-test="import-slug"
        />
        <span class="sm-hint">云端里它的唯一名字。只能是小写字母、数字或连字符。</span>
      </div>
      <div class="sm-field sm-mb-12">
        <label for="import-name">名称</label>
        <input
          id="import-name"
          v-model="importName"
          class="sm-input"
          placeholder="留空就用 slug"
          data-test="import-name"
        />
      </div>
      <div class="sm-field sm-mb-12">
        <label for="import-desc">描述</label>
        <textarea
          id="import-desc"
          v-model="importDescription"
          class="sm-textarea"
          rows="3"
          data-test="import-desc"
        />
      </div>
      <div class="sm-field">
        <label>可见性</label>
        <div class="sm-radio-row" data-test="import-visibility">
          <label class="sm-check">
            <input v-model="importVisibility" type="radio" value="private" />
            私有
          </label>
          <label class="sm-check">
            <input v-model="importVisibility" type="radio" value="public" />
            公开
          </label>
        </div>
      </div>

      <template #footer>
        <button class="sm-btn" @click="importing = false">取消</button>
        <button
          class="sm-btn sm-btn--primary"
          data-test="import-submit"
          :disabled="importBusy"
          @click="runImport"
        >
          {{ importBusy ? '导入中…' : '导入' }}
        </button>
      </template>
    </ModalDialog>

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
    <BatchTagDialog
      :open="tagDialogOpen"
      :initial-tags="checkedTagUnion"
      :count="checkedCount"
      :busy="batchBusy"
      @close="tagDialogOpen = false"
      @confirm="applyBatchTags"
    />

    <ModalDialog :open="deleteConfirmOpen" title="批量删除" @close="deleteConfirmOpen = false">
      <p class="sm-mb-12">
        将删除选中的 {{ checkedCount }} 个 Skill。这是软删除：云端数据留着，可以还原；
        但各端同步后本地那份也会被删掉。
      </p>
      <template #footer>
        <button
          class="sm-btn sm-btn--quiet sm-btn--sm"
          data-test="batch-delete-cancel"
          @click="deleteConfirmOpen = false"
        >
          取消
        </button>
        <button
          class="sm-btn sm-btn--danger sm-btn--sm"
          data-test="batch-delete-confirm"
          @click="applyBatchDelete"
        >
          确定删除
        </button>
      </template>
    </ModalDialog>
  </section>
</template>
