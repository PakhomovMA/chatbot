# chatbot — local-first RAG / knowledge assistant

Local-first chat + knowledge-base assistant on **Java 25 · Spring Boot 4.1.1 · Embabel 1.5.1 · Ollama · Lucene**.
Architecture and phased implementation plan: [`docs/system-plan.md`](docs/system-plan.md).

Current state: **Phase 2** (document management) — the application starts, discovers Ollama models,
loads EmbeddingGemma in-process (ONNX Runtime), exposes Actuator health with the embedding
fingerprint and manages uploaded documents (registry + stored originals). Indexing and chat are
not implemented yet.

## Prerequisites

| Requirement | Notes |
|---|---|
| JDK 25 | e.g. `brew install openjdk@25` (Gradle toolchain resolves it) |
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

Test tiers are JUnit tags: `model`, `eval`, `e2e`, `ui` (excluded by default). The default `test`
profile (`src/test/resources/application-test.yaml`) disables Ollama discovery and mocks LLM calls
via Embabel's `EmbabelMockitoIntegrationTest`.

## Document API (Phase 2)

| Endpoint | Purpose |
|---|---|
| `POST /api/documents` (multipart `file`, optional `title`) | Upload; `202` for a new document, `200` with `duplicate: true` if identical content exists |
| `GET /api/documents?status=&q=&page=&size=` | List, newest first |
| `GET /api/documents/{id}` / `GET /api/documents/{id}/status` | Document details / lightweight status |
| `PUT /api/documents/{id}/content` (multipart `file`) | Replace content as a new version |
| `DELETE /api/documents/{id}` | Remove document and its stored original |
| `GET /api/knowledge-base/status` | Counts by status and the active embedding fingerprint |

Accepted types: md, markdown, txt, html, htm, pdf, docx; max 20 MB (`chatbot.knowledge.*`).
Errors are RFC 9457 problem details. Data lives under `~/.chatbot/documents/registry.json` and
`~/.chatbot/blobs/<documentId>/v<n>/`.

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
