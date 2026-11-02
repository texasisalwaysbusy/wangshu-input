# 设置试打框：有限的主机调度回归

**开发候选；不是 Android 运行或整框可见性验收。**

本目录跟随设置页的布局修改：在外层容器处理安全区域，使 ScrollView 的实测 viewport 缩小；保留待处理 reveal，使后续同高度 layout 不会吞掉它；编辑器或窗口失焦时取消旧请求。未修改输入核心、词典、语音、许可显示、存储或权限行为。

## 运行

需要已有 Python 3 和 JDK 17+（含编译器），不需要 Android SDK，也不会联网、安装、启动设备或删除文件。从仓库根目录运行：

```sh
python3 android/host-tests/trial-reveal-20261102/run.py
```

可传入一个**尚不存在**的结果目录；默认在本测试目录下创建带时间戳的新目录，拒绝覆盖已有目录。脚本从当前 `SettingsActivity.java` 抽取实际 `RevealRequest` 与 layout listener，使用最小 Java View doubles 执行，再做 Java 语法解析。它不解析 Android 类型，也不模拟 Android 事件分发。

本轮在 OpenJDK 21.0.12.1 上实测：33 个主机断言通过；生产 Settings 的 Java 语法解析通过。解析器的 `-source 17` 仅指定语法级别，不是使用 JDK17 的实测或 Android 类型检查。测试脚本的文件读写、Java 源码读取及子进程输出解码均显式使用 UTF-8；Java 子进程有超时限制。断言包括同高度后续 layout、延迟布局、连续 shrink、growth 取消、IME 隐藏/重开、焦点丢失/恢复、detach、编辑器替换、零尺寸、同步重入及无需滚动返回 false。焦点/IME 事件通过模型显式提供；静态检查确认实际 Activity 的取消回调接线，但不证明设备必然按该次序调用。

`old-layout-listener.java.inc` 只保留一个中间版 shrink-only scheduler 的最小反例：shrink 后同高度 layout 使首个 post 过期且不再安排任务。运行时应复现其失败。**它不是公共基线 Settings 的原件，不能把这个反例称为公共基线的 Android 失败。** 公共基线原来在 ScrollView 自身设置 padding，并在 insets 回调中请求 reveal。

## 必须保留的限制

- 新 reveal 在真实 layout 时排队；IME false→true 仅建立 pending。若此后没有 layout，不能保证执行。
- viewport 高度增长会保守取消 pending，即使 IME 仍可见。失焦后重新获焦本身也不保证重新 reveal。
- 不进行 post 自循环；pending-layout 或零尺寸情况下等待下次真实事件。
- 未证明 Android 编译、系统生命周期、API26–29、API30+ edge-to-edge、横屏、大字体或实机结果。本公开候选未进行 Android 构建或运行。
- 已绑定 InputConnection、IME 可见、核心输入或候选测试通过，都不等于试打框完整可见。
- 本次公共更新不新增测试 Activity、manifest、runner 或诊断框架。

## 独立 Android 布局验收要求

保持420dpi，分别检查默认/较大字体、竖屏/横屏，首次显示、隐藏重开、快速切换和窗口失焦恢复；缺少配置或环境时标记未运行，不降低密度换取通过。

在布局稳定时，保留编辑器未经裁剪的屏幕矩形 `E`（`getLocationOnScreen` 加实际 width/height，并记录 measured 尺寸），ScrollView 扣除 padding、祖先裁剪和安全区域后的实际 viewport `V`，以及真实 IME 和系统栏/cutout 排除区域。要求 `E` 完整包含于 `V`，且与遮挡矩形不相交；不能用编辑器 `getGlobalVisibleRect` 返回的一小条代替完整 `E`。矩形采样前后窗口状态须一致，并保存对应的自有合成内容截图。

若完整编辑器大于可用 viewport，记录无法容纳，不把部分可见当作整框通过。活动行/光标可见只能作为另一个单独指标。不要在报告中上传真实输入、剪贴板、账号、录音、系统服务原始转储或不相关窗口截图。

## 许可与范围

本目录为项目原创主机测试，沿用仓库 Apache-2.0 许可；修改文件带变更说明。未复制平台 ScrollView 实现或新增第三方依赖。原数据、模型及第三方 notices 均不变，不能将代码许可套用于另有条款的数据或模型。
