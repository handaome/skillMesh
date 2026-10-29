<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { downloadArtifact, fetchTags, getSkill, getVersions, searchSkills } from '../api'
import { usePagedList } from '../composables/usePagedList'
import { SCAN_STATUS_TEXT, VERSION_STATUS_TEXT, VISIBILITY_TEXT } from '../constants/status'
import type { Skill, SkillVersion, TagCount } from '../types'
import { errorText } from '../utils/errorText'
import { formatDateTime } from '../utils/format'
import EmptyState from '../components/EmptyState.vue'
import FileBrowser from '../components/FileBrowser.vue'
import TagFilter from '../components/TagFilter.vue'

const route = useRoute()
const router = useRouter()

const query = ref('')
const searched = ref(false)
/** 可见性筛选。空串表示不过滤（看全部能看见的）。 */
const visibility = ref('')
/** 选中的标签。多选是「同时满足」。 */
const selectedTags = ref<string[]>([])
/** 只看没打标的。与选了标签互斥。 */
const untagged = ref(false)
/** 可选的标签与各自数量。 */
const tagOptions = ref<TagCount[]>([])

/**
 * 列表。服务端按游标翻页，超过一页的这里能接着往下取。
 *
 * 查询词与筛选条件是这次查询的条件：翻页时用同一组条件接着查，
 * 条件变了就重新 load——拿旧条件的游标查新条件，页面对不上。
 */
const {
  items: skills,
  loadingMore,
  error,
  hasMore,
  countText,
  load: loadPage,
  loadMore,
} = usePagedList<Skill, { q: string; filters: Record<string, string> }>(
  (params, cursor) => searchSkills(params.q, cursor, params.filters),
  { unit: '个' },
)

const openId = ref<string | null>(null)
const detail = ref<Skill | null>(null)

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
  load: loadVersions,
  loadMore: loadMoreVersions,
  reset: resetVersions,
} = usePagedList<SkillVersion, string>((skillId, cursor) =>
  getVersions(skillId, undefined, cursor))

/** 正在浏览哪个版本的文件。空串表示没展开。 */
const browseVersion = ref('')

/**
 * 下载这一版的制品。
 *
 * 走服务端的受控下载（先授权再给流），不把带长期权限的地址留在页面上
 * （设计文档 §12）。
 */
async function download(version: SkillVersion) {
  if (!version.artifact) return
  try {
    const blob = await downloadArtifact(version.artifact.id)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${version.skill_id}-${version.version}.zip`
    link.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    error.value = errorText(e)
  }
}

/**
 * 搜索。
 *
 * 响应回来时输入框里的词已经变了，这份结果就丢掉：不丢的话列表显示的是旧词的
 * 结果，输入框里却是新词，用户会打开错的 Skill。
 * 回车键与搜索按钮走的是同一个入口（@keyup.enter="search"），两条路都算上了。
 */
async function search() {
  const q = query.value
  void loadTags()
  const filters: Record<string, string> = {}
  if (visibility.value) filters.visibility = visibility.value
  // 标签与「未打标」互斥，界面已经保证了，这里只管把条件传下去。
  if (selectedTags.value.length) filters.tag = selectedTags.value[0]
  if (untagged.value) filters.untagged = 'true'
  await loadPage({ q, filters })
  if (query.value !== q || error.value) return
  searched.value = true
}

/**
 * 拉标签清单。
 *
 * 每次搜索都拉一遍：新建的 Skill 带的标签要能立刻出现在筛选器里，
 * 否则用户建完标签还得刷新页面才看得见。
 */
async function loadTags() {
  try {
    tagOptions.value = await fetchTags()
  } catch {
    // 标签拉不到不该挡住列表：筛选器只是没有选项，搜索照常用。
    tagOptions.value = []
  }
}

/**
 * 打开详情。
 *
 * 响应回来时用户已经打开别的 Skill（或退回列表了），这份详情就丢掉：
 * 不丢的话地址上是 ?skill=B、卡片头却显示 A。
 */
async function open(id: string) {
  openId.value = id
  detail.value = null
  resetVersions()
  // 换 Skill 就收起文件浏览：展开的是上一个 Skill 的文件。
  browseVersion.value = ''
  error.value = ''
  if (route.query.skill !== id) {
    await router.replace({ query: { ...route.query, skill: id } })
    // 换人了就别接着拉了。
    if (openId.value !== id) return
  }
  try {
    const skill = await getSkill(id)
    if (openId.value !== id) return
    detail.value = skill
    await loadVersions(id)
  } catch (e) {
    if (openId.value !== id) return
    error.value = errorText(e)
  }
}

/**
 * 编辑产出新版本：刷新版本列表，抽屉跟着新版本走。
 *
 * 不刷新的话，这一页的版本表停在旧版本，新版本在这儿根本看不见——
 * 用户以为没保存成功，又改一遍。
 */
async function onEdited(version?: string) {
  if (!detail.value) return
  const id = detail.value.id
  await loadVersions(id)
  if (version) browseVersion.value = version
}

function back() {
  openId.value = null
  detail.value = null
  resetVersions()
  browseVersion.value = ''
  error.value = ''
  const next = { ...route.query }
  delete next.skill
  router.replace({ query: next })
}

// 首次进来地址上就带着 ?skill=<id> 时直接进详情，之后跟着查询参数走。
watch(
  () => route.query.skill,
  (value) => {
    if (typeof value === 'string' && value !== openId.value) open(value)
  },
  { immediate: true },
)

// 进页面就把能看见的 Skill 列出来：市场是一张目录，
// 空着让人先输关键词，等于让第一屏什么都不回答。
onMounted(() => {
  void loadTags()
  void search()
})
</script>

<template>
  <p v-if="error" class="sm-note sm-note--error sm-mb-16" data-test="error">
    {{ error }}
  </p>

  <div v-if="openId" class="sm-panel">
    <button class="sm-btn sm-btn--quiet sm-btn--sm sm-mb-12" data-test="back" @click="back">
      ← 返回市场
    </button>
    <template v-if="detail">
      <div class="sm-title-row">
        <div>
          <h1 class="sm-title">{{ detail.name }}</h1>
          <p class="sm-sub sm-mono">{{ detail.slug }}</p>
        </div>
        <div class="sm-row">
          <span class="sm-pill">
            {{ VISIBILITY_TEXT[detail.visibility] ?? detail.visibility }}
          </span>
          <span class="sm-pill sm-pill-data">最新 {{ detail.latest_version ?? '—' }}</span>
        </div>
      </div>
      <p class="sm-muted sm-mb-24">{{ detail.description }}</p>

      <!-- Git 来源：这个 Skill 是从哪个仓库来的。 -->
      <div v-if="detail.source?.repository_url" class="sm-panel sm-mb-16">
        <div class="sm-panel-head">
          <h2 class="sm-section">Git 来源</h2>
          <span class="sm-meta">{{ detail.source.provider ?? 'git' }}</span>
        </div>
        <p class="sm-muted sm-sm sm-mono sm-break" data-test="detail-git">
          {{ detail.source.repository_url }}
        </p>
        <p v-if="detail.source.subpath" class="sm-muted sm-sm sm-mono sm-break">
          {{ detail.source.subpath }}
        </p>
      </div>

      <h2 class="sm-section sm-mb-12">版本</h2>
      <!--
        表格只在有数据时渲染：读失败不是「没有版本」，
        跟上面的错误条并排会把人读歪。
      -->
      <p v-if="versionsError" class="sm-note sm-note--error sm-mb-12" data-test="versions-error">
        {{ versionsError }}
      </p>

      <div v-if="versionsLoading" class="sm-empty">加载中…</div>
      <div v-else-if="versions.length" class="sm-table-scroll">
        <table class="sm-table sm-table--flush">
          <thead>
            <tr>
              <th>版本</th>
              <th>状态</th>
              <th>通道</th>
              <th>扫描</th>
              <th>提交</th>
              <th>摘要</th>
              <th class="sm-num">创建时间</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="version in versions" :key="version.id">
              <td class="sm-mono">{{ version.version }}</td>
              <td>{{ VERSION_STATUS_TEXT[version.status] ?? version.status }}</td>
              <td>{{ version.channel }}</td>
              <td>
                <span
                  v-if="version.scan"
                  :class="version.scan.status === 'passed' ? 'sm-pill sm-pill-pos' : 'sm-pill sm-pill-neg'"
                  :title="version.scan.summary"
                >
                  {{ SCAN_STATUS_TEXT[version.scan.status] ?? version.scan.status }}
                </span>
                <span v-else class="sm-muted">未扫描</span>
              </td>
              <td class="sm-mono sm-break">{{ version.source_commit ?? '—' }}</td>
              <td class="sm-mono">{{ version.artifact?.sha256.slice(0, 12) ?? '—' }}</td>
              <td class="sm-num" data-test="version-time">{{ formatDateTime(version.created_at) }}</td>
              <td class="sm-actions">
                <button
                  v-if="version.artifact"
                  class="sm-btn sm-btn--quiet sm-btn--sm"
                  data-test="browse-files"
                  @click="browseVersion = browseVersion === version.version ? '' : version.version"
                >
                  {{ browseVersion === version.version ? '收起' : '浏览文件' }}
                </button>
                <button
                  v-if="version.artifact"
                  class="sm-btn sm-btn--quiet sm-btn--sm"
                  data-test="download-artifact"
                  @click="download(version)"
                >
                  下载
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <EmptyState
        v-if="!versionsLoading && !versionsError && !versions.length"
        text="还没有版本"
      />

      <!-- 还有下一页：说清还有，不假装这就是全部。 -->
      <div v-if="versionsHasMore" class="sm-row sm-mt-16">
        <button
          class="sm-btn sm-btn--quiet sm-btn--sm"
          data-test="load-more-versions"
          :disabled="versionsLoadingMore"
          @click="loadMoreVersions"
        >
          {{ versionsLoadingMore ? '加载中…' : '加载更多版本' }}
        </button>
      </div>

      <!-- 在线浏览这个版本里的文件。挂载即打开抽屉。 -->
      <FileBrowser
        v-if="browseVersion"
        :skill-id="detail.id"
        :version="browseVersion"
        :label="`${detail.slug} · ${browseVersion}`"
        @close="browseVersion = ''"
        @edited="onEdited"
      />
    </template>
  </div>

  <section v-else>
    <div class="sm-title-row">
      <div>
        <h1 class="sm-title">市场</h1>
        <p class="sm-sub">你能看见的 Skill：公开的，加上团队与组织里共享的。</p>
      </div>
    </div>

    <div class="sm-toolbar">
      <input
        v-model="query"
        class="sm-input sm-search"
        type="search"
        placeholder="搜索 Skill 名称、描述或标签"
        @keyup.enter="search"
      />
      <!-- 搜索按钮不禁用：正在加载时再点一次只是多发一次请求，
           禁掉反而让人以为搜索坏了。 -->
      <button class="sm-btn sm-btn--primary" data-test="search" @click="search">
        搜索
      </button>
      <!--
        可见性筛选。服务端支持 visibility 参数（设计文档 §12），
        页面上不给控件就等于这个能力不存在。
      -->
      <select
        v-model="visibility"
        class="sm-select"
        data-test="visibility-filter"
        @change="search"
      >
        <option value="">全部可见性</option>
        <option value="public">公开</option>
        <option value="organization">组织可见</option>
        <option value="team">团队可见</option>
        <option value="private">私有</option>
      </select>
      <!--
        标签筛选。标签清单单独取：列表是游标分页的，从一页数据里汇总标签
        汇总不全，用户想按只出现在第二页的标签筛选时就没有那个选项可点。
      -->
      <TagFilter
        v-model="selectedTags"
        v-model:untagged="untagged"
        :tags="tagOptions"
        @change="search"
      />
      <span class="sm-spacer" />
      <span v-if="searched" class="sm-muted sm-sm sm-num" data-test="count">
        {{ countText }}
      </span>
    </div>

    <EmptyState
      v-if="!error && searched && !skills.length"
      :text="query ? '没有匹配的 Skill，换个词试试。' : '现在还没有你能看见的 Skill。团队里有人发布后就会出现在这里。'"
    />

    <div v-else-if="skills.length" class="sm-list">
      <button
        v-for="skill in skills"
        :key="skill.id"
        class="sm-item sm-item-btn"
        data-test="skill-item"
        @click="open(skill.id)"
      >
        <div class="sm-item-body">
          <div class="sm-item-head">
            <strong class="sm-item-title sm-truncate">{{ skill.name }}</strong>
            <!-- slug 与名字常常一样，重复两遍只是噪声。 -->
            <code v-if="skill.slug !== skill.name" class="sm-mono sm-muted sm-meta sm-truncate">
              {{ skill.slug }}
            </code>
          </div>
          <p class="sm-muted sm-meta sm-truncate">{{ skill.description }}</p>
          <!--
            Git 来源：公开 Skill 的来路对使用者有用（能不能信、怎么更新），
            列表里就摆出来。
          -->
          <p
            v-if="skill.source?.repository_url"
            class="sm-muted sm-sm sm-mono sm-truncate"
            :title="skill.source.repository_url"
            data-test="market-git"
          >
            {{ skill.source.repository_url }}
          </p>
        </div>
        <div class="sm-row sm-mine-meta">
          <span class="sm-pill">
            {{ VISIBILITY_TEXT[skill.visibility] ?? skill.visibility }}
          </span>
          <span class="sm-muted sm-meta sm-num">
            <span class="sm-faint">最新</span> {{ skill.latest_version ?? '—' }}
          </span>
        </div>
      </button>
    </div>

    <!-- 还有下一页：说清还有，不假装这就是全部。 -->
    <div v-if="hasMore" class="sm-row sm-mt-16">
      <button
        class="sm-btn sm-btn--quiet sm-btn--sm"
        data-test="load-more"
        :disabled="loadingMore"
        @click="loadMore"
      >
        {{ loadingMore ? '加载中…' : '加载更多' }}
      </button>
    </div>
  </section>
</template>
