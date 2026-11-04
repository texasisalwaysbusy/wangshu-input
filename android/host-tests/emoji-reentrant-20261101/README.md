# Emoji 同一令牌重入：主机合成验证（2026-11-01）

Android `EmojiSelection.select` 在保留旧行为的同时新增每个令牌的短期 `inFlight` 守卫：同一令牌的同步重入或并发发送返回 `BUSY`，不再次调用编辑器；发送结束或发生异常后通过 `finally` 清除守卫。顺序发生的两次独立用户点击仍各发送一次。当前 `WangshuIme` 调用者对 `BUSY` 不显示额外提示；本轮没有更改该文件或接入 UI。

- `red-output.txt`：旧版实际 `EmojiSelection.java` 与合成宿主编译后执行，因宿主在首次发送完成前同步再次调用而出现两次发送，断言失败。此为可控 host 模型，不证明真实 Android 编辑器发生过该回调时序。
- `green-output.txt`：当前实际源码与 `EmojiReentrantChecks.java` 编译执行，15 项通过，包含同步重入、同令牌并发、顺序双击、无效 token、失败与异常后的释放、返回 false 的未确认结果。用的是假宿主，没有设备数据。
- `prior-regression.txt`：原 `staging/emoji-panel-20261028/tests/org/wangshu/ime/EmojiSelectionTest.java` 与当前实际源码编译运行，16 项通过，包括 ZWJ/肤色序列、顺序两次点击、失效窗口、连接变化和不确定结果；没有修改旧测试。
- 源码 SHA-256 `c44cb66378b41fbc4031df5035db2c50aaa3a7bd32fbe628503628019e756a15`；新测试 SHA-256 `4819e7d32caa67574f9fb70b0218a9be9df0815aa18f0be782ad46c289ed68e1`。

限制：只保护同一个令牌；不同令牌之间或宿主在异步未确认提交后的行为不能由此推断。调用者应继续校验真实编辑器、会话及选择范围；真实 Android 的 InputConnection、触摸、辅助功能、键盘界面、手机设备均未运行。出于现有 WIP 隐私竞态限制，没有构建、安装或运行包含其他未提交实验的 APK。`red-classes/` 和 `green-classes/` 是可重建测试输出，不是源码或交付物，本轮没有删除。
