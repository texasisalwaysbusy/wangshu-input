# Windows TSF 开发边界 / Windows TSF development boundaries

仓库包含原生 x86/x64 TSF COM 输入服务实现及开发工具，不使用网页输入框或全局键盘钩子冒充系统输入法。**源码存在不等于 Windows beta 二进制已经交付，也不等于真实 Windows 验收通过。**

The repository contains native x86/x64 TSF COM implementation and development tools, rather than a web text field or global keyboard-hook replacement. **Source availability does not establish a released Windows beta binary or real-Windows acceptance.**

## 未验收范围 / Not yet accepted

- 真实 Windows 注册、系统启用与默认输入法行为 / Real-Windows registration, system enablement and default-input behavior.
- 32 位宿主、现代应用、应用容器、多应用切换 / 32-bit hosts, modern apps, app containers and cross-app transitions.
- 候选窗口位置、多个 DPI、触屏与视觉可访问性 / Candidate placement, multiple DPIs, touch and visual accessibility.
- 真实麦克风、识别质量与语音生命周期 / Physical microphones, recognition quality and speech lifecycle.
- Windows 与 Android 全部符号、剪贴板、预测及设置功能对齐 / Complete symbol, clipboard, prediction and settings parity with Android.

Windows ARM64 不在当前支持范围。主机、mock TSF、交叉构建与 Wine 结果不能补足上述真实平台缺口。Android 通过的功能与隐私检查也不自动适用于 Windows。

Windows ARM64 is outside the current supported scope. Host tests, mock TSF, cross-builds and Wine do not fill these real-platform gaps. Android feature/privacy results cannot be transferred to Windows.

## 安装与权限 / Installation and permissions

仓库安装脚本属于开发工具；存在脚本不表示已经执行或建议直接日用。不要绕过系统安全警告、自动提权或修改 ACL 来获得“通过”。任何后续二进制发行应单独说明签名、精确构建与未验收范围。

Repository installation scripts are development tools; their existence is not proof of execution or a daily-use recommendation. Do not bypass security warnings, automatically elevate or modify ACLs to manufacture a passing result. A later binary release must identify its signature, exact build and remaining gaps.
