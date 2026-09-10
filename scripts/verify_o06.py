"""O06 live gate against an isolated application and the local Langfuse Compose stack.

Run with uv run scripts/verify_o06.py --application-log /tmp/o06-otlp.log --output /tmp/o06-live.json.
The application must use its own temporary data-dir, observability-otlp and decomposition enabled.
Only a synthetic fixture is uploaded; .env credentials never enter the report.
"""
import argparse
import base64
import json
import re
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

SENTINEL = 'sentinel-secret-O06-Z19'
ROOT = Path(__file__).resolve().parents[1]


def request(url, payload=None, headers=None, timeout=300):
    req = urllib.request.Request(url, data=payload, headers=headers or {})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.read().decode()


def events(body):
    parsed = []
    for block in body.replace('\r\n', '\n').split('\n\n'):
        name = next((line[6:].strip() for line in block.splitlines() if line.startswith('event:')), None)
        data = '\n'.join(line[5:] for line in block.splitlines() if line.startswith('data:'))
        if name and data:
            parsed.append((name, json.loads(data)))
    return parsed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--application', default='http://127.0.0.1:18086')
    parser.add_argument('--management', default='http://127.0.0.1:18087')
    parser.add_argument('--langfuse', default='http://127.0.0.1:3000')
    parser.add_argument('--application-log', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--verify-only', action='store_true', help='Re-read existing runs from Langfuse without model calls')
    args = parser.parse_args()
    if args.verify_only:
        previous = json.loads(args.output.read_text())
        document_id, runs = previous['documentId'], previous['runs']
    else:
        stamp = str(int(time.time()))
        fixture = (ROOT / 'src/test/resources/fixtures/observability-o06.md').read_text()
        boundary = 'o06-' + stamp
        upload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="o06-runbook.md"\r\n'
                  f'Content-Type: text/markdown\r\n\r\n{fixture}\r\n--{boundary}--\r\n').encode()
        uploaded = json.loads(request(args.application + '/api/documents', upload,
            {'Content-Type': 'multipart/form-data; boundary=' + boundary,
             'X-Request-Id': 'o06-upload-' + stamp, 'Authorization': 'Bearer ' + SENTINEL}))
        document_id = uploaded['documentId']
        for _ in range(120):
            status = json.loads(request(args.application + f'/api/documents/{document_id}/status'))
            if status['status'] == 'READY':
                break
            time.sleep(.25)
        assert status['status'] == 'READY', status['status']

        def chat(mode, streaming, conversation=None):
            session = conversation or ('o06-' + mode.lower() + '-' + stamp)
            question = ('How do I restart the payment service and how do I roll it back?' if mode == 'DETERMINISTIC'
                        else 'How do I restart the payment service?')
            payload = {'conversationId': session, 'message': question + ' Reference tag: ' + SENTINEL,
                       'options': {'mode': mode, 'includeDiagnostics': True}}
            started = time.monotonic()
            body = request(args.application + ('/api/chat/stream' if streaming else '/api/chat'),
                           json.dumps(payload).encode(), {'Content-Type': 'application/json', 'X-Request-Id': session})
            stream = events(body) if streaming else []
            if streaming:
                final = [value['response'] for name, value in stream if name == 'final']
                assert len(final) == 1, [name for name, _ in stream]
                answer = final[0]
            else:
                answer = json.loads(body)
            assert answer['conversationId'] == session
            assert answer['citations'] and answer['diagnostics'] is not None
            return {'mode': mode, 'streaming': streaming, 'session': session, 'messageId': answer['messageId'],
                    'grounding': answer['grounding'], 'citations': len(answer['citations']),
                    'diagnostics': answer['diagnostics'] is not None,
                    'retrievalTraceId': answer['retrievalTraceId'], 'seconds': round(time.monotonic() - started, 2),
                    'events': {n: sum(name == n for name, _ in stream) for n in sorted({n for n, _ in stream})}}

        with ThreadPoolExecutor(max_workers=2) as executor:
            tasks = [executor.submit(chat, 'DETERMINISTIC', True), executor.submit(chat, 'AGENTIC', False)]
            runs = [task.result() for task in tasks]
        # A second turn proves history propagation; agentic can finish without any token deltas.
        runs.append(chat('AGENTIC', True, runs[1]['session']))

    env = dict(line.split('=', 1) for line in (ROOT / 'ops/observability/.env').read_text().splitlines()
               if line and not line.startswith('#'))
    auth = base64.b64encode(f"{env['LANGFUSE_PUBLIC_KEY']}:{env['LANGFUSE_SECRET_KEY']}".encode()).decode()
    headers = {'Authorization': 'Basic ' + auth}
    problems = []
    for run in runs:
        log_lines = args.application_log.read_text().splitlines()
        matching = [line for line in log_lines if 'messageId=' + run['messageId'] in line and 'Chat [' in line]
        assert matching, run['messageId']
        run['traceId'] = re.search(r'traceId=([0-9a-f]{32})', matching[-1])[1]
        query = (args.langfuse + '/api/public/v2/observations?traceId=' + run['traceId']
                 + '&limit=100&fields=core,basic,usage,metadata,io')
        found = []
        for _ in range(60):
            found = json.loads(request(query, headers=headers, timeout=30)).get('data', [])
            if any(item.get('isRootObservation') for item in found) and any(item['name'] == 'chatbot.chat.request' for item in found):
                break
            time.sleep(1)
        assert found, run['traceId']
        by_id = {item['id']: item for item in found}
        run['observations'] = []
        for item in found:
            meta = item.get('metadata') or {}
            parent = item.get('parentObservationId')
            if parent and parent not in by_id:
                problems.append(f"{run['traceId']}: missing parent {parent}")
            if item.get('sessionId') != run['session'] or meta.get('messageId') != run['messageId']:
                problems.append(f"{item['name']}: mixed or missing execution metadata")
            if item.get('environment') != 'local':
                problems.append(f"{item['name']}: missing environment")
            release = meta.get('attributes.langfuse.release') or meta.get('resourceAttributes.service.version')
            if release != 'o06-validation':
                problems.append(f"{item['name']}: missing release")
            if item.get('input') or item.get('output'):
                problems.append(f"{item['name']}: content in metadata-only mode")
            if item['type'] not in ('GENERATION', 'EMBEDDING') and item.get('usageDetails'):
                problems.append(f"{item['name']}: usage duplicated on a structural span")
            if SENTINEL in json.dumps(item):
                problems.append('sentinel in Langfuse')
            run['observations'].append({'id': item['id'], 'name': item['name'], 'parent': parent,
                    'type': item['type'], 'session': item.get('sessionId'), 'release': release,
                    'inputEmpty': not item.get('input'), 'outputEmpty': not item.get('output'),
                    'environment': item.get('environment'), 'usage': item.get('usageDetails')})
        assert sum(item.get('isRootObservation', False) for item in found) == 1
    assert len({run['traceId'] for run in runs}) == len(runs)
    log = args.application_log.read_text()
    if SENTINEL in log:
        problems.append('sentinel in application/exporter log')
    metrics = {}
    for name in ['chatbot.chat.active', 'chatbot.sse.connections', 'chatbot.sse.first.delta', 'chatbot.sse.completed']:
        metrics[name] = json.loads(request(args.management + '/actuator/metrics/' + name))
    assert metrics['chatbot.chat.active']['measurements'][0]['value'] == 0
    report = {'documentId': document_id, 'runs': runs, 'metrics': metrics, 'problems': problems}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'runs': [{k: v for k, v in run.items() if k != 'observations'} for run in runs],
                      'problems': problems}, indent=2))
    assert not problems, problems


if __name__ == '__main__':
    main()
