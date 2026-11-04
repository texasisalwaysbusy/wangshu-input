# Android editing, clipboard and settings host boundaries

These tests use existing Python and the JDK compiler module. They install nothing,
connect to no device, do not access the OS clipboard, and use only authored synthetic
text and retained temporary fixtures. Choose a fresh output directory for each run.

From the project directory:

    python3 android/host-tests/editor-boundary-20261103/run.py "$PWD" NEW_EDITOR_OUTPUT
    python3 android/host-tests/editor-boundary-20261103/run_clipboard.py "$PWD" NEW_CLIPBOARD_OUTPUT
    python3 android/host-tests/editor-boundary-20261103/run_settings.py "$PWD" NEW_SETTINGS_OUTPUT

The aggregate tools/check_phase_one_host.py includes all three suites. The editing
runner can compare an old EditorActions.java using --editor-source PATH. It compiles
an exact byte copy under its output directory; the service/session stub uses the
actual extracted candidate editorSession accessor. It does not compile the whole
WangshuIme service. Generated sources and hash-bound receipts are retained.

## Bounded production change

EditorActions now captures the input connection, input session, lifecycle generation,
and observed selection revision. Reads and predispatch checks must still match.
Nested/off-main operations are rejected. Selection state is reset on start, finish,
hide and destruction lifecycle paths. An action never reacquires a replacement
connection for its write. Ordinary own setSelection notifications are accepted only
when the observed endpoint agrees with the requested endpoint. Key-up cleanup stays
on the captured connection. Host exceptions and unconfirmed owner changes stop the
operation without retries or rollback. Feedback no longer promises unchanged text
when a dispatched operation has an uncertain result.

Deletion now requires one complete extracted snapshot of at most 100,000 UTF-16
units, valid UTF-16, valid selection endpoints, and no reported composing span. Before a text deletion it acknowledges
finishComposingText on that connection, then reads again and requires identical
text/selection in the same session. It never deletes after a false/throwing finish.
This prevents a hidden, non-parcelled composing marker from redirecting a
contract-compliant host replacement, without assuming styled snapshots include it.
Separate getSelectedText/before/after responses can be unavailable or truncated even
below the requested limit; concatenating them cannot establish a full Unicode
boundary or distinguish a hidden selection. Whole-text segmentation avoids the
regional-indicator parity error reproduced by the tests. Interior selection endpoints
are refused, while arrow navigation can still leave an interior caret at a boundary.
No editor text is stored in EditorActions fields or written to files.

This deliberately narrows deletion compatibility when an editor withholds a complete
snapshot or exposes a larger document. There is no guessed surrounding-text or key
event fallback. Password fields, very large fields, OEM editors, remote editors,
composing spans, finish-composition callbacks, and responsiveness at the maximum bound need native acceptance.

## Evidence scope

- EditorBoundaryChecks covers owner/session/lifecycle changes, notified selection
  drift, reentry, exceptions, synchronous own callbacks, composing/partial/missing
  snapshots, malformed UTF-16, long regional-indicator parity, ordinary long text,
  graphemes, reverse selections, newline deletion and captured key/menu cleanup.
- BeforeCaretBoundaryChecks compiles the unchanged production class and tests
  acknowledged/false/throwing batch and commit paths, preview identity and undo.
  The official InputConnection contract requests one endBatchEdit for each begin
  call. The existing balancing policy was not changed.
- SymbolBoundaryChecks checks all 192 authored catalog entries, immutability,
  exact glyph search and stale/active selection tokens. It does not assess catalog
  completeness or rendered glyph availability.
- Clipboard suites extract the exact admission, text-validation and capture methods.
  They cover capture consent, private fields, sensitive markers, URI/intent refusal,
  item count, Unicode/length bounds, listener/manager/session changes, fail-closed
  storage, deduplication and the boundary of an already-started save. Preferences,
  clipboard types and storage are synthetic. Persistent ClipboardStore is unchanged.
- SettingsBoundaryChecks compiles the unchanged production codec and symbol parser.
  It tests strict fields/types/bounds, Unicode and 1,024 fixed-seed byte mutations.
  Valid accepted mutations must have a stable semantic/canonical round trip and
  cannot introduce capture/learning consent. It does not restore preferences.

The JVM boundary adapter uses Java regex \\X, not Android ICU. These tests do not
prove an APK build, Android lint, native editor/IME/UI behavior, IPC scheduling,
AtomicFile durability, process death, power-loss safety, microphone behavior, or
phone performance. Existing native instrumentation remains NOT_RUN for this patch.

InputConnection has no compare-and-swap: silent text/selection changes without a
timely notification, dishonest/stale snapshots, connection wrappers silently
retargeted internally, delayed IPC and competing host writes remain outside these
checks. A returned acknowledgement is not proof of an atomic or durable edit.

Primary API contract:
https://developer.android.com/reference/android/view/inputmethod/InputConnection
