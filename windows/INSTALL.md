# Windows 开发工具，未交付已验收安装包

# Windows development tools, not an accepted installer release

本仓库保留原生 x86/x64 TSF 代码及 `install.cmd` / `uninstall.cmd` 开发脚本。**本次 Android beta 介绍不交付已验收的 Windows 二进制，也不证明这些脚本已在真实 Windows 运行通过。**

The repository retains native x86/x64 TSF source and development `install.cmd` / `uninstall.cmd` scripts. **The Android beta documentation does not deliver an accepted Windows binary or establish that these scripts passed on real Windows.**

真实注册、系统启用、GUI、多应用、32 位宿主、现代应用、候选定位和真实麦克风验收均未完成。Windows ARM64 不在当前支持范围。开发包不能作为主力或敏感输入法使用，Android 通过结果也不能移植为 Windows 结论。

Real registration, system enablement, GUI, multiple applications, 32-bit hosts, modern apps, candidate positioning and physical-microphone acceptance remain incomplete. Windows ARM64 is outside the supported scope. Development packages are not suitable as a primary or sensitive-input keyboard, and Android results do not establish Windows readiness.

## 开发版的表情检索

在中文模式按两次反斜杠打开符号分类，翻到第三页选择“表情和颜文字检索”。也可直接键入两个反斜杠、`find` 和一个反斜杠。输入无声调全拼，例如 `weixiao` 找“微笑”，或 `maomi` 找颜文字。数字键、空格或 Enter 确认，PgUp／PgDn 翻页，Esc 取消。拼音检索词留在候选窗中，沿用符号面板的组合范围保护。

In Chinese mode, type two backslashes and select the emoji and kaomoji search on the third category page. For direct access, type two backslashes, `find`, and one backslash. Search with toneless pinyin such as `weixiao` or `maomi`. Select with a number, Space, or Enter. PgUp and PgDn page through results, and Esc cancels. The query stays in the candidate window and selection uses the symbol panel's composition checks.

安装脚本会触及本项目 COM/TSF 注册状态，不能把“源码可读”理解为“脚本已获执行许可”。访问被拒绝或出现安全警告时应停止，不自动提权、不修改 ACL、不绕过警告。任何以后实际提供的 Windows 二进制，应由其独立发布说明给出签名、完整性、安装/回退与验收范围。

Installation scripts change the project's COM/TSF registration state. Source availability is not permission or advice to run them. Stop on access denial or security warnings; do not automatically elevate, change ACLs or bypass warnings. Any later Windows binary should have its own release notes covering signing, integrity, installation/recovery and acceptance scope.

## 文件预检与回退准备

保留交付 ZIP 和随附的 `artifact.json`。用相同源码版本的 `tools/verify_windows_package.py` 读取交接给出的 SHA-256，先核 ZIP，再用 `--extracted` 核新的解压目录。确认所有成员及 PE 架构匹配后，在该目录运行 `install.cmd --preflight` 或 `uninstall.cmd --preflight`。x64 主机会检查两种架构的注册工具和文件。候选签名按交接单另行核对。

在获准注册或更新前，记下当前默认输入法、旧版本和两个架构各自的注册文件路径及哈希。分别保留旧包、旧版本目录和本地个人数据备份，并按原版本支持的格式另存兼容备份。将新版放在独立目录。若出现中途失败，先暂停并保存两步结果。核实各架构生效的路径后，用已确认的原版匹配文件恢复，再核对默认输入法和个人数据读回。签名异常、文件不一致或状态无法确认时暂停注册及回退。

## File preflight and recovery preparation

Keep the development ZIP and its `artifact.json`. Use `tools/verify_windows_package.py` from the matching source version with the SHA-256 from the handoff. Verify the ZIP first, then run the verifier with `--extracted` on a fresh directory. After checking the files and PE architectures, use `install.cmd --preflight` or `uninstall.cmd --preflight` in that directory to check the presence of files and registrars. An x64 host checks both architectures. Verify signing separately against the candidate handoff.

Before approved registration or upgrade, record the default input method and the previous version's registered paths and hashes for both architectures. Keep the old package and version directory, a local personal-data backup, and a compatibility export if the old version requires one. Use a separate directory for the upgrade. If an operation fails partway, preserve the two results and confirm the registered path for each architecture. Follow the approved recovery procedure with the matching prior files, then check the default input method and personal-data readback.

项目代码沿用仓库 Apache-2.0 许可。词典及依赖保留自己的许可。

Project code follows the repository's Apache-2.0 license. Dictionary and dependencies retain separate licenses.
