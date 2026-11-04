# 隐私边界 / Privacy boundaries

本文件是供独立 Windows 开发源码包保留的自包含说明。仓库首页提供更详细的 Android 说明。

This self-contained note is retained for standalone Windows development-source packages. The repository home page provides the detailed Android explanation.

## Android

当前清单不申请 INTERNET 权限，不要求账户。拼音、规则转换与导入模型识别在本地处理；不可用时不转用云识别。语音在当前会话内存中处理，不建立录音历史。自动候选学习和剪贴板历史默认关闭，但主动添加的词条、短句、自定义符号，以及启用采集后产生的数据可以保存在本地。

个人词库/常用语和设置分别备份。备份不含录音、剪贴板历史、模型、学习记录或采集开启状态；部分新开关不在设置备份白名单内。备份可能包含手动文字，Base64 不是加密。系统文件选择器选中的云盘提供方可能收到导出文件。

The current manifest does not request INTERNET permission or an account. Pinyin, rules and imported-model recognition run locally, without cloud fallback when unavailable. Audio is processed in session memory, not saved as a recording history. Candidate learning and clipboard history are off by default, but personal words, phrases, custom symbols and explicitly enabled capture data may be stored locally.

Words/phrases and settings are backed up separately. Exports exclude recordings, clipboard history, models, learning records and capture consent flags; some newer toggles are outside the settings allowlist. Backups may contain manually entered text. Base64 is not encryption. A cloud provider selected in the system file picker may receive an export.

## Windows 与共同限制 / Windows and shared limits

Windows 具有独立的本地数据与语音实现。Android 检查不证明 Windows 隐私、持久化或麦克风行为。真实 Windows 验收未完成，不把开发源码当成日用安全保证。

输入法要访问当前编辑框；宿主与系统也会处理输入。调试 beta 不应用于密码、敏感信息或重要文本。离线设计不等于绝对安全；反馈应使用合成内容，避免私人录音、聊天与数据备份。

Windows has separate local-data and speech implementations. Android checks do not establish Windows privacy, persistence or microphone behavior. Real-Windows acceptance remains incomplete; development source is not a daily-use security guarantee.

An input method accesses the current editor, and the host and OS also process input. Do not use a debug beta for passwords, sensitive information or important text. Offline design is not absolute security. Use synthetic feedback, excluding private recordings, chats and backups.
