<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMessage } from '@/stores/chat'
import { renderAnswer } from '@/utils/markdown'
import { formatSeconds } from '@/utils/format'
import StatusBadge from './StatusBadge.vue'

const props = defineProps<{ message: ChatMessage; selected: boolean }>()
const emit = defineEmits<{ select: [messageId: string]; citation: [messageId: string, marker: number] }>()

const html = computed(() => (props.message.role === 'assistant' && !props.message.error ? renderAnswer(props.message.content) : ''))

function onClick(event: MouseEvent) {
  const target = event.target as HTMLElement
  const marker = target.closest('sup.cite')?.getAttribute('data-n')
  if (marker) {
    emit('citation', props.message.id, Number(marker))
    event.stopPropagation()
    return
  }
  if (props.message.role === 'assistant') emit('select', props.message.id)
}
</script>

<template>
  <div class="bubble" :class="[message.role, { selected, error: message.error }]" @click="onClick">
    <div v-if="message.role === 'user'">{{ message.content }}</div>
    <div v-else-if="message.error" class="error">{{ message.content }}</div>
    <div v-else v-html="html"></div>
    <div v-if="message.role === 'assistant' && !message.error" class="meta">
      <StatusBadge v-if="message.grounding" :value="message.grounding" />
      <span v-if="message.citations.length">{{ message.citations.length }} citation{{ message.citations.length === 1 ? '' : 's' }}</span>
      <span v-if="message.timings">{{ formatSeconds(message.timings.totalMs) }} (retrieval {{ message.timings.retrievalMs }} ms)</span>
      <span v-if="message.notes" class="muted">Not covered: {{ message.notes }}</span>
    </div>
  </div>
</template>
