#!/usr/bin/env -S uv run
"""Collect and validate O01 evidence. stdlib only; run with uv run (see docs/observability/o01/README.md)."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + '\n')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_catalog():
    catalog = read(ROOT / 'docs/observability/metric-catalog.json')
    names = set()
    total = 0
    for metric in catalog['metrics']:
        require(metric['name'] not in names, f"Duplicate meter {metric['name']}")
        names.add(metric['name'])
        require(metric['boundary'] and metric['unit'], f"Missing boundary/unit: {metric['name']}")
        require(not set(metric['labels']) & set(catalog['forbiddenLabels']), 'High-cardinality label')
        combinations = 1
        for values in metric['labels'].values():
            require(isinstance(values, list) and values and len(values) == len(set(values)), 'Unbounded/duplicate values')
            combinations *= len(values)
        require(combinations == metric['maxLabelCombinations'], f"Wrong cardinality: {metric['name']}")
        histogram = metric['histogram']
        multiplier = len(catalog['histogramsSeconds'][histogram]) + 7 if histogram else (
            6 if metric['type'] == 'timer' else 3 if metric['type'] == 'summary' else 1)
        require(combinations * multiplier == metric['maxSeries'], f"Wrong series budget: {metric['name']}")
        if metric['name'] == 'chatbot.ai.operation':
            require(set(metric['labels']['operation']) == set(metric['operationBoundaries']), 'Missing AI operation boundary')
        total += metric['maxSeries']
    require(total == catalog['conservativeApplicationSeries'] and total <= catalog['applicationSeriesLimit'], 'Series budget exceeded')
    return catalog


def collect(live, provider, dependencies, destination):
    catalog = validate_catalog()
    require(not destination.exists(), 'Output must be a new directory; do not overwrite an earlier baseline')
    health = read(live / 'health.json')
    require(health['status'] == 'UP', 'Health gate failed')
    shutdown = read(live / 'shutdown.json')
    require(not shutdown['contextActive'] and shutdown['flushSucceeded'] and shutdown['exporterShutdownCalls'] >= 1,
            'Shutdown gate failed')
    require(shutdown['uniqueSpans'] == shutdown['exportedSpans'], 'Duplicate span export')
    require(all(read(live / 'scope.json').values()), 'Scope restoration failed')
    require(read(live / 'broken.pdf-status.json')['status'] == 'FAILED', 'No parse failure')
    beans = read(live / 'beans.json')
    for role in ['io.opentelemetry.api.OpenTelemetry', 'io.opentelemetry.sdk.trace.SdkTracerProvider', 'io.micrometer.tracing.Tracer']:
        require(sum(bean['role'] == role for bean in beans) == 1, f'Wrong owner count: {role}')
    require(any(b['class'].endswith('.ChatModelMeterObservationHandler') for b in beans), 'No provider usage handler')
    spans = read(live / 'spans.json')
    windows = {}
    for scenario in ['empty', 'sync', 'sse', 'agentic', 'decomposition']:
        if scenario == 'sse':
            events = (live / 'sse.sse').read_text().split('\n\n')
            finals = [event for event in events if 'event:final' in event]
            require(len(finals) == 1 and not any('event:error' in event for event in events), 'SSE terminal gate')
            response = json.loads(next(line[5:] for line in finals[0].splitlines() if line.startswith('data:')))['response']
        else:
            response = read(live / f'{scenario}.json')
        times = response['timings']
        require(times['llmMs'] == max(0, times['totalMs'] - times['retrievalMs']), f'Legacy residual changed: {scenario}')
        require(times['retrievalMs'] == response['diagnostics']['timings']['totalMs'], f'Retrieval projection changed: {scenario}')
        if scenario == 'empty':
            require(not response['diagnostics']['hits'] and response['grounding'] == 'INSUFFICIENT_EVIDENCE', 'Empty evidence gate')
        if scenario == 'decomposition':
            split = response['diagnostics'].get('decomposition')
            require(split and len(split['subQuestions']) >= 2, 'Decomposition was not exercised')
            require(split['tookMs'] == times['retrievalMs'], 'Decomposition legacy total changed')
        window = read(live / f'{scenario}-window.json')
        selected = [s for s in spans if window['startEpochMillis'] <= s['startEpochNanos'] / 1e6 <= window['endEpochMillis']]
        require(selected, f'No spans for {scenario}')
        windows[scenario] = {'window': window, 'spanIds': [s['spanId'] for s in selected],
                             'names': dict(Counter(s['name'] for s in selected))}
    require(any('tool' in s['name'].lower() and s['attributes'].get('embabel.event.type') == 'tool_call' for s in spans),
            'Agentic baseline did not execute an instrumented tool; rerun with a question that requires search')
    schemas = {}
    app_catalog = {m['name']: m for m in catalog['metrics']}
    for meter in read(live / 'meters.json'):
        name = meter['name']
        row = schemas.setdefault(name, {'name': name, 'variants': [], 'labels': {}, 'instances': 0})
        row['instances'] += 1
        variant = {'type': meter['type'], 'baseUnit': meter['baseUnit'], 'labelKeys': sorted(meter['labels'])}
        if variant not in row['variants']:
            row['variants'].append(variant)
        for key, value in meter['labels'].items():
            row['labels'].setdefault(key, set()).add(value)
        if name.startswith('chatbot.'):
            require(len(row['variants']) == 1, f'Inconsistent application meter schema: {name}')
            require(name in app_catalog, f'Undocumented application meter: {name}')
            require(set(meter['labels']) == set(app_catalog[name]['labels']), f'Application keys drift: {name}')
            for key, value in meter['labels'].items():
                require(value in app_catalog[name]['labels'][key], f'Undocumented value {name}/{key}={value}')
    for row in schemas.values():
        row['labels'] = {k: sorted(v) for k, v in row['labels'].items()}
    generation = [s for s in spans if s['attributes'].get('gen_ai.operation.name') == 'chat']
    wrappers = [s for s in spans if s['attributes'].get('embabel.event.type') == 'llm_call']
    require(generation and wrappers, 'No provider generation / Embabel LLM wrapper')
    require(not any(s['attributes'].get('gen_ai.operation.name') == 'chat' for s in wrappers), 'Double generation classification')
    expected = {'retry': (2, 1, 7, 3, 0), 'tools': (2, 2, 18, 8, 1), 'failure': (1, 1, 0, 0, 0), 'stream': (1, 1, 7, 3, 0)}
    for name, values in expected.items():
        result = read(provider / f'{name}.json')
        require(tuple(result[k] for k in ['httpAttempts', 'modelObservations', 'input', 'output', 'toolCalls']) == values, f'Provider gate: {name}')
    graph = read(dependencies)
    require(not any(a['name'] == 'micrometer-registry-prometheus' for a in graph['runtimeClasspath']), 'Production registry changed during baseline')
    destination.mkdir(parents=True)
    for file in ['beans.json', 'conditions.json', 'scope.json', 'shutdown.json', 'health.json', 'environment.json',
                 'empty.json', 'sync.json', 'sse.sse', 'agentic.json', 'decomposition.json', 'broken.pdf-status.json', 'spans.json', 'prometheus.txt']:
        shutil.copyfile(live / file, destination / file)
    shutil.copyfile(dependencies, destination / 'dependencies.json')
    shutil.copytree(provider, destination / 'provider')
    write(destination / 'metric-inventory.json', sorted(schemas.values(), key=lambda m: m['name']))
    declarations = []
    for metric in catalog['metrics']:
        if metric['compatibility'] == 'canonical':
            continue
        references = []
        for source in sorted((ROOT / 'src/main/java').rglob('*.java')):
            for number, line in enumerate(source.read_text().splitlines(), 1):
                if '"' + metric['name'] + '"' in line:
                    references.append({'file': str(source.relative_to(ROOT)), 'line': number})
        require(references, f"No source declaration for {metric['name']}")
        declarations.append({'name': metric['name'], 'labels': metric['labels'], 'sourceReferences': references,
                             'observedInBaseline': metric['name'] in schemas})
    write(destination / 'application-inventory.json', declarations)
    write(destination / 'scenario-spans.json', windows)
    # Real attributes and source span IDs are retained as future O05 mapping fixtures, not simulated Langfuse output.
    representatives = {}
    for span in spans:
        classification = 'GENERATION' if span['attributes'].get('gen_ai.operation.name') == 'chat' else 'SPAN'
        key = (span['name'], classification)
        representatives.setdefault(key, {'expectedLangfuseType': classification, 'sourceSpanId': span['spanId'],
                                        'name': span['name'], 'kind': span['kind'], 'attributes': span['attributes']})
    write(destination / 'classification-fixtures.json', list(representatives.values()))
    files = sorted(p for p in destination.rglob('*') if p.is_file())
    write(destination / 'manifest.json', {'sha256': {str(p.relative_to(destination)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
          'checks': ['health', 'single-sdk-provider-bridge', 'scope-restore', 'shutdown-flush', 'unique-span-export',
                     'sync-sse-agentic-decomposition-empty', 'parse-failure', 'legacy-timing-relations', 'tool-call',
                     'provider-retry-tools-stream-failure-usage', 'bounded-application-labels'],
          'applicationSeriesBudget': catalog['conservativeApplicationSeries']})
    print(f'O01 evidence validated: {destination}; {len(spans)} spans, {len(schemas)} meter names')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--live', type=Path)
    parser.add_argument('--provider', type=Path)
    parser.add_argument('--dependencies', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--verify', type=Path, help='Check the checksum manifest of an existing evidence directory')
    args = parser.parse_args()
    if args.verify:
        validate_catalog()
        manifest = read(args.verify / 'manifest.json')
        for file, expected in manifest['sha256'].items():
            path = args.verify / file
            require(path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == expected, f'Checksum mismatch: {file}')
        require({str(p.relative_to(args.verify)) for p in args.verify.rglob('*') if p.is_file() and p.name != 'manifest.json'} == set(manifest['sha256']),
                'Evidence file set differs from manifest')
        print(f"Verified {len(manifest['sha256'])} evidence files: {args.verify}")
    elif args.live:
        require(all([args.provider, args.dependencies, args.output]), 'Specify --live, --provider, --dependencies and --output')
        collect(args.live, args.provider, args.dependencies, args.output)
    else:
        catalog = validate_catalog()
        print(f"Catalog validated: {len(catalog['metrics'])} families, {catalog['conservativeApplicationSeries']} application series")


if __name__ == '__main__':
    main()
