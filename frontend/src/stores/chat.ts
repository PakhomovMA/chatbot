import { defineStore } from 'pinia'
import { api, ApiError } from '@/api/client'
import type { ChatResponse, Citation, Grounding } from '@/api/types'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  grounding?: Grounding
  citations: Citation[]
  notes?: string
  timings?: { retrievalMs: number; llmMs: number; totalMs: number }
  error?: boolean
}

export const useChatStore = defineStore('chat', {
  state: () => ({
    conversationId: undefined as string | undefined,
    messages: [] as ChatMessage[],
    pending: false,
    error: undefined as string | undefined,
    selectedMessageId: undefined as string | undefined,
  }),
  getters: {
    selectedMessage(state): ChatMessage | undefined {
      return state.messages.find((m) => m.id === state.selectedMessageId)
    },
  },
  actions: {
    async send(text: string) {
      const message = text.trim()
      if (!message || this.pending) return
      this.error = undefined
      this.pending = true
      const userId = `u-${Date.now()}`
      this.messages.push({ id: userId, role: 'user', content: message, citations: [] })
      try {
        const response: ChatResponse = await api.chat({ conversationId: this.conversationId, message })
        this.conversationId = response.conversationId
        this.messages.push({
          id: response.messageId,
          role: 'assistant',
          content: response.answer,
          grounding: response.grounding,
          citations: response.citations,
          notes: response.notes,
          timings: response.timings,
        })
        this.selectedMessageId = response.messageId
      } catch (e) {
        const detail = e instanceof ApiError ? e.message : 'The assistant is unavailable.'
        this.error = detail
        this.messages.push({ id: `err-${Date.now()}`, role: 'assistant', content: detail, citations: [], error: true })
      } finally {
        this.pending = false
      }
    },
    select(messageId: string) {
      this.selectedMessageId = messageId
    },
    reset() {
      this.conversationId = undefined
      this.messages = []
      this.error = undefined
      this.selectedMessageId = undefined
    },
  },
})
