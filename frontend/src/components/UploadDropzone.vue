<script setup lang="ts">
import { ref } from 'vue'

const emit = defineEmits<{ files: [files: File[]] }>()
const active = ref(false)
const input = ref<HTMLInputElement | null>(null)

function onDrop(event: DragEvent) {
  active.value = false
  const files = Array.from(event.dataTransfer?.files ?? [])
  if (files.length) emit('files', files)
}

function onPick(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files ?? [])
  if (files.length) emit('files', files)
  if (input.value) input.value.value = ''
}
</script>

<template>
  <div class="dropzone" :class="{ active }" @dragover.prevent="active = true" @dragleave="active = false" @drop.prevent="onDrop"
       @click="input?.click()">
    <div><strong>Drop documents here</strong> or click to choose</div>
    <div class="small">Markdown, text, HTML, PDF, DOCX · up to 20 MB each</div>
    <input ref="input" type="file" multiple hidden accept=".md,.markdown,.txt,.html,.htm,.pdf,.docx" @change="onPick" />
  </div>
</template>
