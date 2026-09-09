import { marked } from 'marked'
import DOMPurify from 'dompurify'

// A link can come from an indexed document, so it must leave the SPA in a new tab and without
// handing the target a reference to this window.
DOMPurify.addHook('afterSanitizeAttributes', node => {
  if (node.tagName === 'A' && node.hasAttribute('href')) {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})

/**
 * Renders answer Markdown to sanitized HTML and turns citation markers `[n]` into clickable
 * superscripts (`<sup class="cite" data-n="n">`). Only markers that the backend verified remain in
 * the text, so every marker can be resolved to a citation.
 *
 * A marker may hold several passage numbers (`[1, 2]`) — the models group their citations that way —
 * and each number becomes a superscript of its own, so every one of them is clickable.
 */
export function renderAnswer(markdown: string): string {
  const withMarkers = markdown.replace(/\[(\d{1,3}(?:\s*,\s*\d{1,3})*)]/g, (_match, group: string) =>
    group
      .split(',')
      .map(n => n.trim())
      .map(n => `<sup class="cite" data-n="${n}">[${n}]</sup>`)
      .join(''))
  const html = marked.parse(withMarkers, { async: false, gfm: true, breaks: true }) as string
  return DOMPurify.sanitize(html, { ADD_ATTR: ['data-n'] })
}

/**
 * Renders a citation quote. The backend returns the passage as urtext (exactly what the model saw,
 * see docs/system-plan.md §6.7), so a Markdown source document arrives with its markup intact and
 * would otherwise be shown as literal `**bold**` and `##` in the Sources panel.
 * Images are dropped: a local-first app must not fetch remote assets referenced by an indexed document.
 */
export function renderQuote(text: string): string {
  const html = marked.parse(text, { async: false, gfm: true, breaks: true }) as string
  return DOMPurify.sanitize(html, { FORBID_TAGS: ['img'] })
}
