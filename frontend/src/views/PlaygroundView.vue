<script setup lang="ts">
import { ref } from 'vue'
import { api, ApiError } from '@/api/client'
import type { RetrievalMode, RetrievalResult } from '@/api/types'

const query = ref('')
const topK = ref(8)
const mode = ref<RetrievalMode>('HYBRID')
const neighbours = ref(0)
const result = ref<RetrievalResult | undefined>()
const error = ref<string | undefined>()
const busy = ref(false)

async function search() {
  const text = query.value.trim()
  if (!text || busy.value) return
  busy.value = true
  error.value = undefined
  try {
    result.value = await api.retrievalSearch({ query: text, topK: topK.value, mode: mode.value, expandNeighbours: neighbours.value })
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : 'Search failed'
  } finally {
    busy.value = false
  }
}

const fmt = (n?: number | null) => (n == null ? '—' : n.toFixed(3))
</script>

<template>
  <div>
    <p class="muted">Run retrieval without the language model to see which passages would be handed to it, with vector, BM25 and fused scores.</p>
    <form class="card" style="display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 16px" @submit.prevent="search">
      <input v-model="query" placeholder="Query…" style="flex: 1; min-width: 260px; padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px; font: inherit" />
      <label class="small">mode
        <select v-model="mode" style="margin-left: 4px">
          <option>HYBRID</option><option>VECTOR</option><option>TEXT</option>
        </select>
      </label>
      <label class="small">top-k <input v-model.number="topK" type="number" min="1" max="50" style="width: 60px; margin-left: 4px" /></label>
      <label class="small" title="Chunks shown on each side of every hit as continuation context">neighbours <input v-model.number="neighbours" type="number" min="0" max="5" style="width: 55px; margin-left: 4px" /></label>
      <button class="btn primary" type="submit" :disabled="busy || !query.trim()">Search</button>
    </form>

    <div v-if="error" class="banner bad">{{ error }}</div>

    <div v-if="result" class="card">
      <div class="small muted" style="margin-bottom: 10px">
        {{ result.mode }} · top-k {{ result.topK }} · candidates {{ result.candidates }} · {{ result.hits.length }} hits ·
        best cosine {{ fmt(result.maxVectorScore) }} ·
        <strong :style="result.evidenceSufficient ? 'color: var(--ok)' : 'color: var(--warn)'">{{ result.evidenceSufficient ? 'sufficient' : 'insufficient' }}</strong> ·
        vector {{ result.timings.vectorMs }} ms, text {{ result.timings.textMs }} ms, fusion {{ result.timings.fusionMs }} ms, total {{ result.timings.totalMs }} ms ·
        trace <code>{{ result.traceId }}</code>
      </div>
      <div v-if="result.expansion" class="small muted" style="margin-bottom: 10px">
        searched again ({{ result.expansion.strategy }}, +{{ result.expansion.addedHits }} hits, {{ result.expansion.tookMs }} ms):
        <span v-for="q in result.expansion.queries" :key="q"><code>{{ q }}</code> </span>
      </div>
      <p v-if="!result.hits.length" class="muted">No hits.</p>
      <table v-else class="docs">
        <thead><tr><th>#</th><th>Document › section</th><th>cos</th><th>BM25</th><th>fused</th><th>Text</th></tr></thead>
        <tbody>
          <tr v-for="h in result.hits" :key="h.chunkId">
            <td>{{ h.rank }}</td>
            <td><div>{{ h.provenance.documentTitle }}</div><div class="small muted">{{ h.provenance.sectionPath.join(' › ') || '—' }} · <code>{{ h.chunkId }}</code></div></td>
            <td>{{ fmt(h.vectorScore) }}</td>
            <td>{{ fmt(h.textScore) }}</td>
            <td>{{ h.fusedScore.toFixed(4) }}</td>
            <td class="small" style="white-space: pre-wrap; max-width: 520px">{{ h.text }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
