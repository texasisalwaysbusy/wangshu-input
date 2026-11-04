# 拆词写后复核：隔离原型，不修改产品

此原型不是用户可用功能。它在现有 `SplitSelection`（SHA-256 `fdd01c33c43eba8313028aa5f317841b686f21013019a8c4ae9c6c23d09b1015`）基础上，对成功返回的插入或撤回再读取一次完整文本与光标；只有与本次预期完全一致才更新本地选择/偏移。读取失败、看到旧状态或不一致时关闭当前拆词会话，提示上层结果未确认，不自动重试或回滚。仍保留已发生的用户可见编辑；不能声称失败代表宿主没有修改。

`PostWriteChecks.java` 使用合成编辑器：`red-final.txt` 中旧代码对“ack=true 但没有实际修改”错误返回成功且标记选中；`green-final.txt` 同一测试源码在原型上通过 14 项。包括实际插入顺序、按 identity 撤回、写后读取不到/延迟更新时冻结并保留可能已发生的更改、不重试，以及 32767+1 由 32768 字符快照确认并可撤回。既有 13 项独立拆词和 24 项重入检查在该原型中分别通过；这是内存 JVM/fake-host 测试，并非 Android 运行。`red.txt` 是初始草案日志，最终红绿以 `red-final.txt` / `green-final.txt` 为准。原型源码 `SplitSelection.prototype.java.txt` 和 `proposed.patch` 仅供审查，未改 WIP 或生产代码。

**未解决的核心边界：** host 可在对象/session/文本/光标全部不变且无通知时将同一 InputConnection 包装器改指向另一具有相同文本的编辑器。`green-final.txt` 的 `KNOWN LIMITATION` 明确显示即使增加写后复核，错误编辑器已被更改且检查仍通过；这是主动展示的失败能力，不计作安全通过。Android 的 `InputConnectionWrapper.setTarget` 是可变目标 API（https://developer.android.com/reference/android/view/inputmethod/InputConnectionWrapper#setTarget(android.view.inputmethod.InputConnection)），但本合成场景不声称真实设备必然如此。`EditorInfo.fieldId` 官方说明可缺省为 0（https://developer.android.com/reference/android/view/inputmethod/EditorInfo#fieldId），不能仅凭它建立不可变身份。

**误拒绝和卡顿代价：** 编辑器异步应用但检查时仍返回旧内容时，原型会拒绝并冻结；真实编辑也许稍后生效，禁止自动撤销/再发。写后复核的额外一次 `getExtractedText` 在当前点击路径同步执行：fake 故意延迟 120 ms，调用者随之阻塞约 120 ms（不是设备性能测量）；Android IPC 可能更慢，后台设定的 timeout 无法终止已经阻塞的主线程 IPC。没有 Android 16、真实手机、宿主并发、真实长文档或电量测试；现有 32768 是本项目当前 snapshotFrom 的边界，不是所有宿主都提供完整快照。

无法仅凭本方案同时保证立即插入、可撤回以及静默转向时绝不写错编辑器。工程选项：保留即时输入并诚实标注剩余风险，只在可观察失效时停止；或者暂停向宿主发送拆词编辑、改为本地暂存后由用户明确确认（会改变既定即时交互，需产品决定）。回退仍不能凭空提供不可变宿主身份；任何实装均需先解决主线程阻塞、Android API 会话语义及 false/异常/实际生效的专项验证。本测试没有安装、删除、发送或构建 APK；源码未合入。
