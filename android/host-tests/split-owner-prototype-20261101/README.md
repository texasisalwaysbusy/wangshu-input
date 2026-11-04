# 拆词跨编辑器绑定：独立原型与边界（2026-11-01）

**研究原型，未修复生产、未集成 Android。** 本目录三个 Java 文本使用 JDK 21 源码启动器与 `PrototypeRunner` 内存编译；不生成 class 文件、不访问用户文本、剪贴板、网络或设备。`BoundEditorPrototype.java` 不是实际应用源码，假宿主只是用来检验设计假设。

## 现状与真实边界

`PersonalPanel.startSplit` 使用 `Host.snapshot()/replace()` 构造拆词对象。实际 `WangshuIme.snapshot()` 和 `replace()` 分别调用 `getCurrentInputConnection()`；后者依次 `beginBatchEdit → setSelection → commitText → setSelection → endBatchEdit`。`onStartInput`、`onFinishInput`、`onWindowHidden` 有 `inputSession++` 和拆词清理，但这些生命周期方法中某些调用先于递增，不能假设任何同步回调都已失效；Android 回调调度、连接包装器、宿主编辑并发和字段重用都未实测。

本目录不修改 `WangshuIme`、`PersonalPanel` 或已冻结的短语实验。此前 `SplitSelection` 的同实例 reentry 修复不携带连接身份，不能解决不同编辑器同文同光标的问题；更不能把该功能标为已通过真实设备验收。

## 待评估的最小生产改动

1. **接口**：扩展 `PersonalPanel.Host` 与拆词 editor 适配器，让一次拆词会话显式捕获 `inputSession` 和实际 `InputConnection` 身份，不再在每次 replace 时无条件取得一个新连接。使拆词对象拿到绑定 adapter，而不是仅传文字及偏移。改动需针对现有未提交的短语实验做最小叠加，不覆盖其工作。
2. **生命周期**：在输入结束、窗口隐藏、切换/重启输入、私密/密码状态、面板关闭等所有入口足够早地使旧 token 失效；要核对 `super`、取消、清理和同步回调顺序。重新进入拆词必须重新捕获 token。单纯连接对象比较不够：同一 `InputConnection` 在新 session 重用也要拒绝。
3. **每次操作**：在获取完整文本/selection 和每次潜在编辑器 IPC 前后，核对捕获 session、连接、当前允许状态；使用捕获的连接而非重新取并操作新对象。保留实际现有的 `ExtractedText` 完整性、32K 大小、偏移与字素边界校验。开始前再比较预期完整文本与 selection。对 selection、commit、光标复位、结束 batch 的 false/异常/中途失效视为可能部分生效，封存该拆词会话，不自动重试或盲目回滚/删除；提示用户核对。任何局部状态更新必须在确认后的有效会话中进行。
4. **重入与并发**：保留每会话的非阻塞 in-flight 守卫，不在跨进程调用期间持有可能阻塞回调的 monitor；读/写不能互相重入。设计必须明确 `beginBatchEdit`/`endBatchEdit` 之间生命周期切换时的清理：跳过或调用都可能有影响，不能未经平台验证就承诺原子性。
5. **隐私与延迟**：只在明确的非私密会话按需读取，不写日志或持久保存上下文；检查后丢弃失效快照，但无法保证在检查与实际 IPC 之间私密字段未变化。同步 IPC 无法靠本原型保证有界时间；后台化也必须重新证明输入生命周期和私密会话隔离，不能简单把 `getExtractedText` 扔到后台。

这些是候选方案与测试方向，不是已证明可直接合入的实现。

## 合成验证及不可证明场景

`host-final-v2.txt`：`BoundEditorPrototype` 的 52 个假宿主检查通过，覆盖相同文本/光标但不同连接、同连接新 session、读取/选择过程中的同步 session 变化、私密状态切换、嵌套和并发调用、外部内容或光标变化、写入后返回失败/异常、失败不自动重试。原型在发送 selection 后发生状态变化时返回 `UNCERTAIN`；`UNCERTAIN` 不代表没有副作用。

同一日志**明确展示一个未阻断情形**：若平台在没有可观察的 session 变化、且 `InputConnection` 对象不变的情况下把该包装器静默改指向另一个具有相同内容和 selection 的编辑器，原型的身份检查和提交后内容核对均可能通过，但文字写进了第二个编辑器。另一个合成情形在最后一次检查与 `commit` 之间发生外部文本变化：事后对比虽返回 `UNCERTAIN`，但已发出的更改无法自动撤销。这是该协议不能证明的边界，不是声称 Android 实际一定如此；平台验证前禁止据此把拆词安全风险关闭。

候选设计如果用于 production，至少应在真实 Android 16 与多种宿主的 synthetic 测试中覆盖：不同连接、同连接不同 session、相同文本/光标切换、`getExtractedText`/`setSelection`/`commitText` 中同步重入、selection 和文本的外部变化、隐私状态变化、false 与先生效后异常、焦点/窗口丢失、重新进入面板、失败后不重试。应用仍需真实手机验收；本轮未更新或安装 APK。

文件 SHA-256：`BoundEditorPrototype.java` `eb487f80517811f41f2c9c0fa36bae3322b9fd60406e0cfd350f6b0263448ed8`；`BoundEditorChecks.java` `3be6d8fd8727e079d9fac6fb73401db42dd27438e54c5854a29111050f70138d`；`PrototypeRunner.java` `689631f7f0758d079eef3d65c66be481da7fe1d50a73ad91da151084375b01c2`；`host-final-v2.txt` `fa27b2dd6afa62dbaeb1e2d74bc93a17fcf3d5c11e3a28155a881dc552a4287b`。
