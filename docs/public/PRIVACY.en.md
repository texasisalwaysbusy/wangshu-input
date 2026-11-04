# Privacy and data boundaries

[Home](../../README.en.md) · [中文](PRIVACY.md)

This describes the current Android design and known limitations. It is not an independent security audit or evidence that Windows passed the same checks.

## Where processing occurs

- The current Android manifest does not request `INTERNET` permission or require an account. Pinyin, candidate lookup and rule-based mathematical conversion run locally.
- The full variant uses the specified imported offline model, or attempts an explicitly on-device system recognizer. Unavailable models, services or permissions do not trigger a cloud-recognition fallback.
- Session audio is processed in memory rather than stored as a recording history. Exit, field-change and cancellation paths include stop protections, but physical-microphone and host behavior still have unvalidated areas. Do not use icon color alone to decide whether recording stopped.
- Basic Typing Preview intentionally excludes speech and microphone permission. Its results do not validate the full variant's speech privacy.

## What may be stored locally

- Built-in dictionary/symbol assets and explicitly imported offline models.
- Personal words and phrases you add or import.
- Candidate-frequency records after explicitly enabling learning. Learning is off by default; a temporary no-learning mode is also provided.
- Clipboard items after explicitly enabling clipboard history. This is off by default and independent of dictionaries and speech.
- App settings and retained local fault copies from settings recovery.

The product does not maintain a regular keystroke, password or complete input-history archive, and does not provide cloud sync or telemetry. **This does not mean it never saves user text:** personal words, phrases, clipboard entries and custom symbols can all contain it.

## Backups and file providers

Personal words/phrases use JSON backups; dictionaries also accept a constrained TSV import. Settings use a separate allowlisted TSV format. These exports exclude clipboard history, recordings, models, learning records and automatic capture/learning consent flags. Some newer toggles are outside the settings allowlist, so this is not a complete device-migration mechanism.

A backup can include text you entered in custom symbols. Base64 is reversible encoding, **not encryption**. If you choose a cloud drive or another provider through Android's file picker, that provider receives the file. An app without network permission does not guarantee an exported file never leaves the device.

Android system backup is disabled in the current manifest. Uninstalling or clearing app data can still erase local content. Automatic restoration across builds, platforms or devices is not promised.

## Input-method access and beta use

An input method needs access to the current editor. Password-field protections depend on host metadata and implementation behavior; they are not absolute isolation. The host app and OS can also process what you enter.

Do not enter passwords, sensitive information or important text using this debug-signed beta. Use synthetic feedback examples, redact screenshots and do not upload recordings, personal dictionaries, phrases or clipboard backups. Offline design reduces some risks; it does not remove software defects, device-security risks or third-party service behavior.
