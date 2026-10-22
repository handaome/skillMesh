<script setup lang="ts">
import { ref } from 'vue'

/**
 * 标签输入：一个可新增、可删除的标签集合。
 *
 * 不用多选下拉——那里加新标签要按回车、选已有标签要点两下，
 * 在只有几个标签的场景里反而更绕。
 */
const props = defineProps<{ modelValue: string[]; placeholder?: string }>()
const emit = defineEmits<{ 'update:modelValue': [string[]] }>()

const draft = ref('')

function add() {
  const tag = draft.value.trim()
  if (!tag || props.modelValue.includes(tag)) {
    draft.value = ''
    return
  }
  emit('update:modelValue', [...props.modelValue, tag])
  draft.value = ''
}

function remove(tag: string) {
  emit('update:modelValue', props.modelValue.filter((t) => t !== tag))
}

/** 退格清空输入框时，删掉最后一个标签。 */
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' || event.key === ',') {
    event.preventDefault()
    add()
  } else if (event.key === 'Backspace' && !draft.value && props.modelValue.length) {
    remove(props.modelValue[props.modelValue.length - 1])
  }
}
</script>

<template>
  <div class="sm-tags">
    <span v-for="tag in modelValue" :key="tag" class="sm-tag">
      {{ tag }}
      <button type="button" :aria-label="`删除标签 ${tag}`" @click="remove(tag)">×</button>
    </span>
    <input
      v-model="draft"
      type="text"
      :placeholder="modelValue.length ? '' : (placeholder ?? '回车新增一个标签')"
      @keydown="onKeydown"
      @blur="add"
    />
  </div>
</template>
