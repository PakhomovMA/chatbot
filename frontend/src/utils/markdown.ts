import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * Renders answer Markdown to sanitized HTML and turns citation markers `[n]` into clickable
 * superscripts (`<sup class="cite" data-n="n">`). Only markers that the backend verified remain in
 * the text, so every marker can be resolved to a citation.
 */
export function renderAnswer(markdown: string): string {
  const withMarkers = markdown.replace(/\[(\d{1,3})]/g, (_match, n: string) => `<sup class="cite" data-n="${n}">[${n}]</sup>`)
  const html = marked.parse(withMarkers, { async: false, gfm: true, breaks: true }) as string
  return DOMPurify.sanitize(html, { ADD_ATTR: ['data-n'] })
}
