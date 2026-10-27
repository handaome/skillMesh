<script setup lang="ts">
/**
 * 多选工具条。
 *
 * 只在有选中时出现：一排常驻按钮里混着「批量放行」「批量删除」，
 * 谁都不敢点。
 *
 * 动作由调用方通过插槽放进来——工具条只管「已选几项」和「取消选择」，
 * 具体动作各页面不一样。
 */
withDefaults(defineProps<{
  /** 选中的条数。 */
  count: number
  /** 动作执行中：按钮置灰，防止重复点。 */
  busy?: boolean
}>(), { busy: false })

const emit = defineEmits<{ clear: [] }>()
</script>

<template>
  <div v-if="count > 0" class="sm-toolbar sm-mb-12" data-test="multi-toolbar">
    <span class="sm-muted sm-sm" data-test="multi-count">已选 {{ count }} 项</span>
    <span class="sm-spacer" />
    <slot :busy="busy" />
    <button
      class="sm-btn sm-btn--quiet sm-btn--sm"
      data-test="multi-clear"
      :disabled="busy"
      @click="emit('clear')"
    >
      取消选择
    </button>
  </div>
</template>
