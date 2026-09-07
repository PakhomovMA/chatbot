# Retrieval evaluation log

Как измеряется: `./gradlew ragEval` (`src/test/java/com/personal/chatbot/eval/RagEvalTest.java`) индексирует
5 golden-документов из `src/test/resources/eval/docs` реальной моделью EmbeddingGemma (ONNX) и прогоняет
44 вопроса из `questions.json`: 38 позитивных (ответ есть в корпусе) и 6 негативных (ответа нет).
Hit релевантен, если принадлежит ожидаемому документу и содержит одну из фраз `mustContain`
(без учёта регистра и пробелов). Метрики: Recall@5, MRR, nDCG@10 (одна релевантная цитата на вопрос),
доля позитивов с `evidenceSufficient=true`, доля негативов с `evidenceSufficient=true` (должна быть 0),
латентность p50/p95. Отчёты: `build/reports/rag-eval/*.json`. Параметры: `-Peval.chunkSize`,
`-Peval.overlap`, `-Peval.sufficientCosine`, `-Peval.minRecall` (gate, по умолчанию 0.8).

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
