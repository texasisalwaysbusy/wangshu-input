"""Fail-closed readers for local, unsigned Windows build/runtime receipts.

These checks detect accidental stale/mixed artifacts. They are not a security
boundary against an author who can rewrite receipts and their corresponding files.
"""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import struct

PROJECT = Path(__file__).resolve().parents[1]
TEST_OUTPUTS = {
    'store': 'windows-personal-store-test.exe',
    'tsf-edit': 'tsf-edit-test.exe',
    'tsf': 'windows-personal-tsf-test.exe',
    'association': 'phrase-association-tsf-test.exe',
    'dll': 'dll-smoke.exe',
}
CHECKS = {'store': 34, 'tsf-edit': 85, 'tsf': 102, 'association': 16, 'dll': 17}
TOTAL_PATTERNS = {
    'store': [r'TOTAL 34 PASS(?:;[^\r\n]*)?'],
    'tsf-edit': [r'TOTAL 85 PASS(?:;[^\r\n]*)?'],
    'tsf': [r'TOTAL 85 PASS(?:;[^\r\n]*)?', r'TOTAL 17 additional PASS(?:;[^\r\n]*)?'],
    'association': [r'RESULT 16 passed, 0 failed; production Windows PE/mock TSF, no real device'],
    'dll': [r'TOTAL 17 PASS(?:;[^\r\n]*)?'],
}


class InvalidReceipt(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise InvalidReceipt(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path):
    # Duplicate keys would otherwise silently replace a path, suite or digest.
    def unique(pairs):
        value = {}
        for key, item in pairs:
            require(key not in value, 'Duplicate JSON key: ' + key)
            value[key] = item
        return value
    result = json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique)
    require(isinstance(result, dict), 'Receipt must be a JSON object: ' + str(path))
    return result


def existing_file(path):
    path = Path(path).absolute()
    require(not path.is_symlink() and path.is_file(), 'Expected existing regular file: ' + str(path))
    return path.resolve()


def new_destination(path, forbidden=()):
    path = Path(path).absolute()
    require(not os.path.lexists(path), 'Preserve existing output; choose a NEW destination: ' + str(path))
    path = path.resolve()
    for root in forbidden:
        require(not path.is_relative_to(root) and not root.is_relative_to(path),
                'Destination overlaps an input root: ' + str(root))
    return path


def child(root, name):
    require(isinstance(name, str) and name and '\\' not in name and ':' not in name,
            'Invalid relative path: ' + repr(name))
    relative = PurePosixPath(name)
    require(not relative.is_absolute() and str(relative) == name and
            all(part not in ('', '.', '..') for part in relative.parts),
            'Noncanonical relative path: ' + name)
    path = root.joinpath(*relative.parts)
    require(path.resolve().is_relative_to(root), 'Path escapes root: ' + name)
    cursor = path
    while cursor != root:
        require(not cursor.is_symlink(), 'Symlink input is not supported: ' + name)
        cursor = cursor.parent
    return path


def verify_map(root, manifest):
    require(isinstance(manifest, dict) and manifest, 'Missing hash manifest')
    for name, digest in manifest.items():
        require(isinstance(digest, str) and re.fullmatch('[0-9a-f]{64}', digest),
                'Invalid SHA256: ' + str(name))
        path = child(root, name)
        require(path.is_file() and sha(path) == digest, 'Missing or changed file: ' + str(path))


def source_inputs(project, tests=False):
    # Keep this read-only membership contract equal to build_windows_personal.py.
    selected = set()
    for folder in ('windows', 'core', 'staging/personal-native', 'third_party/nlohmann'):
        selected.update(path for path in (project / folder).glob('*')
                        if path.is_file() and path.suffix in {'.h', '.hpp', '.cpp', '.inc', '.MIT', '.json'})
    selected.update(path for path in (project / 'data').rglob('*') if path.is_file())
    selected.update(project / name for name in ('tools/build_windows_personal.py',
                    'tools/build_windows.sh', 'third_party/sherpa/c-api.h'))
    if tests:
        selected.update(path for path in (project / 'tests').glob('*')
                        if path.is_file() and path.suffix in {'.cpp', '.h', '.inc'})
    names = [path.relative_to(project).as_posix() for path in sorted(selected)]
    return {name: sha(child(project, name)) for name in names}


def verify_pe(path, architecture, dll=False):
    data = path.read_bytes()
    require(len(data) >= 64 and data[:2] == b'MZ', 'Not a PE file: ' + str(path))
    offset = struct.unpack_from('<I', data, 60)[0]
    require(64 <= offset <= len(data) - 26 and data[offset:offset + 4] == b'PE\0\0',
            'Invalid PE header: ' + str(path))
    machine, sections = struct.unpack_from('<HH', data, offset + 4)
    size, characteristics = struct.unpack_from('<HH', data, offset + 20)
    magic = struct.unpack_from('<H', data, offset + 24)[0]
    expected = (0x8664, 0x20b, 112) if architecture == 'x64' else (0x14c, 0x10b, 96)
    require((machine, magic) == expected[:2] and sections > 0 and size >= expected[2]
            and offset + 24 + size + sections * 40 <= len(data),
            'Wrong or truncated PE architecture/header: ' + str(path))
    require(bool(characteristics & 0x0002) and bool(characteristics & 0x2000) == dll,
            'Wrong PE executable/DLL kind: ' + str(path))


def load_build(receipt_path, scope, project=PROJECT):
    receipt_path = existing_file(receipt_path)
    record = read_json(receipt_path)
    require(type(record.get('schema_version')) is int and record['schema_version'] == 2,
            'Requires build receipt schema_version 2; rebuild legacy artifacts')
    require(record.get('project_root') == str(project), 'Receipt belongs to a different source checkout')
    raw_root = record.get('output_root')
    require(isinstance(raw_root, str) and Path(raw_root).is_absolute(), 'Missing absolute output_root')
    root = Path(raw_root).resolve()
    require(str(root) == raw_root and root.is_dir() and receipt_path.parent == root,
            'Receipt must be inside its exact existing output_root')
    expected_name = 'tests-source-attestation.json' if scope == 'tests' else 'source-attestation.json'
    require(receipt_path.name == expected_name, 'Expected success attestation: ' + expected_name)
    require(record.get('build_scope') == scope and record.get('tests_build') is (scope == 'tests'),
            'Wrong build scope')
    require(record.get('source_stable') is True and record.get('compilers_stable') is True
            and type(record.get('exit_code')) is int and record['exit_code'] == 0
            and record.get('changed_during_build') == [] and record.get('compilers_changed') == [],
            'Build was not successfully attested as stable')
    inputs = record.get('inputs_sha256')
    verify_map(project, inputs)
    require(inputs == source_inputs(project, scope == 'tests'),
            'Source input bytes or full membership changed; rebuild')
    require('third_party/nlohmann/json.hpp' in inputs and 'windows/phrase_association_tsf_test.cpp' in inputs,
            'Required production/association source missing from receipt')
    outputs = record.get('outputs')
    verify_map(root, outputs)
    if scope == 'tests':
        require(set(outputs) == set(TEST_OUTPUTS.values()), 'Test receipt must contain exactly all five test binaries')
    else:
        allowed = {'wangshu-voice-helper.exe'}
        for architecture in ('x64', 'x86'):
            pair = {'wangshu-ime-' + architecture + '.dll', 'wangshu-personal-settings-' + architecture + '.exe'}
            present = pair & set(outputs)
            require(not present or present == pair, 'Incomplete production architecture pair: ' + architecture)
            allowed |= pair
        require('wangshu-ime-x64.dll' in outputs and 'wangshu-voice-helper.exe' in outputs
                and set(outputs) <= allowed, 'Runtime checks require x64 production and helper outputs')
    commands = record.get('commands')
    require(isinstance(commands, list) and len(commands) == len(outputs), 'Incomplete build command receipt')
    compilers = record.get('compilers')
    require(isinstance(compilers, dict) and compilers and set(compilers) <= {'x64', 'x86'},
            'Missing compiler identity records')
    for compiler in compilers.values():
        require(isinstance(compiler, dict) and isinstance(compiler.get('path'), str)
                and Path(compiler['path']).is_absolute() and isinstance(compiler.get('sha256'), str)
                and re.fullmatch('[0-9a-f]{64}', compiler['sha256']), 'Invalid compiler identity')
    seen = set()
    for entry in commands:
        require(isinstance(entry, dict) and entry.get('output') in outputs
                and entry['output'] not in seen and type(entry.get('exit_code')) is int
                and entry['exit_code'] == 0, 'Invalid/duplicate/failed build command')
        seen.add(entry['output'])
        command = entry.get('command')
        require(isinstance(command, list) and all(isinstance(x, str) for x in command)
                and command.count('-o') == 1, 'Missing build command vector')
        i = command.index('-o')
        require(i + 1 < len(command) and command[i + 1] == str(root / entry['output']),
                'Build command output differs from receipt')
        architecture = 'x86' if '-x86.' in entry['output'] else 'x64'
        require(architecture in compilers and command[0] == compilers[architecture]['path'],
                'Build command compiler differs from receipt')
    for name in outputs:
        verify_pe(child(root, name), 'x86' if '-x86.' in name else 'x64', name.endswith('.dll'))
    return {'path': receipt_path, 'sha256': sha(receipt_path), 'root': root, 'record': record}


def verify_same_build(build, scope, project=PROJECT):
    require(sha(build['path']) == build['sha256'], 'Build receipt changed during operation')
    load_build(build['path'], scope, project)


def passed_output(suite, data):
    text = data.decode('utf-8', errors='replace')
    lines = text.splitlines()
    totals = [line for line in lines if line.startswith(('TOTAL ', 'RESULT '))]
    patterns = TOTAL_PATTERNS[suite]
    return ('FAIL ' not in text and len(totals) == len(patterns) and
            all(re.fullmatch(pattern, line) for pattern, line in zip(patterns, totals)))


def write_new(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('xb') as output:
        output.write(data)


def write_json_new(path, value):
    write_new(path, (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
