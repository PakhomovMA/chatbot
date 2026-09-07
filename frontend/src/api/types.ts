// Mirrors the backend DTOs (docs/system-plan.md §8). Only these shapes cross the API boundary.

export type DocumentStatus = 'UPLOADED' | 'PARSING' | 'CHUNKING' | 'INDEXING' | 'READY' | 'FAILED' | 'PENDING_REINDEX'
export type IndexState = 'EMPTY' | 'READY' | 'INCOMPATIBLE' | 'REBUILDING'
export type Grounding = 'GROUNDED' | 'PARTIAL' | 'INSUFFICIENT_EVIDENCE'

export interface DocumentError { stage: string; message: string; at: string }

export interface Document {
  id: string
  title: string
  originalFilename: string
  mediaType: string
  sizeBytes: number
  contentHash: string
  version: number
  uploadedAt: string
  updatedAt: string
  status: DocumentStatus
  statusMessage?: string
  error?: DocumentError
  chunkCount?: number
  indexedAt?: string
  embeddingFingerprint?: string
}

export interface DocumentPage { items: Document[]; total: number; page: number; size: number }
export interface UploadResponse { documentId: string; status: DocumentStatus; version: number; duplicate: boolean }
export interface DocumentStatusView {
  documentId: string
  status: DocumentStatus
  version: number
  statusMessage?: string
  error?: DocumentError
  updatedAt: string
}

export interface KnowledgeBaseStatus {
  documentCount: number
  documentsByStatus: Partial<Record<DocumentStatus, number>>
  index: {
    state: IndexState
    chunkCount: number
    documentCount: number
    path?: string
    persistent: boolean
    incompatibilityReason?: string
    recoveredFrom?: string
  }
  queue: { pending: number; activeDocumentId?: string }
  embedding: { provider: string; model: string; dimensions: number; fingerprint: string }
}

export interface Citation {
  marker: number
  documentId: string
  documentTitle: string
  sectionTitle: string
  sectionPath: string[]
  chunkId: string
  quote: string
  score: number
}

export interface ChatTimings { retrievalMs: number; llmMs: number; totalMs: number }

export interface ChatRequest {
  conversationId?: string
  message: string
  options?: { topK?: number; documentIds?: string[]; includeDiagnostics?: boolean }
}

export interface ChatResponse {
  conversationId: string
  messageId: string
  answer: string
  grounding: Grounding
  citations: Citation[]
  notes?: string
  timings: ChatTimings
  retrievalTraceId: string
}

export interface ProblemDetail { title?: string; detail?: string; status?: number; reason?: string; indexState?: string }
