<script setup lang="ts">
import type { Citation } from '@/api/types'
import { renderQuote } from '@/utils/markdown'

defineProps<{ citations: Citation[]; highlighted?: number }>()
</script>

<template>
  <section class="card citations">
    <h3 style="margin-top: 0">Sources</h3>
    <p v-if="!citations.length" class="muted small">Select an answer to see the passages it is based on.</p>
    <article v-for="c in citations" :key="c.marker" class="citation" :id="`citation-${c.marker}`"
             :style="highlighted === c.marker ? 'background: var(--accent-soft); border-radius: 6px; padding: 10px 8px' : ''">
      <div class="where">
        <strong>[{{ c.marker }}]</strong> {{ c.documentTitle }}<span v-if="c.sectionPath.length"> › {{ c.sectionPath.join(' › ') }}</span>
        <span class="muted"> · score {{ c.score.toFixed(3) }}</span>
      </div>
      <blockquote v-html="renderQuote(c.quote)"></blockquote>
    </article>
  </section>
</template>
