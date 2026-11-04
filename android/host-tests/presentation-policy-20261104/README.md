# Presentation and voice-result policy host regression

Run with an existing JDK (no dependencies downloaded):

    python3 android/host-tests/presentation-policy-20261104/run.py PROJECT NEW_OUTPUT

The output directory must not exist. The suite directly compiles production SymbolPanelCatalog and VoiceResultPolicy, extracts the real symbol selection/layout callbacks plus voice capture/format methods, and records source hashes. Synthetic framework views, callbacks, editor identity and native converter stand-ins are explicit. It does not build or run Android, perform speech recognition, or access user/device data.

Coverage: complete ASCII punctuation; visible default underscore; defensive data copies; native-category fallback; selected-tab offset boundaries; late callbacks after panel/session replacement; stale symbol events rejected before cancellation or undo invalidation; exact symbol payloads; immutable per-recording voice format; lazy conversion; full/preview label XML wiring; retained category-row heights.

The separate mobile-toolbar suite verifies literal candidate labels, bottom-language behavior and seven equal toolbar slots with a fourth-slot central cursor. One slot is intentionally blank. Layout-parameter arithmetic is not Android measurement: 320dp minus 8dp side padding gives about44.6dp per slot; a48dp width requires at least344dp available window width before side insets. The category row remains36dp portrait/28dp landscape to avoid increasing the known landscape-height issue. Native layout, scaled font, TalkBack and touch-target checks remain required.

VoiceResultPolicy is a compatibility seam only. The service still selects RAW/MATH_SYMBOLS from its existing math panel through exactly one adapter. This suite must not be described as proving panel-independent voice behavior, number/punctuation normalization, recognition quality, new streaming partial results, or a change from review to automatic insertion.
