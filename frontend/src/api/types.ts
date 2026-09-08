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
  recentFailures: RecentFailure[]
}

export interface RecentFailure { documentId: string; stage: string; message: string; at: string }

export type RetrievalMode = 'HYBRID' | 'VECTOR' | 'TEXT'

export interface RetrievalQuery {
  query: string
  topK?: number
  mode?: RetrievalMode
  documentIds?: string[]
  /** Chunks shown on each side of every hit; omit for the configured default. */
  expandNeighbours?: number
}

export type ExpansionStrategy = 'NONE' | 'NEIGHBOURS' | 'REWRITE' | 'HYDE'

/** What a second, widened retrieval pass did; absent on a result with a single pass. */
export interface SearchExpansion {
  strategy: ExpansionStrategy
  queries: string[]
  addedHits: number
  tookMs: number
}

/** What a retrieval per part of the question did; absent on a result that was searched as a whole. */
export interface QuestionDecomposition {
  subQuestions: string[]
  addedHits: number
  tookMs: number
}

export interface Provenance {
  documentId: string
  documentTitle: string
  version: number
  sectionTitle: string
  sectionPath: string[]
  chunkId: string
  sequenceNumber: number
}

export interface RetrievedChunk {
  chunkId: string
  text: string
  provenance: Provenance
  vectorScore?: number | null
  textScore?: number | null
  fusedScore: number
  rank: number
  neighbourOf?: string | null
}

export interface RetrievalResult {
  traceId: string
  query: string
  mode: RetrievalMode
  topK: number
  candidates: number
  hits: RetrievedChunk[]
  evidenceSufficient: boolean
  maxVectorScore: number
  timings: { vectorMs: number; textMs: number; fusionMs: number; totalMs: number }
  at: string
  expansion?: SearchExpansion | null
  decomposition?: QuestionDecomposition | null
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

export type AnswerMode = 'DETERMINISTIC' | 'AGENTIC'

export interface ChatRequest {
  conversationId?: string
  message: string
  options?: { topK?: number; documentIds?: string[]; includeDiagnostics?: boolean; mode?: AnswerMode }
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
