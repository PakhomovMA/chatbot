"""O07 live gate: the trace pipeline under outages, against the local Langfuse Compose stack.

Run with:
    uv run scripts/verify_outage.py --output docs/observability/o07/evidence/outage.json \
        --application-log docs/observability/o07/evidence/outage-application.log

Scenario matrix (docs/observability-plan.md §8.3, phase O07):
    1 collector-down   — the application answers and stays healthy while the collector is stopped;
                         the SDK export fails on the batch worker and recovery needs no app restart.
    2 langfuse faults  — the collector exports into a stub that returns 200/401/429/500/slow;
                         the receiver keeps accepting, retries are bounded, the app keeps answering.
    3 queue persistence — spans queued under a failing backend survive a collector restart
                         (file_storage) and are delivered once the backend recovers.
    4 queue fill       — with a 5-batch queue the receiver starts refusing (observable drops),
                         the app still answers; afterwards the real config is restored and the
                         end-to-end Langfuse check passes.
    5 shutdown         — SIGTERM with the collector down exits inside flush-timeout + grace.

The application is built (bootJar) and started once with an isolated data-dir under /tmp, used by
scenarios 1-4 and stopped by scenario 5. Langfuse credentials are never read here and never enter
the evidence; the stub and the override files live on loopback and in /tmp and hold no secrets.
The Compose project is never taken down: at worst a failing run leaves the collector recreated
from the base file (try/finally below).
"""
import argparse
import json
import os
import secrets
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ROOT / 'ops/observability/compose.yaml'
COLLECTOR_CONFIG = ROOT / 'ops/observability/otel/collector.yaml'
NETWORK = 'chatbot-observability_default'
# A local image with busybox wget, so the collector's unpublished self-metrics port (8888) can be
# scraped from the Compose network without pulling anything.
SCRAPE_IMAGE = 'alpine:latest'

OVERRIDE_STUB = Path('/tmp/o07-outage-override.yaml')
OVERRIDE_SMALLQUEUE = Path('/tmp/o07-outage-smallqueue-override.yaml')
SMALLQUEUE_CONFIG = Path('/tmp/o07-collector-smallqueue.yaml')

QUESTION = 'How do I restart the payment service?'
EXPORT_ERROR_PATTERNS = ('Failed to export spans', 'ConnectException', 'Connection refused',
                         'Telemetry was not flushed', 'Telemetry flush failed')
STAMP = str(int(time.time()))


def request(url, payload=None, headers=None, timeout=60):
    req = urllib.request.Request(url, data=payload, headers=headers or {})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        body = response.read()
        return response.status, body


def attributes(values):
    return [{'key': key, 'value': {'stringValue': str(value)}} for key, value in values.items()]


def wait_until(predicate, timeout, interval=2.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if predicate():
                return True
        except Exception:
            pass
        time.sleep(interval)
    return False


def port_open(port, host='127.0.0.1'):
    with socket.socket() as probe:
        probe.settimeout(2)
        return probe.connect_ex((host, port)) == 0


def compose(*args, override=None):
    cmd = ['docker', 'compose', '-f', str(COMPOSE)]
    if override:
        cmd += ['-f', str(override)]
    cmd += ['--profile', 'llm', *args]
    run = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=300)
    if run.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} failed: {run.stderr.strip()[-500:]}")
    return run


def collector_healthy():
    try:
        status, _ = request('http://127.0.0.1:13133/', timeout=5)
        return status == 200
    except Exception:
        return False


def wait_collector(timeout=180):
    if not wait_until(collector_healthy, timeout, 3):
        raise RuntimeError('collector did not become healthy on 13133')


def collector_metrics():
    out = subprocess.run(['docker', 'run', '--rm', '--network', NETWORK,
                          '--entrypoint', 'wget', SCRAPE_IMAGE, '-qO-',
                          'http://otel-collector:8888/metrics'],
                         capture_output=True, text=True, timeout=90)
    if out.returncode != 0:
        raise RuntimeError('collector metrics scrape failed: ' + out.stderr.strip()[-300:])
    metrics = {}
    for line in out.stdout.splitlines():
        if line.startswith('#') or not line.strip():
            continue
        name = line.split('{', 1)[0].split(' ', 1)[0]
        try:
            value = float(line.rsplit(' ', 1)[1])
        except (ValueError, IndexError):
            continue
        metrics[name] = metrics.get(name, 0.0) + value
    return metrics


def sample_metrics(seconds, interval=4.0):
    samples = []
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            samples.append(collector_metrics())
        except Exception:
            pass
        time.sleep(interval)
    return samples


def at(metrics, name):
    return metrics.get(name)


def peak(samples, name):
    values = [sample[name] for sample in samples if name in sample]
    return max(values) if values else None


def post_spans(collector, count, tag):
    """Posts `count` one-span traces as one OTLP/HTTP request; returns (status, error)."""
    now = time.time_ns()
    spans = [{'traceId': secrets.token_hex(16), 'spanId': secrets.token_hex(8),
              'name': 'o07.outage.probe', 'kind': 1,
              'startTimeUnixNano': str(now + index * 1_000_000),
              'endTimeUnixNano': str(now + (index + 1) * 1_000_000),
              'attributes': attributes({'session.id': tag, 'chatbot.request.id': tag}),
              'status': {}} for index in range(count)]
    payload = {'resourceSpans': [{
        'resource': {'attributes': attributes({'service.name': 'chatbot',
                                               'deployment.environment.name': 'local',
                                               'service.version': 'o07'})},
        'scopeSpans': [{'scope': {'name': 'o07-outage'}, 'spans': spans}]}]}
    try:
        status, _ = request(collector.rstrip('/') + '/v1/traces', json.dumps(payload).encode(),
                            {'Content-Type': 'application/json'}, timeout=20)
        return status, None
    except urllib.error.HTTPError as error:
        return error.code, None
    except Exception as error:
        return None, str(error)


class Stub:
    """Switchable fake Langfuse OTLP endpoint. Bound on all host interfaces because the collector
    reaches the host through host.docker.internal, which on Docker Desktop is not loopback."""

    def __init__(self, port):
        self.mode = '200'
        self.lock = threading.Lock()
        self.received = 0
        self.delivered = 0  # requests answered 200
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def handle_request(self):
                length = int(self.headers.get('Content-Length') or 0)
                if length:
                    self.rfile.read(length)
                mode = stub.mode
                with stub.lock:
                    stub.received += 1
                if mode == 'slow':
                    # No client timeout is configured on the otlphttp exporter and 6 s answers were
                    # observed to succeed, so "slow" parks each consumer long enough to back the
                    # queue up instead of producing a clean per-attempt timeout.
                    time.sleep(30)
                    mode = '200'
                status = int(mode)
                body = b'{}' if status == 200 else b'fault'
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                if status == 200:
                    with stub.lock:
                        stub.delivered += 1

            do_GET = handle_request
            do_POST = handle_request

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(('0.0.0.0', port), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    @property
    def port(self):
        return self.server.server_address[1]

    def snapshot(self):
        with self.lock:
            return {'received': self.received, 'delivered': self.delivered, 'mode': self.mode}

    def stop(self):
        self.server.shutdown()
        self.server.server_close()


class App:
    def __init__(self, args):
        self.args = args
        self.proc = None
        self.pid = None
        self.started_by_script = False
        self.data_dir = None

    def healthy(self):
        try:
            status, body = request(self.args.management + '/actuator/health', timeout=5)
            return status == 200 and json.loads(body).get('status') == 'UP'
        except Exception:
            return False

    def ensure(self):
        if self.healthy():
            out = subprocess.run(['lsof', '-nP', '-iTCP:18086', '-sTCP:LISTEN', '-t'],
                                 capture_output=True, text=True)
            self.pid = int(out.stdout.split()[0])
            return 'adopted (already running, pid %d)' % self.pid
        subprocess.run(['./gradlew', 'bootJar', '-PskipFrontend', '-q'], cwd=ROOT,
                       check=True, timeout=900)
        jars = [jar for jar in (ROOT / 'build/libs').glob('*.jar') if 'plain' not in jar.name]
        assert len(jars) == 1, jars
        self.data_dir = Path(f'/tmp/chatbot-o07-data-{STAMP}')
        self.data_dir.mkdir(parents=True, exist_ok=True)
        env = dict(os.environ,
                   SPRING_PROFILES_ACTIVE='observability-otlp,metrics',
                   SERVER_PORT='18086',
                   MANAGEMENT_SERVER_PORT='18087',
                   CHATBOT_DATA_DIR=str(self.data_dir),
                   # The ONNX model files are shared read-only from the real data dir; everything
                   # the run writes (index, registry, uploads) stays in the isolated /tmp dir.
                   CHATBOT_EMBEDDING_ONNX_MODELDIR=str(Path.home() / '.chatbot/models/embeddinggemma-300m'),
                   CHATBOT_RELEASE='o07-outage',
                   CHATBOT_ENVIRONMENT='local')
        log = open(self.args.application_log, 'ab')
        self.proc = subprocess.Popen(['java', '--enable-native-access=ALL-UNNAMED',
                                      '-jar', str(jars[0])],
                                     cwd=ROOT, env=env, stdout=log,
                                     stderr=subprocess.STDOUT, start_new_session=True)
        self.pid = self.proc.pid
        self.started_by_script = True
        if not wait_until(self.healthy, self.args.startup_timeout, 3):
            raise RuntimeError(f'application did not become UP in {self.args.startup_timeout}s; '
                               f'see {self.args.application_log}')
        return 'started (pid %d, data-dir %s)' % (self.pid, self.data_dir)

    def running(self):
        if self.proc is not None:
            return self.proc.poll() is None
        try:
            os.kill(self.pid, 0)
            return True
        except (OSError, TypeError):
            return False

    def stop(self, timeout=120):
        """SIGTERM and wall time until exit. Returns seconds, or None if it did not exit."""
        started = time.monotonic()
        os.kill(self.pid, signal.SIGTERM)
        while time.monotonic() - started < timeout:
            if not self.running():
                return round(time.monotonic() - started, 2)
            time.sleep(0.2)
        return None


def chat(application, label, timeout=300):
    conversation = f'o07-{label}-{STAMP}'
    payload = {'conversationId': conversation, 'message': QUESTION,
               'options': {'mode': 'DETERMINISTIC'}}
    started = time.monotonic()
    try:
        status, body = request(application + '/api/chat', json.dumps(payload).encode(),
                               {'Content-Type': 'application/json', 'X-Request-Id': conversation},
                               timeout=timeout)
        answer = json.loads(body)
        ok = status == 200 and answer.get('conversationId') == conversation
        return {'conversation': conversation, 'ok': ok, 'status': status,
                'grounding': answer.get('grounding'), 'seconds': round(time.monotonic() - started, 2)}
    except Exception as error:
        return {'conversation': conversation, 'ok': False,
                'error': str(error)[:300], 'seconds': round(time.monotonic() - started, 2)}


def chat_batch(application, count, label):
    with ThreadPoolExecutor(max_workers=2) as executor:
        return list(executor.map(lambda index: chat(application, f'{label}-{index}'),
                                 range(count)))


def export_error_lines(log_path):
    if not Path(log_path).exists():
        return 0, None
    lines = [line for line in Path(log_path).read_text(errors='replace').splitlines()
             if any(pattern in line for pattern in EXPORT_ERROR_PATTERNS)]
    return len(lines), (lines[-1][:300] if lines else None)


def write_stub_override(endpoint):
    OVERRIDE_STUB.write_text(
        'services:\n'
        '  otel-collector:\n'
        '    environment:\n'
        f'      LANGFUSE_OTEL_ENDPOINT: {endpoint}\n')


def write_smallqueue_override(endpoint):
    source = COLLECTOR_CONFIG.read_text()
    changed = source.replace('queue_size: 1000', 'queue_size: 5')
    assert changed != source, 'queue_size: 1000 not found in collector.yaml'
    SMALLQUEUE_CONFIG.write_text(changed)
    OVERRIDE_SMALLQUEUE.write_text(
        'services:\n'
        '  otel-collector:\n'
        '    environment:\n'
        f'      LANGFUSE_OTEL_ENDPOINT: {endpoint}\n'
        '    volumes:\n'
        f'      - {SMALLQUEUE_CONFIG}:/etc/otelcol/collector.yaml:ro\n')


def recreate_collector(override):
    compose('up', '-d', 'otel-collector', override=override)
    wait_collector()


def restore_collector(evidence):
    """Back to the base compose file: real Langfuse endpoint, real collector.yaml."""
    compose('up', '-d', 'otel-collector')
    wait_collector()
    out = subprocess.run(['docker', 'inspect', '-f', '{{range .Config.Env}}{{println .}}{{end}}',
                          'chatbot-observability-otel-collector-1'],
                         capture_output=True, text=True, timeout=30)
    endpoint = next((line.split('=', 1)[1] for line in out.stdout.splitlines()
                     if line.startswith('LANGFUSE_OTEL_ENDPOINT=')), None)
    evidence['collectorEndpointAfterRestore'] = endpoint
    return endpoint


def run_traces_check(output):
    run = subprocess.run(['uv', 'run', 'scripts/verify_traces.py', '--output', str(output)],
                         cwd=ROOT, capture_output=True, text=True, timeout=420)
    return {'exitCode': run.returncode, 'stdout': run.stdout.strip()[-500:],
            'stderr': run.stderr.strip()[-500:]}


class Scenarios:
    def __init__(self, args, evidence):
        self.args = args
        self.evidence = evidence
        self.stub = None
        self.override_active = False

    # -- shared bits ---------------------------------------------------------

    def ensure_stub(self):
        if self.stub is None:
            try:
                self.stub = Stub(self.args.stub_port)
            except OSError:
                self.stub = Stub(0)
        return self.stub

    def ensure_stub_endpoint(self):
        """Recreate the collector exporting into the stub; prove it by observing a delivery."""
        stub = self.ensure_stub()
        endpoint = f'http://host.docker.internal:{stub.port}'
        write_stub_override(endpoint)
        recreate_collector(OVERRIDE_STUB)
        self.override_active = True
        before = stub.snapshot()['delivered']
        status, error = post_spans(self.args.collector, 3, 'o07-stub-probe')
        assert status == 200, (status, error)
        drained = wait_until(lambda: self.stub.snapshot()['delivered'] > before, 90, 2)
        assert drained, 'override did not take effect: stub received nothing from the collector'
        return endpoint

    def result(self, name, **fields):
        problems = fields.setdefault('problems', [])
        fields['pass'] = not problems
        self.evidence['scenarios'][name] = fields
        print(f'--- {name}: {"PASS" if not problems else "FAIL"}', flush=True)
        return fields

    # -- 1: collector down ---------------------------------------------------

    def scenario1(self):
        args = self.args
        problems = []
        baseline = chat_batch(args.application, args.chats, 's1-baseline')
        for run in baseline:
            if not run['ok']:
                problems.append(f"baseline chat failed: {run.get('error') or run}")
        metrics_before = collector_metrics()
        errors_before, _ = export_error_lines(args.application_log)

        compose('stop', 'otel-collector')
        wait_until(lambda: not port_open(4318), 60, 1)
        outage = chat_batch(args.application, args.chats, 's1-outage')
        for run in outage:
            if not run['ok']:
                problems.append(f"chat during collector outage failed: {run.get('error') or run}")
        health = None
        try:
            _, body = request(args.management + '/actuator/health', timeout=10)
            health = json.loads(body).get('status')
        except Exception as error:
            problems.append(f'health unreadable during outage: {error}')
        if health != 'UP':
            problems.append(f'health during outage: {health}')
        # The chats above are instant on an empty index, so give the BatchSpanProcessor's scheduled
        # export (scheduleDelay ~5s) a chance to hit the down collector and log its failure.
        export_logged = wait_until(
            lambda: export_error_lines(args.application_log)[0] > errors_before, 25, 1)
        errors_during, sample = export_error_lines(args.application_log)
        if not export_logged:
            problems.append('no SDK export error line appeared while the collector was down')

        compose('start', 'otel-collector')
        wait_collector()
        recovered_baseline = collector_metrics()
        status, error = post_spans(args.collector, 5, 'o07-s1-recovery')
        if status != 200:
            problems.append(f'synthetic spans after collector restart: {status} {error}')
        time.sleep(3)
        recovered = collector_metrics()
        before_count = recovered_baseline.get('otelcol_receiver_accepted_spans', 0)
        after_count = recovered.get('otelcol_receiver_accepted_spans', 0)
        if not after_count > before_count:
            problems.append(f'receiver accepted not increasing after restart: {before_count} -> {after_count}')

        latencies = lambda runs: [run['seconds'] for run in runs]
        return self.result(
            'collector-down',
            baseline={'chats': baseline, 'seconds': latencies(baseline)},
            outage={'chats': outage, 'seconds': latencies(outage), 'healthDuringOutage': health,
                    'sdkExportErrorLines': errors_during - errors_before,
                    'sdkExportErrorSample': sample},
            recovery={'acceptedSpansAfterRestart': [before_count, after_count],
                      'withoutAppRestart': True},
            notes=['While the collector is down the application-side BatchSpanProcessor keeps spans '
                   'in its bounded SDK queue and drops beyond it; export failures surface as SDK '
                   'error lines in the application log, never on the request path.'],
            problems=problems)

    # -- 2: langfuse fault matrix ---------------------------------------------

    def scenario2(self):
        args = self.args
        endpoint = self.ensure_stub_endpoint()
        stub = self.stub
        modes = {}
        problems = []
        # otlphttp retries only 429/502/503/504 (isRetryableStatusCode in the collector 0.160
        # sources); everything else is a permanent failure and drops the batch at once.
        retryable = {'429', '503'}
        for mode in ('401', '500', '429', '503', 'slow'):
            stub.mode = mode
            entry = {'stub': stub.snapshot()}
            before = collector_metrics()
            accepted_before = before.get('otelcol_receiver_accepted_spans', 0)
            failed_before = before.get('otelcol_exporter_send_failed_spans', 0)
            posted = 0
            statuses = []
            for batch in range(3):
                status, error = post_spans(args.collector, 10, f'o07-s2-{mode}-{batch}')
                statuses.append(status)
                posted += 10
            samples = sample_metrics(args.mode_wait, 4)
            after = samples[-1] if samples else collector_metrics()
            entry.update({
                'postedSpans': posted, 'postStatuses': statuses,
                'acceptedSpansDelta': after.get('otelcol_receiver_accepted_spans', 0) - accepted_before,
                'sendFailedSpansDelta': after.get('otelcol_exporter_send_failed_spans', 0) - failed_before,
                'queueSizePeak': peak(samples, 'otelcol_exporter_queue_size'),
                'queueSizeEnd': after.get('otelcol_exporter_queue_size'),
            })
            if entry['acceptedSpansDelta'] < posted:
                problems.append(f'{mode}: receiver accepted only {entry["acceptedSpansDelta"]} of {posted}')
            if mode in retryable:
                if not (entry['queueSizePeak'] or 0) > 0:
                    problems.append(f'{mode}: retryable fault but the queue never grew')
            elif mode == 'slow':
                if not ((entry['queueSizePeak'] or 0) > 0 or entry['sendFailedSpansDelta'] > 0):
                    problems.append('slow: no backpressure visible (queue flat, no failed sends)')
            else:
                # 401/500 are permanent: the batch is dropped immediately and counted.
                if not entry['sendFailedSpansDelta'] > 0:
                    problems.append(f'{mode}: permanent fault but send_failed stayed at 0')
            entry['chat'] = chat(args.application, f's2-{mode}')
            if not entry['chat']['ok']:
                problems.append(f'{mode}: chat failed during backend fault: {entry["chat"].get("error")}')
            entry['stub'] = stub.snapshot()
            modes[mode] = entry

        # Recovery: the stub answers again, the queue drains without a collector restart. The slow
        # mode leaves two consumers parked for up to 30 s, hence the generous wait.
        stub.mode = '200'
        sent_before = collector_metrics().get('otelcol_exporter_sent_spans', 0)
        delivered_before = stub.snapshot()['delivered']
        drained = wait_until(
            lambda: collector_metrics().get('otelcol_exporter_sent_spans', 0) > sent_before,
            240, 4)
        settled = collector_metrics()
        recovery = {'drained': drained,
                    'sentSpansDelta': settled.get('otelcol_exporter_sent_spans', 0) - sent_before,
                    'stubDeliveredDelta': stub.snapshot()['delivered'] - delivered_before,
                    'queueSizeEnd': settled.get('otelcol_exporter_queue_size')}
        if not drained:
            problems.append('backend recovery: sent_spans did not increase after stub returned 200')
        if not recovery['stubDeliveredDelta'] > 0:
            problems.append('backend recovery: stub received nothing after returning 200')

        return self.result(
            'langfuse-fault-matrix',
            endpoint=endpoint, modes=modes, recovery=recovery,
            notes=['The matrix asserts retry-in-progress and boundedness, not the full retry window: '
                   'a fault outlasting max_elapsed_time=5m drops the spans (bounded, counted), and '
                   'the TraceExporterDrops/TraceSpansFailed alerts cover that case. send_failed and '
                   'refused counters are lazy: absent until the first event.',
                   'Retryable status codes in otlphttp (collector 0.160 isRetryableStatusCode) are '
                   '429/502/503/504 only. 401 AND 500 are permanent: those batches are dropped '
                   'immediately, counted in otelcol_exporter_send_failed_spans, and never queue. '
                   '(500 was verified live as permanent in this run.)',
                   f'Each mode was held for {args.mode_wait}s, deliberately shorter than the 5m window.'],
            problems=problems)

    # -- 3: queue persistence across collector restart -------------------------

    def scenario3(self):
        args = self.args
        if not self.override_active:
            self.ensure_stub_endpoint()
        stub = self.stub
        # 503, not 500: only 429/502/503/504 are retried by otlphttp, and only retried batches
        # stay in the queue this scenario parks on disk.
        stub.mode = '503'
        stub_before = stub.snapshot()
        posted = 0
        for batch in range(5):
            status, error = post_spans(args.collector, 10, f'o07-s3-{batch}')
            assert status == 200, (status, error)
            posted += 10
        queued = wait_until(lambda: (collector_metrics().get('otelcol_exporter_queue_size') or 0) > 0,
                            90, 3)
        queue_size = collector_metrics().get('otelcol_exporter_queue_size')
        problems = []
        if not queued:
            problems.append('queue never filled while the backend returned 503')
        delivered_while_failing = stub.snapshot()['delivered'] - stub_before['delivered']
        if delivered_while_failing != 0:
            problems.append(f'stub delivered {delivered_while_failing} requests while answering 503')

        compose('stop', 'otel-collector', override=OVERRIDE_STUB)
        recreate_collector(OVERRIDE_STUB)
        # In-memory counters reset with the process; the on-disk queue is what survives.
        after_restart = collector_metrics()
        sent_after_restart = after_restart.get('otelcol_exporter_sent_spans', 0)
        time.sleep(15)
        delivered_during_restart_window = stub.snapshot()['delivered'] - stub_before['delivered']
        if delivered_during_restart_window != 0:
            problems.append('spans were delivered while the backend still failed after restart')

        stub.mode = '200'
        drained = wait_until(
            lambda: collector_metrics().get('otelcol_exporter_sent_spans', 0) - sent_after_restart
                    >= posted,
            180, 4)
        final = collector_metrics()
        sent_delta = final.get('otelcol_exporter_sent_spans', 0) - sent_after_restart
        delivered_delta = stub.snapshot()['delivered'] - stub_before['delivered']
        if not drained:
            problems.append(f'delivered-from-disk proof failed: sent delta {sent_delta} < {posted}')
        if not delivered_delta > 0:
            problems.append('stub received nothing after recovery')

        return self.result(
            'queue-persistence',
            postedSpans=posted, queueSizeBeforeRestart=queue_size,
            queueSizeAfterRestart=after_restart.get('otelcol_exporter_queue_size'),
            sentSpansDelta=sent_delta, stubRequestsDeliveredDelta=delivered_delta,
            notes=['At-least-once: the persistent queue may redeliver, so delivered >= queued is the '
                   'assertion, with the exact counts recorded. The spans still in the application '
                   'SDK buffer are not covered by file_storage.'],
            problems=problems)

    # -- 4: bounded queue fill --------------------------------------------------

    def scenario4(self):
        args = self.args
        self.ensure_stub()
        endpoint = f'http://host.docker.internal:{self.stub.port}'
        write_smallqueue_override(endpoint)
        recreate_collector(OVERRIDE_SMALLQUEUE)
        self.override_active = True
        self.stub.mode = '503'
        problems = []
        refused_baseline = collector_metrics()
        statuses = {}
        posted = 0
        deadline = time.monotonic() + 120
        observed = {}
        while time.monotonic() < deadline:
            status, error = post_spans(args.collector, 10, 'o07-s4')
            statuses[status] = statuses.get(status, 0) + 1
            posted += 10
            metrics = collector_metrics()
            refused = metrics.get('otelcol_receiver_refused_spans', 0)
            enqueue_failed = metrics.get('otelcol_exporter_enqueue_failed_spans', 0)
            observed = {'refusedSpans': refused, 'enqueueFailedSpans': enqueue_failed,
                        'queueSize': metrics.get('otelcol_exporter_queue_size'),
                        'queueCapacity': metrics.get('otelcol_exporter_queue_capacity')}
            if refused > 0 or enqueue_failed > 0 or (status is not None and status != 200):
                break
            time.sleep(0.5)
        observed['postStatuses'] = statuses
        observed['postedSpans'] = posted
        if not (observed.get('refusedSpans', 0) > 0 or observed.get('enqueueFailedSpans', 0) > 0):
            problems.append(f'no observable drops with a 5-batch queue: {observed}')
        if observed.get('queueCapacity') != 5:
            problems.append(f'small-queue override not in effect: capacity {observed.get("queueCapacity")}')
        run = chat(args.application, 's4')
        if not run['ok']:
            problems.append(f'chat failed while the receiver refused spans: {run.get("error")}')
        observed['chat'] = run

        # Restore the real config and endpoint, then prove the whole pipeline against Langfuse.
        endpoint_after = restore_collector(self.evidence)
        self.override_active = False
        if endpoint_after != 'http://langfuse-web:3000/api/public/otel':
            problems.append(f'collector endpoint after restore: {endpoint_after}')
        traces = run_traces_check(args.traces_output)
        if traces['exitCode'] != 0:
            problems.append(f'verify_traces after restore failed: {traces["stderr"][-300:]}')

        return self.result(
            'queue-fill-bounded',
            **observed,
            restoredEndpoint=endpoint_after, tracesCheck=traces,
            notes=['Filling the real 1000-batch queue takes 30+ minutes (batch timeout 2s), so the '
                   'collector was recreated once with queue_size 5 mounted over the read-only config; '
                   'everything else, file_storage included, is the real collector.yaml. Afterwards '
                   'the container was recreated from the base compose file and the end-to-end '
                   'Langfuse check re-ran.'],
            problems=problems)

    # -- 5: controlled shutdown with the collector down --------------------------

    def scenario5(self, app):
        args = self.args
        problems = []
        compose('stop', 'otel-collector')
        wait_until(lambda: not port_open(4318), 60, 1)
        run = chat(args.application, 's5')
        if not run['ok']:
            problems.append(f'chat before shutdown failed: {run.get("error")}')
        errors_before, _ = export_error_lines(args.application_log)
        seconds = app.stop()
        errors_after, sample = export_error_lines(args.application_log)
        log_tail = Path(args.application_log).read_text(errors='replace').splitlines()[-50:]
        flush_lines = [line for line in log_tail if 'Telemetry' in line]
        if seconds is None:
            problems.append('application did not exit within 120s of SIGTERM')
        else:
            # flush-timeout is 5s (ChatbotProperties default); graceful drain and the ordered stop
            # of background work get their own bounded phases. 30s is the assertion ceiling.
            if seconds > 30:
                problems.append(f'shutdown took {seconds}s, beyond flush-timeout + grace (30s ceiling)')
        if not flush_lines:
            problems.append('no bounded flush message in the application log tail')

        compose('start', 'otel-collector')
        wait_collector()
        traces = run_traces_check(args.traces_output)
        if traces['exitCode'] != 0:
            problems.append(f'final Langfuse trace check failed: {traces["stderr"][-300:]}')
        ps = compose('ps', '--format', 'json').stdout.strip()

        return self.result(
            'controlled-shutdown',
            chatBeforeShutdown=run,
            shutdownSeconds=seconds,
            exportErrorLinesDuringShutdown=errors_after - errors_before,
            exportErrorSample=sample,
            flushLogTail=[line.strip()[:200] for line in flush_lines][-3:],
            finalStack=ps.splitlines(),
            tracesCheck=traces,
            notes=['One bounded flush runs after the application work stops and before the SDK is '
                   'destroyed (chatbot.observability.flush-timeout, default 5s); an unreachable '
                   'collector may not hold the shutdown. Final state: llm profile up from the base '
                   'compose file, no override, real Langfuse endpoint.'],
            problems=problems)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--application', default='http://127.0.0.1:18086')
    parser.add_argument('--management', default='http://127.0.0.1:18087')
    parser.add_argument('--collector', default='http://127.0.0.1:4318')
    parser.add_argument('--stub-port', type=int, default=14319)
    parser.add_argument('--application-log', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--traces-output', type=Path,
                        default=ROOT / 'docs/observability/o07/evidence/traces-after-outage.json')
    parser.add_argument('--scenarios', default='1,2,3,4,5',
                        help='comma list; the app is stopped by 5 or at the end if it was started here')
    parser.add_argument('--chats', type=int, default=6)
    parser.add_argument('--mode-wait', type=float, default=25,
                        help='seconds each fault mode is held before scraping')
    parser.add_argument('--startup-timeout', type=float, default=600)
    args = parser.parse_args()

    wanted = {int(part) for part in args.scenarios.split(',')}
    evidence = {'stamp': STAMP, 'scenarios': {}, 'arguments': {
        'application': args.application, 'management': args.management,
        'collector': args.collector, 'chats': args.chats, 'modeWait': args.mode_wait}}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.application_log.parent.mkdir(parents=True, exist_ok=True)

    # promtool outputs from the rules check (docs/observability.md); re-run only if missing.
    promtool = [Path('/tmp/promtool-check-config.txt'), Path('/tmp/promtool-test-rules.txt')]
    if not all(path.exists() for path in promtool):
        commands = [
            ['docker', 'compose', '-f', str(COMPOSE), '--profile', 'metrics', 'run', '--rm',
             '--no-deps', '--entrypoint', 'promtool', 'prometheus', 'check', 'config',
             '/etc/prometheus/prometheus.yml'],
            ['docker', 'compose', '-f', str(COMPOSE), '--profile', 'metrics', 'run', '--rm',
             '--no-deps', '-w', '/etc/prometheus', '--entrypoint', 'promtool', 'prometheus',
             'test', 'rules', 'rules.test.yml']]
        for path, command in zip(promtool, commands):
            run = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=300)
            path.write_text(run.stdout + run.stderr)
    target = args.output.parent / 'promtool.txt'
    target.write_text('\n\n'.join(f'== {path} ==\n{path.read_text()}' for path in promtool))
    evidence['promtool'] = str(target)

    app = App(args)
    runner = Scenarios(args, evidence)
    app_started_here = False
    try:
        if wanted & {1, 2, 4, 5}:
            how = app.ensure()
            app_started_here = app.started_by_script
            evidence['application'] = {'start': how, 'dataDir': str(app.data_dir),
                                       'profiles': 'observability-otlp,metrics',
                                       'log': str(args.application_log)}
            print(f'--- application {how}', flush=True)
            if app_started_here:
                warmup = chat(args.application, 'warmup', timeout=420)
                evidence['application']['warmupChat'] = warmup
                assert warmup['ok'], warmup
                print(f'--- warmup chat ok ({warmup["seconds"]}s, {warmup["grounding"]})', flush=True)
        if 1 in wanted:
            runner.scenario1()
        if 2 in wanted:
            runner.scenario2()
        if 3 in wanted:
            runner.scenario3()
        if 4 in wanted:
            runner.scenario4()
        if 5 in wanted:
            runner.scenario5(app)
    finally:
        if runner.stub is not None:
            runner.stub.stop()
        if runner.override_active:
            try:
                restore_collector(evidence)
                runner.override_active = False
            except Exception as error:
                evidence.setdefault('cleanupProblems', []).append(f'collector restore: {error}')
        # Scenario 5 owns the stop in a full run; a partial run must not leak an app it started.
        if app_started_here and 5 not in wanted and app.running():
            seconds = app.stop()
            evidence.setdefault('cleanup', {})['appStoppedAfterPartialRunSeconds'] = seconds
        args.output.write_text(json.dumps(evidence, indent=2) + '\n')

    problems = {name: [p for p in scenario.get('problems', [])]
                for name, scenario in evidence['scenarios'].items()}
    problems = {name: found for name, found in problems.items() if found}
    args.output.write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps({'scenarios': {name: s['pass'] for name, s in evidence['scenarios'].items()},
                      'problems': problems}, indent=2))
    if problems:
        sys.exit(1)


if __name__ == '__main__':
    main()
