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
  /** True for a partial answer the user stopped: kept as text, never as a confirmed result. */
  cancelled?: boolean
}

const STAGE_LABELS: Record<string, string> = {
  retrieving: 'Searching the knowledge base…',
  expanding: 'Searching again with a wider query…',
  researching: 'Researching with the search tools…',
  generating: 'Drafting an answer…',
  verifying: 'Verifying citations…',
}

/** A `fetch` rejected by its AbortSignal, as opposed to a transport or server failure. */
function isAbortError(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as { name?: string }).name === 'AbortError'
}

export const useChatStore = defineStore('chat', {
  state: () => ({
    conversationId: undefined as string | undefined,
    messages: [] as ChatMessage[],
    pending: false,
    stage: undefined as string | undefined,
    /** Progress inside the stage, e.g. the search the model just ran in agentic mode. */
    stageDetail: undefined as string | undefined,
    streamingEnabled: true,
    mode: 'DETERMINISTIC' as AnswerMode,
    error: undefined as string | undefined,
    selectedMessageId: undefined as string | undefined,
    abort: undefined as AbortController | undefined,
    /** Monotonic request counter; also the source of message ids, which must not collide. */
    requestSeq: 0,
    /** Request whose events and completion may still touch the store; undefined once it is done. */
    activeRequestId: undefined as number | undefined,
    /** Placeholder the active request streams into. */
    activeDraftId: undefined as string | undefined,
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
    /**
     * Sends one question. Streaming and non-streaming answers are both cancellable and both carry a
     * request id: a reply that arrives after Stop, New chat or another question is dropped instead of
     * writing into the conversation it no longer belongs to.
     */
    async send(text: string) {
      const message = text.trim()
      if (!message || this.pending) return
      const requestId = ++this.requestSeq
      const draftId = `a-${requestId}`
      const controller = new AbortController()
      this.activeRequestId = requestId
      this.activeDraftId = draftId
      this.abort = controller
      this.error = undefined
      this.pending = true
      this.stage = this.mode === 'AGENTIC' ? 'researching' : 'retrieving'
      this.stageDetail = undefined
      this.messages.push({ id: `u-${requestId}`, role: 'user', content: message, citations: [] })
      try {
        const response = this.streamingEnabled
          ? await this.sendStreaming(requestId, message, draftId, controller.signal)
          : await api.chat({ conversationId: this.conversationId, message, options: { mode: this.mode } }, controller.signal)
        if (!this.isCurrent(requestId)) return
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
        // A cancelled request has already been wound down by cancel(); a late failure of a superseded
        // request is not this conversation's error either.
        if (!this.isCurrent(requestId) || isAbortError(e)) return
        const detail = e instanceof ApiError || e instanceof Error ? e.message : 'The assistant is unavailable.'
        this.error = detail
        this.replaceOrPush(draftId, { id: `err-${requestId}`, role: 'assistant', content: detail, citations: [], error: true })
      } finally {
        if (this.isCurrent(requestId)) this.finish()
      }
    },
    async sendStreaming(requestId: number, message: string, draftId: string, signal: AbortSignal): Promise<ChatResponse> {
      return streamChat(
        { conversationId: this.conversationId, message, options: { mode: this.mode } },
        (event) => {
          if (!this.isCurrent(requestId)) return
          switch (event.type) {
            case 'status':
              this.stage = event.stage
              this.stageDetail = event.detail
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
        signal,
      )
    },
    /** False once the request was cancelled, reset or replaced by a newer one. */
    isCurrent(requestId: number): boolean {
      return this.activeRequestId === requestId
    },
    replaceOrPush(draftId: string, message: ChatMessage) {
      const index = this.messages.findIndex((m) => m.id === draftId)
      if (index >= 0) this.messages[index] = message
      else this.messages.push(message)
    },
    /** Clears the per-request state; the request itself can no longer touch the store. */
    finish() {
      this.pending = false
      this.stage = undefined
      this.stageDetail = undefined
      this.abort = undefined
      this.activeRequestId = undefined
      this.activeDraftId = undefined
    },
    /**
     * Stops the active request. The stream is aborted and the request retires immediately, so a reply
     * still in flight cannot land later. Text already streamed stays visible as an unfinished answer
     * without citations — cancelling the HTTP request stops the browser waiting, but does not prove
     * the server stopped generating.
     */
    cancel() {
      if (this.activeRequestId === undefined) return
      const draftId = this.activeDraftId
      this.abort?.abort()
      this.finish()
      const draft = draftId ? this.messages.find((m) => m.id === draftId) : undefined
      if (draft) {
        draft.streaming = false
        draft.cancelled = true
        draft.citations = []
      }
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
