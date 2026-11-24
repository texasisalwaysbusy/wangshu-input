# Windows 个人数据开发边界 / Personal-data development scope

Windows 源码包含本地个人词库/常用语存储与原生管理程序，并与 TSF 候选路径连接。它们属于开发实现，不代表真实 Windows GUI、宿主、辅助功能或持久化故障恢复已经验收。

The Windows source contains local personal-word/phrase storage and a native management application connected to the TSF candidate path. These are development implementations, not acceptance of real-Windows GUI, host integration, accessibility or persistence recovery.

词库导入确认使用预览中已计算的结果。若本机词库或备份文件在对话框打开期间变化，请重新预览。故障修复确认会保留损坏文件副本。

Import confirmation applies the result computed for the preview. If the local dictionary or its backup changes while the dialog is open, preview again. Confirmed repair preserves copies of damaged files.

## 收藏、交换与旧版恢复 / Favorites, exchange and recovery

在本地个人数据管理选择「收藏表情」，选择内置条目后按「收藏」或「取消收藏」。在中文模式键入两个反斜杠，翻到符号菜单第三页，选择「收藏表情与颜文字」即可输入。使用两个反斜杠加 `favorites` 可直接打开。备份预览显示收藏数量，完整恢复保留收藏顺序。

In the local personal-data manager, open Favorites and select an authored emoji or kaomoji to add or remove it. The third page of the Chinese-mode symbol menu provides the saved list. Two backslashes followed by `favorites` open it directly. Import previews include the number of favorites.

JSON v1 保留词库与常用语。含收藏时完整备份写为 JSON v2，`favorites` 为至多 64 个内置字形，去重后保留原顺序。Android 和 Windows 都可读取两种格式。准备回退到只读 v1 的旧版本时，先保留完整备份，再在新版选择旧版兼容导出。按旧版对应的恢复方案使用 v1 文件，收藏仍可从完整备份在支持 v2 的版本恢复。

「数学模式的补全和数字续写」与普通补全分别保存，修改后在下一次联想查询中生效。旧本机排序文件沿用以往的普通联想选择。两个开关不同才需要本机排序 v7，相同则保持可表达该状态的旧格式。旧版恢复需要升级前留存的原本机排序文件。

共用设置 TSV v2 增加数字面板联想，v1 仍可导入并保留接收端的这项设置。在两端用预览确认后保存。回退前留存原版导出的共用设置 v1、完整个人数据和本机排序，保留新版备份供新版恢复。

JSON v1 contains words and phrases. Complete backups with favorites use JSON v2 and add an ordered list of up to 64 catalog glyphs. Both platforms read both versions. Before a downgrade to a v1-only build, keep a complete backup and create a separate legacy export in the newer version. Use the v1 file with the older build's recovery procedure. Keep the complete backup for restoring favorites in a v2-capable build.

Windows 与 Android 使用各自的平台存储实现。不要以 Android 的导入/导出、设置、隐私或生命周期结果推断 Windows 已完成。个人词条与常用语可能包含私人文字，JSON/TSV 不是加密格式；不要将真实备份附在公开 Issue 或开发包中。

Windows and Android use separate platform storage implementations. Android import/export, settings, privacy or lifecycle results do not validate Windows. Personal entries can contain private text; JSON/TSV are not encrypted formats. Do not attach real backups to public issues or development packages.

公开源码与示例仅应用合成内容，不随开发包播种真实用户记录。后续二进制如果交付，需在自己的发布说明中明确版本、来源、签名、测试范围和剩余限制。本次文档不承诺也不安排新的人工或真机测试。

Public source and examples should use synthetic data only, without seeded real-user records. A later binary delivery must identify its version, provenance, signature, tests and remaining limitations in its own release notes. This document does not promise or schedule additional manual or physical-device tests.
