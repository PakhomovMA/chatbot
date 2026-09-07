<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <div class="brand">Knowledge Assistant</div>
      <nav>
        <RouterLink to="/chat">Chat</RouterLink>
        <RouterLink to="/knowledge">Knowledge Base</RouterLink>
        <RouterLink to="/playground">Retrieval</RouterLink>
      </nav>
    </header>
    <main class="content">
      <RouterView />
    </main>
  </div>
</template>
