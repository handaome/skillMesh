<script setup lang="ts">
/**
 * 空态：一个虚线框加一句话。
 *
 * 不用 Element Plus 的 el-empty——它自带一张大插画，在工作台里显得像
 * 「此处应有内容」的占位海报，而空态真正要说的是「这里确实没有东西」。
 */
defineProps<{ text: string }>()
</script>

<template>
  <div class="sm-empty">{{ text }}</div>
</template>
