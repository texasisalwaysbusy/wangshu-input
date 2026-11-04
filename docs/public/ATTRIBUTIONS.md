# 许可、来源与原创图片 / Licenses, sources and original artwork

[中文首页](../../README.md) · [English home](../../README.en.md)

## 项目代码 / Project code

仓库现有主许可为 [Apache License 2.0](https://github.com/texasisalwaysbusy/wangshu-input/blob/main/LICENSE)。新文档与项目原创矢量构图沿用该许可。此声明不改变第三方代码、字体、数据或模型的独立许可。

The repository's existing main license is [Apache License 2.0](https://github.com/texasisalwaysbusy/wangshu-input/blob/main/LICENSE). New documentation and original vector compositions follow that license. This does not replace separate licenses for third-party code, fonts, data or models.

## 词典与例句 / Dictionary and examples

- **CC-CEDICT**：派生拼音词典为 CC BY-SA 4.0，保留来源与修改说明。[词典归属](../../data/CC-CEDICT-NOTICE.txt)、[上游头部](../../data/notices/CC-CEDICT-UPSTREAM-HEADER.txt)。Adapted Pinyin data remains CC BY-SA 4.0; source and transformation notices are retained.
- **jieba**：用于词频来源，MIT。[许可](../../data/notices/JIEBA-LICENSE.txt)。Frequency source under MIT.
- **115 条本地例句 / 115 local examples**：项目原创合成文字，CC0-1.0。它们不是用户输入历史。[来源说明](../../data/LOCAL-PREDICTIONS-SOURCE.json)。Original synthetic project examples, not user input history.

## 语音与原生依赖 / Speech and native dependencies

- **sherpa-onnx**：[Apache-2.0 许可](../../data/SHERPA-ONNX-LICENSE.txt)。
- **ONNX Runtime**：[许可](../../data/ONNXRUNTIME-LICENSE.txt)、[第三方声明](../../data/ONNXRUNTIME-ThirdPartyNotices.txt)。
- **Kotlin**：[许可](../../data/KOTLIN-LICENSE.txt)、[声明](../../data/KOTLIN-NOTICE.txt)。
- 编译语音依赖的其他条款 / Additional compiled speech dependencies：[汇总声明](../../data/SPEECH-THIRD-PARTY-NOTICES.txt)。
- **nlohmann/json**：[MIT 许可](../../third_party/nlohmann/LICENSE.MIT)。
- 原生工具链归属 / Native toolchain notices：见 `data/notices/`。具体二进制所带组件以该发布的实际清单为准 / Consult the release's actual component inventory.

离线语音模型为独立可选产物，身份与许可记录在 [模型来源清单](../voice-model-source.json)，依赖来源固定在 [语音依赖清单](../speech-dependencies.json)。这些记录不是“模型已经附带在每个发布中”的承诺。

The speech model is a separate optional artifact, identified in the [model-source manifest](../voice-model-source.json); dependency sources are pinned in the [speech manifest](../speech-dependencies.json). These manifests do not mean every release includes the model.

## 原创品牌与功能示意 / Original brand and explanatory artwork

`assets/brand/` 中的月牙键盘标志、双语横幅、功能示意和社交预览为本项目专门绘制，以月白、深靛与月金构成。图形由原生 SVG 几何路径组成，无外链图片、远程字体、脚本或真实截图。示例文字为合成内容，不含私人输入记录。图片内的 `OpenAI` 只是合成英文文本示例，不表示合作或背书。

The crescent/keyboard mark, bilingual banners, feature diagrams and social preview were created for this project in moon white, deep indigo and moon gold. They use native SVG paths, with no external images, remote fonts, scripts or actual screenshots. Examples are synthetic and contain no private input records. `OpenAI` appears only as sample English text, not as an endorsement or affiliation.

为避免中英文缺字，图片文字使用本机 Noto Sans CJK SC 字形转为路径；未附带字体二进制，也不依赖读者安装字体。字体属于 SIL Open Font License 1.1，见 [字体归属与完整许可](../../assets/brand/FONT-NOTICE.txt)。项目原创构图不代表字体本身由项目创作。

To avoid missing Chinese or English glyphs, text was outlined from locally installed Noto Sans CJK SC. No font binary is bundled or required on the reader's device. The font is under SIL Open Font License 1.1; see [font attribution and full license](../../assets/brand/FONT-NOTICE.txt). Original composition does not imply authorship of the typeface.

图片为功能示意，**不是真机截图，不证明像素布局或功能已经验收**。品牌设计也不构成商标权或第三方认可的声明。

The images are explanatory illustrations, **not device screenshots or proof of UI layout/feature acceptance**. The visual identity makes no claim of trademark rights or third-party endorsement.
