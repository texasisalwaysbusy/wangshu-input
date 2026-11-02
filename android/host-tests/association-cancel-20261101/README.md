# Association worker cancellation boundaries

The actual `MathAssociation` and `PersonalPhraseAssociation` controllers now stop
between external stages after their generation was cancelled, the controller was
closed, or the worker thread was interrupted. A completed old read must not start
another context read, personal-phrase query, or provider evaluation. The math
fallback and exception-to-completion paths have the same checks.

Only the controller's volatile cancellation state is inspected on the worker.
Main-thread host/UI eligibility checks remain on the main thread. Early returns
do not clear `running`, `timeout`, `visible`, or a replacement request's state.
Existing queued completions still undergo main-thread generation/owner checks.
No insertion protocol, preference default, data format, or provider model changes.

## Standalone host check

Using existing Python 3 and a JDK with `jdk.compiler`, from the project directory:

```sh
python3 android/host-tests/association-cancel-20261101/run.py \
  "$PWD" /tmp/association-cancel-new-output
```

The output directory must not already exist. The runner records frozen source
snapshots/hashes and compiles actual controllers, pure providers and SplitSelection.
It extracts the actual IME snapshot comparator. Handler/Looper, clock and editor
are explicit synthetic doubles, not Android SDK or runtime implementations.

The 31 scenarios cover healthy requests and fallback, cancellation before debounce,
clear/private lifecycle clear/timeout/close/replacement during a blocked first
read, cancellation during providers or the second read, exceptional and interrupted
returns, and an already-queued old completion crossing a newer request/deadline.
The gate deliberately ignores interruption to model an already-started operation.
Tests wait for physical executor completion, not just a cancelled Future.
Thread/owner violations are recorded before throwing so swallowed executor errors
cannot fake a pass. The harness records worker/main-thread boundary violations before an exception
can be swallowed by an executor.

Observed red/green on the same final harness: old controllers fail 24 of 31 cases;
updated controllers pass 31 cases / 277 assertions. Integrated standalone run
also passes. No actual editor input, personal data, install, download or network
operation is performed by this harness.

## Limits

This is a bounded cancellation fix, not complete phrase-association acceptance.
A host transition that changes allowed/session without calling `clear()` is not
observed by the worker. This known limitation is not fixed or counted as passing
by the 31-scenario suite.
Checks cannot retract an already-started read or close the race between the last
check and IPC. The fake timeout is not a measurement of Android scheduling or IPC
latency. Click-time synchronous reads and captured-owner insertion concerns in
the existing phrase-association path remain separate blockers. Full app build,
native Handler/editor lifecycle tests and device acceptance are NOT_RUN here.
