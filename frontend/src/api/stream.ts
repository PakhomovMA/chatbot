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

  const reader = response.body.getReader()
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
  if (failure) throw new Error(failure)
  if (!finalResponse) throw new Error('The stream ended without a final answer.')
  return finalResponse
}
