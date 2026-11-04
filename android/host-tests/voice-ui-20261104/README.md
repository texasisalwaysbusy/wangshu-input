# Voice UI regressions, 2026-11-04

Run `python3 run.py PROJECT NEW_OUTPUT_DIRECTORY` with the existing JDK 17+ launcher.
The runner only writes generated Java, classes and receipts below the output directory.
It does not use a phone, microphone, Android SDK, model, personal data, network, package
installation, real preferences or source-tree cleanup.

It extracts the actual SettingsActivity right-action dialog body, WangshuIme.cancelVoice,
and the production composition-status rendering. It compiles those methods with synthetic
View, SharedPreferences and capture objects. AlertDialog message/list precedence is a
small model of AOSP AlertController.setupContent, not an Android rendering test.
Actual VoiceHoldSettings and VoiceHoldController are compiled without substitutions.

The frozen checkpoint3/fixed baseline must fail the visibility and cancelled-label checks.
The repaired candidate passes 43 checks. Tests cover cancellation/back-style dismissal,
readback after Save, uncertain commit feedback, corrupt settings, nine status modes,
null views, repeated cancellation, detached capture ownership, KEEP_TEXT versus NEWLINE,
a frozen in-flight action and a delayed result after cancellation. The fake store permits
memory changes on failed commit, matching the existing warning; no disk persistence is claimed.

Android layout/accessibility, native APK/lint compilation, filesystem persistence and actual
microphone stop are NOT_RUN by this harness. The historical phone report separately observed
microphone app-op stopping even while the old listening label remained visible.
