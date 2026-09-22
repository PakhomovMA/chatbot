# chatbot

Local-first RAG / knowledge assistant: Spring Boot 4.1.1 + Embabel 1.5.1 + Ollama (`gemma4:12b`) +
EmbeddingGemma (in-process ONNX) + Lucene via `embabel-agent-rag-lucene`, Vue 3 UI.
Key decision: retrieval, parsing, chunking, embedding and indexing are deterministic Spring services;
Embabel only orchestrates LLM actions on top of them. `docs/system-plan.md` is the authoritative plan
(**local-only**, gitignored — see "Git workflow"); its phases 0–9d are done. Observability hardening is
tracked in `docs/observability-plan.md` (checkpoints O01–O07 done; O08 awaits an API decision). Result
caching is tracked in `docs/cache-plan.md` (K01–K03 done: KB revision, pipeline fingerprint, answer
cache, derivation cache).

## Boundaries

Always:
- Treat `docs/system-plan.md` as the source of truth for scope, contracts, invariants and phase gates;
  when reality contradicts it, verify against Embabel 1.5.1 sources/POMs, adapt, and record the deviation.
- Run the phase verification gate (`./gradlew clean build`, plus `bootRun` with Ollama when the phase
  touches models) before reporting a phase as done.
- Verify Embabel API against 1.5.1 before using it (sources jars via Gradle, or the `java-decompiler`
  MCP); the reference project below is on Embabel 1.0.0 and its API differs.

Ask first:
- Changing versions of Embabel, Spring AI, Lucene, onnxruntime or DJL — they are pinned to what
  Embabel 1.5.1 is built against; a mismatch breaks at runtime, not at compile time.
- Changing an architectural invariant (INV-01..12) or a public `/api` contract from the plan.
- Deleting or rewriting anything under `~/.chatbot` (user's index, registry, uploaded originals, models).

Never:
- Let the LLM or an agent action call Lucene / `LuceneSearchOperations` directly — go through
  `RetrievalService` (INV-01); expose Embabel or Lucene types through `/api` DTOs (INV-10).
- Add `embabel-agent-starter-onnx` — it downloads MiniLM and is incompatible with EmbeddingGemma;
  the project has its own `EmbeddingService`.
- Add an external vector database — Lucene provides BM25 + kNN; the plan forbids it without a concrete need.
- Put an answer into a cache outside the stale-put guard: it is stored only if the KB revision taken at
  lookup still holds after verification and the cancellation check (INV-12, `docs/cache-plan.md` §3.1).
- Use `@EnableAgents` — deprecated for removal in 1.5.1; auto-configuration wires the platform.
- Edit `chatbot-plan-prompt-ru.md` (original task statement) or anything in `build/`.
- Commit model files or `~/.chatbot` contents.

## Setup

- Runtime needs Ollama on `localhost:11434` with `gemma4:12b` and the EmbeddingGemma ONNX files under
  `~/.chatbot/models/embeddinggemma-300m/` (see README). Tests need neither.
- `./gradlew build` also builds `frontend/` with the local `npm` (Node 20+); `-PskipFrontend` skips it.
  Frontend code: Vue 3 SFCs with `<script setup lang="ts">`, Pinia stores, DTO types in `src/api/types.ts`
  mirroring the backend records; keep `router.ts` and `SpaController` route lists in sync.
- Frontend tests are `frontend/test/*.test.ts` on the built-in Node runner (`npm test`, task `frontendTest`,
  on the `check` gate; Node 23.6+ for type stripping). They import the sources directly, so keep those
  strip-only compatible — no constructor parameter properties, `enum` or `namespace`.
- Always use `./gradlew`. JVM flag `--enable-native-access=ALL-UNNAMED` is already set for `test`/`bootRun`;
  pass it yourself for `java -jar`.

## Build / Test / Verify

- `./gradlew clean build` — the default gate; must pass without Ollama or model files.
- `./gradlew test -PincludeTags=model` / `=e2e` / `=eval` — tiers needing model files / Ollama / eval set.
  Tag such tests with `@Tag("model")` etc.; untagged tests must stay hermetic.
- `./gradlew bootRun` — expect the log line listing discovered Ollama models incl. `gemma4:12b`,
  then `curl 127.0.0.1:8080/actuator/health`.

## Conventions

- Java 25, records for DTOs/config/blackboard types, constructor injection; Lombok only where it
  removes real boilerplate, never `@Data` on domain/blackboard types.
- Jackson 3: import `tools.jackson.*`, not `com.fasterxml.jackson.*` (annotations excepted).
- Spring Boot 4 names: `spring-boot-starter-webmvc`, `@MockitoBean` (not `@MockBean`),
  `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.
- Spring tests that boot the context extend `EmbabelMockitoIntegrationTest` with `@ActiveProfiles("hermetic")`;
  the `hermetic` profile disables Ollama discovery and mocks `LlmOperations`. Never name a Spring profile `test`: Embabel disables `@Agent` auto-registration under it.
- Ollama models are addressed by their raw name (`gemma4:12b`), e.g. `embabel.models.default-llm`.
- Docs (`docs/*.md`) in Russian with English terms; code, comments, commit messages in English.
- Prefer capability descriptions over class names in docs; name a class only when it is architecturally significant.

## Architecture

- Layers: API (DTOs) → application services (chat, knowledge/ingestion, retrieval, index, embedding)
  → Embabel agent/tool boundary. Packages are layered by type, as in the reference project:
  `config`, `controller`, `service.<area>`, `models.<area>`, `exceptions`, `utils`, `observability`, `agents`.
- Grounding statuses `GROUNDED | PARTIAL | INSUFFICIENT_EVIDENCE`; citations must be a subset of retrieved evidence.
- Prompts: standing instructions live in `resources/prompts/*.jinja` and reach the model as Embabel
  `PromptContributor`s (system message); history, evidence and the question are assembled in Java (user message).
  Never put untrusted text (document content, user question, history) into a template model — Jinjava
  re-interprets substituted values (D16).
- Index is single-writer (one ingestion worker + write lock); per-document ingestion is all-or-nothing.
- Index manifest stores the embedding fingerprint; mismatch blocks retrieval until rebuild.

## Git workflow

Trunk-based on `master` (protected on GitHub): every change lands through a pull request from a
short-lived branch — `feature/<slug>`, `fix/<slug>` or `chore/<slug>` — squash-merged after CI
(`./gradlew clean build` on GitHub Actions) is green. Never push directly to `master`, even with
admin rights. Branch from an up-to-date `master` (`git pull --ff-only`); one logical change per PR;
conventional commit style (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`).

Before opening or merging a PR:

1. Run `./gradlew clean build` (plus `bootRun` with Ollama when the change touches models).
2. Update docs and this file when behavior, contracts or workflow change.
3. Do not merge a knowingly broken state.

Releases are tagged on `master` (`vX.Y.Z`) when the maintainer decides to cut one.

Local-only working documents — `docs/system-plan.md`, `docs/cache-plan.md`, `docs/concurrency-plan.md`,
`docs/observability-plan.md`, `docs/observability-validation.md`, `docs/observability/o01–o07/` and
`chatbot-plan-prompt-ru.md` — are gitignored and not in the GitHub repo; references to them in code
comments are intentional and stay valid in the maintainer's checkout. Published docs:
`docs/observability.md`, `docs/eval-log.md`, `docs/observability/metric-catalog.json`. On a fresh
clone without the plans, ask the maintainer for direction instead of guessing.

## See Also

- Plan, decisions, invariants, phases: `docs/system-plan.md` (local-only, not in the repo)
- Runtime prerequisites and commands: `README.md`
- Reference Embabel project (agents, prompt templates, observability patterns; **Embabel 1.0.0**, API differs):
  a local checkout the maintainer can point you to (ask for the path)
- When you hit a wrong assumption in this file, propose a AGENTS.md correction.
