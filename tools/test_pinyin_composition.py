#!/usr/bin/env python3
"""Source-bound portable pinyin regression; existing compiler, no install/device calls."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--project', type=Path, default=Path(__file__).resolve().parents[1])
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--baseline', type=Path, help='Optional preserved source: same black-box test must fail there')
parser.add_argument('--sanitizers', action='store_true')
args = parser.parse_args()
project = args.project.resolve()
output = args.output.absolute()
if output.exists():
    parser.error('output must be a new directory; preserve earlier receipts')
output.mkdir(parents=True)
test = project / 'tests/pinyin_composition_test.cpp'
result = {'scope': 'Portable Linux host only. No APK build, device, microphone, installation, Windows runtime, contextual-language accuracy or product acceptance.', 'checks': [], 'success': False}
inputs = [project / path for path in ['core/engine.cpp', 'core/engine.h', 'core/pinyin_composition.h', 'data/pinyin.tsv', 'data/symbols.tsv', 'tests/pinyin_composition_test.cpp', 'tools/test_pinyin_composition.py']]
result['inputs_sha256'] = {str(path.relative_to(project)): hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs}

def run(label, command, env=None, expected=0, expected_failure=None):
    completed = subprocess.run([str(x) for x in command], capture_output=True, text=True, timeout=180, env=env)
    (output / (label + '.stdout.txt')).write_text(completed.stdout)
    (output / (label + '.stderr.txt')).write_text(completed.stderr)
    ok = completed.returncode == expected and (expected_failure is None or expected_failure in completed.stderr)
    result['checks'].append({'name': label, 'command': [str(x) for x in command], 'returncode': completed.returncode, 'expected_returncode': expected, 'expected_failure': expected_failure, 'success': ok})
    print(label + ': ' + ('PASS' if ok else 'FAIL'), flush=True)
    return ok

try:
    for label, source, expected, sanitize in [('fixed', project, 0, False)] + ([('baseline-red', args.baseline.resolve(), 1, False)] if args.baseline else []) + ([('sanitizers', project, 0, True)] if args.sanitizers else []):
        binary = output / label
        command = ['g++', '-std=c++17', '-O1' if sanitize else '-O2', '-Wall', '-Wextra', '-Werror', '-I', source / 'core']
        if sanitize:
            command += ['-g', '-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        if run(label + '-compile', command + [source / 'core/engine.cpp', test, '-o', binary]):
            env = {**os.environ, 'ASAN_OPTIONS': 'detect_leaks=0'} if sanitize else None
            run(label, [binary, source / 'data', output / (label + '-synthetic-dictionary')], env=env, expected=expected, expected_failure="FAIL appending y no longer empties candidates" if label == "baseline-red" else None)
    result['inputs_unchanged'] = result['inputs_sha256'] == {str(path.relative_to(project)): hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs}
    result['sanitizer_limit'] = 'LeakSanitizer disabled on ptrace-constrained host; ASan and UBSan only.' if args.sanitizers else 'Not requested.'
    result['success'] = result['inputs_unchanged'] and all(check['success'] for check in result['checks'])
finally:
    (output / 'RESULT.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
raise SystemExit(0 if result['success'] else 1)
