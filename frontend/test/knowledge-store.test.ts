import assert from 'node:assert/strict'
import { afterEach, beforeEach, test } from 'node:test'
import { createPinia, setActivePinia } from 'pinia'
import { useKnowledgeStore } from '../src/stores/knowledge.ts'
import { document, knowledgeBaseStatus, page, statusView } from './support/fixtures.ts'
import { flush, installEventSource, installFetch } from './support/harness.ts'

let http: ReturnType<typeof installFetch>
let events: ReturnType<typeof installEventSource>

beforeEach(() => {
  setActivePinia(createPinia())
  http = installFetch()
  events = installEventSource()
})

afterEach(() => {
  http.restore()
  events.restore()
})

/** Answers the two requests one refresh() issues. */
function answerSnapshot(documents: ReturnType<typeof document>[], count = documents.length) {
  http.take('/api/documents').respondJson(page(documents))
  http.take('/api/knowledge-base/status').respondJson(knowledgeBaseStatus(count))
}

test('a snapshot that completes out of order does not roll the table back', async () => {
  const kb = useKnowledgeStore()

  const stale = kb.refresh()
  const staleDocuments = http.take('/api/documents')
  const staleStatus = http.take('/api/knowledge-base/status')

  const fresh = kb.refresh()
  answerSnapshot([document('b')], 2)
  await fresh
  assert.deepEqual(kb.documents.map((d) => d.id), ['b'])

  staleDocuments.respondJson(page([document('a')]))
  staleStatus.respondJson(knowledgeBaseStatus(1))
  await stale

  assert.deepEqual(kb.documents.map((d) => d.id), ['b'], 'the older request must not overwrite newer data')
  assert.equal(kb.status?.documentCount, 2)
  assert.equal(kb.loading, false, 'loading ends when the last request in flight ends')
})

test('a failure of a superseded snapshot is not shown as the current error', async () => {
  const kb = useKnowledgeStore()

  const stale = kb.refresh()
  const staleDocuments = http.take('/api/documents')
  const staleStatus = http.take('/api/knowledge-base/status')

  const fresh = kb.refresh()
  answerSnapshot([document('a')])
  await fresh

  staleDocuments.fail(new TypeError('network down'))
  staleStatus.respondJson(knowledgeBaseStatus(1))
  await stale

  assert.equal(kb.error, undefined)
  assert.equal(kb.loading, false)
})

test('a live update invalidates the snapshot that was already in flight', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const kb = useKnowledgeStore()
  kb.documents = [document('a', 'PARSING')]
  kb.connect()

  const pending = kb.refresh()
  const documents = http.take('/api/documents')
  const status = http.take('/api/knowledge-base/status')

  // The event happens after the snapshot was requested, so the snapshot predates it.
  events.instances[0]!.emit('document-status', statusView('a', 'READY'))
  assert.equal(kb.documents[0]!.status, 'READY')

  documents.respondJson(page([document('a', 'PARSING')]))
  status.respondJson(knowledgeBaseStatus(1))
  await pending

  assert.equal(kb.documents[0]!.status, 'READY', 'the stale snapshot must not resurrect the old status')
})

test('bursts of live updates are coalesced into one reconciling snapshot', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const kb = useKnowledgeStore()
  kb.connect()
  const source = events.instances[0]!

  for (const status of ['PARSING', 'CHUNKING', 'INDEXING', 'READY'] as const) source.emit('document-status', statusView('a', status))
  assert.equal(http.outstanding('/api/').length, 0, 'no request before the coalescing window elapses')

  t.mock.timers.tick(250)
  await flush()

  assert.equal(http.outstanding('/api/documents').length, 1)
  assert.equal(http.outstanding('/api/knowledge-base/status').length, 1)
  assert.equal(kb.live, true)
})

test('a scheduled refresh is dropped when the view disconnects', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const kb = useKnowledgeStore()
  kb.connect()
  events.instances[0]!.emit('document-status', statusView('a', 'READY'))

  kb.disconnect()
  t.mock.timers.tick(250)
  await flush()

  assert.equal(http.outstanding('/api/').length, 0)
  assert.equal(events.instances[0]!.closed, true)
})

test('an explicit refresh absorbs the scheduled one', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const kb = useKnowledgeStore()
  kb.connect()
  events.instances[0]!.emit('document-status', statusView('a', 'READY'))

  const explicit = kb.refresh()
  answerSnapshot([document('a')])
  await explicit

  t.mock.timers.tick(250)
  await flush()

  assert.equal(http.outstanding('/api/').length, 0, 'the timer must not fire a second snapshot')
  assert.deepEqual(kb.documents.map((d) => d.id), ['a'])
})
