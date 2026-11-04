#!/usr/bin/env python3
"""Package explicit v2 build/runtime receipts into a NEW local development folder.

No build, runtime execution, installation, registration, publication or deletion.
Legacy runtime records and missing/duplicate suites fail closed.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import sys
import zipfile

from windows_receipts import (PROJECT, TEST_OUTPUTS, CHECKS, child, existing_file,
    load_build, new_destination, passed_output, read_json, require, sha, source_inputs,
    verify_map, verify_same_build, write_json_new, write_new)


def load_runtime(path, production, tests, synthetic=False):
    path = existing_file(path)
    record = read_json(path)
    require(type(record.get('schema_version')) is int and record['schema_version'] == 2,
            'Requires runtime receipt schema_version 2; rerun with adapted runner')
    root = path.parent
    require(path.name == 'runtime.json' and record.get('runtime_root') == str(root)
            and record.get('project_root') == str(PROJECT), 'Runtime receipt root/source mismatch')
    require(record.get('state') == 'PASS' and record.get('inputs_and_staged_files_unchanged') is True
            and record.get('checks') == CHECKS, 'Runtime did not pass the complete required suite set')
    expected_kind = 'SYNTHETIC_CONTRACT_ONLY' if synthetic else 'PE_EXECUTOR_OBSERVATION'
    require(record.get('evidence_kind') == expected_kind,
            'Wrong evidence kind; synthetic fixtures cannot qualify for ordinary binary packaging')
    for kind, build in [('production', production), ('tests', tests)]:
        require(record.get(kind + '_receipt') == str(build['path']) and
                record.get(kind + '_attestation_sha256') == build['sha256'],
                'Runtime belongs to a different ' + kind + ' build')
    required_tools = {'tools/run_windows_personal_checks.py', 'tools/windows_receipts.py'}
    require(isinstance(record.get('tools_sha256'), dict) and set(record['tools_sha256']) == required_tools,
            'Runtime verifier implementation manifest missing')
    verify_map(PROJECT, record['tools_sha256'])
    runner = record.get('runner')
    if runner is not None:
        require(isinstance(runner, str) and Path(runner).is_absolute(), 'Invalid runtime executor path')
        require(sha(existing_file(runner)) == record.get('runner_sha256'), 'Runtime executor changed/missing')
    else:
        require(record.get('runner_sha256') is None and record.get('path_style') == 'native',
                'Invalid native executor receipt')
    require(record.get('path_style') in ('native', 'wine'), 'Invalid runtime path style')
    expected_staged = {'work/' + name: tests['record']['outputs'][name] for name in TEST_OUTPUTS.values()}
    expected_staged['work/wangshu-ime-x64.dll'] = production['record']['outputs']['wangshu-ime-x64.dll']
    expected_staged['work/android-fixture.json'] = record.get('android_fixture_sha256')
    expected_staged.update({'work/' + name: digest for name, digest in production['record']['inputs_sha256'].items()
                            if name.startswith('data/')})
    require(record.get('staged_sha256') == expected_staged, 'Runtime staged membership/digests differ from build inputs')
    verify_map(root, expected_staged)
    runs = record.get('runs')
    require(isinstance(runs, list) and len(runs) == len(TEST_OUTPUTS), 'Missing or duplicate runtime suites')
    seen = set()
    for run in runs:
        require(isinstance(run, dict) and run.get('suite') in TEST_OUTPUTS
                and run['suite'] not in seen, 'Unknown or duplicate runtime suite')
        suite = run['suite']
        seen.add(suite)
        require(run.get('state') == 'PASS' and type(run.get('exit_code')) is int and run['exit_code'] == 0,
                'Nonpassing runtime suite: ' + suite)
        executable = 'work/' + TEST_OUTPUTS[suite]
        require(run.get('executable') == executable and
                run.get('executable_sha256') == expected_staged[executable], 'Runtime binary mismatch: ' + suite)
        require(run.get('raw') == 'logs/' + suite + '.txt', 'Unexpected raw log path')
        raw = child(root, run['raw'])
        require(raw.is_file() and sha(raw) == run.get('raw_sha256') and passed_output(suite, raw.read_bytes()),
                'Changed or nonpassing runtime log: ' + suite)
        saved = read_json(child(root, 'runs/' + suite + '.json'))
        require(saved == run, 'Per-suite receipt mismatch: ' + suite)
        command = run.get('command')
        require(isinstance(command, list) and all(isinstance(x, str) for x in command), 'Missing runtime command')
        def runtime_path(value):
            return 'Z:' + str(value).replace('/', '\\') if record['path_style'] == 'wine' else str(value)
        expected = ([runner] if runner else []) + [str(root / executable)]
        if suite == 'store':
            expected += [runtime_path(root / 'work/store-test-evidence'), runtime_path(root / 'work/android-fixture.json')]
        elif suite == 'dll':
            expected += [runtime_path(root / 'work/wangshu-ime-x64.dll')]
        require(command == expected, 'Runtime command does not match required suite: ' + suite)
    return {'path': path, 'root': root, 'record': record, 'sha256': sha(path)}


def source_members():
    names = set(source_inputs(PROJECT, tests=True))
    names.update(path.relative_to(PROJECT).as_posix() for path in (PROJECT / 'windows').glob('*')
                 if path.is_file() and path.suffix in {'.cmd', '.md', '.py', '.sh'})
    names.update(path.relative_to(PROJECT).as_posix() for path in (PROJECT / 'tests').glob('windows*.py')
                 if path.is_file())
    names.update('tools/' + name for name in ('run_windows_personal_checks.py',
                 'package_windows_personal.py', 'windows_receipts.py'))
    names.update('docs/' + name for name in ('ACCEPTANCE.md', 'PRIVACY.md', 'VOICE.md',
                 'WINDOWS-COMPATIBILITY.md', 'WINDOWS-RECEIPT-TOOLS.md'))
    # Optional high-level context is source material, never evidence of acceptance.
    for name in ('README.md', 'INTEGRATION-STATUS-20261103.md'):
        if (PROJECT / name).is_file():
            names.add(name)
    forbidden = {'.exe', '.dll', '.so', '.apk', '.zip', '.o', '.obj', '.a', '.lib', '.jar', '.aar', '.class'}
    require(all(Path(name).suffix.lower() not in forbidden for name in names),
            'Source-only manifest contains a compiled/archive input; review before packaging')
    return {name: sha(child(PROJECT, name)) for name in sorted(names)}


def package_source(args):
    require(args.production_receipt is None and args.tests_receipt is None and args.runtime is None
            and not args.synthetic_contract, '--source-only is separate from binary/runtime/synthetic packaging')
    destination = new_destination(args.destination, [PROJECT])
    before = source_members()
    status = {'schema_version': 2, 'state': 'SOURCE_CANDIDATE_ONLY', 'scope':
              'Windows source candidate only, not a complete Android checkout or installable Windows release.',
              'compilation': 'NOT_RUN', 'native_runtime_acceptance': 'NOT_RUN',
              'gui_registration_microphone': 'NOT_RUN', 'remote_push_publish_share': 'NONE',
              'source_sha256': before}
    if args.verify_only:
        print(json.dumps(dict(status, writes='NONE'), indent=2))
        return 0
    destination.mkdir(parents=True, exist_ok=False)
    archive = destination / 'wangshu-ime-windows-source-candidate.zip'
    try:
        with zipfile.ZipFile(archive, 'x', zipfile.ZIP_DEFLATED) as output:
            for name, digest in before.items():
                data = child(PROJECT, name).read_bytes()
                require(hashlib.sha256(data).hexdigest() == digest, 'Source changed: ' + name)
                output.writestr('source/' + name, data)
            output.writestr('CANDIDATE-STATUS.json', json.dumps(status, indent=2) + '\n')
        require(source_members() == before, 'Source bytes/membership changed during packaging')
        with zipfile.ZipFile(archive) as output:
            require(output.testzip() is None and set(output.namelist()) ==
                    {'source/' + name for name in before} | {'CANDIDATE-STATUS.json'}, 'Source archive CRC/membership mismatch')
            for name, digest in before.items():
                require(hashlib.sha256(output.read('source/' + name)).hexdigest() == digest,
                        'Source archive digest mismatch: ' + name)
        status.update(archive=archive.name, sha256=sha(archive), bytes=archive.stat().st_size,
                      zip_crc_and_all_source_sha256='PASS')
        write_new(destination / 'SHA256SUMS', (status['sha256'] + '  ' + archive.name + '\n').encode('ascii'))
        write_json_new(destination / 'artifact.json', status)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        write_json_new(destination / 'package-failure.json', {'state': 'FAIL', 'error': str(error),
                       'partial_outputs_retained': True})
        raise
    print(json.dumps({key: value for key, value in status.items() if key != 'source_sha256'}, indent=2))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--production-receipt', type=Path)
    parser.add_argument('--tests-receipt', type=Path)
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--verify-only', action='store_true')
    parser.add_argument('--source-only', action='store_true', help='Package Windows sources only; no native acceptance claimed')
    parser.add_argument('--synthetic-contract', action='store_true', help='TEST HARNESS ONLY: separately named synthetic artifact')
    args = parser.parse_args()
    if args.source_only:
        return package_source(args)
    require(args.production_receipt is not None and args.tests_receipt is not None and args.runtime is not None,
            'Binary packages require explicit --production-receipt, --tests-receipt and --runtime; otherwise use --source-only')
    production = load_build(args.production_receipt, 'production')
    tests = load_build(args.tests_receipt, 'tests')
    # The unchanged installer registers both x64 and x86 on x64 Windows.
    require(all('wangshu-ime-' + arch + '.dll' in production['record']['outputs'] for arch in ('x64', 'x86')),
            'This installer package requires both x64 and x86 production builds')
    runtime = load_runtime(args.runtime, production, tests, args.synthetic_contract)
    destination = new_destination(args.destination, [PROJECT, production['root'], tests['root'], runtime['root']])
    members = {name: child(production['root'], name) for name in production['record']['outputs']}
    for name in ('install.cmd', 'uninstall.cmd', 'INSTALL.md', 'PERSONAL-DATA.md'):
        members[name] = child(PROJECT, 'windows/' + name)
    for name in ('ACCEPTANCE.md', 'PRIVACY.md', 'VOICE.md', 'WINDOWS-COMPATIBILITY.md'):
        members['docs/' + name] = child(PROJECT, 'docs/' + name)
    for name in production['record']['inputs_sha256']:
        if name.startswith('data/'):
            members[name] = child(PROJECT, name)
    for name in ('LICENSE.MIT', 'SOURCE.json'):
        members['notices/nlohmann-' + name] = child(PROJECT, 'third_party/nlohmann/' + name)
    members.update({'evidence/source-attestation.json': production['path'],
                    'evidence/tests-source-attestation.json': tests['path'],
                    'evidence/runtime.json': runtime['path']})
    for suite in TEST_OUTPUTS:
        members['evidence/logs/' + suite + '.txt'] = child(runtime['root'], 'logs/' + suite + '.txt')
        members['evidence/runs/' + suite + '.json'] = child(runtime['root'], 'runs/' + suite + '.json')
    member_digests = {name: sha(existing_file(path)) for name, path in members.items()}
    tool_digests = {name: sha(PROJECT / name) for name in
                   ('tools/package_windows_personal.py', 'tools/windows_receipts.py')}
    if args.verify_only:
        print(json.dumps({'state': 'VERIFIED_NOT_PACKAGED', 'members': member_digests,
                          'writes': 'NONE', 'execution': 'NOT_RUN'}, indent=2))
        return 0
    destination.mkdir(parents=True, exist_ok=False)
    archive = destination / ('SYNTHETIC-CONTRACT-NOT-FOR-INSTALLATION.zip' if args.synthetic_contract
                             else 'wangshu-ime-windows-personal-dev.zip')
    try:
        # Exclusive archive creation, and use exactly the bytes whose digest is checked.
        with zipfile.ZipFile(archive, 'x', zipfile.ZIP_DEFLATED) as output:
            for name, path in sorted(members.items()):
                data = path.read_bytes()
                require(hashlib.sha256(data).hexdigest() == member_digests[name], 'Package member changed: ' + name)
                output.writestr(name, data)
        verify_same_build(production, 'production')
        verify_same_build(tests, 'tests')
        require(sha(runtime['path']) == runtime['sha256'], 'Runtime receipt changed during packaging')
        load_runtime(runtime['path'], production, tests, args.synthetic_contract)
        verify_map(PROJECT, tool_digests)
        for name, path in members.items():
            require(sha(path) == member_digests[name], 'Package source changed: ' + name)
        with zipfile.ZipFile(archive) as output:
            require(output.testzip() is None and output.namelist() == sorted(members), 'Archive CRC/member failure')
            for name, digest in member_digests.items():
                require(hashlib.sha256(output.read(name)).hexdigest() == digest, 'Archive hash mismatch: ' + name)
        record = {'schema_version': 2, 'utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'state': 'PASS', 'scope': 'Local unsigned development package; supplied executor evidence only. '
                  'Real Windows GUI/registration, x86 runtime, voice and microphone NOT_RUN.',
                  'archive': archive.name, 'sha256': sha(archive), 'bytes': archive.stat().st_size,
                  'zip_crc_and_all_member_sha256': 'PASS',
                  'source_stable_and_full_input_membership': 'PASS', 'tools_sha256': tool_digests,
                  'runtime_sha256': runtime['sha256'], 'members_sha256': member_digests,
                  'runtime_working_files_included': False, 'voice_models_or_engine_included': False,
                  'remote_push_publish_share': 'NONE'}
        record['evidence_kind'] = runtime['record']['evidence_kind']
        if args.synthetic_contract:
            record.update(state='SYNTHETIC_CONTRACT_PASS', scope='SYNTHETIC FIXTURES ONLY. '
                          'NOT FOR INSTALLATION. Actual compilation, Windows/Wine runtime and TSF acceptance NOT_RUN.')
        write_new(destination / 'SHA256SUMS', (record['sha256'] + '  ' + archive.name + '\n').encode('ascii'))
        write_json_new(destination / 'artifact.json', record)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        write_json_new(destination / 'package-failure.json', {'state': 'FAIL', 'error': str(error),
                       'partial_outputs_retained': True})
        raise
    print(json.dumps({key: value for key, value in record.items() if key != 'members_sha256'}, indent=2))
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print('REFUSED: ' + str(error), file=sys.stderr)
        raise SystemExit(2)
