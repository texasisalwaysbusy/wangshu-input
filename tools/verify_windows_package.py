#!/usr/bin/env python3
"""Verify a delivered ZIP and optional extracted files without running them."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import zipfile

from windows_receipts import existing_file, read_json, require, verify_pe_stream


SHA256 = re.compile(r'[0-9a-f]{64}')
RESERVED = re.compile(r'CON|PRN|AUX|NUL|CONIN\$|CONOUT\$|(?:COM|LPT)[1-9¹²³]', re.IGNORECASE)
ARCHITECTURES = {
    'wangshu-ime-x64.dll': ('x64', True),
    'wangshu-ime-x86.dll': ('x86', True),
    'wangshu-personal-settings-x64.exe': ('x64', False),
    'wangshu-personal-settings-x86.exe': ('x86', False),
    'wangshu-voice-helper.exe': ('x64', False),
}


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def validate_names(names):
    files = set()
    prefixes = {}
    for name in names:
        parts = name.split('/')
        for part in parts:
            require(part and part[-1] not in '. '
                    and all(ord(char) >= 32 and char not in '\\:"<>|?*' for char in part)
                    and not RESERVED.fullmatch(part.split('.')[0].rstrip(' ')), 'Invalid Windows path: ' + name)
        for size in range(1, len(parts) + 1):
            prefix = '/'.join(parts[:size])
            folded = prefix.casefold()
            require(prefixes.setdefault(folded, prefix) == prefix, 'Case-colliding path: ' + name)
        require(name not in files, 'Duplicate package member: ' + name)
        files.add(name)
    for name in files:
        parts = name.split('/')
        require(all('/'.join(parts[:size]) not in files for size in range(1, len(parts))),
                'File is also a directory: ' + name)
    return files


def inventory(root):
    def plain(path):
        info = path.lstat()
        require(not stat.S_ISLNK(info.st_mode)
                and not (getattr(info, 'st_file_attributes', 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT),
                'Linked or redirected extracted path: ' + str(path))
        return info

    root = root.absolute()
    for folder in (root, *root.parents):
        require(stat.S_ISDIR(plain(folder).st_mode), 'Expected extracted directory: ' + str(folder))
    files = {}
    folders = [root]
    while folders:
        folder = folders.pop()
        with os.scandir(folder) as entries:
            for entry in entries:
                path = Path(entry.path)
                info = plain(path)
                if stat.S_ISDIR(info.st_mode):
                    folders.append(path)
                else:
                    require(stat.S_ISREG(info.st_mode), 'Expected ordinary extracted file: ' + str(path))
                    files[path.relative_to(root).as_posix()] = path
    validate_names(files)
    return files


def receipt_members(receipt):
    require(type(receipt.get('schema_version')) is int and receipt['schema_version'] == 2,
            'Unsupported package receipt schema')
    state = receipt.get('state')
    if state == 'SOURCE_CANDIDATE_ONLY':
        require(receipt.get('zip_crc_and_all_source_sha256') == 'PASS', 'Missing source verification receipt')
        source = receipt.get('source_sha256')
        require(isinstance(source, dict) and source, 'Missing source manifest')
        members = {'source/' + name: value for name, value in source.items()}
    else:
        require(state == 'PASS' and receipt.get('evidence_kind') == 'PE_EXECUTOR_OBSERVATION'
                and receipt.get('zip_crc_and_all_member_sha256') == 'PASS',
                'Expected a binary development receipt from the original packager')
        members = receipt.get('members_sha256')
        require(isinstance(members, dict) and members, 'Missing binary manifest')
        required = set(ARCHITECTURES) | {'install.cmd', 'uninstall.cmd', 'registration.cmd', 'INSTALL.md',
                                        'PERSONAL-DATA.md', 'data/pinyin.tsv', 'data/symbols.tsv',
                                        'evidence/source-attestation.json', 'evidence/runtime.json'}
        require(required <= members.keys(), 'Incomplete binary package membership')
    validate_names(members)
    require(all(isinstance(value, str) and SHA256.fullmatch(value) for value in members.values()),
            'Invalid member digest')
    return state, members


def verify(archive, receipt_file, expected, extracted=None):
    archive = existing_file(archive)
    receipt = read_json(existing_file(receipt_file))
    expected = expected.lower()
    require(SHA256.fullmatch(expected) and receipt.get('sha256') == expected,
            'The expected archive SHA-256 disagrees with the receipt')
    require(archive.name == receipt.get('archive') and archive.stat().st_size == receipt.get('bytes'),
            'Archive name or size disagrees with the receipt')
    require(digest(archive) == expected, 'Archive SHA-256 mismatch')
    state, members = receipt_members(receipt)
    structural = {}
    with zipfile.ZipFile(archive) as package:
        entries = package.infolist()
        names = validate_names(item.orig_filename for item in entries)
        extra = {'CANDIDATE-STATUS.json'} if state == 'SOURCE_CANDIDATE_ONLY' else set()
        require(names == members.keys() | extra, 'Archive membership disagrees with the receipt')
        for info in entries:
            kind = stat.S_IFMT(info.external_attr >> 16)
            require(not info.is_dir() and kind in (0, stat.S_IFREG) and not (info.flag_bits & 1),
                    'Unsupported archive member: ' + info.filename)
            with package.open(info) as data:
                measured = hashlib.file_digest(data, 'sha256').hexdigest()
            if info.filename in members:
                require(measured == members[info.filename], 'Archive member SHA-256 mismatch: ' + info.filename)
            else:
                structural[info.filename] = measured
        if state == 'PASS':
            for name, (architecture, dll) in ARCHITECTURES.items():
                with package.open(name) as executable:
                    verify_pe_stream(executable, package.getinfo(name).file_size, name, architecture, dll)

    if extracted is not None:
        files = inventory(extracted)
        complete = members | structural
        missing = sorted(complete.keys() - files.keys())
        unexpected = sorted(files.keys() - complete.keys())
        require(not missing and not unexpected,
                f'Extracted files differ. Missing: {missing}. Unexpected: {unexpected}')
        for name, path in files.items():
            require(digest(path) == complete[name], 'Extracted file SHA-256 mismatch: ' + name)
    require(digest(archive) == expected, 'Archive changed during verification')
    return {'state': 'PASS', 'package_kind': 'source' if state == 'SOURCE_CANDIDATE_ONLY' else 'binary',
            'archive_sha256': expected, 'verified_members': len(names),
            'extracted_files': 'PASS' if extracted is not None else 'NOT_RUN',
            'pe_headers': 'PASS' if state == 'PASS' else 'NOT_APPLICABLE',
            'signature_verification': 'NOT_RUN', 'installation_and_runtime': 'NOT_RUN'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--receipt', required=True, type=Path)
    parser.add_argument('--sha256', required=True, help='Expected archive identity from the handoff')
    parser.add_argument('--extracted', type=Path, help='Optional fresh directory containing the full extracted package')
    args = parser.parse_args()
    print(json.dumps(verify(args.archive, args.receipt, args.sha256, args.extracted), indent=2))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, NotImplementedError, RuntimeError, zipfile.BadZipFile) as error:
        print('REFUSED: ' + str(error), file=sys.stderr)
        sys.exit(2)
