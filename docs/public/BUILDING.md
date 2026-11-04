# 构建与验证 · Building and verification

[中文首页](../../README.md) · [English home](../../README.en.md)

## 源码与产物 / Source and artifacts

请使用对应 release 标记的源码，核对该版本的依赖清单、构建凭据和输出哈希。仓库源码不意味着完整 SDK、缓存、语音运行库或模型已经随包提供。

Use the source identified by the release, together with its dependency manifests, build receipts and output hashes. A source checkout does not imply that SDKs, caches, speech runtimes or models are included.

## Android

当前构建配置 / Current build configuration:

- JDK 17 or newer, Gradle 8.13, Android Gradle Plugin 8.13.2.
- Android SDK platform 36, build tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1.
- `minSdk 26`, `targetSdk 36`.
- `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` are configured build targets. This is not a device-compatibility claim.
- Full variant: the pinned ASR-only `sherpa-onnx-asr-only-1.13.6.aar` plus required offline Maven cache. A rebuilt AAR requires a matching receipt accepted by the wrapper. A universal TTS AAR is not a substitute.
- Optional speech model is separate from the APK and is pinned by the project's model manifest.

现有工具链准备好后，从仓库根目录运行 / From the repository root, with an existing toolchain:

```sh
python3 tools/build_android.py --flavor full --preflight
python3 tools/build_android.py --flavor full
```

完整变体默认请求 app/test APK 与 lint。包装脚本强制 Gradle 离线并禁止 SDK 自动下载；缺少依赖时会停止，不会自动安装。构建成功不意味着已经安装或完成真实 Android 验收。

The full build requests app/test APKs and lint by default. The wrapper forces offline Gradle and disables automatic SDK downloads. It stops on missing dependencies rather than installing them. A successful build is not installation or real-Android acceptance.

基础打字预览使用独立变体 / Separate, deliberately speech-free preview:

```sh
python3 tools/build_android.py --flavor typingPreview --preflight
python3 tools/build_android.py --flavor typingPreview
```

不要把 preview 产物改名为 Full Beta，也不要用 preview 的通过结果证明 ASR 可用。

Do not relabel preview artifacts as Full Beta or use preview results as evidence that ASR works.

## Linux 主机检查 / Linux host checks

现有编译器、Python 与 Java 就绪后，可以将输出写入一个全新目录 / With existing compiler, Python and Java tools, choose a new output directory:

```sh
python3 tools/check_phase_one_host.py --output /tmp/wangshu-host-checks-unique
python3 tools/test_pinyin_composition.py --output /tmp/wangshu-pinyin-checks-unique --sanitizers
```

示例中的目录必须尚不存在；每次运行应换用新路径以保留证据。每个版本的实际执行结果见该版发布说明与哈希清单。它们覆盖共享逻辑、提取的生产方法或合成平台边界，不是 Android UI/麦克风或 Windows TSF 的真实平台验收。

The example output directories must not already exist; use a new path for each run to retain evidence. See the versioned release notes and hashes for checks actually run on each build. They exercise shared logic, extracted production methods or synthetic platform boundaries, not actual Android UI/microphone or real-Windows TSF acceptance.

## Windows

仓库 `windows/` 和 `tools/` 中保留独立的 TSF 构建、凭据核对及开发工具。Windows 的当前边界见 [中文 beta 状态](BETA-STATUS.md) / [English beta status](BETA-STATUS.en.md)。在真实 Windows 注册、系统启用、宿主兼容与麦克风检查完成前，不把源码或 Wine 记录当成可日用 Windows 安装包。

The `windows/` and `tools/` directories contain TSF build, receipt-verification and development tooling. Source availability or Wine results do not establish a daily-use Windows package. Real-Windows registration, system enablement, host compatibility and microphone checks remain incomplete.

## 不应混淆的验证层级 / Evidence levels

- **编译 / Build:** source compiles and artifacts are produced.
- **静态核验 / Static inspection:** package identity, permissions, signatures, alignment and hashes.
- **主机/合成检查 / Host or synthetic checks:** logic under a specified model or fixture.
- **模拟器 / Emulator:** observed behavior in that exact virtual environment.
- **真实平台 / Real platform:** behavior on the identified device, OS and host app.

发布说明应逐层列出 PASS、FAIL 或 NOT RUN，不用“全部通过”代替范围。后续源码变化不会自动继承旧产物的检查结果。

Release notes should state PASS, FAIL or NOT RUN at each relevant level. A blanket “all passed” is not a substitute for scope; later source changes do not inherit an older artifact's results.
