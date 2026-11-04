# Windows 开发工具，未交付已验收安装包

# Windows development tools, not an accepted installer release

本仓库保留原生 x86/x64 TSF 代码及 `install.cmd` / `uninstall.cmd` 开发脚本。**本次 Android beta 介绍不交付已验收的 Windows 二进制，也不证明这些脚本已在真实 Windows 运行通过。**

The repository retains native x86/x64 TSF source and development `install.cmd` / `uninstall.cmd` scripts. **The Android beta documentation does not deliver an accepted Windows binary or establish that these scripts passed on real Windows.**

真实注册、系统启用、GUI、多应用、32 位宿主、现代应用、候选定位和真实麦克风验收均未完成。Windows ARM64 不在当前支持范围。开发包不能作为主力或敏感输入法使用，Android 通过结果也不能移植为 Windows 结论。

Real registration, system enablement, GUI, multiple applications, 32-bit hosts, modern apps, candidate positioning and physical-microphone acceptance remain incomplete. Windows ARM64 is outside the supported scope. Development packages are not suitable as a primary or sensitive-input keyboard, and Android results do not establish Windows readiness.

安装脚本会触及本项目 COM/TSF 注册状态，不能把“源码可读”理解为“脚本已获执行许可”。访问被拒绝或出现安全警告时应停止，不自动提权、不修改 ACL、不绕过警告。任何以后实际提供的 Windows 二进制，应由其独立发布说明给出签名、完整性、安装/回退与验收范围。

Installation scripts change the project's COM/TSF registration state. Source availability is not permission or advice to run them. Stop on access denial or security warnings; do not automatically elevate, change ACLs or bypass warnings. Any later Windows binary should have its own release notes covering signing, integrity, installation/recovery and acceptance scope.

项目代码沿用仓库 Apache-2.0 许可；词典及依赖保留自己的许可。此说明不修改任何脚本，不运行注册、不更改用户机器或输入法设置。

Project code follows the repository's Apache-2.0 license; dictionary and dependencies retain separate licenses. This document changes no script, executes no registration and alters no machine or input-method settings.
