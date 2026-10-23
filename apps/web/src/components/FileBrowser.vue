<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ApiError, editVersionFile, listVersionFiles, readVersionFile } from '../api'
import { highlight, languageOf } from '../utils/highlight'
import AppIcon from './AppIcon.vue'
import EmptyState from './EmptyState.vue'
import type { VersionFileContent, VersionFiles } from '../types'

const props = defineProps<{ skillId: string; version: string; label?: string }>()
const emit = defineEmits<{ close: []; edited: [version: string] }>()

/**
 * 服务端的单文件上限（VersionEditService.MAX_FILE_BYTES）：1MB。
 *
 * 前端先挡一道：超限的文件读了也白读——base64 之后还要发一遍给服务端才被拒。
 */
const MAX_UPLOAD_BYTES = 1 << 20

/**
 * 编辑的基准版本。
 *
 * 改一个文件会产出**新版本**，下一次编辑得基于那个新版本。
 * 还按打开时的旧版本改的话，新版本里上一轮改掉的东西就被悄悄丢了，
 * 控制台却报「已保存为新版本」——改动就这么没了。
 */
const baseVersion = ref(props.version)
watch(() => props.version, (v) => { baseVersion.value = v })

const dialog = ref<HTMLDialogElement | null>(null)
const files = ref<VersionFiles | null>(null)
const loading = ref(false)
const error = ref('')

const current = ref<VersionFileContent | null>(null)
const contentLoading = ref(false)
const contentError = ref('')

/** 编辑中：正在改的文件与改后的内容。 */
const editing = ref(false)
const draft = ref('')
const saving = ref(false)
const saveError = ref('')
const saveNotice = ref('')
/** 这个文件要不要可执行位。null 表示沿用原来的。 */
const executable = ref<boolean | null>(null)
/** 新建文件：填路径与内容。 */
const creating = ref(false)
const newPath = ref('')
const newContent = ref('')

/** 上传：从本机挑一个文件进来（二进制走这条路）。 */
const uploading = ref(false)
const uploadError = ref('')
const uploadPath = ref('')
const uploadExecutable = ref(false)
const picked = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

/** 收起的目录（按完整路径）。默认全展开——Skill 的目录一般只有一两层。 */
const collapsed = ref(new Set<string>())

/**
 * 文件清单：按路径的目录段分层，渲染成缩进的列表。
 *
 * 服务端给的是扁平路径（references/api.md），这里按 / 切开排出层级。
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

/** 文件少的时候树不用占那么宽——有的 Skill 就一个 SKILL.md。 */
const narrow = computed(() => (files.value?.files.length ?? 0) <= 3)

/** 当前文件的高亮 HTML。正文是不可信输入，高亮器内部先转义了。 */
const highlighted = computed(() => {
  const file = current.value
  if (!file || file.binary || !file.path) return ''
  return highlight(file.content ?? '', languageOf(file.path))
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
    files.value = await listVersionFiles(props.skillId, baseVersion.value)
    // 清单回来后自动打开第一个文件：开抽屉就是为了看内容，
    // 让用户先点一下文件再等一次请求是白等。
    const first = files.value.files.find((f) => f.path === 'SKILL.md')
      ?? files.value.files[0]
    if (first) await open(first.path)
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
  editing.value = false
  saveError.value = ''
  try {
    current.value = await readVersionFile(props.skillId, baseVersion.value, path)
  } catch (e) {
    current.value = null
    contentError.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    contentLoading.value = false
  }
}

/** 进入编辑：把正文放进编辑框。 */
function startEdit() {
  if (!current.value || current.value.binary) return
  draft.value = current.value.content ?? ''
  editing.value = true
  saveError.value = ''
  saveNotice.value = ''
  // 权限位默认沿用原来的：改内容不该顺手改权限。
  executable.value = null
}

/** 放弃编辑，回到只读。 */
function cancelEdit() {
  editing.value = false
  draft.value = ''
  saveError.value = ''
  executable.value = null
}

/**
 * 保存。
 *
 * 版本不可变：保存不改这个版本，而是产出新版本。服务端重打包后要走一遍
 * 正常的发布流程（扫描 → 发布或送审），所以结果可能是「已发布」也可能是
 * 「已提交审核」——团队可见的 Skill 会停在待审队列。
 */
async function save() {
  const file = current.value
  if (!file) return
  saving.value = true
  saveError.value = ''
  saveNotice.value = ''
  try {
    const result = await editVersionFile(props.skillId, baseVersion.value, {
      path: file.path,
      content: draft.value,
      operation: 'replace',
      executable: executable.value ?? undefined,
      changelog: `在控制台修改 ${file.path}`,
    })
    editing.value = false
    saveNotice.value = `已保存为新版本 ${result.version}（${statusText(result.status)}）。`
    await moveBase(result.version, file.path)
  } catch (e) {
    saveError.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    saving.value = false
  }
}

/**
 * 编辑产出新版本后，把基准版本挪到新版本上。
 *
 * 基准不挪，下一次编辑还是基于旧版本：上一轮改掉的内容会被新版本悄悄盖掉，
 * 面板却一直报「已保存」。顺带把文件内容重读一遍——屏幕上得是新版本里的那份。
 */
async function moveBase(version: string, path?: string) {
  baseVersion.value = version
  emit('edited', version)
  if (!path) return
  try {
    current.value = await readVersionFile(props.skillId, version, path)
  } catch {
    // 读不回来就算了：改动已经存成新版本了，别在这儿报错吓人。
  }
}

/** 新建文件。 */
async function createFile() {
  const path = newPath.value.trim()
  if (!path) {
    saveError.value = '文件路径不能为空'
    return
  }
  saving.value = true
  saveError.value = ''
  saveNotice.value = ''
  try {
    const result = await editVersionFile(props.skillId, baseVersion.value, {
      path,
      content: newContent.value,
      operation: 'create',
      changelog: `在控制台新建 ${path}`,
    })
    creating.value = false
    newPath.value = ''
    newContent.value = ''
    saveNotice.value = `已新建 ${path}，保存为新版本 ${result.version}（${statusText(result.status)}）。`
    await moveBase(result.version, path)
  } catch (e) {
    saveError.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    saving.value = false
  }
}

/**
 * 文件太大就说清楚，别把它读成 base64 再让服务端拒。
 *
 * 真正的闸在 upload()：那里挡下就一个字节都没读进内存。
 */
function tooLarge(file: File): boolean {
  if (file.size <= MAX_UPLOAD_BYTES) return false
  uploadError.value =
    `文件太大：${formatSize(file.size)}，上限 ${formatSize(MAX_UPLOAD_BYTES)}。先压缩或拆小再传。`
  return true
}

/**
 * 上传：从本机挑一个文件进包里。
 *
 * 走 base64——图片、字体这类二进制塞不进文本框。
 */
async function upload() {
  const file = picked.value
  if (!file) return
  const path = uploadPath.value.trim()
  if (!path) {
    uploadError.value = '文件路径不能为空'
    return
  }
  // 先查大小再读：读成 base64 是一份完整的内存拷贝，白读一场。
  if (tooLarge(file)) return
  saving.value = true
  uploadError.value = ''
  saveNotice.value = ''
  try {
    const result = await editVersionFile(props.skillId, baseVersion.value, {
      path,
      content_base64: await readBase64(file),
      operation: 'create',
      executable: uploadExecutable.value,
      changelog: `在控制台上传 ${path}`,
    })
    uploading.value = false
    picked.value = null
    uploadPath.value = ''
    uploadExecutable.value = false
    saveNotice.value = `已上传 ${path}，保存为新版本 ${result.version}（${statusText(result.status)}）。`
    await moveBase(result.version, path)
  } catch (e) {
    uploadError.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    saving.value = false
  }
}

/** 打开系统文件选择器。 */
function pickFile() {
  uploadError.value = ''
  fileInput.value?.click()
}

/** 选好了：路径默认用文件名，用户可以改。 */
function onPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 先把文件取出来，再清空选择：给 file 类型的 input 置空会连带清掉已选文件。
  // 清空是为了让同一个文件再选一次也能触发 change。
  input.value = ''
  if (!file) return
  uploading.value = true
  creating.value = false
  // 超限的当场退回：连路径都不必填，填了也传不上去。
  if (tooLarge(file)) {
    picked.value = null
    uploadPath.value = ''
    return
  }
  picked.value = file
  uploadPath.value = file.name
  uploadExecutable.value = false
  uploadError.value = ''
}

function cancelUpload() {
  uploading.value = false
  picked.value = null
  uploadPath.value = ''
  uploadError.value = ''
}

/** 文件 → base64。 */
function readBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result).split(',')[1] ?? '')
    reader.onerror = () => reject(new Error('读取文件失败'))
    reader.readAsDataURL(file)
  })
}

/** 删除文件。删了要出新版本——版本不可变，删不掉「这一版里的它」。 */
async function deleteFile() {
  const file = current.value
  if (!file) return
  if (!window.confirm(`删除 ${file.path}？删除会产出新版本，删不掉原来那一版里的它。`)) return
  saving.value = true
  saveError.value = ''
  saveNotice.value = ''
  try {
    const result = await editVersionFile(props.skillId, baseVersion.value, {
      path: file.path,
      operation: 'delete',
      changelog: `在控制台删除 ${file.path}`,
    })
    saveNotice.value = `已删除 ${file.path}，保存为新版本 ${result.version}（${statusText(result.status)}）。`
    current.value = null
    await moveBase(result.version)
  } catch (e) {
    saveError.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    saving.value = false
  }
}

/** 版本状态的读法。 */
function statusText(status: string): string {
  const map: Record<string, string> = {
    published: '已发布',
    pending_review: '已提交审核',
    rejected: '被服务端的制品扫描拦下',
  }
  return map[status] ?? status
}

// 切到别的版本就重新拉清单——上一个版本的文件留着是骗人的。
watch(() => [props.skillId, props.version], load, { immediate: true })

/**
 * 组件挂上就打开：这是「点浏览文件」的结果，不用用户再点一次。
 *
 * showModal 在 jsdom 里没实现——那边跑测试时不能炸。
 */
onMounted(async () => {
  await nextTick()
  const el = dialog.value
  if (!el) return
  if (typeof el.showModal === 'function') el.showModal()
  else el.setAttribute('open', '')
})

function close() {
  const el = dialog.value
  if (!el) return
  if (typeof el.close === 'function') el.close()
  else {
    el.removeAttribute('open')
    onDialogClosed()
  }
}

/** 浏览器关闭对话框（Esc、点遮罩）也会走这里：通知外面把组件卸掉。 */
function onDialogClosed() {
  emit('close')
}
</script>

<template>
  <dialog
    ref="dialog"
    class="sm-files"
    data-test="files-dialog"
    @close="onDialogClosed"
  >
    <header class="sm-files-head">
      <span class="sm-files-title">{{ label ?? '文件' }}</span>
      <div class="sm-row">
        <button
          class="sm-btn sm-btn--quiet sm-btn--sm"
          data-test="new-file"
          @click="creating = !creating; creating && (uploading = false); saveError = ''"
        >
          新建文件
        </button>
        <button class="sm-btn sm-btn--quiet sm-btn--sm" data-test="upload-file" @click="pickFile">
          上传文件
        </button>
        <button class="sm-close" data-test="files-close" aria-label="关闭" @click="close">
          <AppIcon name="close" :size="14" />
        </button>
      </div>
    </header>

    <!--
      文件选择框藏在视觉外但留在 DOM 里：display:none 的控件浏览器不响应 click。
    -->
    <input
      ref="fileInput"
      type="file"
      class="sm-hidden"
      data-test="file-input"
      @change="onPicked"
    />

    <div class="sm-files-body" :class="{ 'sm-files-narrow': narrow }">
      <div class="sm-browser-side">
        <p v-if="error" class="sm-note sm-note--error sm-mb-12" data-test="files-error">
          {{ error }}
        </p>

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
        <p v-if="contentError" class="sm-note sm-note--error sm-mb-12" data-test="content-error">
          {{ contentError }}
        </p>

        <!-- 提示条不挂在某个文件下：删掉文件后当前视图没了，但结果还要说。 -->
        <p v-if="saveError && !creating" class="sm-note sm-note--error sm-mb-12" data-test="save-error">
          {{ saveError }}
        </p>
        <p v-if="saveNotice" class="sm-note sm-note--success sm-mb-12" data-test="save-notice">
          {{ saveNotice }}
        </p>

        <div v-if="contentLoading" class="sm-empty">加载中…</div>

        <!-- 新建文件 -->
        <div v-else-if="creating" class="sm-newfile">
          <p v-if="saveError" class="sm-note sm-note--error sm-mb-12" data-test="save-error">
            {{ saveError }}
          </p>
          <div class="sm-field sm-mb-12">
            <label for="new-path">路径</label>
            <input
              id="new-path"
              v-model="newPath"
              class="sm-input sm-input--mono"
              placeholder="如 references/new.md"
              data-test="new-path"
            />
            <span class="sm-hint">制品内的相对路径，正斜杠分隔。</span>
          </div>
          <div class="sm-field sm-mb-12">
            <label for="new-content">内容</label>
            <textarea
              id="new-content"
              v-model="newContent"
              class="sm-editor"
              aria-label="新文件内容"
              spellcheck="false"
              data-test="new-content"
            />
          </div>
          <div class="sm-row">
            <button
              class="sm-btn sm-btn--primary"
              data-test="create-file"
              :disabled="saving"
              @click="createFile"
            >创建</button>
            <button class="sm-btn sm-btn--quiet" data-test="cancel-new" @click="creating = false">取消</button>
            <span class="sm-muted sm-sm">
              版本不可变：新建会产出新版本，不会改掉 {{ version }}。
            </span>
          </div>
        </div>

        <!-- 上传文件（二进制走这条路） -->
        <div v-else-if="uploading" class="sm-newfile">
          <p v-if="uploadError" class="sm-note sm-note--error sm-mb-12" data-test="upload-error">
            {{ uploadError }}
          </p>
          <div class="sm-field sm-mb-12">
            <label>文件</label>
            <span class="sm-mono sm-sm" data-test="upload-file-name">
              {{ picked?.name }}（{{ formatSize(picked?.size ?? 0) }}）
            </span>
            <span class="sm-hint">单个文件上限 {{ formatSize(MAX_UPLOAD_BYTES) }}。</span>
          </div>
          <div class="sm-field sm-mb-12">
            <label for="upload-path">路径</label>
            <input
              id="upload-path"
              v-model="uploadPath"
              class="sm-input sm-input--mono"
              placeholder="如 assets/logo.png"
              data-test="upload-path"
            />
            <span class="sm-hint">制品内的相对路径，正斜杠分隔。路径已存在会被覆盖。</span>
          </div>
          <div class="sm-row">
            <button
              class="sm-btn sm-btn--primary"
              data-test="upload-submit"
              :disabled="saving"
              @click="upload"
            >上传</button>
            <button class="sm-btn sm-btn--quiet" data-test="cancel-upload" @click="cancelUpload">取消</button>
            <label class="sm-check">
              <input v-model="uploadExecutable" type="checkbox" data-test="upload-executable" />
              可执行
            </label>
            <span class="sm-muted sm-sm">
              版本不可变：上传会产出新版本，不会改掉 {{ version }}。
            </span>
          </div>
        </div>

        <template v-else-if="current">
          <div class="sm-browser-head">
            <code class="sm-mono sm-break">{{ current.path }}</code>
            <div class="sm-row">
              <span class="sm-muted sm-sm sm-num">{{ formatSize(current.size_bytes) }}</span>
              <button
                v-if="!current.binary && !editing"
                class="sm-btn sm-btn--quiet sm-btn--sm"
                data-test="start-edit"
                @click="startEdit"
              >编辑</button>
              <button
                v-if="!editing"
                class="sm-btn sm-btn--quiet sm-btn--sm"
                data-test="delete-file"
                @click="deleteFile"
              >删除</button>
            </div>
          </div>

          <template v-if="editing">
            <textarea
              v-model="draft"
              class="sm-editor"
              aria-label="文件内容"
              spellcheck="false"
              data-test="file-editor"
            />
            <div class="sm-row sm-mt-12">
              <button
                class="sm-btn sm-btn--primary"
                data-test="save-file"
                :disabled="saving"
                @click="save"
              >保存为新版本</button>
              <button class="sm-btn sm-btn--quiet" data-test="cancel-edit" @click="cancelEdit">取消</button>
              <!--
                可执行位：脚本装了要能直接跑。
                三态：null = 沿用原来的（半选），true/false = 改成这个。
                原生 checkbox 没有半选态，用一个三态的单选组代替：
                不改 / 可执行 / 不可执行。
              -->
              <span class="sm-radio-row">
                <label class="sm-check">
                  <input
                    type="radio"
                    name="executable"
                    :checked="executable === null"
                    data-test="executable"
                    @change="executable = null"
                  />
                  沿用原值
                </label>
                <label class="sm-check">
                  <input
                    type="radio"
                    name="executable"
                    :checked="executable === true"
                    @change="executable = true"
                  />
                  可执行
                </label>
                <label class="sm-check">
                  <input
                    type="radio"
                    name="executable"
                    :checked="executable === false"
                    @change="executable = false"
                  />
                  不可执行
                </label>
              </span>
              <span class="sm-muted sm-sm">
                版本不可变：保存会产出新版本，不会改掉 {{ version }}。
              </span>
            </div>
          </template>
          <!--
            高亮后的 HTML。正文是不可信输入，高亮器内部先转义了才上色，
            所以这里用 v-html 是安全的。
          -->
          <pre
            v-else-if="!current.binary"
            class="sm-pre sm-code"
            data-test="file-content"
            v-html="highlighted"
          />
          <EmptyState
            v-else
            :text="`二进制文件，${formatSize(current.size_bytes)}，不显示内容`"
          />
        </template>

        <EmptyState v-else text="左边选一个文件看内容" />
      </div>
    </div>
  </dialog>
</template>
