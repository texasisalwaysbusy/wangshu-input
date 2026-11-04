# 望舒输入法 Android 0.1.0-beta.1 / Wangshu Input

**源码 beta 已保存；新版 APK 已构建，但公开二进制上传受接口体积限制。** 此目录当前没有可直接下载的 APK，不要把源码 ZIP 当作安装包，也不要把旧 APK 当作修复后版本。不是商店稳定版，亦不代表整体一期已完成。

## 源码与构建 / Source and build

[完整源码 ZIP](wangshu-input-v0.1.0-beta.1-source.zip) · [源码成员清单](SOURCE-INPUTS.json) · [产物身份](ARTIFACTS.json) · [Android 构建证据](ANDROID-BUILD-CHECKS.json) · [主机检查](AUTOMATED-CHECKS.json)

- 源码 ZIP：2,507,216 B；SHA-256 `63d49bbdc82e8b09c57055afd253e14928c302ad66f8e1a4745820ed17d3e863`。
- 已构建 Full APK：46,524,008 B；SHA-256 `729a253b05fad44aaa667502b9f298defaf87f4420b40dcc85137b40c0e595e9`。
- 包名 `org.wangshu.ime`，versionCode `2`，versionName `0.1.0-beta.1`。使用现有调试证书，SHA-256 `7126a0af876f97fac3d20f133ac1368a69eb790003570e677c9e0b00d3e80212`；未附签名私钥。
- Full/Preview 及测试 APK 编译通过；两变体 lint 均 0 错误、0 警告；签名、权限、CRC、zipalign、四 ABI 与 16 KiB 静态检查通过。单元任务为 **NO-SOURCE**，不计作测试通过。
- 406 份输入源码构建前后哈希一致。34 项云端主机命令通过；另有 44 项编辑/本地预编辑、56 项语音安全、43 项语音 UI 的合成边界断言。

当前连接器的单次请求上限 16 MiB，无法上传 46.5 MB APK 的完整编码请求。APK 已私下交付，但没有公开文件链接；此处记录真实哈希，不伪造下载按钮。尚未创建 GitHub Release 页面。

## 此版修复与行为变化

- 兼容 Chromium 完整编辑快照中 `partialEndOffset` 的合法形态，保留范围、会话及 Unicode 安全检查。它是已验证兼容路径修复，不是对手机故障唯一原因的证明。
- 未确认拼音保留在键盘候选栏，确认后才写入宿主；退格先改本地拼音，换字段/隐藏/结束输入会丢弃未确认文本，不删除旧字段正文。空确认不写入、不误删选择。
- 修复取消后的麦克风选中背景，并增加语音、编辑及延迟回调的目标/会话/内容复核。

**修复后没有新增人工或真机复测。** 主机断言和静态检查不等于 Android UI、Binder、麦克风或所有宿主验收。语音识别质量、partial、数字标点、宿主兼容、横屏/窄屏和简拼排序仍有限。可选离线模型不含在 APK 或源码 ZIP 中，来源与打包工具见仓库文档。Windows TSF 仍是开发源码，不提供已验收 Windows 安装包。

请勿用调试签名 beta 输入密码、敏感或重要文本。保留日常输入法，并只用合成文本提供反馈。详见仓库的双语首页、隐私和 beta 状态说明。

## English

The reviewed **source beta** is preserved here. The new Full APK was built and privately delivered, but its public upload is blocked by the connector's 16 MiB request-body limit. **No APK bytes or public APK download are present in this directory.** A GitHub Release page has not been created. This is not a store-stable release or completion of the cross-platform first phase.

The exact source archive and built APK hashes are listed above and in the manifests. All 406 source inputs stayed unchanged during the offline build. Full/Preview app and test-APK compilation passed; both lint reports had zero errors/warnings. Signature, permissions, CRC, zip alignment, four ABIs and 16 KiB static checks passed. Unit tasks were **NO-SOURCE**, not executed test passes.

Changes cover Chromium full-snapshot compatibility, keyboard-local Pinyin pre-edit with single confirmation commits, microphone cancellation styling, and reentrant/late-callback ownership checks. These address verified code paths around the previous test, not a proven unique cause for every observed device failure.

**No new manual or physical-device retest followed these fixes.** Host probes do not establish Android runtime, microphone, general language quality or real-Windows acceptance. ASR quality, partials, numeric punctuation, host compatibility, landscape/narrow screens and abbreviated-Pinyin ranking remain limited. Optional speech models are separate; Windows TSF remains development source. Do not use this debug-signed beta for passwords, sensitive information or important text.
