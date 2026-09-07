import { createRouter, createWebHistory } from 'vue-router'
import ChatView from './views/ChatView.vue'
import KnowledgeView from './views/KnowledgeView.vue'
import PlaygroundView from './views/PlaygroundView.vue'

// Keep this list in sync with SpaController on the backend (history-mode fallback).
export const router = createRouter({
  history: createWebHistory('/'),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/chat', name: 'chat', component: ChatView },
    { path: '/knowledge', name: 'knowledge', component: KnowledgeView },
    { path: '/playground', name: 'playground', component: PlaygroundView },
  ],
})
