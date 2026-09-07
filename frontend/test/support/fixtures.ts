import type { ChatResponse, Document, DocumentPage, DocumentStatus, DocumentStatusView, KnowledgeBaseStatus } from '../../src/api/types.ts'

export function document(id: string, status: DocumentStatus = 'READY', updatedAt = '2026-01-01T00:00:00Z'): Document {
  return {
    id,
    title: id,
    originalFilename: `${id}.md`,
    mediaType: 'text/markdown',
    sizeBytes: 10,
    contentHash: `hash-${id}`,
    version: 1,
    uploadedAt: '2026-01-01T00:00:00Z',
    updatedAt,
    status,
    chunkCount: status === 'READY' ? 3 : undefined,
  }
}

export function page(items: Document[]): DocumentPage {
  return { items, total: items.length, page: 0, size: 100 }
}

export function knowledgeBaseStatus(documentCount = 1): KnowledgeBaseStatus {
  return {
    documentCount,
    documentsByStatus: { READY: documentCount },
    index: { state: 'READY', chunkCount: documentCount * 3, documentCount, persistent: true },
    queue: { pending: 0 },
    embedding: { provider: 'onnx', model: 'embeddinggemma-300m', dimensions: 768, fingerprint: 'fp-1' },
    recentFailures: [],
  }
}

export function statusView(documentId: string, status: DocumentStatus, updatedAt = '2026-01-01T00:05:00Z'): DocumentStatusView {
  return { documentId, status, version: 1, updatedAt }
}

export function chatResponse(messageId: string, answer: string, conversationId = 'c-1'): ChatResponse {
  return {
    conversationId,
    messageId,
    answer,
    grounding: 'GROUNDED',
    citations: [],
    timings: { retrievalMs: 1, llmMs: 2, totalMs: 3 },
    retrievalTraceId: 't-1',
  }
}
