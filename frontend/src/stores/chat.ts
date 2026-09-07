import { defineStore } from 'pinia'
import { api, ApiError } from '@/api/client'
import { streamChat } from '@/api/stream'
import type { AnswerMode, ChatResponse, Citation, Grounding } from '@/api/types'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  grounding?: Grounding
  citations: Citation[]
  notes?: string
  timings?: { retrievalMs: number; llmMs: number; totalMs: number }
  error?: boolean
  /** True while the answer text is still arriving. */
  streaming?: boolean
}

const STAGE_LABELS: Record<string, string> = {
  retrieving: 'Searching the knowledge base…',
  researching: 'Researching with the search tools…',
  generating: 'Drafting an answer…',
  verifying: 'Verifying citations…',
}

export const useChatStore = defineStore('chat', {
  state: () => ({
    conversationId: undefined as string | undefined,
    messages: [] as ChatMessage[],
    pending: false,
    stage: undefined as string | undefined,
    streamingEnabled: true,
    mode: 'DETERMINISTIC' as AnswerMode,
    error: undefined as string | undefined,
    selectedMessageId: undefined as string | undefined,
    abort: undefined as AbortController | undefined,
  }),
  getters: {
    selectedMessage(state): ChatMessage | undefined {
      return state.messages.find((m) => m.id === state.selectedMessageId)
    },
    stageLabel(state): string | undefined {
      return state.stage ? (STAGE_LABELS[state.stage] ?? state.stage) : undefined
    },
  },
  actions: {
    async send(text: string) {
      const message = text.trim()
      if (!message || this.pending) return
      this.error = undefined
      this.pending = true
      this.stage = 'retrieving'
      this.messages.push({ id: `u-${Date.now()}`, role: 'user', content: message, citations: [] })
      const draftId = `a-${Date.now()}`
      try {
        const response = this.streamingEnabled ? await this.sendStreaming(message, draftId) : await api.chat({ conversationId: this.conversationId, message, options: { mode: this.mode } })
        this.conversationId = response.conversationId
        this.replaceOrPush(draftId, {
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
        const detail = e instanceof ApiError || e instanceof Error ? e.message : 'The assistant is unavailable.'
        this.error = detail
        this.replaceOrPush(draftId, { id: `err-${Date.now()}`, role: 'assistant', content: detail, citations: [], error: true })
      } finally {
        this.pending = false
        this.stage = undefined
        this.abort = undefined
      }
    },
    async sendStreaming(message: string, draftId: string): Promise<ChatResponse> {
      this.abort = new AbortController()
      return streamChat(
        { conversationId: this.conversationId, message, options: { mode: this.mode } },
        (event) => {
          switch (event.type) {
            case 'status':
              this.stage = event.stage
              break
            case 'delta': {
              const draft = this.messages.find((m) => m.id === draftId)
              if (draft) draft.content += event.text
              else this.messages.push({ id: draftId, role: 'assistant', content: event.text, citations: [], streaming: true })
              break
            }
            default:
              break
          }
        },
        this.abort.signal,
      )
    },
    replaceOrPush(draftId: string, message: ChatMessage) {
      const index = this.messages.findIndex((m) => m.id === draftId)
      if (index >= 0) this.messages[index] = message
      else this.messages.push(message)
    },
    cancel() {
      this.abort?.abort()
    },
    select(messageId: string) {
      this.selectedMessageId = messageId
    },
    reset() {
      this.cancel()
      this.conversationId = undefined
      this.messages = []
      this.error = undefined
      this.selectedMessageId = undefined
    },
  },
})
