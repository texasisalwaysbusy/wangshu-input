# 验证范围 / Verification scope

本文件保留 Windows 打包工具要求的路径，是公开边界说明，不是通过证明。

This path is retained for the Windows packaging tools. This is a public scope statement, not a passing test receipt.

## 当前边界 / Current boundary

- Android：beta，调试签名。精确构建与测试范围以对应发布说明及 SHA-256 为准；不可将旧源码、旧 APK 或模拟器结果移用到新产物。
- Windows：TSF 开发源码。真实注册、启用、GUI、多应用、32 位运行、现代应用与麦克风验收未完成。本次文档不提供已验收 Windows 二进制。
- 主机、合成编辑器和 Wine 检查不等于真实设备通过。

- Android is a debug-signed beta. Consult the matching release and SHA-256 for exact build and test scope; older source, APK or emulator results do not validate a new artifact.
- Windows is development TSF source. Real registration, enablement, GUI, multiple hosts, 32-bit execution, modern apps and microphone acceptance remain incomplete. This document does not provide an accepted Windows binary.
- Host, synthetic-editor and Wine checks are not physical-device acceptance.

## 记录原则 / Reporting principles

每个结果应注明源码身份、产物身份、环境、检查内容及 PASS / FAIL / NOT RUN。修改后仍未重跑的项目保持 NOT RUN；不能通过改写报告把失败变成通过。合成文本即可，不应采集私人录音、密码或输入历史。

Each result should identify its source, artifact, environment, scope and PASS / FAIL / NOT RUN state. After changes, checks not rerun remain NOT RUN. Editing a report cannot turn a failure into a pass. Synthetic text is sufficient; private recordings, passwords and input histories should not be collected.

这是供自愿评估的 beta，不能用于密码、敏感信息或重要文本。以上是证据边界，不是安排新的人手或真机测试的承诺。

This opt-in beta must not be used for passwords, sensitive information or important text. These are evidence boundaries, not a commitment to arrange further manual or physical-device tests.
