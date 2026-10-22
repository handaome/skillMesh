<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'

/**
 * 模态框。用原生 <dialog>——浏览器自带焦点陷阱与 Esc 关闭，
 * 不需要自己实现一遍。
 */
const props = defineProps<{ open: boolean; title: string }>()
const emit = defineEmits<{ close: [] }>()

const dialog = ref<HTMLDialogElement | null>(null)

/** 打开时把焦点交给第一个输入框，省一次 Tab。 */
function focusFirst() {
  const el = dialog.value?.querySelector<HTMLElement>(
    'input:not([type=hidden]), textarea, select, button',
  )
  el?.focus()
}

function show() {
  const el = dialog.value
  if (!el) return
  // showModal 在 jsdom 里没实现——那边跑测试时不能炸。
  if (typeof el.showModal === 'function') el.showModal()
  else el.setAttribute('open', '')
  void nextTick(focusFirst)
}

function close() {
  const el = dialog.value
  if (!el) return
  if (typeof el.close === 'function') el.close()
  else {
    el.removeAttribute('open')
    emit('close')
  }
}

watch(() => props.open, (open) => {
  if (open) show()
  else close()
})

onMounted(() => {
  if (props.open) show()
})
</script>

<template>
  <dialog ref="dialog" class="sm-dialog" @close="emit('close')" @cancel.prevent="close">
    <header class="sm-dialog-head">
      <span class="sm-dialog-title">{{ title }}</span>
      <button class="sm-close" aria-label="关闭" @click="close">
        <AppIcon name="close" :size="14" />
      </button>
    </header>
    <div class="sm-dialog-body">
      <slot />
    </div>
    <footer v-if="$slots.footer" class="sm-dialog-foot">
      <slot name="footer" />
    </footer>
  </dialog>
</template>
