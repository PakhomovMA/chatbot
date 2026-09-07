# Retrieval evaluation log

Как измеряется: `./gradlew ragEval` (`src/test/java/com/personal/chatbot/eval/RagEvalTest.java`) индексирует
5 golden-документов из `src/test/resources/eval/docs` реальной моделью EmbeddingGemma (ONNX) и прогоняет
44 вопроса из `questions.json`: 38 позитивных (ответ есть в корпусе) и 6 негативных (ответа нет).
Hit релевантен, если принадлежит ожидаемому документу и содержит одну из фраз `mustContain`
(без учёта регистра и пробелов). Метрики: Recall@5, MRR, nDCG@10 (одна релевантная цитата на вопрос),
доля позитивов с `evidenceSufficient=true`, доля негативов с `evidenceSufficient=true` (должна быть 0),
латентность p50/p95. Отчёты: `build/reports/rag-eval/*.json`. Параметры: `-Peval.chunkSize`,
`-Peval.overlap`, `-Peval.sufficientCosine`, `-Peval.minRecall` (gate, по умолчанию 0.8),
`-Peval.llm`, `-Peval.ollamaUrl`.

Второй набор — `questions-hard.json` (10 позитивов + 4 негатива «около темы»): вопросы словами
пользователя, а не документации, включая русские к английскому корпусу. Он существует ради ветки
`expandSearch` (Phase 9a), которая на golden-наборе не срабатывает вовсе; в gate по Recall он не входит.
Стратегии `REWRITE`/`HYDE` требуют работающей Ollama — без неё измеряются только остальные.

Ограничение: набор маленький (5 документов, 25 чанков), цифры показывают порядок, а не статистику.
Пороги пересматривать при смене embedding-модели (fingerprint) или существенном росте корпуса.

## 2026-09-07 — baseline Phase 4

Embedding: `onnx/embeddinggemma-300m/ef835ae565d8/768/gemma-prefix-v1/l2`. Fusion: RRF k=60,
candidates = topK×3, vector floor cos ≥ 0.0, BM25 rank-only.

### Chunking (режим HYBRID, порог достаточности при измерении 0.5)

| chunkSize / overlap | чанков | Recall@5 | MRR | nDCG@10 | pos min cos | neg max cos | p50 / p95 |
|---|---|---|---|---|---|---|---|
| 1200 / 150 (гипотеза плана) | 17 | 1.000 | 0.943 | 0.967 | 0.321 | 0.221 | 27 / 36 ms |
| **800 / 100 (принято)** | 25 | 1.000 | **0.974** | **0.981** | **0.341** | **0.172** | 27 / 37 ms |
| 1600 / 200 | 12 | 1.000 | 0.934 | 0.951 | 0.278 | 0.196 | 26 / 31 ms |

Решение: `chatbot.index.max-chunk-size=800`, `overlap-size=100`. Меньшие чанки дают лучший ранг первой
релевантной цитаты и больший зазор между позитивами и негативами при той же латентности. Смена chunker
меняет manifest индекса: существующий индекс становится `INCOMPATIBLE` и требует
`POST /api/knowledge-base/reindex`.

### Режимы (800/100, порог 0.3)

| Режим | Recall@5 | MRR | nDCG@10 | sufficient (pos) | sufficient (neg) | p50 / p95 |
|---|---|---|---|---|---|---|
| HYBRID | 1.000 | 0.974 | 0.981 | 1.00 | 0.00 | 27 / 32 ms |
| VECTOR | 1.000 | 0.925 | 0.958 | 1.00 | 0.00 | 21 / 24 ms |
| TEXT | 1.000 | 0.936 | 0.952 | n/a | n/a | 2 / 3 ms |

HYBRID лучше каждого фасета по MRR/nDCG: BM25 вытягивает точные идентификаторы (`vault-rotator`,
`Idempotency-Key`), вектор — перефразированные вопросы.

### Калибровка `sufficient-cosine`

Cosine лучшего hit-а (HYBRID): позитивы min 0.341, p10 0.389, медиана 0.535; негативы 0.085–0.172
(в VECTOR-режиме max 0.272). Порог **0.3** отделяет все позитивы от всех негативов с запасом ~0.04
снизу и ~0.07 сверху. Старое значение 0.5 помечало «недостаточно» 42–53% верных ответов.
Напоминание: Lucene возвращает cosine как `(1+cos)/2`; в конфигурации и API используется обычный cosine.

### Промахи

После нормализации пробелов промахов нет (единственный промах первого прогона, `run-06`, был вызван
переносом строки внутри фразы `fourteen days` в тексте чанка).

### Что мерить дальше

- Расширить golden set до ≥ 100 вопросов с вопросами, требующими 2+ чанков (nDCG с несколькими релевантными).
- Добавить негативы «около темы» (вопросы про платежи, ответа на которые нет), чтобы проверить порог 0.3.
- q8-квантование модели (`model_quantized`) против fp32 по тем же метрикам.
- `expandNeighbours` и dedup перекрывающихся чанков (Phase 9).

## 2026-09-07 — Phase 5, e2e grounded answers (Ollama `qwen3:14b`, thinking off, temperature 0.1)

`./gradlew test -PincludeTags=e2e` (`ChatE2eTest`): 5 golden-вопросов на реальном стеке (ONNX embeddings,
Lucene in-memory, Ollama). Все 5 — `GROUNDED`, по одной проверенной цитате, ответы содержат ожидаемые
идентификаторы/команды дословно. Структурированный вывод (`GroundedAnswerDraft`) qwen3:14b вернул с первой
попытки во всех случаях.

| Вопрос | Grounding | Всего | Retrieval | LLM |
|---|---|---|---|---|
| How do I restart the payments service? | GROUNDED | 35.5 s | 68 ms | 35.5 s |
| Where are secrets stored and how often are they rotated? | GROUNDED | 17.8 s | 196 ms | 17.6 s |
| Which header prevents duplicate orders? | GROUNDED | 16.6 s | 32 ms | 16.6 s |
| How quickly must the primary on-call respond to a page? | GROUNDED | 14.4 s | 51 ms | 14.3 s |
| How do I declare an incident? | GROUNDED | 18.2 s | 46 ms | 18.1 s |

Латентность целиком определяется генерацией (14B на локальной машине, до ~8 passages ≈ 6k символов в
промпте). Первый вопрос дольше из-за прогрева модели. Направления: streaming (Phase 7) для воспринимаемой
скорости, меньший `evidence-char-budget` или `top-k` для коротких вопросов, квантование модели.

## 2026-09-07 — Phase 7, streaming (`POST /api/chat/stream`)

Тот же агент, draft генерируется через Embabel `generateStream()` и отдаётся SSE-дельтами; верификация
цитат выполняется по полному тексту после потока. Замеры на реальном стеке (`qwen3:14b`, 2 документа):

| Вопрос | До первой дельты | Дельт | Всего | Grounding |
|---|---|---|---|---|
| How do I declare an incident and who owns communication? (холодная модель) | 42.2 s | 84 | 49.1 s | GROUNDED |
| What are the SEV-1 time targets? (тёплая) | ~4 s | 26 | 13.3 s | GROUNDED |
| How do I retry a failed settlement batch? (UI) | ~7 s | — | 19.4 s | GROUNDED |

Время до первой дельты = prompt processing в Ollama (evidence ≈ 6k символов) плюс прогрев модели после
простоя; сама генерация идёт ~70–80 мс/токен. Воспринимаемая скорость в UI заметно лучше синхронного режима
при той же общей латентности.

## 2026-09-07 — Phase 9c, agentic RAG через Embabel `ToolishRag`

Режим `AGENTIC` (`chatbot.chat.mode` или `options.mode` в запросе): действие `researchIteratively` отдаёт
модели `ToolishRag` поверх того же Lucene-стора (`LockedSearchOperations`: тот же RW-lock, query-префикс
для embeddings). Инструменты, которые Embabel построил из capabilities стора: `knowledge_base_vectorSearch`,
`knowledge_base_textSearch`, `knowledge_base_broadenChunk`, `knowledge_base_zoomOut`. Всё, что модель увидела
через инструменты, собирает `EvidenceCollector` (`ResultsListener`) — это и есть evidence для верификации
цитат; retrieval-инфраструктура не менялась (INV-06/07), LLM не касается Lucene напрямую (INV-01).

Сравнение на 5 golden-вопросах (`ChatE2eTest`, `qwen3:14b`, thinking off):

| Вопрос | DETERMINISTIC | AGENTIC | Поисков (agentic) |
|---|---|---|---|
| How do I restart the payments service? | GROUNDED, 35.5 s | GROUNDED, 36.5 s | 1 |
| Where are secrets stored and how often are they rotated? | GROUNDED, 17.8 s | GROUNDED, 26.1 s | 1 |
| Which header prevents duplicate orders? | GROUNDED, 16.6 s | GROUNDED, 25.8 s | 1 |
| How quickly must the primary on-call respond to a page? | GROUNDED, 14.4 s | GROUNDED, 25.0 s | 1 |
| How do I declare an incident? | GROUNDED, 18.2 s | GROUNDED, 29.7 s | 1 |

Наблюдения:

- Качество одинаковое (5/5 GROUNDED, те же цитаты), латентность agentic-режима выше на 8–11 с: два вызова
  модели (решение о поиске + финальный ответ) вместо одного, плюс tool-loop overhead.
- qwen3:14b всегда делает ровно один `vectorSearch` с исходным вопросом и `topK=5`; `textSearch`,
  `broadenChunk`, повторные запросы не использовал ни разу — на простом корпусе агентность не даёт выигрыша.
- Модель надёжно воспроизводит содержание, но неохотно копирует chunk id в `citedChunkIds` (первый прогон:
  0 цитат при верном ответе). Введён детерминированный fallback `EvidenceAttributor`: чанк засчитывается,
  только если ответ дословно переиспользует ≥ 3 характерных токена (команды, идентификаторы, числа) из чанка,
  который модель реально видела. С ним 5/5.
- Trace agentic-запроса в `/api/diagnostics/retrieval/{id}` содержит все увиденные чанки и список запросов
  (`candidates` = число поисков).

Вывод: default остаётся `DETERMINISTIC`; `AGENTIC` — feature flag для сложных вопросов (multi-hop,
сравнение документов), где ожидается несколько поисков. Следующие измерения: вопросы, требующие 2+ поисков;
`agentic-max-searches`; более сильная модель для tool use.

## 2026-09-07 — Phase 9c follow-up: таймаут tool loop и «зависание» agentic + stream

Симптом: статья 21 KB (`GLM-5.3.md`), вопрос на русском, режим AGENTIC + stream. UI показывал
«BodyStreamBuffer was aborted», повтор висел на «Researching…» без единого события.

Причина (по логу Ollama и thread dump): Embabel оборачивает весь `createObject()` — а в agentic-режиме это
оба хода модели плюс tool calls — в `llm-operations.prompts.default-timeout` = 60 s. Реальная стоимость на
`qwen3:14b` (Apple Silicon, thinking off):

| Шаг | Токены | Время |
|---|---|---|
| ход 1: решение о поиске (prompt 1169) | prompt eval 117 tok/s, 42 tok ответа | ~13 s холодный, ~3 s с кэшем |
| `vectorSearch(topK=5)` | — | < 1 s |
| ход 2: ответ по 5 пассажам (prompt 2290) | prompt eval ~10 s + 629 tok ответа при 12 tok/s | ~61 s |

Итого ~75 s > 60 s → `TimeoutException` → data-binding retry (`max-attempts` 10, backoff 30 ms) запускает
tool loop с нуля; Ollama при этом доделывает брошенные запросы (11 одинаковых циклов в логе). Один вопрос
= до 10 минут работы, второй запрос встаёт в очередь единственного слота Ollama.

Исправления:

- `embabel.agent.platform.llm-operations.prompts.default-timeout: 10m`, `data-binding.max-attempts: 2`;
  реальный ограничитель tool loop — `agentic-max-searches` и лимит итераций Embabel.
- SSE: keep-alive комментарий каждые 15 s; каждый tool call приходит как `status{stage=researching, detail}`
  (через callback `EvidenceCollector`), ответ agentic-режима — один `delta` с `[n]`-маркерами.
- Отмена: обрыв клиента замечается по неудачной записи heartbeat; деterministic stream закрывает Flux
  (и HTTP-стрим к Ollama), agentic loop прерывается перед следующим tool call (`CancellableTool`,
  `ChatCancelledException` как `ToolControlFlowSignal`, чтобы Embabel не делал retry). Текущую генерацию
  non-streaming `createObject` прервать нельзя — она дорабатывает до конца.

Вывод для eval: русскоязычные ответы примерно вдвое дороже английских по токенам; при `evidence-char-budget`
6000 и длинных статьях ход 2 упирается в скорость генерации, а не в retrieval.

## 2026-09-07 — `expand-neighbours`: соседние чанки как контекст

`chatbot.retrieval.expand-neighbours` (по умолчанию 0) добавляет к каждому хиту N чанков с каждой стороны
в порядке чтения. Расширение идёт **после** fusion и после `topK`: соседи не участвуют в ранжировании,
не имеют своих score, наследуют ранг своего хита и помечены `neighbourOf`. Тот же параметр уходит в
`SearchDefaults` агентной ветки, поэтому обе ветки читают корпус одинаково.

Замер на golden-наборе (19 позитивных вопросов, HYBRID, `topK` 10, chunk 800/overlap 100, бюджет
evidence 6000 символов — тот же, что в проде):

| expand | recall@5 | MRR | ответ найден | ответ в бюджете | пассажей | в бюджете | из них хитов | символов |
|---|---|---|---|---|---|---|---|---|
| 0 | 1.000 | 0.974 | 1.00 | 1.00 | 10.0 | 9.3 | 9.3 | 6064 |
| 1 | 1.000 | 0.974 | 1.00 | 1.00 | 16.4 | 9.4 | 6.2 | 9807 |
| 2 | 1.000 | 0.974 | 1.00 | 1.00 | 18.9 | 9.6 | 5.4 | 11207 |

Метрики ранжирования считаются только по сматчившимся чанкам, поэтому расширение не может их «улучшить»
искусственно; `ответ найден` — доля вопросов, где искомая фраза попала хоть в один показанный пассаж.

Вывод: на этом корпусе выигрыша нет и быть не может — `answerCoverage` уже 1.0 при `expand=0`, документы
короткие и самодостаточные (ровно случай, для которого Embabel держит дефолт 0). Цена видна в последней
колонке: при `expand=1` из ~9.4 пассажей в бюджете только 6.2 — сматчившиеся, то есть контекст вытесняет
три ранжированных хита; при `expand=2` — четыре. Латентность не меняется (расширение идёт по in-memory
storage чанков, +0 запросов к Lucene).

Дефолт остаётся 0. Параметр включать на корпусах связной прозы (длинные статьи, регламенты), где
провижн отрывается от управляющего предложения; тогда же поднимать `evidence-char-budget`, иначе
контекст покупается ценой хитов. Golden-набор этого случая не покрывает — для проверки нужен корпус
вроде 21 KB статьи из follow-up выше.

## 2026-09-08 — Phase 9a, `expandSearch`: второй проход retrieval по условию

`chatbot.chat.expand-search.strategy` (`NONE | NEIGHBOURS | REWRITE | HYDE`) включает ветку GOAP
`expandSearch`: она встаёт между `retrieveEvidence` и `draftAnswer`, только если первый проход не
дотянул до `sufficient-cosine`. Условие `evidenceReady` ложно → планировщик вставляет действие;
действие помечает результат `expansion`, условие становится истинным → повтор невозможен, вопрос
ищется максимум дважды. Проходы сливаются тем же RRF, что и фасеты; соседи остаются при своих хитах.
Ветка best-effort: сбой модели логируется и ответ пишется по первому проходу.

Замер: `./gradlew ragEval` (`expandSearchStrategies`), тот же корпус, `topK` 10, chunk 800/100,
`sufficient-cosine` 0.3. Queries для REWRITE/HYDE пишет `qwen3:14b` теми же промптами, что и прод
(`prompts/expand-search-*.jinja`), напрямую в Ollama. Кроме golden-набора добавлен набор
`questions-hard.json`: 10 позитивов «словами пользователя» (симптомы, перифразы, русские вопросы к
английскому корпусу) и 4 негатива «около темы» — golden-набор ветку не запускает вовсе.

| набор | стратегия | сработала | подняла выше порога | recall@5 | MRR | негативы «достаточно» | p50 модели |
|---|---|---|---|---|---|---|---|
| golden (38 поз.) | NONE | 0/38 | — | 1.000 | 0.974 | 0.00 | — |
| golden | NEIGHBOURS | 0/38 | — | 1.000 | 0.974 | 0.00 | — |
| golden | REWRITE | 0/38 | — | 1.000 | 0.974 | 0.00 | 2.7 s |
| golden | HYDE | 0/38 | — | 1.000 | 0.974 | 0.00 | 10.5 s |
| hard (10 поз.) | NONE | 0/10 | — | 1.000 | 0.725 | 0.75 | — |
| hard | NEIGHBOURS | 2/10 | 0 | 1.000 | 0.725 | 0.75 | — |
| hard | **REWRITE** | 2/10 | **1** | 1.000 | **0.817** | 0.75 | 2.6 s |
| hard | HYDE | 2/10 | 1 | 1.000 | 0.750 | 0.75 | 11.7 s |

Условие срабатывает редко, поэтому стратегии измерены ещё раз с принудительным вторым проходом на
каждом вопросе hard-набора — это качество самой стратегии, отдельно от того, права ли она условно:

| стратегия (forced) | recall@5 | MRR | p50 вопроса |
|---|---|---|---|
| baseline (NONE) | 1.000 | 0.725 | 27 ms |
| NEIGHBOURS | 1.000 | 0.725 | 53 ms |
| **REWRITE** | 1.000 | **0.875** | 3.1 s |
| HYDE | **0.900** | 0.694 | 10.9 s |

Наблюдения:

- **REWRITE — единственная стратегия, которая улучшает ранжирование.** Перефразировки словами
  документации поднимают правильный чанк выше (MRR 0.725 → 0.875 на forced-прогоне) и на hard-01/10
  вытаскивают вопрос из-под порога (`hard-10`: cosine 0.262 → 0.380). Recall не теряется ни разу.
- **HYDE вредит на этом корпусе**: выдуманный пассаж иногда вытесняет правильный чанк из топ-5
  (recall 1.000 → 0.900, MRR ниже baseline) и стоит вчетверо дороже — ~11 с на локальном 14B против
  ~2.6 с у REWRITE (пассаж длиннее списка запросов, и его надо целиком сгенерировать).
- **NEIGHBOURS как условная ветка бессмысленна**: соседи не участвуют в ранжировании и не имеют своих
  score, поэтому `maxVectorScore` не меняется — вопрос остаётся «недостаточным». Это настройка корпуса
  (`retrieval.expand-neighbours`, замер от 2026-09-07), а не способ спасти слабый retrieval.
- **Цена ветки платится на вопросах без ответа.** На golden-наборе она не сработала ни на одном
  позитиве (все 38 и так выше порога), но сработала на всех 6 негативах: вопрос, ответа на который нет,
  теперь стоит +1 вызов модели и +3 поиска перед «не нашёл». Перефразировки при этом не подняли ни один
  негатив выше порога (максимум 0.172 → 0.250).
- **Побочная находка про порог**: 3 из 4 негативов «около темы» (`Сколько стоит подписка на PagerDuty?`,
  `Which acquirer do we use in Brazil?`, `график отпусков команды платежей`) дают cosine 0.33–0.42, то
  есть проходят порог 0.3 и считаются «достаточной evidence». Порог калиброван на далёких от темы
  негативах и близкие не отделяет; от галлюцинаций здесь защищает только verifier и сам ответ модели
  («в переданных пассажах этого нет»). Это отдельная задача — калибровка на near-topic негативах.

Проверка на реальном стеке (`ChatE2eTest`, `qwen3:14b` + EmbeddingGemma + Lucene, вопрос
«How much of the real traffic sees a new version before it is everywhere?»):

```
executing action ...KnowledgeAssistantAgent.expandSearch
Expanded search [06c03cf6] REWRITE with 3 queries: 8 hits (+1 new), sufficient false -> true in 6221 ms
-> GROUNDED in 19 934 ms, widened with [canary deployment percentage configuration,
   traffic routing new version rollout, percentage of traffic exposed to new version]:
   "Five percent of production traffic is sent to the new version during the canary stage ... [2]"
```

План GOAP при этом сам вставил действие: `retrieveEvidence -> expandSearch -> draftAnswer -> verifyGrounding`,
а на вопросах, где первый проход достаточен, — прежний `retrieveEvidence -> draftAnswer -> verifyGrounding`.
Вызов переписывания через Embabel стоил ~6 с против ~2.6 с в eval: там прямой JSON-запрос к Ollama, здесь —
structured output Embabel (схема в промпте, tool loop) и холодная модель.

Решение: дефолт `strategy: REWRITE`, `queries: 3`. Он срабатывает редко (на этом корпусе — только на
вопросах ниже порога), помогает, когда срабатывает, и не портит ранжирование; `NONE` — для тех, кому
важнее не платить лишний вызов модели за вопрос без ответа. HYDE оставлен как опция: на корпусе связной
прозы с длинными статьями он может выглядеть иначе, чем на коротких runbook-ах.
