# Observability

Что и где смотреть, когда RAG отвечает не так, как ожидалось, или медленно.

Контракт метрик: [metric catalog](observability/metric-catalog.json). Checkpoint-документы O01–O07
(проверенный стек, baseline, gates, evidence) и рабочие планы (`system-plan.md`, `observability-plan.md`,
`cache-plan.md`, `concurrency-plan.md`) — локальные рабочие материалы maintainer'а и в репозиторий не
входят; ниже собрано всё необходимое для эксплуатации. Verify-скрипты из `scripts/` читают evidence из
локальных `docs/observability/o0*`, поэтому вне окружения maintainer'а запускаются только частично.

## Корреляция логов (MDC)

Формат консольной строки: `время [поток] LEVEL logger - <requestId> <conversationId> <messageId> <documentId> сообщение`.
Пустые ключи не печатаются.

| Ключ | Кто ставит | Где виден |
|---|---|---|
| `requestId` | `RequestIdFilter`: принимает заголовок `X-Request-Id` (буквы/цифры/`._:-`, до 64 символов) или генерирует UUID; всегда возвращает его в ответе | все логи HTTP-запроса, включая Embabel (`object added`, planning) и SSE |
| `conversationId`, `messageId` | `ChatService` на весь принятый run, включая ожидание lease (O05) | ожидание разговора, ретривал, действия агента, вызов LLM |
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
| `chatbot.chat.query.rewrite` | counter | `outcome` (rewritten / unchanged / fallback) | результат попытки восстановления вопроса; вопросы без попытки не учитываются |
| `chatbot.retrieval.expansion` | counter | `strategy`, `outcome` (sufficient / insufficient) | сработавшее расширение поиска (Phase 9a) |
| `chatbot.chat.decomposition` | counter | `outcome` (split / single / failed) | разбор многосоставного вопроса (Phase 9d); вопросы без попытки не учитываются |
| `chatbot.chat.comparison` | counter | `outcome` (conflict / agreement / none / failed) | сравнение источников перед ответом (Phase 9d) |
| `chatbot.retrieval.hits` | summary | — | hits на запрос |
| `chatbot.embedding` | timer | `mode` (query/document), `provider`, `model` | один батч эмбеддинга |
| `chatbot.embedding.texts` | counter | `provider` | текстов заэмбеждено |
| `chatbot.ingestion.stage` | timer | `stage` (parse / index / total) | стадии ingestion |
| `chatbot.ingestion.failures` | counter | `stage` | отказы ingestion |
| `chatbot.ingestion.queue`, `chatbot.ingestion.active` | gauge | — | очередь воркера |
| `chatbot.index.chunks`, `chatbot.index.documents`, `chatbot.index.writable` | gauge | — | состояние индекса |
| `chatbot.documents` | gauge | `status` | документы в реестре по статусу |
| `chatbot.conversations`, `chatbot.retrieval.traces` | gauge | — | память диалогов, буфер трейсов |

Legacy-семейства `chatbot.chat`, `chatbot.llm` и `chatbot.retrieval` (другие границы и population) удалены в O07
вместе с compatibility adapter: все потребители уже читали canonical families. Соответствие старых имён
canonical зафиксировано в checkpoint O02 (локально). Buckets для percentile включены у canonical timers,
`chatbot.embedding` и `chatbot.sse.send` (`chatbot.observability.histograms=false` их снимает).

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
не capacity promise под production load. Результаты измерения и gates — в локальном evidence O04.

### Как читать dashboards

Canonical `chatbot_chat_request_seconds_count` содержит все завершённые принятые runs. Ошибки и
таймауты входят в error numerator, отмены видны отдельно и остаются в denominator. Успешный
`INSUFFICIENT_EVIDENCE` не является ошибкой. Legacy-семейства `chatbot_chat_seconds_*`,
`chatbot_llm_seconds_*` и `chatbot_retrieval_seconds_*` удалены в O07; dashboards/rules всегда
использовали только canonical.

Buckets — явные classic SLO границы из [catalog](observability/metric-catalog.json), время в секундах.
p50/p95 сохраняют `le`, `mode`, `answer_mode`; это начальные границы для калибровки, не обещанные SLO.
`rate(...[5m])` требует хотя бы двух scrapes. После нового запуска или без операций в окне percentile
может быть пустым/NaN: это отсутствие population, не нулевая latency. Multi-pass timer появляется
только при expansion/decomposition (decomposition по умолчанию выключен).

Provider — `gen_ai_client_operation_seconds_*`, usage — `gen_ai_client_token_usage_total`.
Считаются только **input + output**, без `total` и без `embabel_llm_tokens_total`. Один Spring AI
sync observation может содержать HTTP retries; доступного отдельного attempt/retry counter нет.
AI operation — логический workflow, поэтому его нельзя складывать с provider duration.
Цена локальной Ollama не выдумывается. С **O06** `chatbot.sse.first.delta{answer.mode}` измеряет первую
непустую delta, принятую в очередь; `chatbot.sse.completed{answer.mode,outcome}` считает один terminal
event. С **O07** у всех dashboards есть dropdown-навигация по папке Chatbot (tag `chatbot`), а у Traces —
ссылка на Langfuse UI; alerts trace pipeline дополнены `TraceExporterDrops` (drops после исчерпания retry).

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
Пороги — гипотезы для дальнейшей калибровки. Backup/restore и outage-поведение trace pipeline проверены
в O07 — см. «Сбои trace pipeline» и «Backup/restore volumes» ниже.

## Retrieval-диагностика

- `POST /api/retrieval/search` — прогон ретривала без LLM: hits с cosine, BM25 и fused score, provenance, timings,
  `evidenceSufficient`, `traceId`. В UI — вкладка **Retrieval** (`/playground`).
- `GET /api/diagnostics/retrieval?limit=` и `/api/diagnostics/retrieval/{traceId}` — последние N результатов
  (кольцевой буфер `chatbot.retrieval.trace-buffer-size`, 200). `ChatResponse.retrievalTraceId` ссылается на них,
  `options.includeDiagnostics=true` вкладывает результат прямо в ответ чата.
- `GET /api/knowledge-base/status` — состояние индекса/очереди, fingerprint, `recentFailures` (последние 50 ошибок
  ingestion: документ, стадия, сообщение, время). В UI — жёлтый баннер на вкладке Knowledge Base.
- Порог достаточности и его калибровка: `docs/eval-log.md`.

## Трейсинг: режимы экспорта (O05)

Куда идут спаны, задаёт **`chatbot.observability.trace-export`**, и только он. Экспортёр не появляется
из-за отсутствия другого и не подменяется тихо: несовместимая конфигурация останавливает старт
(`TraceExportCheck`), а не откатывается в логирование.

| Режим | Профиль | Что происходит |
|---|---|---|
| `none` | по умолчанию, `metrics` | Ничего не покидает процесс. `management.opentelemetry.enabled=false` и `tracing-enabled=false` вместе означают: нет exporter, нет batch processor, нет SDK-провайдера |
| `logging` | `observability` | Спаны печатаются `LoggingSpanExporter`; читается один прогон локально, без Docker |
| `otlp` | `observability-otlp` | Спаны идут по OTLP/HTTP в локальный OpenTelemetry Collector, который и разговаривает с Langfuse |

Метрики, health и diagnostics API работают одинаково во всех режимах.

```bash
# Спаны в лог.
SPRING_PROFILES_ACTIVE=observability ./gradlew bootRun

# Спаны в Collector → Langfuse (нужен профиль Compose `llm`, см. ниже).
SPRING_PROFILES_ACTIVE=observability-otlp,metrics ./gradlew bootRun
```

Спаны те же, что и раньше: `agent`, `planning …`, действия, `llm <model>` / `llm.invocation`,
`tool-loop`, `embeddings <model>`, RAG-операции, HTTP и canonical `chatbot.*` измерения.
По умолчанию содержимое сообщений не захватывается: `capture-message-content=false` и `trace-http-details=false`
заданы в **базовой** конфигурации, а не только в профиле.

Владельцы pipeline не менялись (проверено в checkpoint O01 (локально) и тестом
`TraceExportTest`): `SdkTracerProvider` и `OpenTelemetry` — Embabel, `BatchSpanProcessor` и `Sampler` —
Boot, exporter — `TraceExportConfiguration`. Второго SDK не создаётся; старт падает, если провайдеров
или batch processor-ов оказалось больше одного.

- **Sampling:** `management.tracing.sampling.probability` (0 и 1 проверены на живом провайдере).
- **Endpoint приложения** — полный signal URL и обязан заканчиваться на `/v1/traces`
  (`CHATBOT_OTLP_ENDPOINT`, по умолчанию `http://localhost:4318/v1/traces`). Endpoint **Collector-а**
  задаётся без этого суффикса — exporter добавляет его сам. Старт отклоняет base URL там, где нужен
  signal URL.
- **Shutdown:** один ограниченный flush после остановки прикладной работы и до уничтожения SDK
  (`chatbot.observability.flush-timeout`, по умолчанию 5s). Недоступный Collector не удлиняет остановку.
- **Атрибуты исполнения:** каждый спан получает `session.id` (= conversationId), `chatbot.request.id`,
  `chatbot.message.id`, `chatbot.document.id` из MDC — только идентификаторы, ничего из содержимого.
  С O06 контекст переносится в SSE worker/sender, Reactor и parallel retrieval; ingestion получает
  отдельный root со span link к enqueue, без удержания HTTP observation.

С O04 `micrometer-registry-prometheus` входит в production runtime без version override.
Для отдельного management listener и dashboards используйте профиль `metrics`, описанный выше.

## Langfuse и Collector (профиль Compose `llm`)

Приложение знает только локальный OTLP-адрес. Про Langfuse — его адрес, ключи проекта и словарь
типов — знает **только Collector** ([`ops/observability/otel/collector.yaml`](../ops/observability/otel/collector.yaml)).
Ключи проекта в приложение не попадают.

```bash
# Один раз: заполнить секреты профиля `llm` в игнорируемом .env (см. .env.example).
cp -n ops/observability/.env.example ops/observability/.env

docker compose -f ops/observability/compose.yaml --profile llm config --quiet
docker compose -f ops/observability/compose.yaml --profile llm up -d --wait
curl -s http://127.0.0.1:13133/          # health Collector-а
open http://127.0.0.1:3000               # Langfuse; логин из .env

SPRING_PROFILES_ACTIVE=observability-otlp,metrics ./gradlew bootRun

# Синтетический trace через Collector в Langfuse, с проверкой дерева и токенов:
uv run scripts/verify_traces.py --output /tmp/o05-traces.json

# Остановить, сохранив данные:
docker compose -f ops/observability/compose.yaml --profile llm stop
```

`scripts/dev-observability.sh` поднимает и то и другое, если задать `CHATBOT_DEV_TRACES=1`.

Состав профиля (все образы закреплены digest-ом и имеют `linux/arm64`): Langfuse **4.33.0** web и
worker, ClickHouse **25.12**, PostgreSQL **17.7**, Redis **7.4.7**, MinIO **RELEASE.2025-09-07**,
OpenTelemetry Collector contrib **0.160.0**. На host публикуются только Langfuse UI **3000**,
OTLP receiver **4318** и health Collector-а **13133** — все на loopback. Базы, Redis и object storage
портов на host не имеют. Организация, проект и API-ключи Langfuse создаются при первом старте из `.env`,
поэтому копировать ключи из UI не требуется.

- Langfuse-специфичное отображение целиком находится в `transform/langfuse`:
  `gen_ai.operation.name=chat` → `generation`, `embabel.event.type` → `agent` / `tool` / `chain` /
  `embedding`, имена `chatbot.retrieval.*` → `retriever`, остальное → `span`.
- **Токены считаются один раз.** Embabel-обёртка `llm.invocation` повторяет usage вызова Spring AI;
  Collector снимает `gen_ai.usage.*` со всех спанов, кроме generation и embedding, и всегда удаляет
  `total_tokens` — суммируются только input и output.
- `session.id` → `langfuse.session.id`, `deployment.environment.name` → `langfuse.environment`,
  `service.version` → `langfuse.release`, идентификаторы приложения → `langfuse.trace.metadata.*`.
  Обогащаются **все** спаны исполнения, не только root: этого требует модель фильтрации Langfuse.
- Заголовок `x-langfuse-ingestion-version: 4` обязателен: без него данные появляются с задержкой до
  10 минут.
- Очередь экспорта ограничена (1000, 2 consumer-а) и переживает рестарт Collector-а через
  `file_storage`. Это не защищает буфер SDK внутри приложения и не даёт exactly-once.
- Dashboard **Traces** показывает счётчики самого Collector-а; alerts `TraceQueueFilling`,
  `TraceSpansRefused`, `TraceSpansFailed` и `TraceExporterDrops` (O07: drops после исчерпания
  bounded retry) работают, пока профиль запущен. Отсутствие профиля не считается инцидентом:
  приложение, метрики и dashboards от него не зависят.
- Grafana не хранит traces. С O07 у dashboard **Traces** есть ссылка на Langfuse UI; поиск конкретного
  trace идёт по `traceId` из structured log или diagnostics. Tempo сознательно не включён: общий trace UI
  в Grafana не нужен, пока Langfuse покрывает разбор выполнения.

### Сбои trace pipeline (проверено в O07)

Воспроизводимый сценарий: `uv run scripts/verify_outage.py --output ... --application-log ...`
(evidence хранится локально). Ключевые факты:

- **Collector недоступен.** Запросы приложения не блокируются: спаны остаются в bounded-очереди
  BatchSpanProcessor SDK (2048), излишек SDK отбрасывает с записью в лог. После старта Collector-а
  приём восстанавливается без рестарта приложения. Остановка приложения при недоступном Collector
  ограничена flush-timeout (измерено: SIGTERM → exit за 8.95 s, не отправленное отбрасывается с логом).
- **Ответ Langfuse 401 или 500 — постоянная ошибка.** Collector 0.160 повторяет только 429/502/503/504;
  401 (ключи) и 500 отбрасывают партию сразу, счётчик `otelcol_exporter_send_failed_spans`, alert
  `TraceSpansFailed`/`TraceExporterDrops`. 429/503/504 и таймауты уходят в bounded retry (1s→30s,
  не более 5 минут), затем drop.
- **Медленный backend** паркует queue consumer-ов; backpressure виден как рост `otelcol_exporter_queue_size`
  (alert `TraceQueueFilling` с 80%).
- **Переполнение очереди** (1000 batches): receiver по-прежнему отвечает 200 (`wait_for_result=false`),
  отброшенное при enqueue считается `otelcol_exporter_enqueue_failed_spans` → `TraceExporterDrops`.
  Принятое в очередь переживает рестарт Collector-а (`file_storage`, проверено доставкой после stop/start).
- Приложение про Langfuse не знает: отказ backend никогда не доходит до request path, кроме
  косвенного backpressure через заполненную очередь Collector-а.

### Backup/restore volumes (проверено в O07)

Все 8 named volumes проекта (`prometheus-data`, `grafana-data`, `otel-queue`, `langfuse-postgres-data`,
`langfuse-clickhouse-data`, `langfuse-clickhouse-logs`, `langfuse-minio-data`, `langfuse-redis-data`)
копируются файловым tar-способом и восстанавливаются в отдельный Compose project
(`chatbot-observability-restore`, порты переопределены). Воспроизводимый drill:
`uv run scripts/verify_restore.py --output ...` — backup, restore, проверка исторических данных
Prometheus, sqlite Grafana, API Langfuse и очереди Collector-а, затем `down -v` **только** restore-проекта
(evidence хранится локально).

Измеренный disk budget локального стека (drill 2026-09-11): **≈592 MiB суммарно** (153 MiB в tar.gz) —
Grafana 181 MiB (sqlite + provisioning/plugins state), ClickHouse data 169 MiB, ClickHouse logs 133 MiB,
PostgreSQL 68 MiB, MinIO 36 MiB, Prometheus 4.6 MiB, очередь Collector-а 1.1 MiB, Redis 0.07 MiB.
Рост ограничен retention Prometheus (14d/2GB) и TTL-политиками Langfuse/ClickHouse;
ClickHouse logs быстро растут и не ротируются Compose-ом — долгоживущему развёртыванию нужна
отдельная ротация.

Ограничения single-node: backup делается tar-ом из volume — живой ClickHouse мержит и удаляет parts
под читающим tar (наблюдалось «No such file or directory»), поэтому drill останавливает source-стек на
время backup и проверки (несколько минут простоя monitoring, на приложение не влияет) и сверяет
manifest каждого volume (пути, размеры, sha256 первых 4 KiB файлов ≤1 MiB) после restore.
Redis восстанавливает только persisted dump — потеря in-flight очереди Langfuse допустима.
Внутри tar сохраняются numeric uid/gid (ClickHouse 101:101) — на rootless Docker проверять ownership
после restore. Langfuse-web на восстановленных данных может становиться healthy 2–3 минуты (Prisma +
ClickHouse init) — не принимать за зависание. Это локальный single-node стек: без репликации, без
Alertmanager, без внешнего хранилища — его отказ не влияет на приложение.

## Типичные симптомы

| Симптом | Куда смотреть |
|---|---|
| Ответы `INSUFFICIENT_EVIDENCE` на вопросы, которые есть в базе | Playground: есть ли hit и его cosine против `sufficient-cosine`; `luceneIndex` health (`EMPTY`? `INCOMPATIBLE`?); `chatbot.documents{status=ready}` |
| Документ завис в `UPLOADED` | `chatbot.ingestion.queue`, health `luceneIndex` (при `INCOMPATIBLE` воркер паркует документы), `recentFailures` |
| Медленные ответы | `chatbot.ai.operation` vs `chatbot.retrieval.search` (логическая AI-операция включает prompt, tools и восстановление — не складывать с provider duration); время до первой дельты также включает retrieval и подготовку; `embabel.llm.tokens.total{direction=input}` показывает размер промпта |
| Ollama недоступен | health `ollama` (DOWN/OUT_OF_SERVICE с причиной) |
| Странные цитаты | `GET /api/diagnostics/retrieval/{traceId}` по `retrievalTraceId` ответа: какие чанки видела модель |

## O06: correlation и содержимое

Checkpoint O06 переносит application MDC/diagnostics вместе с trace context через
chat workers, Embabel parallel executor, Reactor и SSE sender. Ingestion processing начинает новый root
со span link к upload; pending/rerun используют context последнего enqueue, restart начинает новый trace.
Console содержит отдельные `requestId`, `conversationId`, `messageId`, `documentId`, реальные `traceId`/`spanId`.

`chatbot.observability.content-policy=metadata-only` — default для всех exporter-ов. Opt-in
`redacted-content` включает bounded sanitized text bridge, а `chatbot.observability.redact-values`
задаёт дополнительные точные значения для маскирования. Неизвестные attributes, HTTP содержимое,
exception detail и stack traces удаляются в обоих режимах. Console/framework logs также проходят
фильтрацию; непроверенные сообщения показываются как `Event details suppressed` с logger и level.
Поддерживаемый structured formatter:
`logging.structured.format.console=com.personal.chatbot.observability.SafeStructuredLogFormatter`.

Новые SSE families: `chatbot.sse.first.delta{answer.mode}` (только первая непустая queued delta) и
`chatbot.sse.completed{answer.mode,outcome}` (один terminal event, успешный stream без deltas — `no_delta`).
Active chat снимается после выхода worker, даже если client уже отключился.
