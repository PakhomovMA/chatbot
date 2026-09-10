# Observability

Что и где смотреть, когда RAG отвечает не так, как ожидалось, или медленно (docs/system-plan.md D14, Phase 8).

Проверенный стек и ограничения текущих измерений: [O01 baseline](observability/o01/README.md). Контракт миграции: [metric catalog](observability/metric-catalog.json), порядок работ: [observability-plan.md](observability-plan.md).
Canonical families chat, AI и одного retrieval pass введены в [O02](observability/o02/README.md); ingestion, embedding и SSE переходят на них в O03, полный runbook обновляется в O07.

## Корреляция логов (MDC)

Формат консольной строки: `время [поток] LEVEL logger - <requestId> <conversationId> <messageId> <documentId> сообщение`.
Пустые ключи не печатаются.

| Ключ | Кто ставит | Где виден |
|---|---|---|
| `requestId` | `RequestIdFilter`: принимает заголовок `X-Request-Id` (буквы/цифры/`._:-`, до 64 символов) или генерирует UUID; всегда возвращает его в ответе | все логи HTTP-запроса, включая Embabel (`object added`, planning) и SSE |
| `conversationId`, `messageId` | `ChatService` на время запуска агента | ретривал, действия агента, вызов LLM |
| `documentId` | `IngestionService` на время обработки документа воркером | parse / chunk / index, ошибки ingestion |
| `embabel.agent.run_id`, `embabel.agent.name`, `embabel.action.name` | Embabel (`MdcPropagationEventListener`) при включённом tracing | добавляются под профилем `observability` |

Пример: `grep demo-req-42 app.log` собирает всё, что произошло по одному запросу чата.

## Health (`GET /actuator/health`, details всегда включены)

| Компонент | UP | Иначе |
|---|---|---|
| `embedding` | warm-up выполнен; details: provider, model, dimensions, fingerprint, warmupMs | UNKNOWN до warm-up |
| `luceneIndex` | `EMPTY` или `READY`; details: state, chunks, documents, fingerprint, path, `recoveredFrom` | OUT_OF_SERVICE при `INCOMPATIBLE` (details.reason) или `REBUILDING` |
| `ollama` | сервер отвечает на `/api/tags` и `embabel.models.default-llm` установлен | DOWN — сервер недоступен; OUT_OF_SERVICE — модель не скачана (details.reason подсказывает `ollama pull`); UNKNOWN — base-url не задан |

Overall-статус агрегируется Spring: OUT_OF_SERVICE/DOWN любого компонента опускает общий статус.

## Метрики (`GET /actuator/metrics/<name>`)

Собственные, префикс `chatbot.`:

| Метрика | Тип | Теги | Смысл |
|---|---|---|---|
| `chatbot.chat.request` | timer | `mode` (sync/stream), `answer.mode` (deterministic/agentic), `grounding`, `outcome` | все принятые runs, включая error / cancelled / timeout / rejected; до записи истории |
| `chatbot.chat.wait` | timer | `outcome` | ожидание lease разговора; не входит в время модели |
| `chatbot.ai.operation` | timer | `operation` (draft-answer / draft-answer-stream / research-agentic / conversation-query-rewrite / expand-search-rewrite / expand-search-hyde / decompose-question / compare-sources / repair-agentic-answer), `outcome` (+`fallback`) | логическая AI-операция целиком: prompt, вызовы модели, tools, валидация и восстановление |
| `chatbot.retrieval.search` | timer | `mode`, `outcome` | один deterministic pass, включая lock, query embedding и postprocessing |
| `chatbot.chat.active` | gauge | — | принятые runs, которые ещё не завершились |
| `chatbot.chat.rejected` | counter | `reason` (stopping / capacity) | отказ до принятия run; не входит в знаменатель принятых |
| `chatbot.chat` | timer | `grounding`, `mode` (sync/stream), `answerMode` (deterministic/agentic) | **legacy**: только успешные runs, до завершения agent invocation |
| `chatbot.llm` | timer | `operation` | **legacy**: прежние границы try/finally; для `research-agentic` только tool loop |
| `chatbot.chat.query.rewrite` | counter | `outcome` (rewritten / unchanged / fallback) | результат попытки восстановления вопроса; вопросы без попытки не учитываются |
| `chatbot.retrieval.expansion` | counter | `strategy`, `outcome` (sufficient / insufficient) | сработавшее расширение поиска (Phase 9a) |
| `chatbot.chat.decomposition` | counter | `outcome` (split / single / failed) | разбор многосоставного вопроса (Phase 9d); вопросы без попытки не учитываются |
| `chatbot.chat.comparison` | counter | `outcome` (conflict / agreement / none / failed) | сравнение источников перед ответом (Phase 9d) |
| `chatbot.retrieval` | timer | `mode` | **legacy**: только успешные passes, clock read после записи trace |
| `chatbot.retrieval.hits` | summary | — | hits на запрос |
| `chatbot.embedding` | timer | `mode` (query/document), `provider`, `model` | один батч эмбеддинга |
| `chatbot.embedding.texts` | counter | `provider` | текстов заэмбеждено |
| `chatbot.ingestion.stage` | timer | `stage` (parse / index / total) | стадии ingestion |
| `chatbot.ingestion.failures` | counter | `stage` | отказы ingestion |
| `chatbot.ingestion.queue`, `chatbot.ingestion.active` | gauge | — | очередь воркера |
| `chatbot.index.chunks`, `chatbot.index.documents`, `chatbot.index.writable` | gauge | — | состояние индекса |
| `chatbot.documents` | gauge | `status` | документы в реестре по статусу |
| `chatbot.conversations`, `chatbot.retrieval.traces` | gauge | — | память диалогов, буфер трейсов |

Три метрики, помеченные **legacy**, публикует отключаемый compatibility adapter (`chatbot.observability.legacy-metrics=false`).
Они измеряют другие границы и другую population, чем canonical families рядом с ними, и складывать их нельзя.
Buckets для percentile включены у canonical timers, `chatbot.embedding` и `chatbot.sse.send`
(`chatbot.observability.histograms=false` их снимает).

Embabel (`embabel.agent.platform.observability.metrics-enabled=true`, включено всегда): `embabel.agent.duration`,
`embabel.agent.active`, `embabel.agent.errors.total`, `embabel.llm.duration`, `embabel.llm.requests.total`,
`embabel.llm.tokens.total` (тег `direction`), `embabel.llm.cost.total`, `embabel.planning.replanning.total`.

Пример: `curl 'localhost:8080/actuator/metrics/chatbot.chat?tag=grounding:grounded'`.

## Retrieval-диагностика

- `POST /api/retrieval/search` — прогон ретривала без LLM: hits с cosine, BM25 и fused score, provenance, timings,
  `evidenceSufficient`, `traceId`. В UI — вкладка **Retrieval** (`/playground`).
- `GET /api/diagnostics/retrieval?limit=` и `/api/diagnostics/retrieval/{traceId}` — последние N результатов
  (кольцевой буфер `chatbot.retrieval.trace-buffer-size`, 200). `ChatResponse.retrievalTraceId` ссылается на них,
  `options.includeDiagnostics=true` вкладывает результат прямо в ответ чата.
- `GET /api/knowledge-base/status` — состояние индекса/очереди, fingerprint, `recentFailures` (последние 50 ошибок
  ingestion: документ, стадия, сообщение, время). В UI — жёлтый баннер на вкладке Knowledge Base.
- Порог достаточности и его калибровка: `docs/eval-log.md`.

## Трейсинг (профиль `observability`)

```bash
SPRING_PROFILES_ACTIVE=observability ./gradlew bootRun
```

Включает Embabel tracing (`embabel-agent-starter-observability`, Micrometer Tracing → OpenTelemetry SDK):
спаны `agent`, `planning …`, действия, `llm <model>` / `llm.invocation`, `tool-loop`, `embeddings <model>`,
RAG-операции и HTTP-запросы. По умолчанию они печатаются в лог через `LoggingSpanExporter`
(`LoggingSpanExporterConfiguration`); содержимое сообщений не захватывается (`capture-message-content=false`).

Чтобы отправлять спаны в Langfuse / Jaeger / Zipkin, объявите бин `SpanExporter` (например,
`OtlpHttpSpanExporter`) под тем же профилем — Embabel подхватывает все `SpanExporter`-бины, а логирующий
объявлен как `@ConditionalOnMissingBean`. Ключи `embabel.agent.platform.observability.trace-*` выключают
отдельные группы спанов; `management.tracing.sampling.probability` управляет выборкой.

Без профиля `tracing-enabled=false` выключает Embabel tracing, но само по себе не доказывает отсутствие
Spring Boot / Spring AI spans или overhead. Для полного отключения нужно согласовать Boot OTel support и
Embabel; актуальные owners/conditions и shutdown проверены в [O01](observability/o01/README.md).

`prometheus` в списке endpoints ещё не означает доступный scrape: production runtime пока не содержит
`micrometer-registry-prometheus`. В O01 registry подключён только в отдельном baseline/test classpath;
включение для приложения — O04.

## Типичные симптомы

| Симптом | Куда смотреть |
|---|---|
| Ответы `INSUFFICIENT_EVIDENCE` на вопросы, которые есть в базе | Playground: есть ли hit и его cosine против `sufficient-cosine`; `luceneIndex` health (`EMPTY`? `INCOMPATIBLE`?); `chatbot.documents{status=ready}` |
| Документ завис в `UPLOADED` | `chatbot.ingestion.queue`, health `luceneIndex` (при `INCOMPATIBLE` воркер паркует документы), `recentFailures` |
| Медленные ответы | `chatbot.llm` vs `chatbot.retrieval` с учётом legacy-границ; время до первой дельты также включает retrieval и подготовку; `embabel.llm.tokens.total{direction=input}` показывает размер промпта |
| Ollama недоступен | health `ollama` (DOWN/OUT_OF_SERVICE с причиной) |
| Странные цитаты | `GET /api/diagnostics/retrieval/{traceId}` по `retrievalTraceId` ответа: какие чанки видела модель |
