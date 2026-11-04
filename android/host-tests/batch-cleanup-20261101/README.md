# Split adapter batch cleanup regression

This host-only test extracts the actual `SplitOwner`, `splitOwner`, `splitCurrent`,
`snapshotSplit`, and `replaceSplit` declarations from `WangshuIme.java` and compiles
them with the actual `SplitSelection.java`. Android IPC, service lookup, privacy
lookup, and editor snapshots are synthetic test doubles. This is not a full
Android build, real editor, or device test.

## Defect and bounded fix

After a successful `beginBatchEdit`, the old `finally` expression checked
`splitCurrent(candidate)` before calling `endBatchEdit`. An owner change, privacy
transition, or exception from connection lookup could short-circuit cleanup.
The fix attempts `endBatchEdit` exactly once on the captured connection. It never
reacquires a new connection, repeats a text write, or rolls back an uncertain edit.
False acknowledgments and exceptions remain uncertain; the existing final owner
check and `SplitSelection` invalidation behavior remain in effect.

This pairs a successfully returned begin with an end attempt. It cannot prove that
a remote end request was delivered or applied, and cannot infer whether a throwing
or rejected begin started a remote batch. It does not close a pre-existing foreign
batch. Android's [InputConnection contract](https://developer.android.com/reference/android/view/inputmethod/InputConnection#endBatchEdit())
distinguishes the IME acknowledgment from the editor-side nesting return value.

## Run

Requirements: existing Python 3 and a JDK whose `java` exposes the `jdk.compiler`
module. No dependency downloads, installation, or Android SDK are used.

From the input-method project directory, choose a fresh output directory:

```sh
python3 android/host-tests/batch-cleanup-20261101/run.py "$PWD" /tmp/ime-batch-new-run
```

Optional third argument: a frozen alternate `WangshuIme.java` for a red comparison.
The output includes generated Java, source hashes, and compiled classes. Source
paths in receipts can later change; retain a source snapshot alongside the receipt
when comparing revisions. Do not reuse output directories for durable evidence.

## Coverage and observed result

The same 19 scenarios failed in 8 cases before the fix, and passed all 147
assertions after it. Scenarios cover successful insert/withdrawal, owner/privacy
changes and lookup exceptions at begin/selection/commit/end, failures after an
already-applied edit, false/throwing end, rejected begin, nested calls during end,
existing foreign batch nesting, and an unavailable owner. Assertions distinguish
the original and replacement connections and reject extra writes or cleanup.

These are synthetic contract checks, not guarantees against silently retargeted
connection wrappers, fabricated/stale snapshots, competing writers, blocking IPC,
or real Android lifecycle races. Full build, device, and Android editor acceptance
remain separate requirements.
