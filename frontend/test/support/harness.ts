// Controllable transport doubles: every request stays pending until the test resolves it, so the
// interleavings the store must survive (out-of-order completions, a live update overtaking a
// snapshot, a reply that wins the race against Stop) are expressed as an explicit order, not a sleep.

export function abortError(): DOMException {
  return new DOMException('This operation was aborted.', 'AbortError')
}

/** One `fetch` the code under test issued, still waiting for the test to decide its outcome. */
export interface PendingRequest {
  url: string
  init: RequestInit
  /** True once the request's signal was aborted. */
  aborted: boolean
  /** Answers with a JSON body. Ignored once the request has already settled. */
  respondJson(body: unknown, status?: number): void
  /** Answers with an open `text/event-stream` body the test feeds event by event. */
  respondWithStream(): PendingStream
  fail(error: unknown): void
}

export interface PendingStream {
  send(event: string, data: unknown): void
  close(): void
  /** False once the consumer released its reader — what the `finally` in streamChat must guarantee. */
  readonly locked: boolean
}

export interface FetchHarness {
  /** Every request seen, in order, including already answered ones. */
  readonly seen: readonly PendingRequest[]
  /** Removes and returns the oldest unanswered request whose URL contains `fragment`. */
  take(fragment: string): PendingRequest
  /** Unanswered requests whose URL contains `fragment`. */
  outstanding(fragment: string): PendingRequest[]
  restore(): void
}

export function installFetch(): FetchHarness {
  const original = globalThis.fetch
  const seen: PendingRequest[] = []
  const queue: PendingRequest[] = []

  globalThis.fetch = ((input: RequestInfo | URL, init: RequestInit = {}) => {
    let settle!: (response: Response) => void
    let fail!: (error: unknown) => void
    const promise = new Promise<Response>((resolve, reject) => {
      settle = resolve
      fail = reject
    })
    let settled = false

    const request: PendingRequest = {
      url: String(input),
      init,
      aborted: false,
      respondJson(body: unknown, status = 200) {
        if (settled) return
        settled = true
        remove(request)
        settle(
          status === 204
            ? new Response(null, { status })
            : new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }),
        )
      },
      respondWithStream() {
        if (settled) throw new Error(`${request.url} was already answered`)
        settled = true
        remove(request)
        const encoder = new TextEncoder()
        let controller!: ReadableStreamDefaultController<Uint8Array>
        const stream = new ReadableStream<Uint8Array>({
          start(c) {
            controller = c
          },
        })
        // A real fetch errors the body when the signal fires; the consumer's pending read rejects.
        init.signal?.addEventListener('abort', () => {
          try {
            controller.error(abortError())
          } catch {
            /* already closed */
          }
        })
        settle(new Response(stream, { status: 200, headers: { 'content-type': 'text/event-stream' } }))
        return {
          send(event: string, data: unknown) {
            controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`))
          },
          close() {
            controller.close()
          },
          get locked() {
            return stream.locked
          },
        }
      },
      fail(error: unknown) {
        if (settled) return
        settled = true
        remove(request)
        fail(error)
      },
    }

    const signal = init.signal
    if (signal) {
      const onAbort = () => {
        request.aborted = true
        request.fail(abortError())
      }
      if (signal.aborted) onAbort()
      else signal.addEventListener('abort', onAbort, { once: true })
    }

    seen.push(request)
    if (!settled) queue.push(request)
    return promise
  }) as typeof fetch

  function remove(request: PendingRequest) {
    const index = queue.indexOf(request)
    if (index >= 0) queue.splice(index, 1)
  }

  return {
    seen,
    take(fragment: string): PendingRequest {
      const request = queue.find((r) => r.url.includes(fragment))
      if (!request) throw new Error(`no pending request for "${fragment}" (pending: ${queue.map((r) => r.url).join(', ') || 'none'})`)
      remove(request)
      return request
    },
    outstanding(fragment: string) {
      return queue.filter((r) => r.url.includes(fragment))
    },
    restore() {
      globalThis.fetch = original
    },
  }
}

/** A live-updates connection the test drives by hand. */
export class FakeEventSource {
  url: string
  closed = false
  onopen: (() => void) | undefined
  onerror: (() => void) | undefined
  private readonly listeners = new Map<string, ((event: unknown) => void)[]>()

  constructor(url: string) {
    this.url = url
  }

  addEventListener(type: string, listener: (event: unknown) => void) {
    const existing = this.listeners.get(type)
    if (existing) existing.push(listener)
    else this.listeners.set(type, [listener])
  }

  close() {
    this.closed = true
  }

  emit(type: string, data: unknown) {
    for (const listener of this.listeners.get(type) ?? []) listener({ data: JSON.stringify(data) })
  }
}

export function installEventSource(): { instances: FakeEventSource[]; restore(): void } {
  const original = (globalThis as Record<string, unknown>).EventSource
  const instances: FakeEventSource[] = []
  ;(globalThis as Record<string, unknown>).EventSource = class extends FakeEventSource {
    constructor(url: string) {
      super(url)
      instances.push(this)
    }
  }
  return {
    instances,
    restore() {
      ;(globalThis as Record<string, unknown>).EventSource = original
    },
  }
}

/** Lets every already-scheduled microtask and I/O callback run. */
export async function flush(times = 3): Promise<void> {
  for (let i = 0; i < times; i++) await new Promise((resolve) => setImmediate(resolve))
}
