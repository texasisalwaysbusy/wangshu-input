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

在解压后的开发包目录可用 `install.cmd --preflight` 或 `uninstall.cmd --preflight` 检查所需注册工具和文件是否存在且非空。x64 主机一次检查 x64 和 x86 两个架构。该模式不执行注册工具。签名、哈希与架构身份按候选收据另行核对。

在获准注册或更新前，记下当前默认输入法，保留此前版本的两种架构文件、位置、哈希和个人数据备份。使用独立版本目录。若注册在中途失败，保留两步结果和目录，先确认各架构的注册状态，再按已确认的恢复方案使用原版匹配文件恢复。不要重跑脚本、删除原目录或清理个人数据来试错。

## File preflight and recovery preparation

From the extracted development package, run `install.cmd --preflight` or `uninstall.cmd --preflight` to verify that the required registrars and files are present and nonempty. On an x64 host both architectures are checked together. This mode launches no registrar. Use the candidate receipt to check signatures, hashes and architecture separately.

Before an approved registration or upgrade, record the current default input method and retain the previous version's files and hashes for both architectures, their locations and the personal-data backup. Keep each version in its own directory. If only one operation succeeds, preserve both results and the files, establish the registration state for each architecture, then recover with the matching prior files under the approved recovery procedure. Avoid blind retries or clearing data.

项目代码沿用仓库 Apache-2.0 许可。词典及依赖保留自己的许可。

Project code follows the repository's Apache-2.0 license. Dictionary and dependencies retain separate licenses.
