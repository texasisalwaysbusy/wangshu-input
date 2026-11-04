# Beta status and known limitations

[Home](../../README.en.md) · [中文](BETA-STATUS.md)

This document defines the beta's boundaries. **Whether a fix is included in a particular download must be established from that release's notes, matching source and artifact hashes.** Host tests, simulated editors, Wine, emulators, physical phones and real Windows are distinct evidence levels, not interchangeable results.

## Android: available for evaluation, with known issues

The full variant includes local Pinyin, explicit mixed segments, offline speech entry points, symbols and personal-data tools. Compatibility across hosts is not established. This build includes the following source fixes, covered by automated checks against production methods and synthetic hosts:

- Accept Chromium-style full snapshots: `partialStartOffset == -1` marks complete text, without requiring `partialEndOffset == -1`. This fixes the compatibility path shared by first backspace and speech KEEP_TEXT. Partial and out-of-range snapshots remain rejected.
- Keep unconfirmed Pinyin inside the keyboard and write to the host only on explicit confirmation. Field changes, hiding and input termination discard local pending text without deleting old-field text.
- Reset the microphone selected state and background on cancellation, while preserving the rule that late recognition after cancellation cannot write.
- Recheck ownership and readback around cancellation, editor replacement, the last snapshot read, and delayed selection callbacks. Unavailable snapshots can still cause conservative loss of pending text; dispatched writes are not blindly retried.

**No additional manual or physical-device retest followed these fixes.** The earlier Chrome report did not record native snapshots or connection identity. These results establish source compatibility and automated regressions, not a uniquely proven cause of the phone symptoms or universal host compatibility. Icon color remains insufficient proof of capture state.

The abbreviated input `ceshy` retrieves `测试页`, but an existing host query ranks it 57th. That ranking limitation remains open.

Other open areas include English and mixed-language recognition, partial results, number/punctuation formatting, hold/slide speech gestures, narrow-screen touch targets, landscape occlusion, and host-specific commit and selection behavior. Offline speech samples do not establish general accuracy or physical-device latency.

Local example prediction uses only 115 manually authored fixed examples and is off by default. It is not open-ended generation, general bilingual prediction or semantic understanding. Automatic learning from speech corrections, quick translation and semantic rewriting are not completed beta capabilities.

## Windows: development implementation, not real-platform acceptance

The repository contains native x86/x64 TSF code and checking tools. Historical host or Wine results can inform development, but do not establish that the current candidate works on real Windows.

Real-Windows registration, system enablement, 32-bit hosts, modern apps, multiple DPIs, candidate placement and physical microphone acceptance remain incomplete. Windows ARM64 is outside the current supported scope. Clipboard, symbols, prediction and settings are not fully aligned with Android. Android privacy, persistence and feature test results must not be applied to Windows without separate evidence.

## Debug signing and data

Android beta builds use a debug signature, not a store-release signature. In-place upgrade compatibility across builds is not established. Do not use this beta for passwords, sensitive information or important text; keep another keyboard available.

Backups are not complete app snapshots. Personal words/phrases and settings are exported separately; these backups exclude clipboard history, recordings, models and learning records. Choose a trusted destination. See [Privacy](PRIVACY.en.md).

## What to check in release notes

1. Full variant or **Basic Typing Preview (no speech)**.
2. Exact version, source commit/archive hash, APK hash and optional model SHA-256.
3. The evidence level behind each fix and checks that were not run.
4. Outstanding issues and signing/upgrade limitations for that exact file.

Voluntary reports can use synthetic text. Private recordings and input histories are unnecessary. A count of passing tests is not a cross-platform or general-quality guarantee.
