# 可选本地语音 / Optional local speech

## Android Full Beta

包名 `org.wangshu.ime`。完整变体包含 sherpa-onnx ASR 运行路径，可在设置中导入该发布版本指定的 `wangshu-voice-zh-en-v1.zip`。模型独立于 APK，使用前须校验来源和 SHA-256，并由用户授予麦克风权限。只接受项目锁定的指定模型包，不是任意 ONNX 导入器。

当前模型来源为 sherpa-onnx 的中英 streaming Zipformer 2023-02-20，具体来源与成员身份记录在 `voice-model-source.json`。模型包通常约 176 MB，展开约 199 MB，重新导入需要额外空间；实际文件以发布清单为准。识别质量、英语/混合语言、partial、数字标点与真实设备速度均有未验证范围。

语音结果需要核对。长按空格和可配右侧动作存在宿主兼容边界；“结果已保留”不等于编辑器已经收到文本。无可用模型或设备端服务时不转云。麦克风图标颜色不是录音状态的独立证明。

Package `org.wangshu.ime` includes the sherpa-onnx ASR path. Import the release's specified `wangshu-voice-zh-en-v1.zip` through settings. The model is separate from the APK; verify its source and SHA-256, and grant microphone access when using speech. Only the pinned model package is supported, not arbitrary ONNX files.

The current source is sherpa-onnx bilingual streaming Zipformer 2023-02-20, identified by `voice-model-source.json`. The package is typically about 176 MB compressed and 199 MB extracted; re-import needs extra space. Consult the actual release manifest. English/mixed quality, partials, number/punctuation formatting and real-device latency remain incompletely validated.

Review results. Hold-space and configurable right-side actions have host-specific limits. A retained result is not proof of editor insertion. Unavailable models or on-device services do not trigger cloud fallback. Microphone icon color alone does not prove capture state.

## Basic Typing Preview

`org.wangshu.ime.typingpreview` 刻意无语音，不请求麦克风权限，不能导入模型以解锁完整变体功能。

`org.wangshu.ime.typingpreview` deliberately excludes speech and microphone permission. Importing a model does not turn it into the full variant.

## Windows

仓库保留本地识别子进程与系统 SAPI 的开发实现。真实 Windows TSF、注册和麦克风尚未验收。本说明不声称 Windows 语音包已经发布或可日用；只有实际发布说明列出的附件才是交付物。

The repository retains development implementations for a local recognition subprocess and system SAPI. Real Windows TSF, registration and microphone acceptance are incomplete. This note does not claim a Windows speech package has been released or is ready for daily use. Only assets actually listed and attached to a release are deliverables.
