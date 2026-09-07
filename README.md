# chatbot — local-first RAG / knowledge assistant

Local-first chat + knowledge-base assistant on **Java 25 · Spring Boot 4.1.1 · Embabel 1.5.1 · Ollama · Lucene**.
Architecture and phased implementation plan: [`docs/system-plan.md`](docs/system-plan.md).

Current state: **Phase 8** (observability) — Chat (streamed, cited answers), Knowledge Base (upload, live
status, re-index, delete) and a Retrieval playground on top of the Embabel agent, hybrid retrieval and the
Lucene index, with health components, RAG metrics, request correlation and optional tracing.

## Prerequisites

| Requirement | Notes |
|---|---|
| JDK 25 | e.g. `brew install openjdk@25` (Gradle toolchain resolves it) |
| Node.js 20+ with npm | builds the Vue frontend during `./gradlew build` (skip with `-PskipFrontend`) |
| Ollama ≥ 0.11 running on `http://localhost:11434` | `ollama pull qwen3:14b` (LLM). `embeddinggemma:300m` is optional (fallback embedding provider, Phase 1) |
| EmbeddingGemma ONNX files | Required with the default provider: `model.onnx`, `model.onnx_data` (fp32, 1.2 GB) and `tokenizer.json` from [onnx-community/embeddinggemma-300m-ONNX](https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX) under `~/.chatbot/models/embeddinggemma-300m/` (see below) |

Everything is stored under `~/.chatbot` (`chatbot.data-dir`): Lucene index, document registry, uploaded originals, model files.

## Embedding model files

```bash
D=~/.chatbot/models/embeddinggemma-300m; mkdir -p "$D"
B=https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX/resolve/main
curl -L -o "$D/model.onnx"      "$B/onnx/model.onnx"
curl -L -o "$D/model.onnx_data" "$B/onnx/model.onnx_data"
curl -L -o "$D/tokenizer.json"  "$B/tokenizer.json"
```

Without these files the app refuses to start and prints the same instructions. Alternative: set
`chatbot.embedding.provider=ollama` to embed through Ollama (`ollama pull embeddinggemma:300m`);
note that the two providers produce different index fingerprints.

## Web UI

Open `http://127.0.0.1:8080/` after `./gradlew bootRun`: **Chat** (`/chat`) and **Knowledge Base** (`/knowledge`).
The UI lives in `frontend/` (Vue 3, Vite, TypeScript, Pinia) and is bundled into the jar under `static/`
by the `frontendBuild` Gradle task (`npm ci` + `vue-tsc` + `vite build`).

Frontend development with hot reload:

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun   # enables CORS for the Vite dev server
cd frontend && npm run dev                      # http://localhost:5173, proxies /api to :8080
```

## Run

```bash
./gradlew bootRun
```

The app binds to `127.0.0.1:8080` only. On startup Embabel logs the Ollama models it discovered
(`qwen3:14b` must be among them; it is the `embabel.models.default-llm`).

Health: `curl http://127.0.0.1:8080/actuator/health`

Running the packaged jar (JDK 24+ JNI native-access flag for ONNX Runtime / DJL tokenizers):

```bash
./gradlew bootJar
java --enable-native-access=ALL-UNNAMED -jar build/libs/chatbot-0.0.1-SNAPSHOT.jar
```

## Build and test

```bash
./gradlew clean build          # unit + Spring tests; needs neither Ollama nor model files
./gradlew test -PincludeTags=model   # tests that need the local ONNX model files
./gradlew test -PincludeTags=e2e     # tests that need a running Ollama
```

Test tiers are JUnit tags: `model`, `eval`, `e2e`, `ui` (excluded by default). The `hermetic` Spring
profile (`src/test/resources/application-hermetic.yaml`) disables Ollama discovery and mocks LLM calls
via Embabel's `EmbabelMockitoIntegrationTest` (the profile is deliberately not called `test`: Embabel
skips `@Agent` registration under that name).

## Document API

| Endpoint | Purpose |
|---|---|
| `POST /api/documents` (multipart `file`, optional `title`) | Upload; `202` for a new document, `200` with `duplicate: true` if identical content exists |
| `GET /api/documents?status=&q=&page=&size=` | List, newest first |
| `GET /api/documents/{id}` / `GET /api/documents/{id}/status` | Document details / lightweight status |
| `PUT /api/documents/{id}/content` (multipart `file`) | Replace content as a new version |
| `DELETE /api/documents/{id}` | Remove document and its stored original |
| `GET /api/knowledge-base/status` | Counts by status and the active embedding fingerprint |

| `POST /api/documents/{id}/reindex` | Re-parse and re-index one document |
| `POST /api/knowledge-base/reindex` | Drop the index and re-ingest everything (also the fix for an `INCOMPATIBLE` index) |
| `GET /api/knowledge-base/events` | Server-sent `document-status` events for the admin UI |

Accepted types: md, markdown, txt, html, htm, pdf, docx; max 20 MB (`chatbot.knowledge.*`).
Errors are RFC 9457 problem details.

## Chat API

| Endpoint | Purpose |
|---|---|
| `POST /api/chat` `{conversationId?, message, options?:{topK?, documentIds?, includeDiagnostics?}}` | Grounded answer: `answer` (Markdown with `[n]` markers), `grounding` (`GROUNDED` / `PARTIAL` / `INSUFFICIENT_EVIDENCE`), `citations[]` with document, section, chunk id and quote, `timings`, `retrievalTraceId` |
| `POST /api/chat/stream` (same body) | Server-sent events: `status` (`retrieving` / `generating` / `verifying`), `delta` (`{text}`), `final` (`{response}` with the same shape as `POST /api/chat`), or `error` (`{message}`) |
| `GET /api/conversations/{id}` / `DELETE` | In-memory conversation history (last 10 turns, 24 h idle TTL) |

The flow is deterministic retrieve → generate → verify (`agents/KnowledgeAssistantAgent`): retrieval never
involves the model, the model only sees numbered evidence passages, and `[n]` markers that do not point at
a shown passage are removed before the answer is returned. A question with no retrieved evidence is answered
without calling the model. Prompts: `src/main/resources/prompts/grounded-answer.md` (structured output) and
`grounded-answer-stream.md` (free text for streaming; a trailing `INSUFFICIENT: …` line marks missing evidence);
tuning: `chatbot.chat.*`. Streaming uses Embabel's streaming prompt runner when the model supports it and
falls back to the structured path (one `delta` with the whole answer) otherwise; verification is identical.

## Retrieval and diagnostics

| Endpoint | Purpose |
|---|---|
| `POST /api/retrieval/search` `{query, topK?, mode?, documentIds?}` | Hybrid (default), `VECTOR` or `TEXT` search; hits carry provenance, cosine, BM25 and fused scores |
| `GET /api/diagnostics/retrieval?limit=` / `GET /api/diagnostics/retrieval/{traceId}` | Recent retrieval traces (bounded ring buffer) |

The **Retrieval** tab of the UI (`/playground`) runs the same search interactively. Health components
(`embedding`, `luceneIndex`, `ollama`), `chatbot.*` and `embabel.*` metrics, log correlation via `X-Request-Id`
and the optional `observability` profile (Embabel spans exported to the log) are described in
[`docs/observability.md`](docs/observability.md).

Retrieval is deterministic: vector k-NN and BM25 candidates are fused with reciprocal rank fusion
(`chatbot.retrieval.*`). `evidenceSufficient` is true when the best cosine clears the calibrated
floor (`sufficient-cosine`, 0.3). Quality is measured with `./gradlew ragEval` against
`src/test/resources/eval` (needs the ONNX model files); results and the calibration history live in
[`docs/eval-log.md`](docs/eval-log.md).

## Ingestion and index

Upload → `PARSING` → `INDEXING` → `READY` (or `FAILED` with the failing stage) is driven by a single
background worker; every document is indexed all-or-nothing. Data under `~/.chatbot`:

| Path | Content |
|---|---|
| `documents/registry.json` | Document metadata and statuses (atomic writes, `.bak` fallback) |
| `blobs/<documentId>/v<n>/original.<ext>` | Uploaded originals |
| `index/lucene/` | Lucene index (BM25 + HNSW vectors) |
| `index/manifest.json` | Embedding fingerprint and chunker settings the index was built with |

If the embedding model or chunking settings change, the index becomes `INCOMPATIBLE`: nothing is
searched or ingested until `POST /api/knowledge-base/reindex`. A corrupt index directory is moved
aside as `lucene.corrupt-<timestamp>` at startup and documents are re-queued automatically.
Chunk ids are `<documentId>:<version>:<sequence>`; chunk text carries `Document: <title> › <section>`.

## Key configuration (`src/main/resources/application.yaml`)

| Property | Default | Meaning |
|---|---|---|
| `chatbot.data-dir` | `~/.chatbot` | Root for all persistent local state |
| `embabel.models.default-llm` | `qwen3:14b` | Ollama model used by agents |
| `embabel.agent.platform.models.ollama.base-url` | `http://localhost:11434` | Ollama endpoint |
| `server.address` | `127.0.0.1` | Loopback-only binding |
| `chatbot.embedding.provider` | `onnx` | `onnx` (in-process EmbeddingGemma) or `ollama` (fallback) |
| `chatbot.embedding.onnx.model-dir` | `~/.chatbot/models/embeddinggemma-300m` | Location of the ONNX files |
| `chatbot.embedding.batch-size` / `max-concurrent-batches` | `16` / `2` | Batching and CPU protection for embedding calls |
