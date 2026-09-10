"""O05 live gate: one synthetic trace through the collector into Langfuse, checked as a tree.

Run with uv run scripts/verify_traces.py --output /tmp/o05-traces.json.
Requires the `llm` Compose profile; it needs neither the application, nor Ollama, nor a model, because
the shape it sends is taken from the spans a real run produced in O01
(docs/observability/o01/baseline/classification-fixtures.json).

What it proves is what the plan asks for: every observation arrives with the type the adapter assigns,
the parent links of the tree survive the pipeline, one model call is one generation, and the tokens of
that call are counted once rather than once per structural span that repeats them.

Credentials are read from the ignored Compose .env and never written into the report.
"""
import argparse
import base64
import json
import secrets
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / 'docs/observability/o01/baseline/classification-fixtures.json'

# The tree, as (span name in the fixtures, parent, expected Langfuse type). The attributes come from
# the fixtures verbatim, so a change in what the frameworks emit shows up here rather than being
# re-invented by this script.
TREE = [
    ('chatbot.chat.request', None, 'SPAN'),
    ('agent KnowledgeAssistantAgent', 'chatbot.chat.request', 'AGENT'),
    ('action retrieveEvidence', 'agent KnowledgeAssistantAgent', 'CHAIN'),
    ('chatbot.retrieval.search', 'action retrieveEvidence', 'RETRIEVER'),
    ('embeddings embeddinggemma-300m', 'chatbot.retrieval.search', 'EMBEDDING'),
    ('action draftAnswer', 'agent KnowledgeAssistantAgent', 'CHAIN'),
    ('llm gemma4:12b', 'action draftAnswer', 'SPAN'),
    ('llm.invocation gemma4:12b', 'llm gemma4:12b', 'SPAN'),
    ('chat gemma4:12b', 'llm.invocation gemma4:12b', 'GENERATION'),
    ('tool-loop', 'action draftAnswer', 'CHAIN'),
    ('execute_tool knowledge_base_vectorSearch', 'tool-loop', 'TOOL'),
]

# The measurements this application publishes are named after the operation, and O01 recorded only a
# probe span of that shape, so the retrieval span is described here instead of taken from a fixture.
SYNTHETIC = {'chatbot.chat.request': {'mode': 'sync', 'answer.mode': 'agentic', 'outcome': 'success'},
             'chatbot.retrieval.search': {'mode': 'vector', 'outcome': 'success'}}

# What the model call reported, and therefore what the whole trace should add up to, once.
USAGE = {'input': 6438, 'output': 451}


def fixtures():
    by_name = {}
    for fixture in json.loads(FIXTURES.read_text()):
        by_name.setdefault(fixture['name'], fixture['attributes'])
    return by_name


def attributes(values):
    return [{'key': key, 'value': {'stringValue': str(value)}} for key, value in values.items()]


def request(url, data=None, headers=None, method=None):
    call = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    with urllib.request.urlopen(call, timeout=30) as response:
        body = response.read()
        return response.status, json.loads(body) if body else None


def send(collector, session, environment, release):
    """Posts the tree as one OTLP/HTTP trace and returns (trace id, {name: span id})."""
    known = fixtures()
    trace_id = secrets.token_hex(16)
    ids = {name: secrets.token_hex(8) for name, _, _ in TREE}
    now = time.time_ns()
    spans = []
    for index, (name, parent, _) in enumerate(TREE):
        values = dict(known.get(name, SYNTHETIC.get(name, {})))
        if name == 'chat gemma4:12b':
            values['gen_ai.usage.input_tokens'] = USAGE['input']
            values['gen_ai.usage.output_tokens'] = USAGE['output']
            values['gen_ai.usage.total_tokens'] = USAGE['input'] + USAGE['output']
        if name == 'llm.invocation gemma4:12b':
            # The wrapper repeats the usage of the call below it; the adapter has to drop it.
            values['gen_ai.usage.input_tokens'] = USAGE['input']
            values['gen_ai.usage.output_tokens'] = USAGE['output']
            values['gen_ai.usage.total_tokens'] = USAGE['input'] + USAGE['output']
        values['session.id'] = session
        values['chatbot.request.id'] = 'o05-request'
        values['chatbot.message.id'] = 'o05-message'
        span = {'traceId': trace_id, 'spanId': ids[name], 'name': name, 'kind': 1,
                'startTimeUnixNano': str(now + index * 1_000_000),
                'endTimeUnixNano': str(now + (index + len(TREE)) * 1_000_000),
                'attributes': attributes(values), 'status': {}}
        if parent:
            span['parentSpanId'] = ids[parent]
        spans.append(span)
    payload = {'resourceSpans': [{
        'resource': {'attributes': attributes({'service.name': 'chatbot',
                                               'deployment.environment.name': environment,
                                               'service.version': release})},
        'scopeSpans': [{'scope': {'name': 'o05-verify'}, 'spans': spans}]}]}
    status, _ = request(collector.rstrip('/') + '/v1/traces', json.dumps(payload).encode(),
                        {'Content-Type': 'application/json'})
    assert status == 200, status
    return trace_id, ids


# Langfuse 4 runs in "events only" mode: the v1 trace endpoints are gone and observations are read
# through v2 with explicit field groups.
OBSERVATIONS = '/api/public/v2/observations'


def await_trace(langfuse, headers, trace_id, deadline):
    """Langfuse ingests asynchronously; the wait is bounded and the absence is reported, not retried."""
    last = None
    query = f'{langfuse}{OBSERVATIONS}?traceId={trace_id}&limit=100&fields=core,basic,usage,metadata'
    while time.time() < deadline:
        try:
            status, page = request(query, headers=headers)
            observations = page.get('data', []) if status == 200 else []
            if len(observations) >= len(TREE):
                return observations
            last = f'{len(observations)} of {len(TREE)} observations'
        except urllib.error.HTTPError as error:
            if error.code not in (404, 500):
                raise
            last = f'HTTP {error.code}'
        time.sleep(3)
    raise AssertionError(f'Langfuse did not show the whole trace {trace_id} in time ({last})')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--collector', default='http://127.0.0.1:4318')
    parser.add_argument('--langfuse', default='http://127.0.0.1:3000')
    parser.add_argument('--env-file', type=Path, default=ROOT / 'ops/observability/.env')
    parser.add_argument('--timeout', type=float, default=180)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()

    env = dict(line.split('=', 1) for line in args.env_file.read_text().splitlines()
               if line and not line.startswith('#'))
    credential = base64.b64encode(
        f"{env['LANGFUSE_PUBLIC_KEY']}:{env['LANGFUSE_SECRET_KEY']}".encode()).decode()
    headers = {'Authorization': 'Basic ' + credential}
    environment = env.get('LANGFUSE_ENVIRONMENT', 'local')
    session = 'o05-' + secrets.token_hex(4)

    trace_id, ids = send(args.collector, session, environment, 'o05')
    found = await_trace(args.langfuse, headers, trace_id, time.time() + args.timeout)

    observations = {observation['id']: observation for observation in found}
    problems = []
    report = {'traceId': trace_id, 'session': session, 'environment': environment,
              'langfuse': args.langfuse, 'observations': []}

    for name, parent, expected in TREE:
        observation = observations.get(ids[name])
        if observation is None:
            problems.append(f'{name}: not in the trace')
            continue
        report['observations'].append({'sent': name, 'name': observation.get('name'),
                                       'type': observation.get('type'),
                                       'parent': observation.get('parentObservationId'),
                                       'session': observation.get('sessionId'),
                                       'environment': observation.get('environment'),
                                       'usage': observation.get('usageDetails')})
        if observation.get('type') != expected:
            problems.append(f'{name}: type {observation.get("type")}, expected {expected}')
        if (observation.get('parentObservationId') or None) != (ids[parent] if parent else None):
            problems.append(f'{name}: parent {observation.get("parentObservationId")}, expected {parent}')
        # Every span of the execution carries them, not only the root (§7.2).
        if observation.get('sessionId') != session:
            problems.append(f'{name}: session {observation.get("sessionId")}, expected {session}')
        if observation.get('environment') != environment:
            problems.append(f'{name}: environment {observation.get("environment")}, expected {environment}')
        metadata = observation.get('metadata') or {}
        if metadata.get('requestId') != 'o05-request':
            problems.append(f'{name}: metadata requestId {metadata.get("requestId")}')

    generations = [o for o in found if o.get('type') == 'GENERATION']
    if len(generations) != 1:
        problems.append(f'{len(generations)} generations for one model call')

    # Usage is the model call's own, once: a structural span that repeats it must not add to the total.
    totals = [0, 0]
    for observation in found:
        if observation.get('type') == 'EMBEDDING':
            continue
        usage = observation.get('usageDetails') or {}
        totals = [totals[0] + (usage.get('input') or 0), totals[1] + (usage.get('output') or 0)]
    report['tokens'] = {'input': totals[0], 'output': totals[1], 'expected': USAGE}
    if totals != [USAGE['input'], USAGE['output']]:
        problems.append(f'tokens {totals}, expected {[USAGE["input"], USAGE["output"]]}')

    report['problems'] = problems
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    assert not problems, problems
    print(f'Verified {len(TREE)} observations of trace {trace_id}: '
          f'1 generation, {totals[0]} input and {totals[1]} output tokens counted once')


if __name__ == '__main__':
    main()
