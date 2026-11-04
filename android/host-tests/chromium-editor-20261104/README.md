# Chromium snapshot / local preedit host regressions

Run with existing tools only:

    python3 android/host-tests/chromium-editor-20261104/run.py PROJECT NEW_OUTPUT_DIRECTORY

This compiles the actual EditorActions, BeforeCaretEditor, BackspaceGesture,
PendingTextCommit, SplitSelection, MixedComposition, FuzzyPinyin and EnterPolicy
classes. It extracts the selected production WangshuIme method bodies unchanged,
including key/space/Enter/punctuation, captured TextWrite and CandidateClick
bindings, commits, selection updates, and start/finish/hide lifecycle methods.
The service/UI/dictionary result and InputConnection are explicit synthetic
boundaries. A Java regex `\X` adapter replaces Android ICU. Receipts hash all
source methods and tests. The Android framework, Binder, Chrome, APK, real device,
microphone, screen layout and native ICU are NOT_RUN.

The Chromium-shaped full snapshot uses partialStartOffset=-1 and a nonnegative
partialEndOffset, with the latter equal to the text length. Android documents the
first field as the full/delta discriminator; Chromium sets the second field to
length even for the full snapshot:

- https://developer.android.com/reference/android/view/inputmethod/ExtractedText#partialStartOffset
- https://raw.githubusercontent.com/chromium/chromium/main/content/public/android/java/src/org/chromium/content/browser/input/ThreadedInputConnection.java

The synthetic failures on the old source establish defective source paths, not
an independent reproduction of a particular phone report. That report did not
include the Chrome version or native ExtractedText/callback traces.

Local preedit now remains in the keyboard's composition/candidate strip. It is
committed once after explicit candidate/Space/Enter/raw confirmation. Punctuation
confirms the candidate once and then inserts punctuation once. Backspace/Escape,
field transition, hide and finish do not retract host text, because raw preedit
never entered the host. A selected host range is therefore preserved until a
nonempty confirmation replaces it. Own synchronous commit notifications are
separate from external caret moves. Delayed notifications are ignored only when a bounded current readback still
matches the captured text and selection. The original owner/session/raw input
and snapshot identity are checked again after that read. Changed or unavailable
state drops only local pending text; readback reentry cannot clear newer state.

Existing instrumentation source expectations were updated for local preedit.
Those suites were not executed. Historical evidence is not upgraded by changing
test source, and the old source remains in the delivery baseline.
