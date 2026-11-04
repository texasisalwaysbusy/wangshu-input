# 第三方材料

自有代码许可不覆盖第三方源码、词库、模型和依赖。请保留对应版权、许可证和修改说明；构建与分发前需核对实际使用的所有传递依赖。

- `data/pinyin.tsv` 来源于 [CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cc-cedict)，按 [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) 许可；提取简体词头、转换无声调拼音并加入排序权重。原作者、来源、修改和许可链接见 `data/CC-CEDICT-NOTICE.txt` 和 `data/notices/CC-CEDICT-UPSTREAM-HEADER.txt`；固定源归档信息见 `docs/dictionary-source.json`。
- 词频数据来自 [jieba 0.42.1](https://github.com/fxsjy/jieba/tree/v0.42.1)，MIT，许可证见 `data/notices/JIEBA-LICENSE.txt`；来源与哈希见 `docs/frequency-source.json`。
- `third_party/nlohmann/json.hpp` 来自 [nlohmann/json 3.12.0](https://github.com/nlohmann/json/tree/v3.12.0)，MIT，附带原许可证及来源记录。
- `third_party/sherpa/c-api.h` 来源于 [sherpa-onnx 1.13.6](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.6)，Apache-2.0，附带上游许可。ONNX Runtime 使用 MIT；其及其他传递依赖的 notices 在 `data/` 中，具体来源见 `docs/speech-dependencies.json`。
- 双语 Zipformer 语音模型、上游通用 AAR 与项目定制 ASR AAR不在此仓库。[模型发布方](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20)声明 Apache-2.0；文件与哈希记录在 `docs/voice-model-source.json`。实际模型、其上游数据和 AAR 的完整分发范围仍需逐项核实。
- Kotlin、NDK、MinGW、GCC 运行时和其他第三方依赖须按实际构建版本分别保留其许可与 notices；某项第三方许可并不自动成为整个项目代码的许可。

- 本地预测的 115 条例句为项目原创合成材料，CC0-1.0；来源与边界见 `data/LOCAL-PREDICTIONS-SOURCE.json`，不是用户输入或训练语料。
- 本次公开首页与原创图片的归属见 `docs/public/ATTRIBUTIONS.md`。

