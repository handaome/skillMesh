<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApiError, listVersionFiles, readVersionFile } from '../api'
import EmptyState from './EmptyState.vue'
import type { VersionFileContent, VersionFiles } from '../types'

const props = defineProps<{ skillId: string; version: string }>()

const files = ref<VersionFiles | null>(null)
const loading = ref(false)
const error = ref('')

const current = ref<VersionFileContent | null>(null)
const contentLoading = ref(false)
const contentError = ref('')

/**
 * 文件清单：按路径的目录段分层，渲染成缩进的列表。
 *
 * 服务端给的是扁平路径（references/api.md），这里按 / 切开排出层级。
 * 不用递归组件——一层缩进就够读懂，多一层文件夹已经很少见了。
 */
interface TreeRow {
  name: string
  path: string
  depth: number
  isDir: boolean
  size: number
}

/** 树的中间节点：同名同路径的目录只建一个。 */
interface TreeNode {
  name: string
  path: string
  dirs: Map<string, TreeNode>
  files: { name: string; path: string; size: number }[]
}

/** 收起的目录（按完整路径）。默认全展开——Skill 的目录一般只有一两层。 */
const collapsed = ref(new Set<string>())

const tree = computed<TreeRow[]>(() => {
  // 先建树：同一目录（按完整路径）只出现一次。
  // 直接按路径逐段铺平会让 capacity 重复四次——每个文件都带一遍前缀。
  const root: TreeNode = { name: '', path: '', dirs: new Map(), files: [] }
  for (const file of files.value?.files ?? []) {
    const parts = file.path.split('/')
    let node = root
    parts.slice(0, -1).forEach((part, index) => {
      const path = parts.slice(0, index + 1).join('/')
      let child = node.dirs.get(part)
      if (!child) {
        child = { name: part, path, dirs: new Map(), files: [] }
        node.dirs.set(part, child)
      }
      node = child
    })
    const name = parts[parts.length - 1]
    node.files.push({ name, path: file.path, size: file.size_bytes })
  }

  // 再展平成带缩进的行：目录在前，同层按名字排。
  const rows: TreeRow[] = []
  const walk = (node: TreeNode, depth: number) => {
    const dirs = [...node.dirs.values()].sort((a, b) => a.name.localeCompare(b.name))
    const files = [...node.files].sort((a, b) => a.name.localeCompare(b.name))
    for (const dir of dirs) {
      rows.push({ name: dir.name, path: dir.path, depth, isDir: true, size: 0 })
      if (collapsed.value.has(dir.path)) continue
      walk(dir, depth + 1)
    }
    for (const file of files) {
      rows.push({ name: file.name, path: file.path, depth, isDir: false, size: file.size })
    }
  }
  walk(root, 0)
  return rows
})

/** 文件大小：给人看的，不是给人算的。 */
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** 点目录：收起或展开。 */
function toggleDir(path: string) {
  const next = new Set(collapsed.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  collapsed.value = next
}

function isCollapsed(path: string): boolean {
  return collapsed.value.has(path)
}

async function load() {
  loading.value = true
  error.value = ''
  current.value = null
  contentError.value = ''
  try {
    files.value = await listVersionFiles(props.skillId, props.version)
  } catch (e) {
    files.value = null
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    loading.value = false
  }
}

async function open(path: string) {
  contentLoading.value = true
  contentError.value = ''
  try {
    current.value = await readVersionFile(props.skillId, props.version, path)
  } catch (e) {
    current.value = null
    contentError.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    contentLoading.value = false
  }
}

// 切到别的版本就重新拉清单——上一个版本的文件留着是骗人的。
watch(() => [props.skillId, props.version], load, { immediate: true })
</script>

<template>
  <div class="sm-browser">
    <div class="sm-browser-side">
      <el-alert
        v-if="error"
        data-test="files-error"
        :title="error"
        type="error"
        :closable="false"
        show-icon
        class="sm-mb-12"
      />

      <div v-if="loading" class="sm-empty">加载中…</div>

      <EmptyState
        v-else-if="!error && !files?.files.length"
        text="这个版本没有文件"
      />

      <ul v-else class="sm-tree" data-test="file-tree">
        <li
          v-for="row in tree"
          :key="row.path"
          class="sm-tree-row"
          :class="{
            'sm-tree-dir': row.isDir,
            'sm-tree-on': !row.isDir && current?.path === row.path,
          }"
          :style="{ paddingLeft: `${8 + row.depth * 14}px` }"
          :data-test="row.isDir ? 'tree-dir' : 'tree-file'"
          @click="row.isDir ? toggleDir(row.path) : open(row.path)"
        >
          <span class="sm-tree-name">
            <span v-if="row.isDir" class="sm-tree-caret">{{ isCollapsed(row.path) ? '▸' : '▾' }}</span>
            {{ row.name }}
          </span>
          <span v-if="!row.isDir" class="sm-muted sm-text-sm sm-num">
            {{ formatSize(row.size) }}
          </span>
        </li>
      </ul>
    </div>

    <div class="sm-browser-main">
      <el-alert
        v-if="contentError"
        data-test="content-error"
        :title="contentError"
        type="error"
        :closable="false"
        show-icon
        class="sm-mb-12"
      />

      <div v-if="contentLoading" class="sm-empty">加载中…</div>

      <template v-else-if="current">
        <div class="sm-browser-head">
          <span class="sm-mono sm-truncate">{{ current.path }}</span>
          <span class="sm-muted sm-text-sm sm-num">{{ formatSize(current.size_bytes) }}</span>
        </div>
        <pre v-if="!current.binary" class="sm-pre" data-test="file-content">{{ current.content }}</pre>
        <EmptyState
          v-else
          :text="`二进制文件，${formatSize(current.size_bytes)}，不显示内容`"
        />
      </template>

      <EmptyState v-else text="左边选一个文件看内容" />
    </div>
  </div>
</template>
