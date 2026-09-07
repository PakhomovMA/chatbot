<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useChatStore } from '@/stores/chat'
import MessageBubble from '@/components/MessageBubble.vue'
import CitationPanel from '@/components/CitationPanel.vue'

const chat = useChatStore()
const draft = ref('')
const list = ref<HTMLElement | null>(null)
const highlighted = ref<number | undefined>()

async function send() {
  const text = draft.value
  draft.value = ''
  await chat.send(text)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void send()
  }
}

function onCitation(messageId: string, marker: number) {
  chat.select(messageId)
  highlighted.value = marker
  void nextTick(() => document.getElementById(`citation-${marker}`)?.scrollIntoView({ block: 'nearest' }))
}

watch(() => chat.messages.length, () => nextTick(() => list.value?.scrollTo({ top: list.value.scrollHeight, behavior: 'smooth' })))
watch(() => chat.selectedMessageId, () => (highlighted.value = undefined))
</script>

<template>
  <div class="chat">
    <div class="chat-column">
      <div ref="list" class="messages">
        <p v-if="!chat.messages.length" class="muted">Ask a question about the documents in the knowledge base. Answers cite the passages they rely on.</p>
        <MessageBubble v-for="m in chat.messages" :key="m.id" :message="m" :selected="m.id === chat.selectedMessageId"
                       @select="chat.select" @citation="onCitation" />
        <div v-if="chat.pending" class="bubble assistant typing">{{ chat.stageLabel ?? 'Working…' }}</div>
      </div>
      <form class="composer" @submit.prevent="send">
        <textarea v-model="draft" placeholder="Ask about the knowledge base… (Enter to send, Shift+Enter for a new line)" rows="2"
                  :disabled="chat.pending" @keydown="onKeydown"></textarea>
        <button class="btn primary" type="submit" :disabled="chat.pending || !draft.trim()">Send</button>
        <button v-if="chat.pending" class="btn" type="button" @click="chat.cancel()">Stop</button>
        <button v-else class="btn" type="button" :disabled="!chat.messages.length" @click="chat.reset()">New chat</button>
        <label class="small muted" style="display: flex; align-items: center; gap: 4px"><input type="checkbox" v-model="chat.streamingEnabled" :disabled="chat.pending" /> stream</label>
      </form>
    </div>
    <CitationPanel :citations="chat.selectedMessage?.citations ?? []" :highlighted="highlighted" />
  </div>
</template>
