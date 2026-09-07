import type { ChatRequest, ChatResponse } from './types'
import { ApiError } from './client'

export type ChatStreamEvent =
  | { type: 'status'; stage: string; detail?: string }
  | { type: 'delta'; text: string }
  | { type: 'final'; response: ChatResponse }
  | { type: 'error'; message: string }

/**
 * Streams `POST /api/chat/stream` (server-sent events over fetch, since EventSource cannot POST).
 * Resolves with the final response; rejects on transport errors or an `error` event. Comment blocks
 * (`:keep-alive`, sent by the server through silent phases) carry no data and are skipped.
 *
 * Cancellation: aborting `signal` rejects with an `AbortError`, releases the reader and cancels the
 * response body, so the browser stops waiting and drops the connection. The server treats the lost
 * connection as a cancellation signal, but a disconnect is not proof that generation stopped — the
 * model call is only cooperatively cancelled on the server side.
 */
export async function streamChat(body: ChatRequest, onEvent: (event: ChatStreamEvent) => void, signal?: AbortSignal): Promise<ChatResponse> {
  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(body),
    signal,
  })
  if (!response.ok || !response.body) {
    let problem: { detail?: string; title?: string } | undefined
    try {
      problem = await response.json()
    } catch {
      problem = undefined
    }
    throw new ApiError(response.status, problem, problem?.detail ?? problem?.title ?? `${response.status} ${response.statusText}`)
  }

  const stream = response.body
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let finalResponse: ChatResponse | undefined
  let failure: string | undefined

  const dispatch = (block: string) => {
    let name = 'message'
    const data: string[] = []
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) name = line.slice(6).trim()
      else if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
    }
    if (!data.length) return
    const payload = JSON.parse(data.join('\n'))
    switch (name) {
      case 'status':
        onEvent({ type: 'status', stage: payload.stage, detail: payload.detail ?? undefined })
        break
      case 'delta':
        onEvent({ type: 'delta', text: payload.text })
        break
      case 'final':
        finalResponse = payload.response as ChatResponse
        onEvent({ type: 'final', response: finalResponse })
        break
      case 'error':
        failure = payload.message
        onEvent({ type: 'error', message: failure ?? 'error' })
        break
    }
  }

  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let separator = buffer.indexOf('\n\n')
      while (separator >= 0) {
        dispatch(buffer.slice(0, separator))
        buffer = buffer.slice(separator + 2)
        separator = buffer.indexOf('\n\n')
      }
    }
    if (buffer.trim()) dispatch(buffer)
  } finally {
    // Abort, an `error` event and a missing final answer all leave the body unread; release the lock
    // and drop the connection instead of letting it linger until the response is garbage collected.
    try {
      reader.releaseLock()
    } catch {
      /* a reader whose stream already errored may refuse to release; the body cancel below suffices */
    }
    void stream.cancel().catch(() => undefined)
  }
  if (failure) throw new Error(failure)
  if (!finalResponse) throw new Error('The stream ended without a final answer.')
  return finalResponse
}
