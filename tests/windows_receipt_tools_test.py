"""SYNTHETIC contract tests only: mock PE headers/compiler/executor, never Windows.

All fixtures are retained. No downloads, installs, Wine, registration or deletion.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--builder', type=Path, default=PROJECT / 'tools/build_windows_personal.py')
parser.add_argument('--fixtures', type=Path, default=PROJECT / 'windows/receipt-contract-fixtures')
args = parser.parse_args()
args.fixtures.mkdir(parents=True, exist_ok=True)
ROOT = Path(tempfile.mkdtemp(prefix='SYNTHETIC_NOT_WINDOWS-', dir=args.fixtures.resolve()))
CHECKS = []
COMPILER = '''#!/usr/bin/env python3
# SYNTHETIC PE HEADER FIXTURE GENERATOR. NOT A C++ COMPILER.
import os,struct,sys
from pathlib import Path
target=Path(sys.argv[sys.argv.index('-o')+1]);x86='-x86.' in target.name
data=bytearray(512);data[:2]=b'MZ';struct.pack_into('<I',data,60,128)
data[128:132]=b'PE\\0\\0';struct.pack_into('<HHIIIHH',data,132,0x14c if x86 else 0x8664,1,0,0,0,224 if x86 else 240,0x2022 if target.suffix=='.dll' else 0x22)
struct.pack_into('<H',data,152,0x10b if x86 else 0x20b)
data[450:500]=b'SYNTHETIC HEADER ONLY. NOT A RUNNABLE WINDOWS BINARY.'.ljust(50)
target.write_bytes(data)
include=Path(os.environ.get('SYNTHETIC_BUILD_INCLUDE','not-selected'))
action=os.environ.get('SYNTHETIC_BUILD_ACTION','')
if action=='mutate-inc' and include.is_file():
 with include.open('ab') as f:f.write(b'// SYNTHETIC include mutation\\n')
if action=='remove-inc' and include.is_file():
 include.rename(include.with_suffix('.parked'))
'''
RUNNER = '''#!/usr/bin/env python3
# SYNTHETIC CLI FIXTURE. Does not execute its PE argument.
import json,os,sys,time
from pathlib import Path
name=Path(sys.argv[1]).name;action=os.environ.get('SYNTHETIC_ACTION','')
with Path(os.environ['SYNTHETIC_CALLS']).open('a') as log:
 log.write(json.dumps({'argv':sys.argv[1:],'cwd':str(Path.cwd()),'localappdata':os.environ.get('LOCALAPPDATA')})+'\\n')
print('SYNTHETIC CONTRACT OUTPUT. NOT WINDOWS, WINE OR TSF EXECUTION.',flush=True)
if action=='timeout':time.sleep(5)
if action=='fail':print('FAIL injected synthetic failure')
if action=='mutate-binary':
 with Path(sys.argv[1]).open('ab') as f:f.write(b'SYNTHETIC MUTATION')
if action=='mutate-source':
 with Path(os.environ['SYNTHETIC_SOURCE']).open('a') as f:f.write('// synthetic mutation\\n')
if action=='mutate-runner':
 with Path(__file__).open('a') as f:f.write('# synthetic mutation\\n')
if action=='mutate-data':
 with (Path.cwd()/'data/synthetic.tsv').open('a') as f:f.write('changed\\n')
totals={'windows-personal-store-test.exe':'TOTAL 34 PASS; synthetic',
'tsf-edit-test.exe':'TOTAL 85 PASS; synthetic',
'windows-personal-tsf-test.exe':'TOTAL 85 PASS; synthetic\\nTOTAL 17 additional PASS; synthetic',
'phrase-association-tsf-test.exe':'RESULT 16 passed, 0 failed; production Windows PE/mock TSF, no real device',
'dll-smoke.exe':'TOTAL 17 PASS; synthetic'}
line=totals[name]
if action=='wrong-total':line=line.replace('34','340')
if action!='no-total':print(line)
if action=='duplicate-total':print(line)
raise SystemExit(9 if action=='exit-nonzero' else 0)
'''


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def put_json(path, record):
    path.write_text(json.dumps(record, indent=2) + '\n')


def check(label, passed, detail=''):
    CHECKS.append({'label': label, 'state': 'PASS' if passed else 'FAIL', 'detail': detail})


def make_case(label, architectures=('x64', 'x86')):
    root = ROOT / label
    project = root / 'arbitrary standalone source'
    project.mkdir(parents=True)
    for folder in ('tools', 'tests', 'windows', 'core', 'staging/personal-native',
                   'third_party/nlohmann', 'third_party/sherpa', 'docs', 'data'):
        (project / folder).mkdir(parents=True)
    for name in ('run_windows_personal_checks.py', 'package_windows_personal.py', 'windows_receipts.py'):
        shutil.copyfile(PROJECT / 'tools' / name, project / 'tools' / name)
    shutil.copyfile(PROJECT / 'docs/WINDOWS-RECEIPT-TOOLS.md', project / 'docs/WINDOWS-RECEIPT-TOOLS.md')
    shutil.copyfile(args.builder, project / 'tools/build_windows_personal.py')
    for name in ('tools/build_windows.sh', 'windows/dll_smoke.cpp', 'tests/tsf_edit_test.cpp',
                 'tests/windows_personal_store_test.cpp', 'tests/windows_personal_tsf_test.cpp',
                 'windows/phrase_association_tsf_test.cpp', 'windows/personal_store.cpp',
                 'staging/personal-native/personal_data.cpp', 'core/engine.cpp',
                 'core/local_predictions.h', 'core/local_prediction_data.inc', 'tests/synthetic_fixture.inc',
                 'windows/text_service.cpp', 'windows/personal_settings.cpp', 'windows/voice_helper.cpp',
                 'third_party/sherpa/c-api.h', 'third_party/nlohmann/json.hpp',
                 'third_party/nlohmann/LICENSE.MIT', 'third_party/nlohmann/SOURCE.json',
                 'windows/install.cmd', 'windows/uninstall.cmd', 'windows/INSTALL.md',
                 'windows/PERSONAL-DATA.md', 'docs/ACCEPTANCE.md', 'docs/PRIVACY.md',
                 'docs/VOICE.md', 'docs/WINDOWS-COMPATIBILITY.md', 'data/synthetic.tsv'):
        (project / name).write_text('SYNTHETIC CONTRACT FIXTURE. NOT REAL SOURCE/DATA.\n')
    compiler = root / 'synthetic-compiler.py'
    compiler.write_text(COMPILER)
    compiler.chmod(0o700)
    runner = root / 'synthetic-runner.py'
    runner.write_text(RUNNER)
    runner.chmod(0o700)
    fixture = root / 'SYNTHETIC-android-fixture.json'
    put_json(fixture, {'fixture': 'SYNTHETIC contract bytes; NOT Android execution evidence'})
    case = {'root': root, 'project': project, 'runner': runner, 'fixture': fixture,
            'production': root / 'production/source-attestation.json',
            'tests': root / 'tests/tests-source-attestation.json', 'runtime': root / 'runtime/runtime.json'}
    for folder, flags in [('production', ['--architectures', *architectures]), ('tests', ['--tests'])]:
        command = [sys.executable, '-B', str(project / 'tools/build_windows_personal.py'),
                   '--cxx-x64', str(compiler), '--cxx-x86', str(compiler), '--output', str(root / folder), *flags]
        result = subprocess.run(command, capture_output=True, text=True)
        (root / (folder + '-synthetic-build.stdout.txt')).write_text(result.stdout)
        (root / (folder + '-synthetic-build.stderr.txt')).write_text(result.stderr)
        if result.returncode:
            raise RuntimeError(result.stderr)
    return case


def invoke(case, kind='run', suffix='runtime', action='', extra=(), optimized=False, synthetic=True):
    name = 'run_windows_personal_checks.py' if kind == 'run' else 'package_windows_personal.py'
    command = [sys.executable, '-B'] + (['-O'] if optimized else []) + [str(case['project'] / 'tools' / name),
               '--production-receipt', str(case['production']), '--tests-receipt', str(case['tests']),
               '--destination', str(case['root'] / suffix)]
    if synthetic:
        command += ['--synthetic-contract']
    if kind == 'run':
        command += ['--android-fixture', str(case['fixture']), '--android-fixture-sha256', digest(case['fixture']),
                    '--runner', str(case['runner'])]
    else:
        command += ['--runtime', str(case['runtime'])]
    environment = dict(os.environ, PYTHONDONTWRITEBYTECODE='1', SYNTHETIC_ACTION=action,
                       SYNTHETIC_CALLS=str(case['root'] / 'synthetic-calls.jsonl'),
                       SYNTHETIC_SOURCE=str(case['project'] / 'core/engine.cpp'))
    result = subprocess.run(command + list(extra), capture_output=True, text=True, env=environment)
    return result


def calls(case):
    path = case['root'] / 'synthetic-calls.jsonl'
    return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []


def edit_receipt(case, kind, operation):
    record = json.loads(case[kind].read_text())
    operation(record)
    put_json(case[kind], record)


good = make_case('happy-path')
for kind in ('production', 'tests'):
    receipt = json.loads(good[kind].read_text(encoding='utf-8'))
    check(kind + ' receipt includes the compiled prediction .inc bytes',
          receipt['inputs_sha256'].get('core/local_prediction_data.inc') ==
          digest(good['project'] / 'core/local_prediction_data.inc'))
check('test-only .inc is included only by test discovery',
      'tests/synthetic_fixture.inc' in json.loads(good['tests'].read_text(encoding='utf-8'))['inputs_sha256'] and
      'tests/synthetic_fixture.inc' not in json.loads(good['production'].read_text(encoding='utf-8'))['inputs_sha256'])
result = invoke(good, suffix='read-only-plan', extra=['--verify-only'])
check('portable separate v2 receipts pass read-only preflight', result.returncode == 0 and
      not (good['root'] / 'read-only-plan').exists() and not calls(good), result.stderr)
result = invoke(good)
runtime = json.loads(good['runtime'].read_text())
check('all five synthetic suites including 16 associations are required', result.returncode == 0 and
      runtime['state'] == 'PASS' and [x['suite'] for x in runtime['runs']] ==
      ['store', 'tsf-edit', 'tsf', 'association', 'dll'] and runtime['checks']['association'] == 16, result.stderr)
check('executables/data staged away from immutable build roots', len(calls(good)) == 5 and
      all(Path(x['argv'][0]).is_relative_to(good['root'] / 'runtime/work') for x in calls(good)) and
      all(Path(x['localappdata']).is_relative_to(good['root'] / 'runtime/work') for x in calls(good)))
check('synthetic limitations are visible in every raw log', all('SYNTHETIC CONTRACT OUTPUT' in
      (good['root'] / 'runtime' / x['raw']).read_text() for x in runtime['runs']))
old = digest(good['runtime'])
result = invoke(good)
check('runtime rerun never overwrites existing evidence', result.returncode != 0 and
      digest(good['runtime']) == old and len(calls(good)) == 5)
result = invoke(good, 'package', suffix='package-plan', extra=['--verify-only'])
check('package preflight writes nothing', result.returncode == 0 and not (good['root'] / 'package-plan').exists(), result.stderr)
result = invoke(good, 'package', suffix='package')
artifact_path = good['root'] / 'package/artifact.json'
check('synthetic-only archive verifies successfully', result.returncode == 0 and artifact_path.is_file(), result.stderr)
artifact = json.loads(artifact_path.read_text())
archive = good['root'] / 'package' / artifact['archive']
check('synthetic package cannot be mistaken for native acceptance',
      artifact['state'] == 'SYNTHETIC_CONTRACT_PASS' and artifact['evidence_kind'] == 'SYNTHETIC_CONTRACT_ONLY'
      and archive.name == 'SYNTHETIC-CONTRACT-NOT-FOR-INSTALLATION.zip')
result = invoke(good, 'package', suffix='ordinary-package-refused', synthetic=False)
check('synthetic runtime cannot qualify for ordinary binary packaging', result.returncode != 0 and
      not (good['root'] / 'ordinary-package-refused').exists())
with zipfile.ZipFile(archive) as output:
    names = output.namelist()
    check('archive contains helper, both architectures and association evidence',
          all(name in names for name in ['wangshu-voice-helper.exe', 'wangshu-ime-x64.dll',
              'wangshu-ime-x86.dll', 'evidence/logs/association.txt', 'evidence/runs/association.json']) and
          not any('store-test-evidence' in name or name.startswith('work/') for name in names))
    check('archive all member hashes and CRC match manifest', output.testzip() is None and
          all(hashlib.sha256(output.read(name)).hexdigest() == value for name, value in artifact['members_sha256'].items()))
old = digest(archive)
result = invoke(good, 'package', suffix='package')
check('package rerun never overwrites existing archive', result.returncode != 0 and digest(archive) == old)

for label, action in [('exit-nonzero', 'exit-nonzero'), ('missing-total', 'no-total'),
                      ('substring-total', 'wrong-total'), ('duplicate-total', 'duplicate-total'),
                      ('failure-line', 'fail'), ('binary-mutation', 'mutate-binary'),
                      ('source-mutation', 'mutate-source'), ('runner-mutation', 'mutate-runner'),
                      ('data-mutation', 'mutate-data'), ('timeout', 'timeout')]:
    case = make_case(label)
    result = invoke(case, action=action, extra=['--timeout', '0.1'] if action == 'timeout' else [])
    record = json.loads(case['runtime'].read_text())
    check('reject ' + label + ' and preserve nonpassing receipt', result.returncode != 0 and
          record['state'] != 'PASS' and len(calls(case)) == 1, result.stderr)
    package = invoke(case, 'package', suffix='blocked-package')
    check('package rejects ' + label + ' evidence without writing', package.returncode != 0 and
          not (case['root'] / 'blocked-package').exists())

def before_run(label, operation, optimized=False):
    case = make_case(label)
    operation(case)
    result = invoke(case, optimized=optimized)
    check(label + ' refused before execution/output', result.returncode != 0 and
          not calls(case) and not case['runtime'].parent.exists(), result.stderr)

before_run('legacy receipt', lambda c: edit_receipt(c, 'production', lambda x: x.update(schema_version=1)))
before_run('different source root', lambda c: edit_receipt(c, 'production', lambda x: x.update(project_root='/unrelated')))
before_run('different output root', lambda c: edit_receipt(c, 'tests', lambda x: x.update(output_root=str(c['root']))))
before_run('failed build', lambda c: edit_receipt(c, 'production', lambda x: x.update(exit_code=1)))
before_run('unstable compiler', lambda c: edit_receipt(c, 'production', lambda x: x.update(compilers_stable=False)))
before_run('missing association binary', lambda c: edit_receipt(c, 'tests', lambda x: x['outputs'].pop('phrase-association-tsf-test.exe')))
before_run('missing output hash', lambda c: edit_receipt(c, 'tests', lambda x: x['outputs'].update({'dll-smoke.exe': '0' * 64})))
before_run('changed source bytes', lambda c: (c['project'] / 'core/engine.cpp').write_text('SYNTHETIC changed source'))
before_run('changed compiled include bytes', lambda c: (c['project'] / 'core/local_prediction_data.inc').write_text(
           '// SYNTHETIC changed include\n', encoding='utf-8'))
before_run('removed compiled include', lambda c: (c['project'] / 'core/local_prediction_data.inc').rename(
           c['project'] / 'core/local_prediction_data.parked'))
before_run('added source membership', lambda c: (c['project'] / 'windows/new-input.h').write_text('SYNTHETIC new header'))
before_run('source path traversal', lambda c: edit_receipt(c, 'tests', lambda x: x['inputs_sha256'].update({'../escape': '0' * 64})))
before_run('duplicate build commands', lambda c: edit_receipt(c, 'tests', lambda x: x['commands'].__setitem__(1, x['commands'][0])))
before_run('wrong build compiler', lambda c: edit_receipt(c, 'tests', lambda x: x['commands'][0]['command'].__setitem__(0, '/different/compiler')))
before_run('optimized Python still validates', lambda c: edit_receipt(c, 'tests', lambda x: x.update(source_stable=False)), optimized=True)
before_run('duplicate JSON keys', lambda c: c['tests'].write_text(c['tests'].read_text().replace('"schema_version": 2', '"schema_version": 2, "schema_version": 2', 1)))

case = make_case('pe-rejection')
pe = case['tests'].parent / 'phrase-association-tsf-test.exe'
pe.write_bytes(b'SYNTHETIC TEXT IS NOT A PE')
edit_receipt(case, 'tests', lambda x: x['outputs'].update({pe.name: digest(pe)}))
result = invoke(case)
check('matching digest does not bypass PE header validation', result.returncode != 0 and not calls(case))

case = make_case('wrong-architecture')
pe = case['tests'].parent / 'dll-smoke.exe'
data = bytearray(pe.read_bytes()); data[132:134] = b'\x4c\x01'; pe.write_bytes(data)
edit_receipt(case, 'tests', lambda x: x['outputs'].update({pe.name: digest(pe)}))
result = invoke(case)
check('wrong PE machine is rejected before runtime', result.returncode != 0 and not calls(case))

for label, package in [('dangling-runtime', False), ('dangling-package', True)]:
    case = make_case(label)
    if package:
        invoke(case)
    (case['root'] / 'dangling').symlink_to(case['root'] / 'not-created')
    result = invoke(case, 'package' if package else 'run', suffix='dangling')
    check(label + ' symlink never followed/overwritten', result.returncode != 0 and
          (case['root'] / 'dangling').is_symlink() and not (case['root'] / 'not-created').exists())

case = make_case('source-output-overlap')
result = invoke(case, suffix='arbitrary standalone source/evidence')
check('runtime refuses destination inside source', result.returncode != 0 and not calls(case))
case = make_case('missing-wine-prefix')
result = invoke(case, extra=['--path-style', 'wine', '--verify-only'])
check('Wine paths require an explicit existing initialized prefix', result.returncode != 0 and not calls(case))
case = make_case('wine-plan-only')
prefix = case['root'] / 'SYNTHETIC-prefix'; prefix.mkdir()
for name in ('system.reg', 'user.reg'):
    (prefix / name).write_text('SYNTHETIC NOT A REAL WINE PREFIX\n')
result = invoke(case, extra=['--path-style', 'wine', '--wine-prefix', str(prefix), '--verify-only'])
check('Wine-style preflight never invokes Wine or runner', result.returncode == 0 and not calls(case)
      and not case['runtime'].parent.exists(), result.stderr)

case = make_case('x64-only', architectures=('x64',))
result = invoke(case)
check('x64-only production is valid for x64 runtime contracts', result.returncode == 0)
result = invoke(case, 'package', suffix='package')
check('two-architecture installer refuses x64-only production', result.returncode != 0 and
      not (case['root'] / 'package').exists())

def after_run(label, operation):
    case = make_case(label)
    result = invoke(case)
    if result.returncode:
        raise RuntimeError(result.stderr)
    operation(case)
    result = invoke(case, 'package', suffix='package')
    check(label + ' package refused before destination creation', result.returncode != 0 and
          not (case['root'] / 'package').exists(), result.stderr)

after_run('omitted suite', lambda c: edit_receipt(c, 'runtime', lambda x: x['runs'].pop()))
after_run('duplicate suite', lambda c: edit_receipt(c, 'runtime', lambda x: x['runs'].__setitem__(1, x['runs'][0])))
after_run('raw log mutation', lambda c: (c['runtime'].parent / 'logs/association.txt').write_text('SYNTHETIC forged PASS'))
after_run('per-suite receipt mutation', lambda c: (c['runtime'].parent / 'runs/association.json').write_text('{}'))
after_run('runtime belongs to different build', lambda c: edit_receipt(c, 'runtime', lambda x: x.update(production_attestation_sha256='0' * 64)))
after_run('changed staged data', lambda c: (c['runtime'].parent / 'work/data/synthetic.tsv').write_text('SYNTHETIC changed data'))
after_run('changed original tests', lambda c: (c['tests'].parent / 'dll-smoke.exe').write_text('SYNTHETIC changed binary'))
after_run('changed compiled include after runtime', lambda c: (c['project'] / 'core/local_prediction_data.inc').write_text(
          '// SYNTHETIC changed include after runtime\n', encoding='utf-8'))
after_run('removed compiled include after runtime', lambda c: (c['project'] / 'core/local_prediction_data.inc').rename(
          c['project'] / 'core/local_prediction_data.parked'))
after_run('changed runtime implementation', lambda c: (c['project'] / 'tools/windows_receipts.py').write_text(
          (c['project'] / 'tools/windows_receipts.py').read_text() + '\n# SYNTHETIC mutation\n'))

case = make_case('source-candidate')
source_command = [sys.executable, '-B', str(case['project'] / 'tools/package_windows_personal.py'),
                  '--source-only', '--destination', str(case['root'] / 'source-package')]
result = subprocess.run(source_command + ['--verify-only'], capture_output=True, text=True)
check('source-only preflight requires no runtime and writes nothing', result.returncode == 0 and
      not (case['root'] / 'source-package').exists() and not calls(case), result.stderr)
result = subprocess.run(source_command, capture_output=True, text=True)
check('source-only package requires no build/runtime acceptance', result.returncode == 0, result.stderr)
source_artifact = json.loads((case['root'] / 'source-package/artifact.json').read_text())
with zipfile.ZipFile(case['root'] / 'source-package' / source_artifact['archive']) as output:
    check('source-only archive contains compiled prediction include and test include exactly',
          output.read('source/core/local_prediction_data.inc') ==
          (case['project'] / 'core/local_prediction_data.inc').read_bytes() and
          output.read('source/tests/synthetic_fixture.inc') ==
          (case['project'] / 'tests/synthetic_fixture.inc').read_bytes())
    check('source-only archive clearly says native acceptance NOT_RUN',
          source_artifact['state'] == 'SOURCE_CANDIDATE_ONLY' and
          source_artifact['native_runtime_acceptance'] == 'NOT_RUN' and
          json.loads(output.read('CANDIDATE-STATUS.json'))['compilation'] == 'NOT_RUN' and
          not any(name.endswith(('.exe', '.dll')) for name in output.namelist()))
result = subprocess.run(source_command, capture_output=True, text=True)
check('source-only refuses existing destination', result.returncode != 0)
result = invoke(case, 'package', suffix='mixed-source-package', extra=['--source-only'])
check('source-only refuses mixed runtime/synthetic options', result.returncode != 0 and
      not (case['root'] / 'mixed-source-package').exists())

for action in ('mutate-inc', 'remove-inc'):
    for scope in ('production', 'tests'):
        case = make_case('during-build-' + scope + '-' + action)
        target = case['root'] / 'changed-include-build'
        compiler = case['root'] / 'synthetic-compiler.py'
        command = [sys.executable, '-B', str(case['project'] / 'tools/build_windows_personal.py'),
                   '--cxx-x64', str(compiler), '--cxx-x86', str(compiler), '--output', str(target)]
        if scope == 'tests':
            command.append('--tests')
        environment = dict(os.environ, SYNTHETIC_BUILD_ACTION=action,
                           SYNTHETIC_BUILD_INCLUDE=str(case['project'] / 'core/local_prediction_data.inc'))
        result = subprocess.run(command, capture_output=True, text=True, env=environment)
        record = json.loads((target / ('last-' + scope + '-build.json')).read_text(encoding='utf-8'))
        success = 'tests-source-attestation.json' if scope == 'tests' else 'source-attestation.json'
        check(scope + ' build fails attestation on ' + action + ' during compilation',
              result.returncode == 65 and record['source_stable'] is False and
              'core/local_prediction_data.inc' in record['changed_during_build'] and
              not (target / success).exists(), result.stderr)

failed = sum(item['state'] == 'FAIL' for item in CHECKS)
report = {'state': 'FAIL' if failed else 'PASS', 'passed': len(CHECKS) - failed, 'failed': failed,
          'checks': CHECKS, 'fixture_root': str(ROOT), 'fixtures_preserved': True,
          'scope': 'SYNTHETIC CLI contract tests only. PE headers, sources, compiler and runner are mocks. '
                   'Actual MinGW compilation, Windows/Wine runtime, TSF cases, GUI, registration and microphone NOT_RUN.'}
print(json.dumps(report, indent=2))
raise SystemExit(bool(failed))
