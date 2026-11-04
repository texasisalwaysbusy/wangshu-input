# Mobile candidate and toolbar source checks

This suite executes selected production Java bodies with fake Android views using
an existing JDK. It does not build an APK, resolve Android APIs, measure native UI,
inject input, run an emulator, or touch a phone.

From the candidate root, run:

```sh
python3 android/host-tests/mobile-toolbar-20261103/run.py \
  "$PWD" /absolute/path/to/a/new-evidence-directory /absolute/path/to/baseline
```

The final baseline argument is optional. When supplied, it verifies unchanged
candidate action/long-press methods, bottom language methods, hardware handlers,
generic button factory, version/build files, Windows, core and data. Each run
requires a new output directory and preserves compilation/runtime output.

The suite extracts the current candidate-strip loop, expanded grid, bottom row,
language key, generic button factory and candidate-specific factory, plus the
toolbar construction fragment. It verifies:

- Only generated visible selection prefixes disappear. `3D`, `2 号`, `001`, and
  `A1` are preserved verbatim.
- Literal `空格`, `中`, `数学`, `↵`, `⌫`, `⌄`, and `⌃` are plain candidate buttons,
  never glyph/function keys or replacements for the real space gesture binding.
- Candidate tap/long-press indices, next/previous page mapping, fuzzy metadata,
  accessibility ordinal descriptions and minimum strip width remain wired.
- Settings is the only Settings toolbar entry, first on the left, with the
  existing action and accessible label/help. No language switch is present in
  either toolbar mode or preview/password state.
- Equal side-container weights retain the existing centered cursor structure.
  This is not a measured pixel-layout assertion.
- Bottom language-key left/right preferences, invalid-value fallback, password
  restriction and pending-composition guard retain their existing behavior.
- The language-position dialog remains list-only, with its existing two choices.

The separate parser processes every Java file under `android/app/src`. Parsing
only establishes syntax, not Android type correctness or successful packaging.
The normal JDK warning about `-source 17` without a Java 17 system module path is
retained in the compile log.

Known evidence limitations: click/long-press Android dispatch, TalkBack output,
native text rendering, touch geometry, small screens, font scaling, themes,
orientation changes, Settings navigation/Back, and real editor behavior still
need a separately authorized Android build/device session. Existing
`ToolbarUiChecks` was updated for the new toolbar but has not been run here.
