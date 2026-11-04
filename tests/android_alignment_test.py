#!/usr/bin/env python3
"""Synthetic parser contracts only; none of these fixtures are runnable APKs."""
import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import subprocess
import sys
import unittest
from unittest import mock
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("alignment", ROOT / "tools/check_android_alignment.py")
alignment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(alignment)
OUTPUT = None


def elf(bits=64, machine=183, align=16384, order="<", segments=None):
    header_format = order + ("HHIIIIIHHHHHH" if bits == 32 else "HHIQQQIHHHHHH")
    program_format = order + ("IIIIIIII" if bits == 32 else "IIQQQQQQ")
    header_size = 16 + struct.calcsize(header_format)
    program_size = struct.calcsize(program_format)
    if segments is None:
        segments = [(1, 0, 0, 256, 0x8000, 6, align)]
    total = max([256, header_size + len(segments) * program_size] + [x[1] + x[3] for x in segments])
    data = bytearray(total)
    data[:16] = b"\x7fELF" + bytes([1 if bits == 32 else 2, 1 if order == "<" else 2, 1]) + b"\0" * 9
    struct.pack_into(header_format, data, 16, 3, machine, 1, 0, header_size, 0, 0,
                     header_size, program_size, len(segments), 0, 0, 0)
    for i, (typ, offset, vaddr, filesz, memsz, flags, segment_align) in enumerate(segments):
        fields = ((typ, offset, vaddr, 0, filesz, memsz, flags, segment_align) if bits == 32 else
                  (typ, flags, offset, vaddr, 0, filesz, memsz, segment_align))
        struct.pack_into(program_format, data, header_size + i * program_size, *fields)
    return bytes(data)


def apk(path, flavor="full", overrides=None, stored=False, zip_page=16384, extra=None, skip=None):
    overrides, skip = overrides or {}, skip or set()
    libraries = ["libwangshuime.so"]
    if flavor == "full":
        libraries += ["libsherpa-onnx-jni.so", "libonnxruntime.so"]
    with zipfile.ZipFile(path, "w") as archive:
        for abi, (bits, machine) in alignment.ABIS.items():
            for library in libraries:
                name = "lib/" + abi + "/" + library
                if name in skip:
                    continue
                data = overrides.get(name, elf(bits, machine, 16384 if bits == 64 else 4096))
                info = zipfile.ZipInfo(name)
                info.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
                if stored:
                    base = archive.fp.tell() + 30 + len(name.encode())
                    padding = (-base) % zip_page
                    if padding and padding < 4:
                        padding += zip_page
                    if padding:
                        info.extra = struct.pack("<HH", 0xCAFE, padding - 4) + bytes(padding - 4)
                archive.writestr(info, data)
        if extra:
            for name, data, compress_type in extra:
                archive.writestr(name, data, compress_type=compress_type)


class ElfTests(unittest.TestCase):
    def test_four_android_identities(self):
        for abi, (bits, machine) in alignment.ABIS.items():
            with self.subTest(abi=abi):
                result = alignment.inspect_elf(elf(bits, machine), abi)
                self.assertEqual(result["alignment_16k"], "PASS")

    def test_32bit_4k_is_optional(self):
        result = alignment.inspect_elf(elf(32, 40, 4096), "armeabi-v7a")
        self.assertEqual(result["load_alignment_16k"], "FAIL")
        self.assertEqual(result["policy"], "optional_32bit_consistency")

    def test_64bit_4k_is_required(self):
        result = alignment.inspect_elf(elf(align=4096), "arm64-v8a")
        self.assertEqual(result["load_alignment_16k"], "FAIL")
        self.assertEqual(result["policy"], "required_64bit")

    def test_reject_identity_mismatch(self):
        with self.assertRaisesRegex(ValueError, "identity"):
            alignment.inspect_elf(elf(machine=62), "arm64-v8a")

    def test_big_endian_parses_but_not_android(self):
        self.assertEqual(alignment.parse_elf(elf(order=">"))["byte_order"], "big")
        with self.assertRaisesRegex(ValueError, "identity"):
            alignment.inspect_elf(elf(order=">"), "arm64-v8a")

    def test_reject_bad_magic(self):
        with self.assertRaisesRegex(ValueError, "not an ELF"):
            alignment.parse_elf(b"not elf")

    def test_reject_truncated_header(self):
        with self.assertRaisesRegex(ValueError, "truncated"):
            alignment.parse_elf(elf()[:20])

    def test_reject_truncated_program_table(self):
        with self.assertRaisesRegex(ValueError, "table outside"):
            alignment.parse_elf(elf()[:80])

    def test_reject_no_load(self):
        with self.assertRaisesRegex(ValueError, "no PT_LOAD"):
            alignment.parse_elf(elf(segments=[(4, 0, 0, 20, 20, 4, 4)]))

    def test_reject_extended_phnum(self):
        data = bytearray(elf())
        struct.pack_into("<H", data, 56, 0xFFFF)
        with self.assertRaisesRegex(ValueError, "program-header"):
            alignment.parse_elf(data)

    def test_reject_non_power_two(self):
        with self.assertRaisesRegex(ValueError, "power of two"):
            alignment.parse_elf(elf(align=12288))

    def test_reject_incongruent_load(self):
        with self.assertRaisesRegex(ValueError, "incongruent"):
            alignment.parse_elf(elf(segments=[(1, 0, 1, 256, 512, 6, 16384)]))

    def test_reject_filesz_over_memsz(self):
        with self.assertRaisesRegex(ValueError, "memory size"):
            alignment.parse_elf(elf(segments=[(1, 0, 0, 256, 100, 6, 16384)]))

    def test_reject_segment_past_file(self):
        data = bytearray(elf())
        struct.pack_into("<Q", data, 64 + 32, 4096)
        with self.assertRaisesRegex(ValueError, "beyond file"):
            alignment.parse_elf(data)

    def test_no_relro_is_alignment_pass_not_security_claim(self):
        result = alignment.inspect_elf(elf(), "arm64-v8a")
        self.assertEqual(result["gnu_relro"], [])
        self.assertEqual(result["relro_layout_16k"], "PASS")

    def test_aligned_relro(self):
        result = alignment.inspect_elf(elf(segments=[(1, 0x1000, 0x1000, 256, 0x8000, 6, 16384),
                                                      (alignment.PT_GNU_RELRO, 0, 0x1000, 0, 0x3000, 4, 1)]), "arm64-v8a")
        self.assertEqual(result["relro_layout_16k"], "PASS")

    def test_aligned_relro_filesz_can_exceed_memsz(self):
        # Android's documented aligned example has FileSiz 0x1510 and
        # MemSiz 0x510. The LOAD-only size invariant must not reject RELRO.
        result = alignment.inspect_elf(elf(segments=[(1, 0, 0, 256, 256, 5, 16384),
                                                      (1, 0xCFAF0, 0xDFAF0, 0x1510, 0x3000, 6, 16384),
                                                      (alignment.PT_GNU_RELRO, 0xCFAF0, 0xDFAF0,
                                                       0x1510, 0x510, 4, 1)]), "arm64-v8a")
        self.assertEqual(result["relro_layout_16k"], "PASS")

    def test_misaligned_shared_relro_fails(self):
        result = alignment.inspect_elf(elf(segments=[(1, 0, 0, 256, 0x8000, 6, 16384),
                                                      (alignment.PT_GNU_RELRO, 0, 0x1000, 0, 0x1000, 4, 1)]), "arm64-v8a")
        self.assertEqual(result["relro_layout_16k"], "FAIL")

    def test_relro_head_writable_collision_fails(self):
        result = alignment.inspect_elf(elf(segments=[(1, 0, 0, 0x4000, 0x4000, 6, 16384),
                                                      (alignment.PT_GNU_RELRO, 0x3000, 0x3000,
                                                       0x1000, 0x1000, 4, 1)]), "arm64-v8a")
        self.assertEqual(result["relro_layout_16k"], "FAIL")
        self.assertTrue(result["gnu_relro"][0]["writable_head_collision"])

    def test_dedicated_relro_official_example_passes(self):
        result = alignment.inspect_elf(elf(segments=[(1, 0, 0, 0x680, 0x680, 5, 16384),
                                                      (1, 0x680, 0x4680, 0x1D8, 0x980, 6, 16384),
                                                      (alignment.PT_GNU_RELRO, 0x680, 0x4680, 0x1D8, 0x980, 4, 1)]), "arm64-v8a")
        self.assertEqual(result["relro_layout_16k"], "PASS")
        self.assertFalse(result["gnu_relro"][0]["end_aligned_16k"])

    def test_dedicated_relro_colliding_following_load_fails(self):
        result = alignment.inspect_elf(elf(segments=[(1, 0, 0, 256, 256, 5, 16384),
                                                      (1, 0x680, 0x4680, 0x1D8, 0x980, 6, 16384),
                                                      (1, 0x1000, 0x5000, 16, 256, 6, 16384),
                                                      (alignment.PT_GNU_RELRO, 0x680, 0x4680, 0x1D8, 0x980, 4, 1)]), "arm64-v8a")
        self.assertEqual(result["relro_layout_16k"], "FAIL")

    def test_outside_relro_fails(self):
        with self.assertRaisesRegex(ValueError, "outside PT_LOAD"):
            alignment.inspect_elf(elf(segments=[(1, 0, 0, 256, 256, 6, 16384),
                                                   (alignment.PT_GNU_RELRO, 0, 0x4000, 0, 4, 4, 1)]), "arm64-v8a")


class ApkTests(unittest.TestCase):
    def path(self, name="fixture.apk"):
        folder = OUTPUT / self.id().rsplit(".", 1)[-1]
        folder.mkdir(exist_ok=True)
        return folder / name

    def inspect(self, path, flavor="full"):
        before = alignment.sha256(path)
        result = alignment.inspect_apk(path, flavor)
        self.assertEqual(alignment.sha256(path), before, "inspection must not change APK")
        return result

    def test_compressed_full_32bit_4k_passes(self):
        path = self.path(); apk(path)
        result = self.inspect(path)
        self.assertEqual(result["status"], "PASS", result)
        self.assertEqual(result["optional_32bit_16k_consistency"], "NOT_ALL_16K")
        self.assertEqual(result["runtime_acceptance"], "NOT_RUN")
        self.assertEqual(result["signature_verification"], "NOT_RUN")
        self.assertEqual(result["official_zipalign"]["status"], "NOT_RUN")

    def test_uncompressed_16k_packaging_passes(self):
        path = self.path(); apk(path, stored=True)
        result = self.inspect(path)
        self.assertEqual(result["status"], "PASS", result)
        self.assertEqual(result["zipalign_p16_convention"], "PASS")

    def test_uncompressed_4byte_only_packaging_fails64(self):
        path = self.path(); apk(path, stored=True, zip_page=4)
        result = self.inspect(path)
        self.assertEqual(result["status"], "FAIL")
        self.assertEqual(result["required_64bit_zip"], "FAIL")

    def test_32bit_stored_4k_is_not_required_failure(self):
        path = self.path()
        apk(path, skip={"lib/x86/libwangshuime.so"})
        name = "lib/x86/libwangshuime.so"
        with zipfile.ZipFile(path, "a") as archive:
            archive.writestr(name, elf(32, 3, 4096), compress_type=zipfile.ZIP_STORED)
        result = self.inspect(path)
        self.assertEqual(result["status"], "PASS", result)
        self.assertEqual(result["zipalign_p16_convention"], "FAIL")

    def test_bad64_load_fails(self):
        path = self.path(); apk(path, overrides={"lib/arm64-v8a/libwangshuime.so": elf(align=4096)})
        self.assertEqual(self.inspect(path)["status"], "FAIL")

    def test_bad32_identity_is_structural_failure(self):
        path = self.path(); apk(path, overrides={"lib/x86/libwangshuime.so": elf()})
        self.assertEqual(self.inspect(path)["status"], "FAIL")

    def test_32bit_4k_incongruence_is_baseline_failure(self):
        path = self.path(); apk(path, overrides={"lib/x86/libwangshuime.so": elf(32, 3, segments=[(1, 0, 1, 256, 4096, 6, 1)])})
        result = self.inspect(path)
        self.assertEqual(result["status"], "FAIL")
        self.assertTrue(any("baseline 4 KiB" in item for item in result["errors"]))

    def test_32bit_align_one_with_congruent_pages_not_rejected(self):
        path = self.path(); apk(path, overrides={"lib/x86/libwangshuime.so": elf(32, 3, 1)})
        self.assertEqual(self.inspect(path)["status"], "PASS")

    def test_missing_library_fails(self):
        path = self.path(); apk(path, skip={"lib/x86/libonnxruntime.so"})
        result = self.inspect(path)
        self.assertEqual(result["status"], "FAIL")
        self.assertTrue(any("missing expected" in item for item in result["errors"]))

    def test_preview_not_accepted_as_full(self):
        path = self.path(); apk(path, flavor="typingPreview")
        self.assertEqual(self.inspect(path)["status"], "FAIL")
        self.assertEqual(self.inspect(path, "typingPreview")["status"], "PASS")

    def test_additional_dependency_is_checked(self):
        path = self.path()
        apk(path, extra=[("lib/arm64-v8a/libextra.so", elf(align=4096), zipfile.ZIP_DEFLATED)])
        self.assertEqual(self.inspect(path)["status"], "FAIL")

    def test_duplicate_rejected(self):
        path = self.path()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            apk(path, extra=[("lib/x86/libwangshuime.so", elf(32, 3), zipfile.ZIP_DEFLATED)])
        self.assertEqual(self.inspect(path)["status"], "FAIL")

    def test_unknown_abi_rejected(self):
        path = self.path(); apk(path, extra=[("lib/unknown/libextra.so", elf(), zipfile.ZIP_DEFLATED)])
        self.assertEqual(self.inspect(path)["status"], "FAIL")

    def test_unsafe_path_rejected(self):
        path = self.path(); apk(path, extra=[("../unsafe", b"abc", zipfile.ZIP_DEFLATED)])
        self.assertEqual(self.inspect(path)["status"], "FAIL")

    def test_bad_elf_rejected(self):
        path = self.path(); apk(path, overrides={"lib/arm64-v8a/libwangshuime.so": b"bad ELF"})
        self.assertEqual(self.inspect(path)["status"], "FAIL")

    def test_bad_local_header_rejected(self):
        path = self.path(); apk(path)
        with path.open("r+b") as stream:
            stream.write(b"BAD!")
        self.assertEqual(self.inspect(path)["status"], "FAIL")

    def test_crc_failure_rejected(self):
        path = self.path(); apk(path, stored=True)
        with zipfile.ZipFile(path) as archive, path.open("r+b") as stream:
            offset = alignment.local_data_offset(stream, archive.infolist()[0])
            stream.seek(offset + 200); stream.write(b"x")
        self.assertEqual(self.inspect(path)["status"], "FAIL")

    def test_malformed_deflate_is_fail_receipt_not_traceback(self):
        path = self.path(); apk(path)
        with zipfile.ZipFile(path) as archive, path.open("r+b") as stream:
            offset = alignment.local_data_offset(stream, archive.infolist()[0])
            stream.seek(offset); stream.write(b"\x07")  # Invalid DEFLATE block type.
        self.assertEqual(self.inspect(path)["status"], "FAIL")
        receipt = self.path("bad-deflate.json")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(alignment.main([str(path), "--receipt", str(receipt)]), 1)
        self.assertEqual(json.loads(receipt.read_text())["status"], "FAIL")

    def test_missing_apk_fails_not_pass(self):
        self.assertEqual(alignment.inspect_apk(self.path())["status"], "FAIL")

    def test_cli_receipt_and_preserve_existing(self):
        path = self.path(); apk(path)
        receipt = self.path("result.json")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(alignment.main([str(path), "--receipt", str(receipt)]), 0)
        before = alignment.sha256(receipt)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            alignment.main([str(path), "--receipt", str(receipt)])
        self.assertEqual(alignment.sha256(receipt), before)

    def test_requested_zipalign_failure_not_ignored(self):
        path = self.path(); apk(path)
        output = io.StringIO()
        with mock.patch.object(alignment.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "", "failure")) as run:
            with contextlib.redirect_stdout(output):
                self.assertEqual(alignment.main([str(path), "--zipalign", "/existing/zipalign"]), 1)
            self.assertEqual(run.call_args[0][0][1:6], ["-c", "-P", "16", "-v", "4"])
        self.assertEqual(json.loads(output.getvalue())["official_zipalign"]["status"], "FAIL")

    def test_requested_zipalign_missing_not_ignored(self):
        path = self.path(); apk(path)
        with mock.patch.object(alignment.subprocess, "run", side_effect=FileNotFoundError("missing")):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(alignment.main([str(path), "--zipalign", "/missing/zipalign"]), 1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    OUTPUT = args.evidence.resolve()
    OUTPUT.mkdir(parents=True, exist_ok=False)
    (OUTPUT / "FIXTURES-NOT-APKS.txt").write_text(__doc__ + "\n", encoding="utf-8")
    unittest.main(argv=[sys.argv[0]], verbosity=2)
