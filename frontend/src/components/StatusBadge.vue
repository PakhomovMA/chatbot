<script setup lang="ts">
import { computed } from 'vue'
import type { DocumentStatus, Grounding, IndexState } from '@/api/types'

const props = defineProps<{ value: DocumentStatus | Grounding | IndexState | string }>()

const tone = computed(() => {
  switch (props.value) {
    case 'READY':
    case 'GROUNDED':
      return 'ok'
    case 'FAILED':
    case 'INCOMPATIBLE':
    case 'INSUFFICIENT_EVIDENCE':
      return 'bad'
    case 'PARTIAL':
    case 'PENDING_REINDEX':
    case 'REBUILDING':
      return 'warn'
    case 'PARSING':
    case 'CHUNKING':
    case 'INDEXING':
    case 'UPLOADED':
      return 'busy'
    default:
      return ''
  }
})
</script>

<template>
  <span class="badge" :class="tone">{{ value.replace(/_/g, ' ') }}</span>
</template>
