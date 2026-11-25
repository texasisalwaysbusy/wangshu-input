# 望舒输入法 Android 0.1.0-beta.2 / Wangshu Input

**Full Beta 预发布，非商店稳定版。** 此版本源自已原生构建的开发候选，后续仅更新包版本号并重新编译，尚未完成一期全部验收。

- [下载 Full APK](https://github.com/texasisalwaysbusy/wangshu-input/releases/download/v0.1.0-beta.2/wangshu-input-full-0.1.0-beta.2.apk) · [发布页](https://github.com/texasisalwaysbusy/wangshu-input/releases/tag/v0.1.0-beta.2)
- [应用产物身份](ARTIFACTS.json) · [源码构建输入](SOURCE-INPUTS.json) · [自动检查范围](AUTOMATED-CHECKS.json)
- [以前的 Beta.1（保留）](https://github.com/texasisalwaysbusy/wangshu-input/releases/tag/v0.1.0-beta.1)

| 对象 | 身份 |
|---|---|
| Full APK | 60,667,009 bytes |
| SHA-256 | `3972b56f654806626981b3aecc3e5e61c9782f6c5fab7f62226c1c06d59b4b8a` |
| 包名 / Package | `org.wangshu.ime` |
| versionCode / versionName | 3 / 0.1.0-beta.2 |
| Debug certificate SHA-256 | `7126a0af876f97fac3d20f133ac1368a69eb790003570e677c9e0b00d3e80212` |
| Build source | `df154a47866877778b29feeb95e4ea2b7247d3e8` |
| Android API / ABI | min 26, target 36; arm64-v8a, armeabi-v7a, x86, x86_64 |

## 本版改进 / Development changes

- **编辑安全**：保留待确认拼音在键盘内、编辑器归属及读回保护；增强旧候选、语音、光标选择、批量删除的过期与撤销控制。
- **语音**：独立流式预览、松手最终落点、取消及过期回调保护；工具栏识别及复制动作绑定原字段。数字与日期等格式使用独立确定性规则。
- **日用输入**：新增模糊候选入口、表情与颜文字拼音检索和收藏、数字面板独立联想设置，改善英文下一词的上下文候选。算术仍为确定性引擎。
- **个人数据**：加强词库、常用语、收藏及设置的预览、回读与失败恢复。

这些是开发候选功能和自动回归结果，**并非新真机通过声明**。通用整句预测尚未完成，中英文补全与下一词仍有质量不足，英文及混合语音、标点、横屏、Chrome 和真实宿主兼容、跨重启恢复仍待验。

## 更新与恢复 / Update and recovery

1. 下载后核对 APK SHA-256 与包名，保留旧安装包、原日用输入法以及个人词库/常用语、设置的独立备份。设置和个人数据备份不是完整应用快照。
2. 本版 versionCode 高于 Beta.1（2 → 3），Full 包名与公开 Beta.1 的调试证书相同。这是静态升级兼容证据，尚未实际覆盖安装或重启。若系统拒绝更新，不卸载、不清除数据、不绕过平台限制。
3. 安装和启用输入法、麦克风权限由测试者本人决定。先在合成文本框试用；不用密码、敏感或重要文本。需要回退时先切换到原日用输入法。旧版下载保留；Android 可能禁止覆盖降级，因此不能承诺带数据直接回退。
4. 离线语音模型是可选的独立 ZIP，不内置、不随发布上传；构建未增加 INTERNET 权限。仅 Full 公开发布；Basic Typing Preview（无语音）及两个测试 APK 均为本地验证产物，两个应用包数据独立。

两变体原生编译、测试应用编译、lint（0 项）、v2 签名、权限、ABI、ELF 依赖、ZIP CRC 及静态 16 KiB 对齐核对通过。Gradle 单元测试为 NO-SOURCE，不计作执行通过。设置 119 个主机断言通过，两项符号链接检查及真实目录同步不可用；真实 Android、麦克风及实际升级/降级均 NOT_RUN。

## English

This is an opt-in debug-signed prerelease, not a stable store or completed cross-platform phase-one release. The Full APK is the only published binary; the basic typing preview and test APKs were built for local validation. New changes include keyboard-local composing, editor ownership checks, separate streaming voice previews, bounded undo and deletion, fuzzy candidates, symbol search/favorites, more contextual English suggestions, and improved personal-data recovery.

Native compilation, test-APK compilation, lint, signature, permission, four-ABI, ELF dependency, CRC and static alignment checks passed. Gradle unit tasks were NO-SOURCE. The previous functional candidate had host-only tests; this build changes only version metadata in the Android product source. Physical device testing, microphone quality, actual update and reboot persistence remain unverified. Do not use the beta for sensitive text. Keep a separate keyboard and backups; the old release stays available. Windows is still a development implementation.
