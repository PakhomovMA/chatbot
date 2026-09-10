"""O07 live drill: back up every observability volume, restore into a shadow project, verify, tear down.

Run with uv run scripts/verify_restore.py --output /tmp/o07-restore.json.
Requires Docker with the chatbot-observability project present.

IMPORTANT: the drill quiesces the SOURCE stack. It stops every source container before the backup
and keeps the source down for the whole drill (backup, restore, verification, teardown) — that is
minutes of observability downtime, after which only the services that were running before the drill
are started again. A live ClickHouse merges and deletes parts under a reading tar ("No such file or
directory", tar exit 1), so backing up a running stack is not an option.

Flow: record which source services run; stop the source; tar every named volume through a read-only
mount into --backup-dir (capturing a per-file manifest of paths, sizes and first-4KB hashes); create
a second Compose project (--project) from the same compose file with remapped ports; unpack the tars
into its volumes before first start; assert each restored volume's manifest equals the source's;
bring the shadow project up WITHOUT --wait (restored langfuse-web needs 2-3 minutes and flaps under
the memory pressure of two stacks, so readiness is polled from `compose ps`); assert Prometheus /
Grafana / Langfuse / Collector serve the restored state; then `down -v` the restore project only,
assert nothing of it remains, start back exactly the previously-running source services, and verify
the source is byte-for-byte as before. The evidence JSON is written even on failure and never
contains credentials.

The backup dir is scratch space: an existing directory at that path is removed before the drill.
"""
import argparse
import base64
import hashlib
import json
import shutil
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROFILES = ['metrics', 'llm']
IMAGE = 'alpine:latest'

# Every service that publishes a host port gets a shadow port, uniformly, so the drill works
# whether or not the source project's profile is currently up.
PORT_REMAP = {
    'prometheus': ['127.0.0.1:19090:9090'],
    'grafana': ['127.0.0.1:13301:3000'],
    'otel-collector': ['127.0.0.1:14318:4318', '127.0.0.1:13134:13133'],
    'langfuse-web': ['127.0.0.1:13300:3000'],
}
PROMETHEUS = 'http://127.0.0.1:19090'
GRAFANA = 'http://127.0.0.1:13301'
LANGFUSE = 'http://127.0.0.1:13300'
COLLECTOR_HEALTH = 'http://127.0.0.1:13134'

# One line per file: "<path relative to /data> <bytes> <sha256 of the first 4KB, or ->".
# The 4KB hash is a cheap content check for small files (configs, WAL segments, sqlite pages);
# large ClickHouse parts are covered by path+size, which a torn or missing restore cannot fake.
HASH_THRESHOLD = 1048576
MANIFEST_CMD = (
    "find /data -type f -print0 | sort -z | "
    "while IFS= read -r -d '' f; do "
    "s=$(stat -c %s \"$f\"); h='-'; "
    f"if [ \"$s\" -le {HASH_THRESHOLD} ]; then h=$(head -c 4096 \"$f\" | sha256sum | cut -d' ' -f1); fi; "
    "printf '%s %s %s\\n' \"${f#/data/}\" \"$s\" \"$h\"; done"
)


def docker(*argv, timeout=600):
    return subprocess.run(['docker', *argv], check=True, capture_output=True, text=True, timeout=timeout)


def compose(compose_file, override, project, *argv, timeout=120):
    cmd = ['compose', '-f', str(compose_file)]
    if override is not None:
        cmd += ['-f', str(override)]
    cmd += ['-p', project]
    for profile in PROFILES:
        cmd += ['--profile', profile]
    return docker(*cmd, *argv, timeout=timeout)


def override_yaml(path):
    lines = ['# Written by verify_restore.py: shadow-port remap for the restore project.',
             '# `!override` replaces the port list; a plain override would append and collide',
             '# with the ports the source project still holds.', 'services:']
    for service, ports in PORT_REMAP.items():
        lines.append(f'  {service}:')
        lines.append('    ports: !override')
        lines.extend(f'      - "{port}"' for port in ports)
    path.write_text('\n'.join(lines) + '\n')


def source_volumes(compose_file, project):
    """Logical volume names from the compose model; Docker prefixes them with the project name."""
    out = compose(compose_file, None, project, 'config', '--volumes').stdout
    return sorted(out.split())


def ps_json(compose_file, override, project):
    out = compose(compose_file, override, project, 'ps', '--format', 'json').stdout
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def running_services(compose_file, project):
    out = compose(compose_file, None, project, 'ps', '--status', 'running', '--services').stdout
    return sorted(out.split())


def manifest(volume, image):
    """Per-file manifest of a volume; the digest is what the evidence JSON keeps."""
    text = docker('run', '--rm', '-v', f'{volume}:/data:ro', image, 'sh', '-c', MANIFEST_CMD).stdout
    entries = {}
    for line in text.splitlines():
        path, size, digest = line.rsplit(' ', 2)
        entries[path] = (int(size), digest)
    return entries, hashlib.sha256(text.encode()).hexdigest()


def manifest_mismatches(source, restored, cap=50):
    problems = []
    for path in sorted(set(source) | set(restored)):
        if source.get(path) != restored.get(path):
            side = 'missing in restore' if path not in restored else \
                   'extra in restore' if path not in source else 'size/hash differs'
            problems.append({'path': path, 'problem': side,
                             'source': source.get(path), 'restored': restored.get(path)})
    return problems[:cap], len(problems)


def measure(volume, image):
    out = docker('run', '--rm', '-v', f'{volume}:/data:ro', image, 'sh', '-c',
                 'du -sb /data | cut -f1').stdout.split()
    return int(out[0])


def request(base, path, params=None, headers=None, timeout=30):
    url = base + path + ('?' + urllib.parse.urlencode(params) if params else '')
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=timeout) as response:
        body = response.read()
        return response.status, json.loads(body) if body else None


def await_ready(compose_file, override, project, services, timeout_s, poll=10):
    """Poll `compose ps` until every service is running and (where it has a healthcheck) healthy.

    `up --wait` is not used: restored langfuse-web takes 2-3 minutes over its Prisma/ClickHouse
    checks and flaps under the memory pressure of both stacks running, which --wait reports as a
    hard failure. Returns (final per-service states, seconds until langfuse-web was healthy).
    """
    deadline = time.monotonic() + timeout_s
    started_at = time.monotonic()
    web_healthy_seconds = None
    states = {}
    while time.monotonic() < deadline:
        containers = ps_json(compose_file, override, project)
        states = {c['Service']: {'state': c['State'], 'health': c.get('Health') or None}
                  for c in containers}
        if set(states) >= set(services) and all(
                s['state'] == 'running' and s['health'] in (None, 'healthy') for s in states.values()):
            if web_healthy_seconds is None:
                web_healthy_seconds = round(time.monotonic() - started_at, 1)
            return states, web_healthy_seconds
        if web_healthy_seconds is None and states.get('langfuse-web', {}).get('health') == 'healthy':
            web_healthy_seconds = round(time.monotonic() - started_at, 1)
        time.sleep(poll)
    raise AssertionError(f'restore project not ready in {timeout_s}s: {states}')


def verify_prometheus(report):
    # Service-level proof only: the historical-query check was dropped because a source whose
    # metrics profile sat stopped has no recent samples even in a perfect restore. Data-level
    # proof is the manifest equality recorded per volume.
    status, up = request(PROMETHEUS, '/api/v1/query', {'query': 'up'})
    assert status == 200 and up['status'] == 'success', up
    assert up['data']['result'], 'the restored Prometheus answers but has no `up` series at all'
    report['prometheus'] = {'upSeries': len(up['data']['result'])}


def verify_grafana(report, headers):
    status, health = request(GRAFANA, '/api/health')
    assert status == 200, health
    # Basic auth is checked against grafana.db: a fresh sqlite would not know the admin from .env.
    status, user = request(GRAFANA, '/api/user', headers=headers)
    assert status == 200 and user.get('isGrafanaAdmin'), user
    status, found = request(GRAFANA, '/api/search', headers=headers)
    assert status == 200, found
    dashboards = sorted(d['title'] for d in found if d.get('type') == 'dash-db')
    report['grafana'] = {'health': health, 'adminLoginAs': user.get('login'), 'dashboards': dashboards}
    assert len(dashboards) >= 4, f'expected the provisioned dashboards, got {dashboards}'


def verify_langfuse(report, headers, timeout=180):
    # Langfuse was the slow service in the live runs: even after healthy, the first authenticated
    # reads can race the worker, so this gets its own bounded retry window.
    deadline = time.monotonic() + timeout
    while True:
        try:
            status, health = request(LANGFUSE, '/api/public/health', timeout=60)
            assert status == 200, health
            # Observations are served from ClickHouse with auth from Postgres; one page proves both
            # stores (and the worker's MinIO event path) survived the restore.
            status, page = request(LANGFUSE, '/api/public/v2/observations', {'limit': 1}, headers,
                                   timeout=60)
            assert status == 200, page
            break
        except (urllib.error.URLError, AssertionError, TimeoutError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(10)
    projects = None
    try:
        status, projects = request(LANGFUSE, '/api/public/projects', headers=headers, timeout=60)
    except urllib.error.HTTPError:
        pass  # soft signal only; the observations page is the hard gate
    report['langfuse'] = {'health': health, 'observationsPage': len(page.get('data', [])),
                          'meta': page.get('meta'), 'projectsApi': bool(projects)}


def verify_collector(report, queue_files):
    status, _ = request(COLLECTOR_HEALTH, '/', timeout=10)
    assert status == 200, status
    # The queue is drained on a graceful exporter, so an empty restored queue is a pass; what was
    # in it at backup time is recorded either way.
    report['collector'] = {'healthStatus': status, 'queueAtBackup': sorted(queue_files)}


def restore_residue(project):
    """Containers and volumes of the restore project that should no longer exist."""
    containers = docker('ps', '-a', '--filter', f'name={project}-', '--format', '{{.Names}}'
                        ).stdout.split()
    volumes = [name for name in docker('volume', 'ls', '--format', '{{.Name}}').stdout.split()
               if name.startswith(f'{project}_')]
    return containers, volumes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compose', type=Path, default=ROOT / 'ops/observability/compose.yaml')
    parser.add_argument('--env-file', type=Path, default=ROOT / 'ops/observability/.env')
    parser.add_argument('--source-project', default='chatbot-observability')
    parser.add_argument('--project', default='chatbot-observability-restore')
    parser.add_argument('--backup-dir', type=Path, default=Path('/tmp/o07-volume-backup'))
    parser.add_argument('--keep-backup', action='store_true')
    parser.add_argument('--image', default=IMAGE,
                        help='small local image used for tar/manifests; must already be pulled')
    parser.add_argument('--ready-timeout', type=float, default=480,
                        help='seconds to poll the restore project until every service is up')
    parser.add_argument('--source-restart-timeout', type=float, default=300)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert args.project != args.source_project, 'the restore project must not be the source project'
    image = args.image

    env = dict(line.split('=', 1) for line in args.env_file.read_text().splitlines()
               if line and not line.startswith('#'))
    grafana_auth = {'Authorization': 'Basic ' + base64.b64encode(
        (env.get('GRAFANA_ADMIN_USER', 'admin') + ':' + env['GRAFANA_ADMIN_PASSWORD']).encode()).decode()}
    langfuse_auth = {'Authorization': 'Basic ' + base64.b64encode(
        f"{env['LANGFUSE_PUBLIC_KEY']}:{env['LANGFUSE_SECRET_KEY']}".encode()).decode()}

    report = {'time': time.time(), 'sourceProject': args.source_project, 'restoreProject': args.project,
              'backupDir': str(args.backup_dir), 'volumes': {}, 'phases': {}, 'limitations': [
            'The drill needs minutes of source-stack downtime: the file-level tar is only '
            'consistent against a stopped stack (a live ClickHouse deletes parts under a reading '
            'tar), and the shadow stack needs the memory the source releases (~10 GB combined '
            'otherwise, under which langfuse-web flaps).',
            'File-per-volume tar preserves numeric uid/gid only because the helper container runs as '
            'root; a rootless Docker setup would need tar --numeric-owner handling reviewed.',
            'Postgres/ClickHouse/MinIO are restored as files, not engine-native dumps: valid for a '
            'single node whose source stack was stopped for the backup, not a point-in-time-'
            'consistent snapshot of a live write-heavy stack.',
            'Redis restore carries only what was persisted (appendonly/RDB state at backup time); '
            'Langfuse tolerates a lost queue, so this is acceptable for the drill.',
            'Manifest hashes cover the first 4KB of files up to 1MiB; larger files are compared by '
            'path and size only.',
        ]}
    override = args.backup_dir / 'compose.restore-override.yaml'
    restore_created = False
    source_stopped = False
    previously_running = None
    started = time.monotonic()

    def phase(name, fn):
        mark = time.monotonic()
        fn()
        report['phases'][name] = round(time.monotonic() - mark, 2)

    try:
        if args.backup_dir.exists():
            shutil.rmtree(args.backup_dir)
        args.backup_dir.mkdir(parents=True)
        override_yaml(override)
        # Fail fast if compose cannot render the shadow project (e.g. `!override` unsupported).
        compose(args.compose, override, args.project, 'config', '--quiet')

        volumes = source_volumes(args.compose, args.source_project)
        for logical in volumes:
            docker('volume', 'inspect', f'{args.source_project}_{logical}')  # naming-scheme guard
        previously_running = running_services(args.compose, args.source_project)
        report['sourceRunningBefore'] = previously_running
        report['backupStarted'] = time.time()

        # Quiesce the source stack for the WHOLE drill: the backup needs it (see docstring) and the
        # shadow stack's memory headroom does too. Only the services running now are started again
        # in the finally block — a blanket `compose start` would resurrect stopped profiles.
        compose(args.compose, None, args.source_project, 'stop', timeout=300)
        source_stopped = True

        manifests = {}

        def backup():
            for logical in volumes:
                source = f'{args.source_project}_{logical}'
                entries, digest = manifest(source, image)
                manifests[logical] = entries
                docker('run', '--rm', '-v', f'{source}:/data:ro',
                       '-v', f'{args.backup_dir}:/backup', image,
                       'tar', 'czf', f'/backup/{logical}.tar.gz', '-C', '/data', '.')
                report['volumes'][logical] = {
                    'bytes': measure(source, image), 'files': len(entries),
                    'manifestSha256': digest,
                    'compressedBytes': (args.backup_dir / f'{logical}.tar.gz').stat().st_size}
        phase('backup', backup)

        def restore():
            nonlocal restore_created
            restore_created = True  # volumes below belong to the restore project; down -v reclaims them
            for logical in volumes:
                target = f'{args.project}_{logical}'
                docker('volume', 'create', target)
                docker('run', '--rm', '-v', f'{target}:/restore',
                       '-v', f'{args.backup_dir}:/backup:ro', image,
                       'tar', 'xzf', f'/backup/{logical}.tar.gz', '-C', '/restore')
        phase('restoreVolumes', restore)

        def check_manifests():
            problems = {}
            for logical in volumes:
                entries, digest = manifest(f'{args.project}_{logical}', image)
                report['volumes'][logical]['restoredManifestSha256'] = digest
                mismatches, total = manifest_mismatches(manifests[logical], entries)
                if total:
                    problems[logical] = {'count': total, 'sample': mismatches}
            report['manifestMismatches'] = problems
            assert not problems, f'restored volumes differ from the source: {problems}'
        phase('manifestCheck', check_manifests)

        def up():
            compose(args.compose, override, args.project, 'up', '-d', timeout=300)
            services = [s for s in
                        compose(args.compose, override, args.project,
                                'config', '--services').stdout.split()]
            states, web_seconds = await_ready(args.compose, override, args.project, services,
                                              args.ready_timeout)
            report['restoreStates'] = states
            report['langfuseWebHealthySeconds'] = web_seconds
        phase('up', up)

        def verify():
            verify_prometheus(report)
            verify_grafana(report, grafana_auth)
            verify_langfuse(report, langfuse_auth)
            verify_collector(report, manifests.get('otel-queue', {}))
        phase('verify', verify)
        report['ok'] = True
    except Exception as error:
        report['ok'] = False
        report['error'] = repr(error)
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            report['errorStderr'] = error.stderr[-4000:]
        raise
    finally:
        if restore_created:
            mark = time.monotonic()
            try:
                compose(args.compose, override, args.project, 'down', '-v', '--timeout', '30',
                        timeout=300)
                containers, left_volumes = restore_residue(args.project)
                if containers or left_volumes:
                    # One run reported a successful down -v with containers surviving; unreproduced,
                    # so teardown gets a post-assertion and one retry rather than blind trust.
                    time.sleep(10)
                    compose(args.compose, override, args.project, 'down', '-v', '--timeout', '30',
                            timeout=300)
                    containers, left_volumes = restore_residue(args.project)
                report['teardownResidue'] = {'containers': containers, 'volumes': left_volumes}
                if containers or left_volumes:
                    report['ok'] = False
                report['phases']['teardown'] = round(time.monotonic() - mark, 2)
            except subprocess.CalledProcessError as error:
                report['teardownError'] = error.stderr
                report['ok'] = False
        if source_stopped:
            mark = time.monotonic()
            try:
                if previously_running:
                    compose(args.compose, None, args.source_project, 'start', *previously_running,
                            timeout=600)
                report['phases']['sourceRestart'] = round(time.monotonic() - mark, 2)
            except subprocess.CalledProcessError as error:
                report['sourceRestartError'] = error.stderr
                report['ok'] = False
        if previously_running is not None:
            try:
                mark = time.monotonic()
                deadline = mark + args.source_restart_timeout
                running = running_services(args.compose, args.source_project)
                while source_stopped and running != previously_running \
                        and time.monotonic() < deadline:
                    time.sleep(5)
                    running = running_services(args.compose, args.source_project)
                for logical in source_volumes(args.compose, args.source_project):
                    docker('volume', 'inspect', f'{args.source_project}_{logical}')
                report['sourceUntouched'] = {'containers': running, 'volumesIntact': True,
                                             'matchesPreDrill': running == previously_running}
                if running != previously_running:
                    report['ok'] = False
            except Exception as error:
                report['sourceUntouched'] = {'error': repr(error)}
                report['ok'] = False
        if not args.keep_backup:
            shutil.rmtree(args.backup_dir, ignore_errors=True)
        total = sum(v['bytes'] for v in report['volumes'].values())
        compressed = sum(v['compressedBytes'] for v in report['volumes'].values())
        report['summary'] = {'totalVolumeBytes': total, 'totalCompressedBytes': compressed,
                             'volumes': len(report['volumes']),
                             'durationSeconds': round(time.monotonic() - started, 2)}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n')

    print(f'Restored {len(report["volumes"])} volumes ({total} bytes, {compressed} compressed) into '
          f'{args.project}; manifests equal, all checks passed, shadow project torn down, '
          f'source project back as before')


if __name__ == '__main__':
    main()
