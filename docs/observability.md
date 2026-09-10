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

## Prometheus и Grafana (O04)

Профиль `metrics` включает отдельный management listener **127.0.0.1:8081** с двумя endpoints:
`/actuator/prometheus` и `/actuator/health`. Health возвращает общий статус без details/components.
Основной API остаётся на **127.0.0.1:8080**; diagnostics API на management-порту недоступен.
Без профиля приложение и метрики продолжают работать без Docker. Профиль `observability`
отдельно включает logging traces; для сочетания используйте `observability,metrics` в таком порядке.

```bash
# Из корня репозитория. Ollama и ONNX нужны приложению, но не monitoring stack.
SPRING_PROFILES_ACTIVE=metrics ./gradlew bootRun

# В другом терминале: скопировать только при первоначальной настройке.
cp -n ops/observability/.env.example ops/observability/.env
# Заменить GRAFANA_ADMIN_PASSWORD в .env; файл игнорируется Git.
docker compose -f ops/observability/compose.yaml --profile metrics config --quiet
docker compose -f ops/observability/compose.yaml --profile metrics up -d --wait
curl http://127.0.0.1:8081/actuator/health
```

- [Grafana](http://127.0.0.1:3001): пользователь и пароль из `.env`, папка **Chatbot**,
  dashboards **Overview / RAG / Ingestion / LLM**. Datasource и JSON загружаются provisioning-ом;
  ручной импорт не требуется, изменения в UI не сохраняются поверх файлов.
- [Prometheus targets](http://127.0.0.1:9090/targets): job `chatbot` должен быть `UP`.
  Контейнер обращается к `host.docker.internal:8081`, а не к своему `localhost`.
  Docker Desktop на macOS проверен с loopback bind; Linux host-gateway сам по себе не делает
  host loopback доступным. Для Linux нужна отдельно ограниченная management network/address.
- `CHATBOT_MANAGEMENT_PORT` / `CHATBOT_MANAGEMENT_ADDRESS` читает **приложение**, Compose `.env`
  его не настраивает. При смене порта обновить также `prometheus/prometheus.yml`.
  Не переносить адрес на `0.0.0.0` без настройки доступа к management network.
- При `metrics` health details скрыты. Локальный UI состояния базы остаётся на основном API:
  `/api/knowledge-base/status`. Для подробного Actuator health используйте обычный локальный профиль.

```bash
# Проверка синтаксиса и unit tests recording/alert rules на закреплённой версии Prometheus.
docker compose -f ops/observability/compose.yaml --profile metrics run --rm --no-deps \
  --entrypoint promtool prometheus check config /etc/prometheus/prometheus.yml
docker compose -f ops/observability/compose.yaml --profile metrics run --rm --no-deps \
  -w /etc/prometheus --entrypoint promtool prometheus test rules rules.test.yml
# После representative sync/SSE/agentic/decomposition traffic:
uv run scripts/verify_metrics.py --output /tmp/chatbot-metrics-validation.json

# Остановить monitoring, сохранив данные; приложение продолжит работать.
docker compose -f ops/observability/compose.yaml --profile metrics stop
# Возобновить с теми же данными и provisioning.
docker compose -f ops/observability/compose.yaml --profile metrics up -d --wait
# Удалить контейнеры и сеть, сохранив named volumes:
docker compose -f ops/observability/compose.yaml --profile metrics down
```

Compose project — `chatbot-observability`; отдельные named volumes `prometheus-data` и `grafana-data`
не связаны с `~/.chatbot`. `down -v` не является штатной остановкой. Retention Prometheus: **14 дней
и 2 GB** (срабатывает первое ограничение; WAL/head сверх block budget требуют свободного места).
Порты UI опубликованы только на loopback: Grafana **3001**, Prometheus **9090**.
Оба контейнера ограничены 512 MiB / 1 CPU; это проверенные стартовые лимиты для локального smoke,
не capacity promise под production load. [Результаты измерения и gates](observability/o04/README.md).

### Как читать dashboards

Canonical `chatbot_chat_request_seconds_count` содержит все завершённые принятые runs. Ошибки и
таймауты входят в error numerator, отмены видны отдельно и остаются в denominator. Успешный
`INSUFFICIENT_EVIDENCE` не является ошибкой. Legacy `chatbot_chat_seconds_*`, `chatbot_llm_seconds_*`
и `chatbot_retrieval_seconds_*` в dashboards/rules не используются.

Buckets — явные classic SLO границы из [catalog](observability/metric-catalog.json), время в секундах.
p50/p95 сохраняют `le`, `mode`, `answer_mode`; это начальные границы для калибровки, не обещанные SLO.
`rate(...[5m])` требует хотя бы двух scrapes. После нового запуска или без операций в окне percentile
может быть пустым/NaN: это отсутствие population, не нулевая latency. Multi-pass timer появляется
только при expansion/decomposition (decomposition по умолчанию выключен).

Provider — `gen_ai_client_operation_seconds_*`, usage — `gen_ai_client_token_usage_total`.
Считаются только **input + output**, без `total` и без `embabel_llm_tokens_total`. Один Spring AI
sync observation может содержать HTTP retries; доступного отдельного attempt/retry counter нет.
AI operation — логический workflow, поэтому его нельзя складывать с provider duration.
Цена локальной Ollama не выдумывается. First-delta measurement и завершение SSE lifecycle — **O06**;
на dashboard пока пояснение, без запроса к отсутствующей метрике. Trace links, Collector/exporter
alerts появятся вместе с соответствующей инфраструктурой O05/O06.

Очередь использует `chatbot_ingestion_queue_wait_active_seconds_max` для старейшего **документного**
ожидания, включая rerun; rebuild command в этот возраст не входит. Progress — все terminal processing
outcomes; failed processing тоже означает движение очереди. Alert требует backlog без progress,
возраст >10 минут и ещё 5 минут `for`; большая движущаяся очередь сама по себе не тревожит.
Index unavailable подавляется при `chatbot_index_maintenance=1` (rebuild).

Сканирование registry и чтение index counts выполняются один раз в 5 секунд фоновым потоком.
Scrape читает immutable snapshot; до первого refresh counts = NaN, при сбое остаётся предыдущий
snapshot. `chatbot_metrics_snapshot_age_seconds` показывает его возраст (до первого refresh — время
с запуска reader); alert замечает остановку обновлений. Остальные gauges читают короткое in-memory
состояние. Configured model allowlist сворачивает неожиданные имена в `unknown`; caps ограничивают
provider response models, application operation names и HTTP URIs. Budget — **3637** консервативных
application series при лимите **5000**, включая ещё не реализованные семейства catalog;
JVM/HTTP/Embabel/provider учитываются отдельно. Prometheus sample limit — 12000 на scrape.

Alerts локальны, **Alertmanager и отправка уведомлений не настроены**. Error ratio >10% требует
не менее 20 завершений за 5 минут и `for: 5m`. Storage alert следит за TSDB blocks возле 2 GB,
а не за свободным местом всей Docker VM; свободное место проверять через Docker Desktop / `docker system df`.
Пороги — гипотезы для дальнейшей калибровки. Backup/restore volumes и outage trace pipeline — gate O07.

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

С O04 `micrometer-registry-prometheus` входит в production runtime без version override.
Для отдельного management listener и dashboards используйте профиль `metrics`, описанный выше.

## Типичные симптомы

| Симптом | Куда смотреть |
|---|---|
| Ответы `INSUFFICIENT_EVIDENCE` на вопросы, которые есть в базе | Playground: есть ли hit и его cosine против `sufficient-cosine`; `luceneIndex` health (`EMPTY`? `INCOMPATIBLE`?); `chatbot.documents{status=ready}` |
| Документ завис в `UPLOADED` | `chatbot.ingestion.queue`, health `luceneIndex` (при `INCOMPATIBLE` воркер паркует документы), `recentFailures` |
| Медленные ответы | `chatbot.llm` vs `chatbot.retrieval` с учётом legacy-границ; время до первой дельты также включает retrieval и подготовку; `embabel.llm.tokens.total{direction=input}` показывает размер промпта |
| Ollama недоступен | health `ollama` (DOWN/OUT_OF_SERVICE с причиной) |
| Странные цитаты | `GET /api/diagnostics/retrieval/{traceId}` по `retrievalTraceId` ответа: какие чанки видела модель |
