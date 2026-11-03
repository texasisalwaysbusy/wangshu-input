# 设置页旋转：源码修改与验证边界

2026-11-03 修改说明。开发预览，仍不提供安装包。

## 这次改动

在公开基线 `4b5769f6078b0de9d81968f908d81cc1404cc58b` 上，把 `.SettingsActivity` 的 `windowSoftInputMode` 从 `stateAlwaysHidden|adjustResize` 改为 `stateHidden|adjustResize`，并附加修改说明注释。另把试打区的“不保存文本”承诺替换为准确提示：只用合成文字，状态可能恢复，启用学习可能保存所选词。没有改输入法服务、词典、依赖、权限、存储或第三方许可。

Android 文档区分了“窗口获焦时总是隐藏”和“正常需要时隐藏，例如向前导航进入窗口”。本次希望避免设置页在旋转重建后再次请求总是隐藏键盘，保留 resize 和通常的进入隐藏策略。不新增强制弹出调用，也不恢复旧的 InputConnection。参考：[ALWAYS_HIDDEN](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#SOFT_INPUT_STATE_ALWAYS_HIDDEN)、[HIDDEN](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#SOFT_INPUT_STATE_HIDDEN)。这项设置不能改变其他应用的窗口策略。

## 已核验与未核验

- 原公开基线 118 个文件的 Git blob 哈希及根 tree 已逐项核对。原文件只改 manifest、SettingsActivity 的一条提示字符串及变更注释、README；其余 115 个原文件保持不变。本次另外增加本文与 Android 数据说明。
- 在该候选上重新执行既有[试打框主机回归](../android/host-tests/trial-reveal-20261102/README.md)：33 项调度断言和 Java 语法解析通过；[联想取消回归](../android/host-tests/association-cancel-20261101/README.md)的 31 个场景、277 项断言通过。使用已有 OpenJDK 21.0.12.1 和合成 doubles，不是 Android 类型检查、旋转或输入生命周期验证。
- 一个未随本仓库发布的基础打字变体，在 API 36、420 dpi、合成文本普通界面测试中复现了旧版旋转收键盘；相同属性修改后，竖屏/横屏/竖屏键盘保持可见，旋转后不重新点框可继续退格及输入标点，主动 Back/收起也没有被强制弹回。另一个 UID 的系统设置搜索框也做了有限输入/选词/退格检查。
- 该变体与公开源码的 manifest 对应属性、设置页 reveal/layout 状态代码及试打框焦点回调相同，但它另有基础预览 flavor、不同 application ID、语音禁用和其他源码差异，也没有本次更正的提示文字。**这不是当前公开全量源码构建产物的 Android 运行验证。** 新提示在设备上的换行和布局尚未验收。
- 当前公开候选没有 Android 构建或设备运行结论。完整 ASR、其他宿主/OEM、未确认拼音跨旋转、数值光标位置和真实 Windows TSF 仍不能由上述结果推定通过。原 instrumentation 和独立夹具的 `SETUP_FAILED` 不改记为通过。

## 有终点的后续验收

固定源码、依赖、包名、签名和 APK 哈希后，在得到所需安装许可的隔离环境运行一轮普通 UI 对照；不把不同变体混为同一个包，不卸载或清数据来绕过更新不兼容。

1. 在自有试打框输入 `nihao` 选 `你好`；旋转横屏后不点框退格为 `你`；回到竖屏不点框输入句号为 `你。`，同时检查完整试打区可见。
2. 主动 Back/收起后旋转，检查不强制弹出；Home 返回时记录焦点、方向和文字。
3. 在两个明确的普通外部编辑器之间输入、选词、退格、往返，记录各自内容，检查是否串写旧框。此检查不等于所有宿主均兼容。
4. 单列未确认拼音旋转、跨编辑器切换及中间光标操作结果；发现失败如实保存，不放宽断言，也不进入无期限重跑。

仅使用合成数据。通过、失败和未运行分别记录；隐私数据流应按[Android 数据说明](PRIVACY.md)独立验证。
