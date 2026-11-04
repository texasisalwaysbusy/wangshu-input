package org.wangshu.ime;

/** Capability disclosure shared by every preview voice entry point. */
final class PreviewFeatures {
    static final String LABEL="望舒 Basic Typing Preview";
    static final String VOICE_UNAVAILABLE="基础输入预览不包含语音识别或语音包导入；不会录音。请使用拼音、候选和数学符号输入。";
    static final String HELP="Basic Typing Preview · 基础输入预览\n\n输入 nihao，用空格或候选按钮确认“你好”；shi 可翻页并用数字选择。Esc 取消拼音，退格删除；数学面板和 \\alpha 等命令可输入数学符号。\n\n本预览不包含本地或系统语音识别、按住空格说话、麦克风授权或语音包导入，不会录音。完整版本的语音源码保留，预览没有这些运行库。\n\n自动候选学习和剪贴板历史默认关闭。开启后可能在本机保存所选拼音、词和次数，或纯文本剪贴板历史。关闭不会删除已有记录；普通文字也可能含未被识别的秘密。保留、置顶和清理规则请查看“候选个性化”和“剪贴板历史”。\n\n个人词库与常用语由你手动添加或导入。备份会写入你选择的系统文件提供方，它可能是云端；从剪贴板转存为常用语的内容也会随词库备份导出。\n\n本预览有独立应用包和设置/词库，不会自动读取完整版的应用私有目录；你主动导入文件仍可能把其他版本的数据带入本预览。\n\n这是开发预览。未确认拼音旋转后可能失去候选，请先确认候选再旋转；数字面板可能需要重新打开。编译成功不代表所有功能、宿主或机型已通过实际验证。";
    private PreviewFeatures() { }
}
