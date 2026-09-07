// Resolver for `node --test`: the app sources use the Vite `@/` alias and extensionless imports,
// which Node's ESM loader does not understand. Nothing here transforms code — Node itself strips
// the TypeScript types (enabled by default since Node 23.6).
import { existsSync } from 'node:fs'
import { extname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const srcDir = new URL('../src/', import.meta.url)

export async function resolve(specifier, context, nextResolve) {
  let target = specifier
  if (target.startsWith('@/')) target = new URL(target.slice(2), srcDir).href
  else if (target.startsWith('./') || target.startsWith('../')) target = new URL(target, context.parentURL).href

  if (target.startsWith('file:') && !extname(fileURLToPath(target))) {
    for (const candidate of [`${target}.ts`, `${target}.js`, `${target}/index.ts`]) {
      if (existsSync(fileURLToPath(candidate))) return nextResolve(candidate, context)
    }
  }
  return nextResolve(target, context)
}
