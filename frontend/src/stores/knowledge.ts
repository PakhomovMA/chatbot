import { defineStore } from 'pinia'
import { api, ApiError } from '@/api/client'
import type { Document, DocumentStatusView, KnowledgeBaseStatus } from '@/api/types'

/** How long live updates are collected before one reconciling snapshot request is sent. */
const REFRESH_COALESCE_MS = 250

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
    /**
     * Generation of the newest snapshot request. Responses to older requests — HTTP completions can
     * arrive in any order, and a request issued before a live update returns pre-update data — are
     * dropped instead of overwriting documents, status, error or loading.
     */
    refreshGeneration: 0,
    refreshTimer: undefined as ReturnType<typeof setTimeout> | undefined,
    /** Snapshot requests still in flight, including superseded ones; `loading` mirrors it. */
    refreshesInFlight: 0,
  }),
  actions: {
    async refresh() {
      this.cancelScheduledRefresh()
      const generation = ++this.refreshGeneration
      this.refreshesInFlight++
      this.loading = true
      try {
        const [page, status] = await Promise.all([api.listDocuments(), api.knowledgeBaseStatus()])
        if (generation !== this.refreshGeneration) return
        this.documents = page.items
        this.status = status
        this.error = undefined
      } catch (e) {
        if (generation !== this.refreshGeneration) return
        this.error = e instanceof ApiError ? e.message : 'Cannot reach the backend.'
      } finally {
        // Superseded requests still hold a connection; loading ends when the last one does.
        this.refreshesInFlight--
        if (this.refreshesInFlight === 0) this.loading = false
      }
    },
    /**
     * Invalidates the in-flight snapshot — it was issued before the update that triggered this call —
     * and asks for one reconciling snapshot. Repeated calls inside the window share that request.
     */
    scheduleRefresh() {
      this.refreshGeneration++
      if (this.refreshTimer !== undefined) return
      this.refreshTimer = setTimeout(() => {
        this.refreshTimer = undefined
        void this.refresh()
      }, REFRESH_COALESCE_MS)
    },
    cancelScheduledRefresh() {
      if (this.refreshTimer === undefined) return
      clearTimeout(this.refreshTimer)
      this.refreshTimer = undefined
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
          // The event carries only the status; chunk counts, the queue and documents this client has
          // never listed still come from a snapshot.
          this.scheduleRefresh()
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
      this.cancelScheduledRefresh()
      this.unsubscribe?.()
      this.unsubscribe = undefined
      this.live = false
    },
  },
})
