<script setup lang="ts">
import type { TagCount } from '../types'

/**
 * 标签筛选器：多选标签（同时满足）+「未打标」。
 *
 * 标签与「未打标」互斥：既是某个标签又没打标，说不通。所以选中哪个先动哪个——
 * 选标签时清掉「未打标」，选「未打标」时清掉标签。
 */
const props = defineProps<{
  /** 可选标签与各自的数量。 */
  tags: TagCount[]
  /** 选中的标签。 */
  modelValue: string[]
  /** 是否只看没打标的。 */
  untagged: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [string[]]
  'update:untagged': [boolean]
  /**
   * 状态变了，该重新查列表了。
   *
   * 单独一个事件，而不是让父组件分别听两个 update：一次点选会同时改
   * 标签和「未打标」，听两个就是两次请求。
   */
  change: []
}>()

function toggle(tag: string) {
  const next = props.modelValue.includes(tag)
    ? props.modelValue.filter((t) => t !== tag)
    : [...props.modelValue, tag]
  emit('update:modelValue', next)
  if (next.length && props.untagged) emit('update:untagged', false)
  emit('change')
}

function toggleUntagged() {
  const next = !props.untagged
  emit('update:untagged', next)
  if (next) emit('update:modelValue', [])
  emit('change')
}
</script>

<template>
  <div class="sm-tagfilter" data-test="tag-filter">
    <button
      v-for="item in tags"
      :key="item.tag"
      type="button"
      class="sm-tagfilter-item"
      :class="{ 'sm-tagfilter-on': modelValue.includes(item.tag) }"
      :aria-pressed="modelValue.includes(item.tag)"
      :data-test="`tag-filter-${item.tag}`"
      @click="toggle(item.tag)"
    >
      {{ item.tag }}
      <span class="sm-tagfilter-count">{{ item.count }}</span>
    </button>
    <button
      type="button"
      class="sm-tagfilter-item"
      :class="{ 'sm-tagfilter-on': untagged }"
      :aria-pressed="untagged"
      data-test="untagged-filter"
      @click="toggleUntagged"
    >
      未打标
    </button>
  </div>
</template>
