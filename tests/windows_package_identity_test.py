"""Exercise ZIP/extracted identity with synthetic files. Never runs a PE."""

import argparse
import hashlib
import json
from pathlib import Path
import stat
import struct
import sys
import warnings
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from verify_windows_package import ARCHITECTURES, digest, verify
from windows_receipts import InvalidReceipt


def pe(arch, dll):
    machine = 0x8664 if arch == 'x64' else 0x14c
    magic = 0x20b if arch == 'x64' else 0x10b
    optional_bytes = 112 if arch == 'x64' else 96
    header = bytearray(64 + 24 + optional_bytes + 40)
    header[:2] = b'MZ'
    struct.pack_into('<I', header, 60, 64)
    header[64:68] = b'PE\0\0'
    struct.pack_into('<HH', header, 68, machine, 1)
    struct.pack_into('<HHH', header, 84, optional_bytes, 0x2002 if dll else 0x0002, magic)
    return bytes(header)


def fixture(root, state, members, corrupt=False, archive_entries=None):
    root.mkdir()
    archive = root / 'synthetic-fixture.zip'
    entries = list(members.items()) if archive_entries is None else archive_entries
    if state == 'SOURCE_CANDIDATE_ONLY' and archive_entries is None:
        entries.append(('CANDIDATE-STATUS.json', b'{"scope":"SYNTHETIC ONLY"}'))
    with warnings.catch_warnings(), zipfile.ZipFile(archive, 'x') as output:
        warnings.simplefilter('ignore', UserWarning)
        for name, content in entries:
            output.writestr(name, content)
    if corrupt:
        # Flip one stored member byte, leaving the stated CRC unchanged.
        data = archive.read_bytes()
        offset = data.index(b'SYNTHETIC DATA PAYLOAD')
        archive.write_bytes(data[:offset] + b'X' + data[offset + 1:])
    manifest = {name: hashlib.sha256(value).hexdigest() for name, value in members.items()}
    if state == 'SOURCE_CANDIDATE_ONLY':
        fields = {'source_sha256': {name.removeprefix('source/'): value for name, value in manifest.items()},
                  'zip_crc_and_all_source_sha256': 'PASS'}
    else:
        fields = {'members_sha256': manifest, 'zip_crc_and_all_member_sha256': 'PASS',
                  'evidence_kind': 'PE_EXECUTOR_OBSERVATION' if state == 'PASS' else 'SYNTHETIC_CONTRACT_ONLY'}
    receipt = root / 'artifact.json'
    receipt.write_text(json.dumps({'schema_version': 2, 'state': state, 'archive': archive.name,
                                   'scope': 'TEST FIXTURE ONLY. HEADER BYTES ARE NOT EXECUTABLES.',
                                   'bytes': archive.stat().st_size, 'sha256': digest(archive), **fields}), encoding='utf-8')
    return archive, receipt, digest(archive)


def check(condition, label):
    if not condition:
        raise RuntimeError(label)
    print('PASS ' + label)


def rejected(args, expected, **options):
    try:
        verify(*args, **options)
    except (InvalidReceipt, zipfile.BadZipFile) as failure:
        check(expected in str(failure), expected)
    else:
        raise RuntimeError('Expected refusal: ' + expected)


def unpack(archive, root):
    root.mkdir()
    with zipfile.ZipFile(archive) as package:
        for entry in package.infolist():
            path = root / entry.filename
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open('xb') as out:
                out.write(package.read(entry))


def main():
    options = argparse.ArgumentParser(description=__doc__)
    options.add_argument('output', type=Path)
    root = options.parse_args().output.resolve()
    root.mkdir()
    files = {'source/core/engine.cpp': b'SYNTHETIC DATA PAYLOAD'}
    source = fixture(root / 'source', 'SOURCE_CANDIDATE_ONLY', files)
    check(verify(*source)['verified_members'] == 2, 'detached source receipt and ZIP')
    extracted = root / 'extracted tree'
    unpack(source[0], extracted)
    check(verify(*source, extracted=extracted)['extracted_files'] == 'PASS', 'extracted identity')
    changed = root / 'modified'
    unpack(source[0], changed)
    (changed / 'source/core/engine.cpp').write_bytes(b'MODIFIED TEST FIXTURE')
    rejected(source, 'Extracted file SHA-256 mismatch', extracted=changed)
    other = root / 'unexpected'
    unpack(source[0], other)
    (other / 'extra.txt').write_text('unexpected test fixture', encoding='ascii')
    rejected(source, 'Unexpected:', extracted=other)
    empty = root / 'incomplete'
    empty.mkdir()
    rejected(source, 'Missing:', extracted=empty)
    rejected((*source[:2], '1' * 64), 'expected archive SHA-256 disagrees')
    invalid_crc = fixture(root / 'invalid-crc', 'SOURCE_CANDIDATE_ONLY', files, corrupt=True)
    rejected(invalid_crc, 'Bad CRC-32')
    for label, bypass, reason in (
        ('parent', 'source/../out.txt', 'Invalid Windows path'),
        ('device', 'source/NUL.txt', 'Invalid Windows path'),
        ('trailing-space', 'source/out.txt ', 'Invalid Windows path'),
        ('absolute', '/source/out.txt', 'Invalid Windows path'),
        ('alternate-stream', 'source/out.txt:meta', 'Invalid Windows path'),
        ('duplicate', 'source/core/engine.cpp', 'Duplicate package member'),
        ('same-casefold', 'SOURCE/core/second.txt', 'Case-colliding path'),
        ('file-as-directory', 'source/core/engine.cpp/child', 'File is also a directory'),
    ):
        entries = [*files.items(), (bypass, b'SYNTHETIC TEST MEMBER'), ('CANDIDATE-STATUS.json', b'{}')]
        argv = fixture(root / label, 'SOURCE_CANDIDATE_ONLY', files, archive_entries=entries)
        rejected(argv, reason)
    link = zipfile.ZipInfo('source/core/engine.cpp')
    link.create_system = 3
    link.external_attr = (stat.S_IFLNK | 0o777) << 16
    fixture_link = fixture(root / 'archive-link', 'SOURCE_CANDIDATE_ONLY', files,
                           archive_entries=[(link, b'linked fixture'), ('CANDIDATE-STATUS.json', b'{}')])
    rejected(fixture_link, 'Unsupported archive member')

    headers = {name: pe(*kind) for name, kind in ARCHITECTURES.items()}
    metadata = {name: b'SYNTHETIC CONTRACT' for name in
                ('install.cmd', 'uninstall.cmd', 'registration.cmd', 'INSTALL.md', 'PERSONAL-DATA.md',
                 'data/pinyin.tsv', 'data/symbols.tsv', 'evidence/source-attestation.json', 'evidence/runtime.json')}
    binary = fixture(root / 'binary-contract', 'PASS', headers | metadata)
    check(verify(*binary)['pe_headers'] == 'PASS', 'correct PE header shapes in labeled fixtures')
    rejected(fixture(root / 'synthetic-state', 'SYNTHETIC_CONTRACT_PASS', headers | metadata), 'Expected a binary')
    missing = (headers | metadata).copy()
    missing.pop('wangshu-ime-x86.dll')
    rejected(fixture(root / 'missing-x86', 'PASS', missing), 'Incomplete binary')
    wrong_arch = headers | metadata | {'wangshu-ime-x86.dll': pe('x64', True)}
    rejected(fixture(root / 'incorrect-x86', 'PASS', wrong_arch), 'Wrong or truncated PE architecture')
    wrong_kind = headers | metadata | {'wangshu-ime-x64.dll': pe('x64', False)}
    rejected(fixture(root / 'incorrect-kind', 'PASS', wrong_kind), 'Wrong PE executable/DLL kind')
    short = (headers | metadata) | {'wangshu-voice-helper.exe': pe('x64', False)[:100]}
    rejected(fixture(root / 'incomplete-header', 'PASS', short), 'Wrong or truncated PE architecture')
    print('SYNTHETIC CONTRACT ONLY. Installation, PE execution and registration NOT_RUN.')


if __name__ == '__main__':
    main()
