import assert from 'node:assert/strict'
import { afterEach, beforeEach, test } from 'node:test'
import { createPinia, setActivePinia } from 'pinia'
import { useChatStore } from '../src/stores/chat.ts'
import { chatResponse } from './support/fixtures.ts'
import { flush, installFetch } from './support/harness.ts'

let http: ReturnType<typeof installFetch>

beforeEach(() => {
  setActivePinia(createPinia())
  http = installFetch()
})

afterEach(() => http.restore())

test('Stop cancels a non-streaming request and is not reported as a failure', async () => {
  const chat = useChatStore()
  chat.streamingEnabled = false

  const sending = chat.send('hello')
  const request = http.take('/api/chat')
  assert.equal(chat.pending, true)

  chat.cancel()

  assert.equal(request.aborted, true, 'the plain HTTP request must receive the abort signal')
  assert.equal(chat.pending, false)
  await sending

  assert.equal(chat.error, undefined, 'a user cancellation is not a server error')
  assert.deepEqual(chat.messages.map((m) => m.role), ['user'])
  assert.equal(chat.abort, undefined)
})

test('a reply that wins the race against Stop does not land in the new conversation', async () => {
  const chat = useChatStore()
  chat.streamingEnabled = false

  const sending = chat.send('hello')
  // The server answered, but the user pressed Stop and started over before the body was parsed.
  http.take('/api/chat').respondJson(chatResponse('m-1', 'late answer'))
  chat.cancel()
  chat.reset()
  await sending

  assert.deepEqual(chat.messages, [])
  assert.equal(chat.conversationId, undefined)
  assert.equal(chat.selectedMessageId, undefined)
  assert.equal(chat.error, undefined)
})

test('a superseded request cannot finish the request that replaced it', async () => {
  const chat = useChatStore()
  chat.streamingEnabled = false

  const first = chat.send('one')
  http.take('/api/chat')
  chat.cancel()

  const second = chat.send('two')
  const secondRequest = http.take('/api/chat')
  await first
  assert.equal(chat.pending, true, 'the abandoned request must not clear the new request state')

  secondRequest.respondJson(chatResponse('m-2', 'answer two'))
  await second

  assert.equal(chat.pending, false)
  assert.equal(chat.messages.at(-1)!.content, 'answer two')
  assert.equal(chat.error, undefined)
})

test('streaming Stop keeps the partial text as an unfinished answer without citations', async () => {
  const chat = useChatStore()

  const sending = chat.send('hello')
  const stream = http.take('/api/chat/stream').respondWithStream()
  await flush()
  stream.send('status', { stage: 'generating' })
  stream.send('delta', { text: 'partial ' })
  stream.send('delta', { text: 'answer' })
  await flush()
  assert.equal(chat.messages.at(-1)!.content, 'partial answer')

  chat.cancel()
  await sending

  const last = chat.messages.at(-1)!
  assert.equal(last.content, 'partial answer')
  assert.equal(last.cancelled, true)
  assert.equal(last.streaming, false)
  assert.deepEqual(last.citations, [])
  assert.equal(last.grounding, undefined, 'a stopped answer carries no verified grounding')
  assert.equal(chat.error, undefined)
  assert.equal(chat.pending, false)
  assert.equal(chat.stage, undefined)
  assert.equal(stream.locked, false, 'the reader must be released when the stream is abandoned')
})

test('New chat during a stream clears the conversation and the late failure changes nothing', async () => {
  const chat = useChatStore()

  const sending = chat.send('hello')
  const stream = http.take('/api/chat/stream').respondWithStream()
  await flush()
  stream.send('delta', { text: 'partial' })
  await flush()

  chat.reset()
  await sending

  assert.deepEqual(chat.messages, [])
  assert.equal(chat.conversationId, undefined)
  assert.equal(chat.error, undefined)
  assert.equal(chat.pending, false)
})

test('a completed stream applies the final answer and releases the reader', async () => {
  const chat = useChatStore()

  const sending = chat.send('hello')
  const stream = http.take('/api/chat/stream').respondWithStream()
  await flush()
  stream.send('delta', { text: 'draft' })
  stream.send('final', { response: chatResponse('m-1', 'final answer') })
  stream.close()
  await sending

  assert.equal(chat.messages.at(-1)!.id, 'm-1')
  assert.equal(chat.messages.at(-1)!.content, 'final answer')
  assert.equal(chat.selectedMessageId, 'm-1')
  assert.equal(chat.conversationId, 'c-1')
  assert.equal(chat.pending, false)
  assert.equal(stream.locked, false)
})

test('a server error is still surfaced as an error message', async () => {
  const chat = useChatStore()

  const sending = chat.send('hello')
  const stream = http.take('/api/chat/stream').respondWithStream()
  await flush()
  stream.send('error', { message: 'the index is unavailable' })
  stream.close()
  await sending

  assert.equal(chat.error, 'the index is unavailable')
  assert.equal(chat.messages.at(-1)!.error, true)
  assert.equal(chat.messages.at(-1)!.cancelled, undefined)
  assert.equal(stream.locked, false)
})

test('consecutive questions get distinct message ids', async (t) => {
  // Frozen clock: ids must come from the request counter, not from the wall clock, or two questions
  // inside the same millisecond would share a bubble.
  t.mock.timers.enable({ apis: ['Date'] })
  const chat = useChatStore()
  chat.streamingEnabled = false

  const first = chat.send('one')
  http.take('/api/chat').respondJson(chatResponse('m-1', 'answer one'))
  await first
  const second = chat.send('two')
  http.take('/api/chat').respondJson(chatResponse('m-2', 'answer two'))
  await second

  const ids = chat.messages.map((m) => m.id)
  assert.equal(new Set(ids).size, ids.length, `ids must be unique: ${ids.join(', ')}`)
})
