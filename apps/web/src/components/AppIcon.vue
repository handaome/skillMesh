<script setup lang="ts">
import { computed } from 'vue'
import { PATHS, type IconName } from './icons'

/** 自绘线性图标。尺寸默认 16px，跟正文的笔画粗细对齐。 */
const props = withDefaults(
  defineProps<{ name: IconName; size?: number; label?: string }>(),
  { size: 16, label: undefined },
)

/** 图标名对不上时不渲染路径：画一个空图标比抛异常好。 */
const paths = computed(() => PATHS[props.name] ?? [])
</script>

<template>
  <svg
    class="sm-icon"
    :width="size"
    :height="size"
    viewBox="0 0 16 16"
    fill="none"
    stroke="currentColor"
    stroke-width="1.5"
    stroke-linecap="round"
    stroke-linejoin="round"
    :aria-hidden="label ? undefined : 'true'"
    :aria-label="label"
    :role="label ? 'img' : undefined"
  >
    <path v-for="(d, i) in paths" :key="i" :d="d" />
  </svg>
</template>

<style scoped>
.sm-icon {
  flex: none;
  display: block;
}
</style>
