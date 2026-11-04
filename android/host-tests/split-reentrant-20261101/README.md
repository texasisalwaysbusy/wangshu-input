# 拆词令牌重入与编辑器身份风险（2026-11-01）

这是对实际 `SplitSelection.java` 的纯主机故障注入，不是 Android、真实用户编辑器或设备测试。测试仅使用合成文本 `XSAFE` 和 `A`。因磁盘不足，所有运行使用 JDK 21 源码启动器及 `MemoryRunner.java` 的内存 JavaCompiler/file manager；没有产生 `.class` 文件或安装依赖。

## 已修复：同一 SplitSelection 实例的重入

`red-insert-detail.txt`：原始生产源码中，假宿主在首次编辑器 replace 生效前同步重入 `toggle`，两次调用插入，合成结果 `XSAFE → XAASAFE`。`red-remove-detail.txt`：在 `XASAFE` 的已选 `A` 上重复进入删除，导致原有后缀 `S` 也被删除，结果 `XAFE`。此时序可由控制的假宿主复现，尚不证明真实 Android 应用出现过相同时序。

生产组件新增每实例 `AtomicBoolean`，在整个 `toggle` 的读取、校验、编辑和状态更新期间阻止同一实例嵌套/并发调用；`finally` 释放，不跨编辑器调用持有 Java monitor。`green-final.txt` 记录 24 项通过：插入与撤回重入、读取期间重入、并发、顺序点击、按先后追加、只撤自己的片段、宿主返回 false 或修改后抛出异常时不自动重试。`prior-regression.txt` 使用此前独立测试 `projects/input-method/tests/jvm/SplitSelectionIndependentTest.java`，原有 13 项通过。

当前 `PersonalPanel` 会对阻塞的嵌套操作显示“操作未确认”并重绘；该具体 UI 回调未在 Android 运行。此变更只约束同一 `SplitSelection` 对象，不对其他对象、宿主自身并发编辑、跨进程内存和已提交/不确定操作作原子性保证。

## 尚未修复：相同文本的跨编辑器切换

`known-identity-risk-final.txt` 使用独立合成假宿主展示：旧/新编辑器有相同完整文本和光标时，`read()` 返回后宿主切到另一个编辑器，当前 `Snapshot` 只携带文本/坐标，没有编辑器身份，随后 `replace()` 可写入新编辑器。实际 `WangshuIme.snapshot()` 和 `replace()` 都各自调用 `getCurrentInputConnection()`；这证实源码存在需要解决的身份绑定缺口，但合成测试不证明真机重现。完整修复需要重新设计携带输入会话/连接身份的读取和替换接口，并在实际宿主变化、重入、复用连接、失败和 IPC 边界验证；本轮按父任务限定没有修改 `WangshuIme`、`PersonalPanel` 或已冻结的其他输入法实验。因此拆词功能不能宣称已经完整安全验收。

生产源码 SHA-256 `ca84efa3066185e25876d847bb5a05c714ccfcd71b535d5196a861a3fe6c968d`；最终测试源码 SHA-256 `987ee4d81d18e911e268b4113f1ebf7f2bb418b7f98ea2e584ff2bbb98147b05`；runner SHA-256 `3cfc901d826c6115bb33872172fb0e24776acf9d4411ba006482ef1ea79c8bcd`。没有运行 Android InputConnection、键盘 UI、真实手机或安装 APK。
