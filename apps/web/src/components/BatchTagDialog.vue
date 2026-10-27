<script setup lang="ts">
import { ref, watch } from 'vue'
import ModalDialog from './ModalDialog.vue'
import TagInput from './TagInput.vue'

/**
 * 批量打标签对话框。
 *
 * 初值是所选 Skill 的标签并集：用户在这个基础上改，改完统一覆盖。
 * 覆盖而不是「加标签」——加标签在多人协作里说不清该给谁加，
 * 覆盖则一眼看得见最后会变成什么样。
 */
const props = defineProps<{
  open: boolean
  /** 所选 Skill 的标签并集，作为初值。 */
  initialTags: string[]
  /** 所选条数。 */
  count: number
  busy?: boolean
}>()
const emit = defineEmits<{ close: []; confirm: [string[]] }>()

const tags = ref<string[]>([])

watch(
  () => props.open,
  (open) => {
    if (open) tags.value = [...props.initialTags]
  },
)
</script>

<template>
  <ModalDialog :open="open" title="批量打标签" @close="emit('close')">
    <p class="sm-mb-12">将把选中的 {{ count }} 个 Skill 的标签统一改成下面这组。</p>
    <TagInput v-model="tags" />
    <template #footer>
      <button
        class="sm-btn sm-btn--quiet sm-btn--sm"
        data-test="batch-tag-cancel"
        @click="emit('close')"
      >
        取消
      </button>
      <button
        class="sm-btn sm-btn--primary sm-btn--sm"
        data-test="batch-tag-confirm"
        :disabled="busy"
        @click="emit('confirm', tags)"
      >
        确定
      </button>
    </template>
  </ModalDialog>
</template>
