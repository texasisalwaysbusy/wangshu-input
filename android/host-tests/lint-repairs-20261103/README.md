# Focused lint repair source checks

Run with the existing host Python and Java compiler, no SDK or downloads:

```text
python3 android/host-tests/lint-repairs-20261103/run.py --project PROJECT --baseline UNMODIFIED_PRE_REPAIR_PROJECT --output NEW_EVIDENCE_DIRECTORY
```

The output directory must not exist. The runner writes generated Java stand-ins, class files, logs and a hash-bound receipt only there. No fixture deletion, installation, device operations, backup/restore or microphone use.

Coverage:

- Both generations of backup XML exclude all nine app-data domains. `allowBackup=false` remains in the manifest.
- Persistence/consent-critical classes and production voice entry points are byte-for-byte unchanged.
- Actual original and repaired `setText` expressions agree under US, Chinese, Arabic and Persian locales, including spaces, line breaks, numbers, null error messages and expression side effects. The runner models Android quoted-resource decoding; it does not replace `aapt` resource verification.
- The original test-rectangle clipping statements reproduce the root/screen mismatch and the first repair's false-negative readiness. The actual repaired capture method translates root-relative visible bounds by the root screen origin before clipping. Regressions cover root y=600, two-axis and negative offsets, partial clips, nonintersection, hidden/empty views and nonzero EditText self-scroll.
- Actual `GlyphKey` code produces the original vector commands, resets its reusable path, avoids new `Path` instances during repeated draws and does not call label `toString()`.
- Actual cursor touch handler and `CursorControlButton` code keep accessibility/framework clicks, short taps, cancel, holds, drag and stale-session behavior separate. Framework and editor objects are explicit fakes.
- The actual RTL reveal expression opens toward the logical start and closes either direction.

This is host verification, not Android lint, Android resource packaging, a real accessibility-service test, on-device rendering, backup transport or microphone acceptance. Run the Android build/lint and relevant instrumented UI tests separately on an authorized Android environment.
