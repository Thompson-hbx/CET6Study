# 个人真题内容包

个人真题、参考答案和原卷 PDF 只作本人备份与学习（远端是私有仓库），不随程序分发。朱批 Windows 版目前只练作文和翻译；内容包里的阅读部分保留在原文件中，程序暂不使用。在朱批左侧「套卷」页点「导入内容包…」选择 JSON 文件即可导入；每套只取作文题、作文要求、翻译原文和参考译文 / 范文。

## 来源与授权

套次目录来源：https://www.wehuster.com/cet6 。该站公开可访问不代表获得再分发授权；只核对目录，未复制试卷原文。内容包必须写明 `sourceName`、`sourceUrl`、`licenseStatus`；个人合法材料用 `personal-use`，明确可再分发的材料用 `redistributable`，原创测试用 `original`。这些字段是导入者声明，并非程序确认授权。套次不是官方统一 ID，导入时核对原卷、题目与参考答案的对应关系。

## 从 PDF / Word 建立内容包

工具全程本地运行，不调用 AI、不上传资料。DOCX / TXT 使用 Python 标准库；文本 PDF 需要 `pypdf`。旧 `.doc` 请先另存为 `.docx`。图片扫描件或无文本层 PDF 会停止并提示人工 OCR、核对。

1. `python tools/prepare_exam_pack.py extract 我的试卷.docx extracted.txt`（PDF 同样适用）。工具拒绝覆盖已有输出文件。
2. 对照原卷编辑提取文本，添加下面的分区标记，每个标记单独一行。每份文件只放一套，删除页眉、广告和答案；不能确定的内容留空。
3. 从 `tools/exam-sample.json` 的 paper 对象复制元数据到独立 `metadata.json`，删除 `sample` 或设为 false，使用真实日期、套次与 ID（例如 `cet6-2025-06-14-1`）；不要复制测试样本的题目。补上资料来源和使用权声明。
4. `python tools/prepare_exam_pack.py convert extracted.txt my-pack.json --metadata metadata.json`。工具生成待校对 JSON，**始终设置 reviewed=false**，不推断答案、不自动拼接缺失内容。
5. 对照原卷逐一核实写作要求和中文原文；参考译文须填 `referenceSource`，参考范文须填 `referenceWritingSource`。不同来源冲突时保留原材料、维持待校对。
6. 确认年份、套次及来源全部一致后，人工设置 `reviewed=true`。

文本标记示例：

```text
[WRITING]
原始作文任务
[INSTRUCTIONS]
完整要求
[TRANSLATION]
原始中文段落
[REFERENCE_TRANSLATION]
可选参考译文
[REFERENCE_SOURCE]
可选译文来源
[REFERENCE_WRITING]
可选参考范文
[REFERENCE_WRITING_SOURCE]
可选范文来源（与译文来源独立）
```

阅读部分的 `[READING n]`、`[Q46]` 等标记仍被工具接受，用于保留完整原卷信息。

## 格式与修订

根对象：`format=CET6ExamPack`、`version=1`、`papers=[...]`。完整字段见 `tools/exam-sample.json`（明确标记的原创样本）。上限 4 MiB，最多 40 套。`stableId` 必须与日期、套次一致。不能把未举行考次写成真题。

同 ID 同 `revision` 不同内容视为冲突；修正须核对来源后增加 `revision`。结构缺失、无使用权声明或未人工校对的内容视为「待校对」。没有参考答案也可以练习，但不能冒充官方判题。

朱批导入时：整个文件格式不对（不是 JSON、`format` / `version` 不符、超过 4 MiB 或 40 套）就整包拒绝；单套有问题只跳过那一套并说明原因——`sample=true` 的样本、`stableId` 与日期或套次对不上、`revision` 不是正整数、缺 `licenseStatus` 或 `sourceName`、既无作文题也无翻译原文、同包内重复 ID。`reviewed=false` 的套卷照常导入，在书架上标「待校对」。本子里已有的套卷：修订号更高才更新，相同修订号内容不同或修订号更低都不覆盖。

## 本机资料

- 最新个人内容包：`.tools/private-exams/private-study-pack-complete-20261028.json`；逐套状态与来源哈希见 `.tools/private-exams/` 下的审计 JSON。
- `tools/extract_exam_pack.py` 只输出 `.tools/private-exams/agent-extracted/` 待校对候选，不会把扫描 OCR 或无来源答案改成正确答案，既有输出拒绝覆盖。
- `tools/download_wehuster_papers.py` 只审计现有缓存；自动网络获取因该站 robots 的 `/static` 禁止规则而关闭。
- `tools/content_status.py` 只汇总内容包元数据，不复制试卷原文。
