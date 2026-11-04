# Voice snapshot, cancellation and toolbar regressions

Run `python3 run.py PROJECT NEW_OUTPUT_DIRECTORY` with the existing JDK 17+.
The runner writes only below OUTPUT and does not install packages, contact a
network, run Android, use a device/microphone, or touch real input/preferences.
`tools/check_phase_one_host.py` also runs this suite and the existing voice UI suite.

## Bound production paths

The harness extracts the real `WangshuIme` held-voice adapter, `HeldVoice`,
connection snapshots, ownership checks, guarded commit/review, cancellation,
and `voiceToolState`. It compiles the actual `VoiceHoldController` and
`SplitSelection` classes. It also extracts `KeyboardSkin.tool` (substituting
only its Android Context type with the synthetic context) so the toolbar
check observes the actual selected background construction, not merely a
mocked call counter. Generated classes and source SHA-256 receipts are retained.

Views, drawables, InputConnection, speech capture, and lifecycle callbacks are
synthetic. Java regex `\X` supplies fixture grapheme boundaries, not Android
ICU. These are method-level tests, not Chrome, native rendering, audio, Android
build/lint, or actual-device acceptance. Every text is a fixed synthetic fixture.
No production telemetry, input logs, or stored diagnostics are introduced.

## Regression evidence

The frozen 2026-11-04 integration baseline reports 38 pass / 18 fail under the
same 56 checks. The repaired candidate reports 56 pass / 0 fail. Failed checks
are not 18 distinct defects: several assert consequences of the same defect.

1. A complete ExtractedText snapshot is marked by `partialStartOffset == -1`.
   `partialEndOffset` describes partial changes only. Chromium's complete
   snapshot sets partialEndOffset to text length. The old guard incorrectly
   rejected this valid full-text representation. Tests cover KEEP_TEXT and
   NEWLINE through the actual controller and commit adapter for both legacy
   and Chromium-style full snapshots. Partial snapshots, unknown negative
   sentinels, nonzero start offsets, oversize input, out-of-range selections,
   and split Unicode clusters still fail closed.
2. Cancellation formerly changed the microphone label/icon but left both the
   selected flag and the selected-color drawable intact. The exact toolbar
   method now synchronizes both to current recording ownership without a
   full keyboard rebuild.
3. External snapshot/finish/cancel calls can synchronously reenter a synthetic
   host. Ownership and a cancellation/other-input epoch are checked after the
   last external read and before dispatch, and before retaining a review.
   Tests cover cancellation and session/editor replacement at creation,
   capture release, final snapshot, explicit review insertion, and readback.
   Changed composition blocks dispatch. An uncertain dispatch remains
   copy-only and is never blindly repeated. The original left-discard path
   leaves previous host text untouched, and later recognition results after
   cancellation cannot append after a single synthetic ASCII 8.

The reported phone run did not expose its native ExtractedText values or
connection identity. Thus the full-snapshot change fixes a source-backed
Chromium compatibility path; it does not prove this was the sole cause of
that specific phone's safety-review outcome. No new device test is requested.

Primary references checked 2026-11-04:
- Android ExtractedText fields:
  https://developer.android.com/reference/android/view/inputmethod/ExtractedText#partialStartOffset
- Chromium ThreadedInputConnection.convertToExtractedText:
  https://raw.githubusercontent.com/chromium/chromium/main/content/public/android/java/src/org/chromium/content/browser/input/ThreadedInputConnection.java
