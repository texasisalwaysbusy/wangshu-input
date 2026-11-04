# Windows 个人数据开发边界 / Personal-data development scope

Windows 源码包含本地个人词库/常用语存储与原生管理程序，并与 TSF 候选路径连接。它们属于开发实现，不代表真实 Windows GUI、宿主、辅助功能或持久化故障恢复已经验收。

The Windows source contains local personal-word/phrase storage and a native management application connected to the TSF candidate path. These are development implementations, not acceptance of real-Windows GUI, host integration, accessibility or persistence recovery.

Windows 与 Android 使用各自的平台存储实现。不要以 Android 的导入/导出、设置、隐私或生命周期结果推断 Windows 已完成。个人词条与常用语可能包含私人文字，JSON/TSV 不是加密格式；不要将真实备份附在公开 Issue 或开发包中。

Windows and Android use separate platform storage implementations. Android import/export, settings, privacy or lifecycle results do not validate Windows. Personal entries can contain private text; JSON/TSV are not encrypted formats. Do not attach real backups to public issues or development packages.

公开源码与示例仅应用合成内容，不随开发包播种真实用户记录。后续二进制如果交付，需在自己的发布说明中明确版本、来源、签名、测试范围和剩余限制。本次文档不承诺也不安排新的人工或真机测试。

Public source and examples should use synthetic data only, without seeded real-user records. A later binary delivery must identify its version, provenance, signature, tests and remaining limitations in its own release notes. This document does not promise or schedule additional manual or physical-device tests.
