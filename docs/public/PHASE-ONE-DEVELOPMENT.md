# 一期开发分支

本分支汇总 Android、Windows 和共享核心的一期开发改动。公开 Beta 继续使用现有发布页上的 APK。

| 范围 | 改动 |
|---|---|
| Android 编辑 | 待确认拼音仅显示在键盘内。会话、选区与写入结果的校验覆盖候选提交、语音和删除。保留 Chromium 系列编辑器的完整文本快照兼容。 |
| Android 语音 | 流式文字显示在独立预览区，松手按最终落点处理。撤销前核对本次插入后的正文和光标。数字格式使用确定性规则。 |
| Android 日常输入 | 横屏键盘留出编辑区；设置与个人数据恢复增加读回和失败回归；拼音、补全和下一词继续使用冻结样本评估。 |
| Windows | 增加剪贴板、符号与表情入口、选区和批量删除、完整字符簇退格，并推进共享候选、个人数据及语音保护。 |
| 共享核心 | 中英文补全与上下文词候选使用有界离线数据，支持数字后续和光标上下文。通用整句预测仍待验收。 |

## 模糊候选入口

启用某组模糊音后，Android 输入拼音并展开候选，点「模糊候选」。Windows 输入拼音后按 Shift+PgDn，按 PgUp 返回首组。该入口最多显示九个结果，保留个人记词优先级，排除首组中已展示的词。普通翻页沿用原顺序。选择模糊候选时，结果不会学到原拼音下。

Windows x64 生产候选路径在合成回放中保持了 2,942 条普通候选记录，独立模糊页覆盖 24 条诊断输入中的 21 条。`suohua` 启用 s/sh 时，「说话」为独立页第一项。Android 本机测试验证了查询上限与候选页动作，手机界面和 Windows 实际应用仍待验。

## 离线语音开发评估

在同一批 30 条公开音频上比较模型和解码设置。中文、英文及混合各 10 条。CER 和 WER 先做 NFKC 归一化和小写转换，并将标点转为空格。CER 忽略空白，WER 按词计数。

| 配置 | 中文 CER | 英文 WER | 混合 CER | 混合英文词保留 |
|---|---:|---:|---:|---:|
| X-ASR 贪心基线 | 2.4% | 26.2% | 10.3% | 19/31 |
| Paraformer Small | 1.9% | 29.1% | 15.9% | 19/31 |
| Whisper Tiny 自动语言 | 37.2% | 40.8% | 65.6% | 5/31 |
| FireRedASR2 CTC INT8 | 2.4% | 32.0% | 22.2% | 13/31 |
| Qwen3-ASR 0.6B INT8 | 2.4% | 21.4% | 18.2% | 14/31 |

X-ASR 空白符权重扫描未改善逐句门槛。Paraformer Small 和 Whisper Tiny 均在全零音频上产生文字。参考中的 `base line` 与转写 `baseline` 按原词级口径计分。候选筛选结果为 FAIL，独立集为 NOT_RUN。现有生产模型和解码设置继续使用。 [ASCEND 语料](https://github.com/hlt-mt/ASCEND)

FireRedASR2 CTC 在静音上返回词表标记 `<sil>`。Qwen3-ASR 的静音返回为空，英文最差单条 WER 仍为 71.4%，混合词保留低于 X-ASR。两者开发筛选均为 FAIL，独立集为 NOT_RUN。Qwen3-ASR 约需 987 MB 模型文件，在 Windows 两线程整段解码的 P95 为 1.94 秒，峰值工作集约 1.60 GiB。 [FireRedASR2 CTC 转换](https://k2-fsa.github.io/sherpa/onnx/FireRedAsr/pretrained.html#sherpa-onnx-fire-red-asr2-ctc-zh-en-int8-2026-02-25) · [Qwen3-ASR 转换](https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/tree/68818b2313fe77bd06f6a7c5068ff3ef59d02b8a)

## 自动标点开发评估

CT-Transformer 的直接输出在 7 条结构化文本中拆开了 5 条里的小数、版本号、网址等内容。原文对齐原型只插入可对齐的标点，在 10 条保护检查和 7 条结构化探针中保住原词、大小写和空白。新的 24 条合成文字中，中文、英文和混合标点的类别与位置 micro-F1 分别为 0.824、0.625、0.848。英文未达 0.75 阈值。英文轻量模型在相同英文样本上为 0.333。自动标点继续作为开发候选，实际 ASR 输出和设备验收待做。 [CT-Transformer 与轻量英文模型](https://k2-fsa.github.io/sherpa/onnx/punctuation/pretrained_models.html)

## 验收范围

主机用例使用合成编辑器、存储及捕获接口。开发分支的 Android full 与 typingPreview 原生编译、测试包 Java 编译和 lint 通过。两变体的 Gradle 单测任务为 NO-SOURCE。十二组 Android 主机回归和合成语音流回归通过，真实目录同步和两项主机符号链接分支仍未运行。新提交的 Android 手机验证及 Windows 真实宿主验收均为 NOT_RUN。语音质量、手机横屏、跨重启恢复及双平台实际宿主仍在一期验收范围。
