<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { useKnowledgeStore } from '@/stores/knowledge'
import StatusBadge from '@/components/StatusBadge.vue'
import UploadDropzone from '@/components/UploadDropzone.vue'
import { formatBytes, formatTime } from '@/utils/format'

const kb = useKnowledgeStore()

onMounted(() => {
  void kb.refresh()
  kb.connect()
})
onUnmounted(() => kb.disconnect())

async function remove(id: string, title: string) {
  if (window.confirm(`Delete "${title}" from the knowledge base?`)) await kb.remove(id)
}

async function rebuild() {
  if (window.confirm('Rebuild the whole index and re-ingest every document?')) await kb.reindexAll()
}
</script>

<template>
  <div>
    <div class="kb-header">
      <div class="kb-stats" v-if="kb.status">
        <div><strong>{{ kb.status.documentCount }}</strong> documents</div>
        <div><strong>{{ kb.status.index.chunkCount }}</strong> chunks</div>
        <div><strong>{{ kb.status.queue.pending + (kb.status.queue.activeDocumentId ? 1 : 0) }}</strong> in queue</div>
        <div>index <StatusBadge :value="kb.status.index.state" /></div>
        <div class="small muted">embedding {{ kb.status.embedding.model }} · {{ kb.status.embedding.dimensions }}d · <code>{{ kb.status.embedding.fingerprint }}</code></div>
      </div>
      <div class="actions">
        <span class="small muted" :title="kb.live ? 'Live status updates' : 'Live updates disconnected'">{{ kb.live ? '● live' : '○ polling' }}</span>
        <button class="btn" :disabled="kb.loading" @click="kb.refresh()">Refresh</button>
        <button class="btn danger" :disabled="kb.loading" @click="rebuild">Rebuild index</button>
      </div>
    </div>

    <div v-if="kb.status?.index.state === 'INCOMPATIBLE'" class="banner bad">
      The index was built with a different embedding model or chunking configuration and cannot be searched.
      <span class="small">{{ kb.status.index.incompatibilityReason }}</span> — use <em>Rebuild index</em>.
    </div>
    <div v-else-if="kb.status?.index.recoveredFrom" class="banner warn">
      A corrupt index was moved to <code>{{ kb.status.index.recoveredFrom }}</code> at startup; documents are being re-indexed.
    </div>
    <div v-if="kb.status?.recentFailures.length" class="banner warn">
      <strong>Recent ingestion failures</strong>
      <div v-for="f in kb.status.recentFailures.slice(0, 5)" :key="f.documentId + f.at" class="small">
        {{ formatTime(f.at) }} · {{ f.documentId }} · {{ f.stage }}: {{ f.message }}
      </div>
    </div>
    <div v-if="kb.error" class="banner bad">{{ kb.error }}</div>
    <div v-else-if="kb.notice" class="banner">{{ kb.notice }}</div>

    <UploadDropzone @files="kb.upload" />
    <p v-if="kb.uploading" class="muted small">Uploading {{ kb.uploading }} file(s)…</p>

    <table class="docs">
      <thead>
        <tr><th>Title</th><th>File</th><th>Status</th><th>Chunks</th><th>Updated</th><th></th></tr>
      </thead>
      <tbody>
        <tr v-if="!kb.documents.length"><td colspan="6" class="muted">No documents yet. Upload something to get started.</td></tr>
        <tr v-for="d in kb.documents" :key="d.id">
          <td><div>{{ d.title }}</div><div class="small muted">v{{ d.version }} · {{ d.id }}</div></td>
          <td><div>{{ d.originalFilename }}</div><div class="small muted">{{ d.mediaType }} · {{ formatBytes(d.sizeBytes) }}</div></td>
          <td>
            <StatusBadge :value="d.status" />
            <div v-if="d.error" class="small" style="color: var(--bad)">{{ d.error.stage }}: {{ d.error.message }}</div>
            <div v-else-if="d.statusMessage" class="small muted">{{ d.statusMessage }}</div>
          </td>
          <td>{{ d.chunkCount ?? '—' }}</td>
          <td class="small">{{ formatTime(d.updatedAt) }}</td>
          <td class="actions">
            <button class="btn small" :title="'Re-parse and re-index'" @click="kb.reindex(d.id)">Re-index</button>
            <button class="btn small danger" @click="remove(d.id, d.title)">Delete</button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
