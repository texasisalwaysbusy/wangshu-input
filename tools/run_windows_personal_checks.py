#!/usr/bin/env python3
"""Run existing, v2-attested PE tests in a NEW local evidence directory.

No compiler, installer, registration command or legacy Wine wrapper is selected
implicitly. --verify-only validates inputs without executing or writing anything.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import subprocess
import sys

from windows_receipts import (PROJECT, TEST_OUTPUTS, CHECKS, child,
    existing_file, load_build, new_destination, passed_output, require, sha,
    verify_map, verify_same_build, write_json_new, write_new)


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--production-receipt', type=Path, required=True)
    parser.add_argument('--tests-receipt', type=Path, required=True)
    parser.add_argument('--android-fixture', type=Path, required=True,
                        help='Existing owned synthetic Android-format roundtrip fixture')
    parser.add_argument('--android-fixture-sha256', required=True)
    parser.add_argument('--destination', type=Path, required=True, help='NEW directory outside source/build roots')
    parser.add_argument('--runner', type=Path, help='Explicit existing trusted executable/wrapper; no shell expansion')
    parser.add_argument('--path-style', choices=('native', 'wine'), default='native')
    parser.add_argument('--wine-prefix', type=Path, help='Existing initialized prefix, required for wine paths')
    parser.add_argument('--timeout', type=float, default=180)
    parser.add_argument('--verify-only', action='store_true')
    parser.add_argument('--synthetic-contract', action='store_true',
                        help='TEST HARNESS ONLY: label all results synthetic, never native runtime acceptance')
    args = parser.parse_args()
    require(0 < args.timeout <= 3600, 'Timeout must be finite, > 0 and <= 3600 seconds')
    production = load_build(args.production_receipt, 'production')
    tests = load_build(args.tests_receipt, 'tests')
    require(production['root'] != tests['root'], 'Production and tests require separate fresh build directories')
    fixture = existing_file(args.android_fixture)
    require(sha(fixture) == args.android_fixture_sha256, 'Android fixture hash mismatch')
    runner = existing_file(args.runner) if args.runner else None
    require(runner is not None or os.name == 'nt', 'Non-Windows hosts require an explicit existing --runner')
    require(runner is None or os.access(runner, os.X_OK), 'Runner is not executable')
    require(args.path_style != 'wine' or runner is not None, 'Wine path style requires an explicit runner')
    require(args.path_style == 'wine' or args.wine_prefix is None, '--wine-prefix only applies to wine paths')
    prefix = None
    if args.path_style == 'wine':
        require(os.name != 'nt' and args.wine_prefix is not None,
                'Wine paths require a Unix host and explicit existing --wine-prefix')
        prefix = args.wine_prefix.resolve()
        require(prefix.is_dir() and all((prefix / name).is_file() for name in ('system.reg', 'user.reg')),
                'Wine prefix must already be initialized; no prefix setup is performed by this tool')
    forbidden = [PROJECT, production['root'], tests['root']] + ([prefix] if prefix else [])
    destination = new_destination(args.destination, forbidden)
    require(not fixture.is_relative_to(destination) and
            (runner is None or not runner.is_relative_to(destination)), 'Destination overlaps fixture/runner input')
    sources = {name: child(tests['root'], name) for name in TEST_OUTPUTS.values()}
    sources['wangshu-ime-x64.dll'] = production['root'] / 'wangshu-ime-x64.dll'
    sources['android-fixture.json'] = fixture
    for name in production['record']['inputs_sha256']:
        if name.startswith('data/'):
            sources[name] = child(PROJECT, name)
    require(any(name.startswith('data/') for name in sources), 'No attested dictionary assets')
    staged = {'work/' + name: sha(path) for name, path in sources.items()}
    runner_hash = sha(runner) if runner else None
    tools_hash = {name: sha(PROJECT / name) for name in
                  ('tools/run_windows_personal_checks.py', 'tools/windows_receipts.py')}
    record = {
        'schema_version': 2, 'project_root': str(PROJECT), 'runtime_root': str(destination),
        'started_utc': now(), 'state': 'RUNNING',
        'evidence_kind': 'SYNTHETIC_CONTRACT_ONLY' if args.synthetic_contract else 'PE_EXECUTOR_OBSERVATION',
        'scope': 'Observed outputs of explicitly selected PE executor; TSF hosts are mocks. '
                 'PE headers and unsigned receipts do not establish compiler/runtime authenticity.',
        'production_receipt': str(production['path']), 'tests_receipt': str(tests['path']),
        'production_attestation_sha256': production['sha256'],
        'tests_attestation_sha256': tests['sha256'],
        'runner': str(runner) if runner else None, 'runner_sha256': runner_hash,
        'path_style': args.path_style, 'wine_prefix': str(prefix) if prefix else None,
        'android_fixture_sha256': args.android_fixture_sha256, 'staged_sha256': staged,
        'tools_sha256': tools_hash, 'runs': [],
        'unrun': ['actual Windows GUI/application integration', 'Windows registration',
                  '32-bit runtime', 'physical microphone', 'voice helper runtime'],
    }
    if args.synthetic_contract:
        record['scope'] = 'SYNTHETIC CONTRACT FIXTURES ONLY. No genuine compilation, Windows/Wine execution or TSF acceptance.'
        record['unrun'].extend(['actual MinGW compilation', 'all genuine PE/TSF runtime suites'])
    if args.verify_only:
        print(json.dumps({'state': 'VERIFIED_NOT_RUN', 'suites': list(TEST_OUTPUTS),
              'destination': str(destination), 'writes': 'NONE', 'execution': 'NOT_RUN'}, indent=2))
        return 0
    # Allocate once, exclusively. A failed/interrupted directory is never reused.
    destination.mkdir(parents=True, exist_ok=False)
    write_json_new(destination / 'started.json', record)
    def runtime_path(path):
        return 'Z:' + str(path).replace('/', '\\') if args.path_style == 'wine' else str(path)
    try:
        for name, source in sources.items():
            write_new(destination / 'work' / name, source.read_bytes())
        verify_map(destination, staged)
        environment = dict(os.environ)
        if prefix:
            environment['WINEPREFIX'] = str(prefix)
        for variable, name in [('LOCALAPPDATA', 'local-app-data'), ('APPDATA', 'app-data'),
                               ('TEMP', 'temp'), ('TMP', 'temp')]:
            folder = destination / 'work' / name
            folder.mkdir(exist_ok=True)
            environment[variable] = runtime_path(folder)
        record['environment_overrides'] = {key: environment[key] for key in
                                           ('LOCALAPPDATA', 'APPDATA', 'TEMP', 'TMP')}
        if prefix:
            record['environment_overrides']['WINEPREFIX'] = str(prefix)
        for suite, name in TEST_OUTPUTS.items():
            verify_same_build(production, 'production')
            verify_same_build(tests, 'tests')
            verify_map(destination, staged)
            verify_map(PROJECT, tools_hash)
            require(runner is None or sha(runner) == runner_hash, 'Runner changed')
            executable = destination / 'work' / name
            arguments = []
            if suite == 'store':
                arguments = [runtime_path(destination / 'work' / 'store-test-evidence'),
                             runtime_path(destination / 'work' / 'android-fixture.json')]
            elif suite == 'dll':
                arguments = [runtime_path(destination / 'work' / 'wangshu-ime-x64.dll')]
            command = ([str(runner)] if runner else []) + [str(executable)] + arguments
            run = {'suite': suite, 'command': command, 'started_utc': now(),
                   'executable': 'work/' + name, 'executable_sha256': staged['work/' + name],
                   'raw': 'logs/' + suite + '.txt'}
            print('RUN ' + suite, flush=True)
            try:
                result = subprocess.run(command, cwd=destination / 'work', env=environment,
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=args.timeout)
                data = result.stdout
                run.update(exit_code=result.returncode, state='PASS' if
                           result.returncode == 0 and passed_output(suite, data) else 'FAIL')
            except subprocess.TimeoutExpired as error:
                data = error.stdout or b''
                run.update(exit_code=None, state='TIMEOUT_OUTCOME_NOT_PASSED',
                           error='Direct child timed out; descendant outcome may be unknown. Do not retry blindly.')
            raw = destination / run['raw']
            write_new(raw, data)
            run.update(raw_sha256=sha(raw), finished_utc=now())
            record['runs'].append(run)
            write_json_new(destination / 'runs' / (suite + '.json'), run)
            if run['state'] != 'PASS':
                record['state'] = run['state']
                break
        else:
            verify_same_build(production, 'production')
            verify_same_build(tests, 'tests')
            verify_map(destination, staged)
            verify_map(PROJECT, tools_hash)
            require(sha(fixture) == args.android_fixture_sha256, 'Original Android fixture changed')
            require(runner is None or sha(runner) == runner_hash, 'Runner changed')
            record.update(state='PASS', checks=CHECKS, inputs_and_staged_files_unchanged=True)
    except (OSError, ValueError) as error:
        record.update(state='ERROR', error=str(error))
    except KeyboardInterrupt:
        record.update(state='INTERRUPTED_OUTCOME_UNKNOWN', error='Check owned child processes before retrying')
    record['finished_utc'] = now()
    write_json_new(destination / 'runtime.json', record)
    print(json.dumps({'state': record['state'], 'runtime': str(destination / 'runtime.json')}, indent=2))
    return 0 if record['state'] == 'PASS' else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as error:
        print('REFUSED: ' + str(error), file=sys.stderr)
        raise SystemExit(2)
