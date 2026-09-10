"""O04 live gate: validate provisioned dashboards and their selectors against a real scrape.

Run with uv run scripts/verify_metrics.py --output /tmp/o04-validation.json.
Requires the metrics stack and an application exercised by sync/SSE/agentic/multi-pass traffic.
Credentials are read from the ignored Compose .env and never written into the report.
"""
import argparse
import base64
import json
import math
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def request(base, path, params=None, headers=None):
    url = base + path + ('?' + urllib.parse.urlencode(params) if params else '')
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=20) as response:
        return json.load(response)

def nodes(tree):
    if isinstance(tree, dict):
        yield tree
        for value in tree.values():
            yield from nodes(value)
    elif isinstance(tree, list):
        for value in tree:
            yield from nodes(value)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prometheus', default='http://127.0.0.1:9090')
    parser.add_argument('--grafana', default='http://127.0.0.1:3001')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    env = dict(line.split('=', 1) for line in (ROOT / 'ops/observability/.env').read_text().splitlines()
               if line and not line.startswith('#'))
    credential = base64.b64encode((env.get('GRAFANA_ADMIN_USER', 'admin') + ':' + env['GRAFANA_ADMIN_PASSWORD']).encode()).decode()
    headers = {'Authorization': 'Basic ' + credential}
    def prom(path, **params):
        result = request(args.prometheus, '/api/v1/' + path, params)
        assert result['status'] == 'success', result
        assert not result.get('warnings'), result
        return result['data']
    targets = prom('targets')['activeTargets']
    assert any(t['labels']['job'] == 'chatbot' and t['health'] == 'up' for t in targets), targets
    datasource = request(args.grafana, '/api/datasources/uid/chatbot-prometheus/health', headers=headers)
    assert datasource['status'] == 'OK', datasource
    # Gauges/counters may legitimately be zero. Timers only exist after their boundary was exercised.
    # An empty selector is a hard failure: it often means the dashboard invented a series name.
    report = {'time': time.time(), 'targets': [{'url': t['scrapeUrl'], 'health': t['health'], 'lastError': t['lastError']} for t in targets],
              'datasource': datasource, 'dashboards': [], 'queries': []}
    missing = []
    for file in sorted((ROOT / 'ops/observability/grafana/dashboards').glob('*.json')):
        expected = json.loads(file.read_text())
        actual = request(args.grafana, '/api/dashboards/uid/' + expected['uid'], headers=headers)
        assert actual['meta']['provisioned'] and actual['dashboard']['title'] == expected['title'], actual['meta']
        assert actual['dashboard']['panels'] == expected['panels'], file
        report['dashboards'].append(expected['uid'])
        for panel in expected['panels']:
            for target in panel.get('targets', []):
                expression = target['expr']
                tree = prom('parse_query', query=expression)
                names = sorted({node['name'] for node in nodes(tree) if node.get('type') in ('vectorSelector', 'matrixSelector') and node.get('name')})
                for name in names:
                    values = prom('query', query=name)['result']
                    if not values:
                        missing.append({'panel': panel['title'], 'metric': name})
                results = prom('query_range', query=expression, start=time.time()-1800, end=time.time(), step=15)['result']
                finite = sum(1 for result in results for _, value in result['values'] if math.isfinite(float(value)))
                report['queries'].append({'dashboard': expected['uid'], 'panel': panel['title'], 'expr': expression,
                                          'series': names, 'resultSeries': len(results), 'finiteSamples': finite})
                if not finite:
                    missing.append({'panel': panel['title'], 'expr': expression, 'reason': 'no finite samples in 30m'})
    count = prom('query', query='count({__name__=~"chatbot_.*",job="chatbot"})')['result']
    report['applicationSeries'] = int(float(count[0]['value'][1]))
    assert report['applicationSeries'] <= 5000, report['applicationSeries']
    report['missing'] = missing
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    assert not missing, f'Exercise the missing scenarios or fix the queries: {missing}'
    print(f"Verified {len(report['dashboards'])} dashboards, {len(report['queries'])} queries, {report['applicationSeries']} application series")

if __name__ == '__main__':
    main()
