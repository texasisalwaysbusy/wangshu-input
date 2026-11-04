#!/usr/bin/env python3
"""Read-only APK ELF/ZIP gate using Python's standard library; no extraction/install.

PASS covers the inspected static properties, not device/runtime acceptance. The
Android 16 KiB ELF requirement is checked for arm64-v8a and x86_64. 32-bit 16 KiB
consistency is reported separately and is not a required compatibility failure.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import zipfile
import zlib

PAGE = 16384
ABIS = {"armeabi-v7a": (32, 40), "arm64-v8a": (64, 183),
        "x86": (32, 3), "x86_64": (64, 62)}
PT_LOAD, PT_GNU_RELRO, PF_W = 1, 0x6474E552, 2
MAX_NATIVE_BYTES = 512 * 1024 * 1024
MAX_TOTAL_BYTES = 2 * 1024 * 1024 * 1024


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_elf(data):
    """Parse program headers, rejecting truncated or unsupported ELF inputs."""
    if len(data) < 16 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    elf_class, encoding, version = data[4:7]
    if elf_class not in (1, 2) or encoding not in (1, 2) or version != 1:
        raise ValueError("unsupported ELF identification")
    bits, order = (32 if elf_class == 1 else 64), ("<" if encoding == 1 else ">")
    header_format = order + ("HHIIIIIHHHHHH" if bits == 32 else "HHIQQQIHHHHHH")
    header_size = 16 + struct.calcsize(header_format)
    if len(data) < header_size:
        raise ValueError("truncated ELF header")
    header = struct.unpack_from(header_format, data, 16)
    kind, machine, version, _, phoff, _, _, ehsize, phsize, phnum, _, _, _ = header
    fmt = order + ("IIIIIIII" if bits == 32 else "IIQQQQQQ")
    if kind != 3 or version != 1 or ehsize != header_size:
        raise ValueError("expected a version-1 ET_DYN shared object")
    if not 0 < phnum < 0xFFFF or phsize != struct.calcsize(fmt):
        raise ValueError("missing/unsupported program-header table")
    if phoff < header_size or phoff + phnum * phsize > len(data):
        raise ValueError("program-header table outside file")
    segments = []
    for index in range(phnum):
        fields = struct.unpack_from(fmt, data, phoff + index * phsize)
        if bits == 32:
            typ, offset, vaddr, _, filesz, memsz, flags, align = fields
        else:
            typ, flags, offset, vaddr, _, filesz, memsz, align = fields
        if offset + filesz > len(data):
            raise ValueError("program segment extends beyond file")
        if vaddr + memsz >= 1 << bits:
            raise ValueError("program segment virtual address overflow")
        if typ == PT_LOAD and filesz > memsz:
            raise ValueError("segment file size exceeds memory size")
        if typ == PT_LOAD:
            if align not in (0, 1) and (align & (align - 1)):
                raise ValueError("PT_LOAD alignment is not a power of two")
            if align > 1 and offset % align != vaddr % align:
                raise ValueError("PT_LOAD offset/address alignment incongruent")
        segments.append({"type": typ, "offset": offset, "vaddr": vaddr,
                         "filesz": filesz, "memsz": memsz, "flags": flags,
                         "align": align})
    if not any(segment["type"] == PT_LOAD for segment in segments):
        raise ValueError("no PT_LOAD segments")
    return {"bits": bits, "machine": machine, "byte_order": "little" if encoding == 1 else "big",
            "segments": segments}


def inspect_elf(data, abi):
    elf = parse_elf(data)
    if abi not in ABIS:
        raise ValueError("unsupported Android ABI: " + abi)
    if (elf["bits"], elf["machine"]) != ABIS[abi] or elf["byte_order"] != "little":
        raise ValueError("ELF identity does not match Android ABI directory")
    loads = [segment for segment in elf["segments"] if segment["type"] == PT_LOAD]
    congruent_4k = all(segment["offset"] % 4096 == segment["vaddr"] % 4096 for segment in loads)
    load_ok = all(segment["align"] >= PAGE and
                  segment["offset"] % PAGE == segment["vaddr"] % PAGE for segment in loads)
    relros = []
    for relro in (segment for segment in elf["segments"] if segment["type"] == PT_GNU_RELRO):
        start, end = relro["vaddr"], relro["vaddr"] + relro["memsz"]
        if not any(segment["vaddr"] <= start and
                   end <= segment["vaddr"] + segment["memsz"] for segment in loads):
            raise ValueError("GNU_RELRO is outside PT_LOAD memory")
        rounded_end = (end + PAGE - 1) // PAGE * PAGE
        rounded_start = start // PAGE * PAGE
        head_collisions = [segment for segment in loads if segment["flags"] & PF_W and
                           max(rounded_start, segment["vaddr"]) <
                           min(start, segment["vaddr"] + segment["memsz"])]
        # A non-aligned end is valid when the tail contains no writable LOAD
        # bytes, including the documented dedicated-RELRO-LOAD layout.
        tail_collisions = [segment for segment in loads if segment["flags"] & PF_W and
                           max(end, segment["vaddr"]) <
                           min(rounded_end, segment["vaddr"] + segment["memsz"])]
        relros.append({"vaddr": start, "memsz": relro["memsz"], "end": end,
                       "end_aligned_16k": end % PAGE == 0,
                       "writable_head_collision": bool(head_collisions),
                       "writable_tail_collision": bool(tail_collisions),
                       "status": "FAIL" if head_collisions or tail_collisions else "PASS"})
    relro_ok = all(item["status"] == "PASS" for item in relros)
    return {"bits": elf["bits"], "machine": elf["machine"],
            "load_segments": loads, "load_congruence_4k": "PASS" if congruent_4k else "FAIL",
            "load_alignment_16k": "PASS" if load_ok else "FAIL",
            "gnu_relro": relros, "relro_layout_16k": "PASS" if relro_ok else "FAIL",
            "alignment_16k": "PASS" if load_ok and relro_ok else "FAIL",
            "policy": "required_64bit" if elf["bits"] == 64 else "optional_32bit_consistency"}


def local_data_offset(stream, info):
    stream.seek(info.header_offset)
    raw = stream.read(30)
    if len(raw) != 30:
        raise ValueError("truncated ZIP local header")
    signature, _, flags, method, _, _, _, _, _, name_len, extra_len = struct.unpack("<4s5H3I2H", raw)
    if signature != b"PK\x03\x04" or method != info.compress_type or flags != info.flag_bits:
        raise ValueError("inconsistent ZIP local header")
    name = stream.read(name_len).decode("utf-8" if flags & 0x800 else "cp437")
    if name != info.orig_filename:
        raise ValueError("ZIP local/central names disagree")
    if len(stream.read(extra_len)) != extra_len:
        raise ValueError("truncated ZIP extra field")
    return info.header_offset + 30 + name_len + extra_len


def inspect_apk(path, flavor="full"):
    path = Path(path)
    report = {"schema": 1, "apk": str(path.resolve()), "flavor": flavor,
              "status": "FAIL", "evidence_kind": "STATIC_APK_INSPECTION_ONLY",
              "runtime_acceptance": "NOT_RUN", "signature_verification": "NOT_RUN",
              "official_zipalign": {"status": "NOT_RUN"}, "errors": [],
              "libraries": [], "zip_entries": [],
              "scope": "required 64-bit ELF/RELRO and ZIP placement; 32-bit 16 KiB consistency is informational"}
    errors = report["errors"]
    try:
        report["apk_sha256"] = sha256(path)
        report["apk_bytes"] = path.stat().st_size
        expected = {"libwangshuime.so"}
        if flavor == "full":
            expected |= {"libsherpa-onnx-jni.so", "libonnxruntime.so"}
        with zipfile.ZipFile(path) as archive, path.open("rb") as stream:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            if len(names) != len(set(names)):
                raise ValueError("duplicate ZIP member names")
            if sum(info.file_size for info in infos) > MAX_TOTAL_BYTES:
                raise ValueError("uncompressed APK exceeds inspection bound")
            for info in infos:
                name = info.filename
                if name.startswith("/") or "\\" in name or ".." in name.split("/") or "\x00" in info.orig_filename:
                    raise ValueError("unsafe ZIP member name")
                if info.flag_bits & 1:
                    raise ValueError("encrypted ZIP member")
                if info.is_dir():
                    continue
                offset = local_data_offset(stream, info)
                if offset + info.compress_size > report["apk_bytes"]:
                    raise ValueError("ZIP data outside file")
                stored = info.compress_type == zipfile.ZIP_STORED
                native = name.startswith("lib/") and name.endswith(".so")
                # Mirrors the documented zipalign -P 16 -c 4 convention. Its
                # 32-bit .so recommendation is kept separate from required_64bit.
                conventional = not stored or offset % (PAGE if name.endswith(".so") else 4) == 0
                report["zip_entries"].append({"name": name, "data_offset": offset,
                                              "compression": info.compress_type,
                                              "zipalign_p16_convention": "PASS" if conventional else "FAIL"})
                if stored and not name.endswith(".so") and offset % 4:
                    errors.append(name + ": stored non-.so ZIP data is not 4-byte aligned")
                if not native:
                    continue
                parts = name.split("/")
                if len(parts) != 3 or parts[1] not in ABIS:
                    raise ValueError("unsupported native-library path: " + name)
                if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                    raise ValueError("unsupported native-library ZIP compression")
                if info.file_size > MAX_NATIVE_BYTES:
                    raise ValueError("native library exceeds inspection bound")
                data = archive.read(info)
                record = {"name": name, "abi": parts[1], "bytes": len(data),
                          "sha256": hashlib.sha256(data).hexdigest(),
                          "zip_data_offset": offset, "compression": info.compress_type,
                          "zip_16k": "NOT_APPLICABLE_COMPRESSED" if not stored else
                          ("PASS" if offset % PAGE == 0 else "FAIL")}
                try:
                    record.update(inspect_elf(data, parts[1]))
                    if record["load_congruence_4k"] != "PASS":
                        errors.append(name + ": baseline 4 KiB LOAD offset/address congruence failed")
                except ValueError as exc:
                    record["error"] = str(exc)
                    errors.append(name + ": " + str(exc))
                report["libraries"].append(record)
            corrupt = archive.testzip()
            if corrupt:
                raise ValueError("ZIP CRC failure: " + corrupt)
            report["zip_crc"] = "PASS"
            for abi in ABIS:
                for library in sorted(expected):
                    if "lib/" + abi + "/" + library not in names:
                        errors.append("missing expected " + abi + "/" + library)
        required = [item for item in report["libraries"] if ABIS[item["abi"]][0] == 64]
        optional = [item for item in report["libraries"] if ABIS[item["abi"]][0] == 32]
        for item in required:
            if item.get("alignment_16k") != "PASS":
                errors.append(item["name"] + ": required 64-bit ELF/RELRO check failed")
            if item["zip_16k"] == "FAIL":
                errors.append(item["name"] + ": uncompressed 64-bit ZIP data is not 16 KiB aligned")
        report["required_64bit_elf"] = "PASS" if required and all(
            item.get("alignment_16k") == "PASS" for item in required) else "FAIL"
        report["required_64bit_zip"] = "PASS" if required and all(
            item["zip_16k"] != "FAIL" for item in required) else "FAIL"
        report["optional_32bit_16k_consistency"] = "PASS" if optional and all(
            item.get("alignment_16k") == "PASS" and item["zip_16k"] != "FAIL" for item in optional) else "NOT_ALL_16K"
        report["zipalign_p16_convention"] = "PASS" if all(
            item["zipalign_p16_convention"] == "PASS" for item in report["zip_entries"]) else "FAIL"
        if sha256(path) != report["apk_sha256"]:
            errors.append("APK changed during inspection")
        report["status"] = "PASS" if not errors else "FAIL"
    except (OSError, ValueError, RuntimeError, EOFError, NotImplementedError, struct.error, zipfile.BadZipFile, zlib.error) as exc:
        errors.append(str(exc))
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--flavor", choices=("full", "typingPreview"), default="full")
    parser.add_argument("--receipt", type=Path, help="create new JSON receipt; never overwrite")
    parser.add_argument("--zipalign", type=Path, help="optional existing SDK tool; read-only -c invocation")
    args = parser.parse_args(argv)
    if args.receipt and args.receipt.exists():
        parser.error("receipt already exists; choose a new path")
    report = inspect_apk(args.apk, args.flavor)
    if args.zipalign:
        try:
            command = [str(args.zipalign.resolve()), "-c", "-P", "16", "-v", "4", str(args.apk.resolve())]
            result = subprocess.run(command, capture_output=True, text=True, timeout=120)
            report["official_zipalign"] = {"status": "PASS" if result.returncode == 0 else "FAIL",
                                           "command": command, "returncode": result.returncode,
                                           "stdout": result.stdout, "stderr": result.stderr}
            if result.returncode != 0:
                report["status"] = "FAIL"
                report["errors"].append("explicitly requested official zipalign verification failed")
            if sha256(args.apk) != report.get("apk_sha256"):
                report["status"] = "FAIL"
                report["errors"].append("APK changed during official zipalign verification")
        except (OSError, subprocess.TimeoutExpired) as exc:
            report["official_zipalign"] = {"status": "NOT_RUN", "error": str(exc)}
            report["status"] = "FAIL"
            report["errors"].append("requested official zipalign did not complete")
    output = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.receipt:
        with args.receipt.open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(output)
    print(output, end="")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
