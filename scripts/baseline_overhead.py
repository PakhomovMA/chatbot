"""O07 overhead baseline: tracing off vs on vs exporters-down under identical fake-provider load.

Run with uv run scripts/baseline_overhead.py --output docs/observability/o07/evidence/baseline-overhead.json.
The gate (docs/observability-plan.md §10, "Предварительный performance gate"): p95 of the tracing-on
mode may exceed p95 of tracing-off by at most max(5% of the off baseline, 1 ms). All times are kept in
seconds internally; milliseconds appear only in the human-readable summary.

Load is POST /api/retrieval/search against one small uploaded fixture: real Lucene/embedding work, no
chat endpoint, so Ollama latency never enters the measurement. The harness starts the application once
per mode with `./gradlew bootRun`, measures, and always stops it again (SIGTERM, 30 s, then SIGKILL),
including on failure.

Mode wiring (verified against src/main/resources/application*.yaml and docs/observability/o06):
  off            SPRING_PROFILES_ACTIVE=metrics                     (trace-export defaults to none)
  on             SPRING_PROFILES_ACTIVE=observability-otlp,metrics  (default OTLP endpoint :4318)
  exporters-down observability-otlp,metrics + CHATBOT_OTLP_ENDPOINT=http://127.0.0.1:14999/v1/traces
Ports come from SERVER_PORT / MANAGEMENT_SERVER_PORT (OS env overrides the `metrics` profile value,
as the O06 live gate used them); the data directory goes through bootRun --args because relaxed
binding maps `chatbot.data-dir` to CHATBOT_DATADIR, not CHATBOT_DATA_DIR. The ONNX model files are
read from the user's ~/.chatbot/models read-only; everything else lives in the temporary data-dir.
"""
import argparse
import json
import os
import queue
import re
import shutil
import signal
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN_CLASS = 'com.personal.chatbot.ChatbotApplication'  # build/resolvedMainClassName
SEARCH_PATH = '/api/retrieval/search'
EXPORT_FAILURE = re.compile(r'Failed to export spans')
ALLOC_PROM = re.compile(r'^jvm_gc_memory_allocated_bytes_total(?:\{[^}]*\})?\s+(\S+)$', re.M)
ACCEPTED_SPANS = re.compile(r'^otelcol_receiver_accepted_spans(?:\{[^}]*\})?\s+(\S+)$', re.M)

MODES = {
    'off': {'profiles': 'metrics', 'env': {}},
    'on': {'profiles': 'observability-otlp,metrics', 'env': {}},
    'exporters-down': {'profiles': 'observability-otlp,metrics',
                       'env': {'CHATBOT_OTLP_ENDPOINT': 'http://127.0.0.1:14999/v1/traces'}},
}

# One small self-contained corpus; the ten load queries below all hit it, so retrieval does real work.
FIXTURE_NAME = 'o07-baseline-fixture.md'
FIXTURE = """# O07 Baseline Operations

## Service restart
To restart the baseline payment service, execute `systemctl restart payments` on its host. Confirm
that `/health` returns `UP` before routing traffic back to the node.

## Service rollback
To roll back the baseline payment service, deploy the previous stable image and restart the service.
The synthetic example release is `payments:stable`.

## Health checks
The load balancer polls `/health` every five seconds and drains a node after three failed checks.

## Index maintenance
Nightly compaction merges index segments; a rebuild is only required after a schema change.
"""

QUERIES = [
    'How do I restart the payment service?',
    'How do I roll back the payment service?',
    'What does the health endpoint return?',
    'When does the load balancer drain a node?',
    'systemctl restart payments',
    'previous stable image rollback',
    'nightly compaction index segments',
    'when is an index rebuild required?',
    'health check interval',
    'restart and rollback procedure',
]

# --------------------------------------------------------------------------- pure helpers


def percentile(values, pct):
    """Linear-interpolation percentile; `values` are seconds, result is seconds."""
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    rank = (len(ordered) - 1) * pct / 100.0
    low = int(rank)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (rank - low)


def verdict(p95_off, p95_on):
    """Gate from docs/observability-plan.md §507: on-p95 may regress by at most max(5%, 1 ms)."""
    budget = max(0.05 * p95_off, 0.001)
    regression = p95_on - p95_off
    return {'p95OffMedianSeconds': p95_off, 'p95OnMedianSeconds': p95_on,
            'regressionSeconds': regression, 'budgetSeconds': budget, 'pass': regression <= budget}


def request(url, payload=None, headers=None, timeout=60):
    req = urllib.request.Request(url, data=payload, headers=headers or {})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.read().decode()


def request_json(url, payload=None, headers=None, timeout=60):
    return json.loads(request(url, payload, headers, timeout))


# --------------------------------------------------------------------------- application lifecycle


def find_app_pid(data_dir):
    """bootRun spawns a child JVM; locate it by the main class and this run's data-dir argument.
    No fallback to a bare main-class match: another harness may run its own app JVM on this machine,
    and SIGTERM/CPU sampling must never land on it."""
    out = subprocess.run(['ps', '-eo', 'pid=,args='], capture_output=True, text=True).stdout
    for line in out.splitlines():
        parts = line.split(None, 1)
        if len(parts) != 2:
            continue
        pid, args = parts
        if MAIN_CLASS in args and 'GradleDaemon' not in args and f'--chatbot.data-dir={data_dir}' in args:
            return int(pid)
    return None


def start_app(mode, args):
    spec = MODES[mode]
    env = dict(os.environ)
    env.update({'SPRING_PROFILES_ACTIVE': spec['profiles'],
                'SERVER_PORT': str(args.port),
                'MANAGEMENT_SERVER_PORT': str(args.management_port),
                'CHATBOT_ENVIRONMENT': 'local',
                'CHATBOT_RELEASE': 'o07-baseline'})
    env.update(spec['env'])
    log_path = Path(args.app_log_dir) / f'o07-baseline-{mode}.log'
    log = open(log_path, 'w')
    program_args = (f'--chatbot.data-dir={args.data_dir} '
                    f'--chatbot.embedding.onnx.model-dir={args.model_dir}')
    proc = subprocess.Popen(['./gradlew', 'bootRun', f'--args={program_args}'],
                            cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    return proc, log, log_path


def wait_health(management, timeout):
    deadline = time.time() + timeout
    last = 'no response'
    while time.time() < deadline:
        try:
            status = request_json(management + '/actuator/health', timeout=5)
            if status.get('status') == 'UP':
                return
            last = json.dumps(status)
        except (urllib.error.URLError, OSError, json.JSONDecodeError) as error:
            last = str(error)
        time.sleep(2)
    raise AssertionError(f'application did not become UP on {management} in {timeout}s ({last})')


def stop_app(proc, log, data_dir, timeout=30):
    """Graceful: SIGTERM the application JVM, then the Gradle wrapper; SIGKILL after the timeout."""
    pid = find_app_pid(data_dir)
    if pid is not None:
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    try:
        proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        if pid is not None:
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        proc.kill()
        proc.wait(timeout=10)
    log.close()


# --------------------------------------------------------------------------- captures


class CpuSampler(threading.Thread):
    """1 Hz `ps -o %cpu=` samples of the application JVM over the measured window."""

    def __init__(self, pid):
        super().__init__(daemon=True)
        self.pid = pid
        self.samples = []
        self._done = threading.Event()

    def run(self):
        while not self._done.is_set():
            try:
                out = subprocess.run(['ps', '-o', '%cpu=', '-p', str(self.pid)],
                                     capture_output=True, text=True, timeout=5).stdout.split()
                if out:
                    self.samples.append(float(out[0]))
            except (subprocess.SubprocessError, ValueError, IndexError):
                pass
            self._done.wait(1.0)

    def stop(self):
        self._done.set()


def allocated_bytes(management):
    """jvm.gc.memory.allocated (COUNTER, bytes) — present in docs/observability/o01/baseline/
    metric-inventory.json. Read from /actuator/prometheus because the `metrics` profile exposes only
    health,prometheus in every mode; the JSON /actuator/metrics endpoint is the fallback."""
    try:
        text = request(management + '/actuator/prometheus', timeout=10)
        match = ALLOC_PROM.search(text)
        if match:
            return float(match[1])
    except (urllib.error.URLError, OSError, ValueError):
        pass
    try:
        metric = request_json(management + '/actuator/metrics/jvm.gc.memory.allocated', timeout=10)
        return float(metric['measurements'][0]['value'])
    except (urllib.error.URLError, OSError, ValueError, KeyError, IndexError):
        return None


def collector_metrics_text(args):
    """The collector's own Prometheus endpoint (8888) is NOT host-published by
    ops/observability/compose.yaml (only 4318 and 13133 are). Try the host binding anyway, then a
    throwaway curl container on the Compose network, using an image already present locally."""
    try:
        return request(args.collector_metrics, timeout=3), 'host'
    except (urllib.error.URLError, OSError):
        pass
    if not shutil.which('docker'):
        return None, 'unavailable: no docker'
    try:
        images = subprocess.run(['docker', 'images', '--format', '{{.Repository}}:{{.Tag}}'],
                                capture_output=True, text=True, timeout=15).stdout.split()
    except subprocess.SubprocessError:
        return None, 'unavailable: docker images failed'
    curl = next((image for image in images if image.startswith('curlimages/curl:')), None)
    busybox = next((image for image in images if image.startswith('busybox:')), None)
    alpine = next((image for image in images if image.startswith('alpine:')), None)
    if curl:
        command = ['docker', 'run', '--rm', '--network', args.collector_network,
                   curl, '-s', 'http://otel-collector:8888/metrics']
    elif busybox or alpine:
        command = ['docker', 'run', '--rm', '--network', args.collector_network,
                   busybox or alpine, 'wget', '-qO-', 'http://otel-collector:8888/metrics']
    else:
        return None, 'unavailable: no curlimages/curl, busybox or alpine image'
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
        if result.returncode == 0 and result.stdout:
            return result.stdout, 'docker'
    except subprocess.SubprocessError:
        pass
    return None, 'unavailable: throwaway container failed'


def accepted_spans(args):
    text, source = collector_metrics_text(args)
    if text is None:
        return None, source
    values = [float(match) for match in ACCEPTED_SPANS.findall(text)]
    return (sum(values) if values else 0.0), source


def export_failures(log_path, skip_lines):
    try:
        lines = log_path.read_text(errors='replace').splitlines()
    except OSError:
        return None
    return sum(1 for line in lines[skip_lines:] if EXPORT_FAILURE.search(line))


def log_line_count(log_path):
    try:
        return len(log_path.read_text(errors='replace').splitlines())
    except OSError:
        return 0


# --------------------------------------------------------------------------- fixture and load


def ensure_fixture(base):
    """Upload the fixture once per data-dir; identical content deduplicates (200, no re-index)."""
    boundary = f'o07-{int(time.time())}'
    upload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{FIXTURE_NAME}"\r\n'
              f'Content-Type: text/markdown\r\n\r\n{FIXTURE}\r\n--{boundary}--\r\n').encode()
    uploaded = request_json(base + '/api/documents', upload,
                            {'Content-Type': 'multipart/form-data; boundary=' + boundary})
    document_id = uploaded['documentId']
    for _ in range(240):
        status = request_json(base + f'/api/documents/{document_id}/status')
        if status['status'] == 'READY':
            return document_id
        if status['status'] == 'FAILED':
            raise AssertionError(f'fixture ingestion failed: {status}')
        time.sleep(0.5)
    raise AssertionError(f'fixture did not become READY: {status}')


def run_phase(base, first_index, count, concurrency):
    """`count` completed searches, rotating the fixed queries; latencies in seconds."""
    work = queue.Queue()
    for index in range(first_index, first_index + count):
        work.put(index)
    latencies, errors = [], []
    lock = threading.Lock()

    def worker():
        while True:
            try:
                index = work.get_nowait()
            except queue.Empty:
                return
            payload = json.dumps(
                {'query': QUERIES[index % len(QUERIES)], 'topK': 8, 'mode': 'HYBRID'}).encode()
            started = time.monotonic()
            try:
                request(base + SEARCH_PATH, payload, {'Content-Type': 'application/json'})
                with lock:
                    latencies.append(time.monotonic() - started)
            except Exception as error:  # recorded, then asserted per repeat
                with lock:
                    errors.append(f'{type(error).__name__}: {error}')

    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        for _ in range(concurrency):
            executor.submit(worker)
    return latencies, errors


# --------------------------------------------------------------------------- one mode


def run_mode(mode, args, base, management):
    result = {'profiles': MODES[mode]['profiles'], 'env': MODES[mode]['env'],
              'port': args.port, 'managementPort': args.management_port}
    proc = log = log_path = None
    if not args.skip_app_start:
        proc, log, log_path = start_app(mode, args)
        result['applicationLog'] = str(log_path)
    elif args.app_log:
        log_path = Path(args.app_log)
        result['applicationLog'] = str(log_path)
    try:
        wait_health(management, args.startup_timeout)
        result['documentId'] = ensure_fixture(base)
        # Identical warm-up in every mode, discarded: JIT, ONNX session, Lucene caches.
        run_phase(base, 0, args.warmup, args.concurrency)
        allocated_before = allocated_bytes(management)
        spans_before, spans_source = accepted_spans(args) if mode == 'on' else (None, None)
        log_lines_before = log_line_count(log_path) if log_path else 0
        pid = find_app_pid(args.data_dir)
        result['applicationPid'] = pid
        sampler = CpuSampler(pid) if pid else None
        if sampler:
            sampler.start()
        next_index = args.warmup
        repeats = []
        for repeat in range(1, args.repeats + 1):
            latencies, errors = run_phase(base, next_index, args.requests, args.concurrency)
            next_index += args.requests
            assert not errors, f'{mode} repeat {repeat}: {len(errors)} failed requests, {errors[:3]}'
            assert len(latencies) == args.requests, (len(latencies), args.requests)
            repeats.append({'repeat': repeat, 'requests': len(latencies),
                            'p50Seconds': percentile(latencies, 50),
                            'p95Seconds': percentile(latencies, 95),
                            'meanSeconds': statistics.fmean(latencies)})
        if sampler:
            sampler.stop()
            sampler.join(timeout=5)
        result['repeats'] = repeats
        result['p50MedianSeconds'] = statistics.median(r['p50Seconds'] for r in repeats)
        result['p95MedianSeconds'] = statistics.median(r['p95Seconds'] for r in repeats)
        result['cpu'] = ({'samples': len(sampler.samples), 'meanPercent': statistics.fmean(sampler.samples)}
                         if sampler and sampler.samples else {'samples': 0, 'meanPercent': None})
        allocated_after = allocated_bytes(management)
        measured = args.repeats * args.requests
        if allocated_before is not None and allocated_after is not None:
            result['allocations'] = {'bytesBefore': allocated_before, 'bytesAfter': allocated_after,
                                     'bytesPerRequest': (allocated_after - allocated_before) / measured}
        else:
            result['allocations'] = {'bytesPerRequest': None,
                                     'reason': 'jvm.gc.memory.allocated not readable on either actuator endpoint'}
        failures = export_failures(log_path, log_lines_before) if log_path else None
        if mode == 'on':
            spans_after, _ = accepted_spans(args)
            telemetry = {'acceptedSpansSource': spans_source}
            if spans_before is not None and spans_after is not None:
                # Attribution caveat: the collector is shared, so a concurrent run inflates this.
                telemetry.update({'acceptedSpansDelta': spans_after - spans_before,
                                  'spansPerRequest': (spans_after - spans_before) / measured})
            else:
                telemetry['spansPerRequest'] = None
            telemetry['exportFailureLines'] = failures
            result['telemetry'] = telemetry
        elif mode == 'off':
            # No exporter is configured; zero export failures in the log is the zero-volume proof.
            result['telemetry'] = {'traceExport': 'none', 'exportFailureLines': failures}
            assert not failures, f'trace export attempted in off mode: {failures} failure lines'
        else:
            result['telemetry'] = {'exportFailureLines': failures,
                                   'failuresPerRequest': (failures / measured) if failures is not None else None}
    finally:
        if proc is not None:
            stop_app(proc, log, args.data_dir)
    return result


# --------------------------------------------------------------------------- entry point


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--modes', default='off,on,exporters-down',
                        help='Comma-separated subset of off,on,exporters-down; run sequentially')
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--requests', type=int, default=300, help='Measured requests per repeat')
    parser.add_argument('--warmup', type=int, default=50, help='Discarded requests before measuring')
    parser.add_argument('--concurrency', type=int, default=4)
    parser.add_argument('--port', type=int, default=18096)
    parser.add_argument('--management-port', type=int, default=18097)
    parser.add_argument('--data-dir', default='/tmp/o07-baseline-data')
    parser.add_argument('--model-dir', default=str(Path.home() / '.chatbot/models/embeddinggemma-300m'),
                        help='Existing ONNX model files, read-only')
    parser.add_argument('--collector-metrics', default='http://127.0.0.1:8888/metrics')
    parser.add_argument('--collector-network', default='chatbot-observability_default')
    parser.add_argument('--startup-timeout', type=float, default=420,
                        help='Includes the Gradle build on a cold start')
    parser.add_argument('--app-log-dir', default='/tmp')
    parser.add_argument('--skip-app-start', action='store_true',
                        help='The app is already running with the right profiles; do not start or stop it')
    parser.add_argument('--app-log', help='Application log to scan with --skip-app-start')
    args = parser.parse_args()

    modes = args.modes.split(',')
    unknown = [mode for mode in modes if mode not in MODES]
    assert not unknown, f'unknown modes: {unknown}'
    base = f'http://127.0.0.1:{args.port}'
    management = f'http://127.0.0.1:{args.management_port}'

    report = {'time': time.time(), 'load': {'endpoint': SEARCH_PATH, 'queries': len(QUERIES),
              'concurrency': args.concurrency, 'warmup': args.warmup, 'requests': args.requests,
              'repeats': args.repeats}, 'modes': {}}
    for mode in modes:
        print(f'=== mode {mode}: profiles {MODES[mode]["profiles"]} ===', flush=True)
        started = time.time()
        report['modes'][mode] = run_mode(mode, args, base, management)
        report['modes'][mode]['wallSeconds'] = round(time.time() - started, 1)
        median = report['modes'][mode]['p95MedianSeconds']
        print(f'    p95 median {median * 1000:.2f} ms over {args.repeats} repeats', flush=True)

    gate = None
    if 'off' in report['modes'] and 'on' in report['modes']:
        gate = verdict(report['modes']['off']['p95MedianSeconds'],
                       report['modes']['on']['p95MedianSeconds'])
        gate['exportersDownP95MedianSeconds'] = (report['modes'].get('exporters-down') or {}).get(
            'p95MedianSeconds')
    report['gate'] = gate
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')

    if gate is None:
        print(f'Partial run ({modes}); no gate verdict. Evidence: {args.output}')
        return
    print(json.dumps({'p95OffMs': round(gate['p95OffMedianSeconds'] * 1000, 3),
                      'p95OnMs': round(gate['p95OnMedianSeconds'] * 1000, 3),
                      'regressionMs': round(gate['regressionSeconds'] * 1000, 3),
                      'budgetMs': round(gate['budgetSeconds'] * 1000, 3),
                      'pass': gate['pass']}, indent=2))
    if not gate['pass']:
        sys.exit(1)


if __name__ == '__main__':
    main()
