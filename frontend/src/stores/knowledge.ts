import { defineStore } from 'pinia'
import { api, ApiError } from '@/api/client'
import type { Document, DocumentStatusView, KnowledgeBaseStatus } from '@/api/types'

export const useKnowledgeStore = defineStore('knowledge', {
  state: () => ({
    documents: [] as Document[],
    status: undefined as KnowledgeBaseStatus | undefined,
    loading: false,
    uploading: 0,
    error: undefined as string | undefined,
    notice: undefined as string | undefined,
    live: false,
    unsubscribe: undefined as (() => void) | undefined,
  }),
  actions: {
    async refresh() {
      this.loading = true
      try {
        const [page, status] = await Promise.all([api.listDocuments(), api.knowledgeBaseStatus()])
        this.documents = page.items
        this.status = status
        this.error = undefined
      } catch (e) {
        this.error = e instanceof ApiError ? e.message : 'Cannot reach the backend.'
      } finally {
        this.loading = false
      }
    },
    async upload(files: File[]) {
      for (const file of files) {
        this.uploading++
        try {
          const result = await api.uploadDocument(file)
          this.notice = result.duplicate
            ? `"${file.name}" was already in the knowledge base.`
            : `"${file.name}" uploaded, indexing…`
          this.error = undefined
        } catch (e) {
          this.error = e instanceof ApiError ? `${file.name}: ${e.message}` : `${file.name}: upload failed`
        } finally {
          this.uploading--
        }
      }
      await this.refresh()
    },
    async remove(id: string) {
      try {
        await api.deleteDocument(id)
        this.documents = this.documents.filter((d) => d.id !== id)
        this.error = undefined
      } catch (e) {
        this.error = e instanceof ApiError ? e.message : 'Delete failed'
      }
      await this.refresh()
    },
    async reindex(id: string) {
      try {
        await api.reindexDocument(id)
        this.error = undefined
      } catch (e) {
        this.error = e instanceof ApiError ? e.message : 'Re-index failed'
      }
      await this.refresh()
    },
    async reindexAll() {
      try {
        const result = await api.reindexAll()
        this.notice = `Index rebuild started, ${result.queued} document(s) queued.`
        this.error = undefined
      } catch (e) {
        this.error = e instanceof ApiError ? e.message : 'Rebuild failed'
      }
      await this.refresh()
    },
    connect() {
      if (this.unsubscribe) return
      this.unsubscribe = api.subscribeDocumentEvents(
        (view: DocumentStatusView) => {
          this.live = true
          const index = this.documents.findIndex((d) => d.id === view.documentId)
          if (index >= 0) {
            const current = this.documents[index]!
            this.documents[index] = {
              ...current,
              status: view.status,
              version: view.version,
              statusMessage: view.statusMessage,
              error: view.error,
              updatedAt: view.updatedAt,
            }
          }
          if (view.status === 'READY' || view.status === 'FAILED') {
            void this.refresh()
          }
        },
        () => {
          this.live = false
        },
        () => {
          this.live = true
        },
      )
    },
    disconnect() {
      this.unsubscribe?.()
      this.unsubscribe = undefined
      this.live = false
    },
  },
})
