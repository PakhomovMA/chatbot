import type {
  ChatRequest,
  ChatResponse,
  Document,
  DocumentPage,
  DocumentStatus,
  DocumentStatusView,
  KnowledgeBaseStatus,
  ProblemDetail,
  RetrievalQuery,
  RetrievalResult,
  UploadResponse,
} from './types'

/** Error carrying the RFC 9457 problem detail returned by the backend. */
export class ApiError extends Error {
  constructor(readonly status: number, readonly problem: ProblemDetail | undefined, message: string) {
    super(message)
  }
}

async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(url, init)
  if (!response.ok) {
    let problem: ProblemDetail | undefined
    try {
      problem = (await response.json()) as ProblemDetail
    } catch {
      problem = undefined
    }
    throw new ApiError(response.status, problem, problem?.detail ?? problem?.title ?? `${response.status} ${response.statusText}`)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

const json = (body: unknown, method = 'POST'): RequestInit => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})

export const api = {
  chat: (body: ChatRequest) => request<ChatResponse>('/api/chat', json(body)),

  listDocuments: (params: { status?: DocumentStatus; q?: string; page?: number; size?: number } = {}) => {
    const query = new URLSearchParams()
    if (params.status) query.set('status', params.status)
    if (params.q) query.set('q', params.q)
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 100))
    return request<DocumentPage>(`/api/documents?${query}`)
  },
  getDocument: (id: string) => request<Document>(`/api/documents/${encodeURIComponent(id)}`),
  uploadDocument: (file: File, title?: string) => {
    const form = new FormData()
    form.append('file', file, file.name)
    if (title) form.append('title', title)
    return request<UploadResponse>('/api/documents', { method: 'POST', body: form })
  },
  deleteDocument: (id: string) => request<void>(`/api/documents/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  reindexDocument: (id: string) => request<DocumentStatusView>(`/api/documents/${encodeURIComponent(id)}/reindex`, { method: 'POST' }),

  knowledgeBaseStatus: () => request<KnowledgeBaseStatus>('/api/knowledge-base/status'),
  retrievalSearch: (body: RetrievalQuery) => request<RetrievalResult>('/api/retrieval/search', json(body)),
  reindexAll: () => request<{ queued: number }>('/api/knowledge-base/reindex', { method: 'POST' }),

  /** Live document status changes; returns a function that closes the stream. */
  subscribeDocumentEvents: (
    onEvent: (view: DocumentStatusView) => void,
    onError?: () => void,
    onOpen?: () => void,
  ): (() => void) => {
    const source = new EventSource('/api/knowledge-base/events')
    source.addEventListener('document-status', (event) => {
      onEvent(JSON.parse((event as MessageEvent).data) as DocumentStatusView)
    })
    source.onopen = () => onOpen?.()
    source.onerror = () => onError?.()
    return () => source.close()
  },
}
