# 个人真题内容导入

## 个人离线安装包

按用户 2026-10-20 要求，个人 APK 可直接包含题库，首次启动自动载入，无需手动导入。使用已校对的完整 JSON：

```powershell
.\gradlew.bat --offline assembleDebug '-PpersonalExamPack=.tools/private-exams/private-study-pack-reading18-20261020.json' '-PpersonalExamPdfs=.tools/private-exams/downloads'
```

JSON 只打包指定文件；可选 personalExamPdfs 仅复制目录内 cet6_*.pdf。套卷详情可打开原卷与参考资料，内置离线查看器支持翻页、放大与滚动，无需外部阅读器。2023 年 3 月第 2、3 套指向资料原有的合并卷，不伪造独立题序。未传参数的普通构建仍不带私有资料；私有包和 APK 不进入 Git。应用启动时合并并保存：新增套卷自动加入，较高修订更新内容，本机同版本或较新版本优先，自定义套卷保留；Room 中既有作答快照不受影响。未来题库更新随个人 APK 一起交付。打包不会把待校对材料变成已校对，目前部分资料仍待补齐。

普通构建只内置元数据目录，个人构建按上文携带本地资料。2026-10-18 核对 8 个已举行考次、24 个资料整理套次；2023-03-12 属 2022 下半年加考，按实际日期归档。套次不是官方统一 ID，导入时核对原卷、题目与参考答案对应关系。

## 来源与授权

考试日期来源在 `ExamCatalog.sessions` 中逐条记录。套次目录来源：https://www.wehuster.com/cet6 。该站公开可访问不代表获得再分发授权；只核对目录，未复制试卷原文。内容包必须写明 `sourceName`、`sourceUrl`、`licenseStatus`；个人合法材料用 `personal-use`，明确可再分发的材料用 `redistributable`，原创测试用 `original`。这些字段是导入者声明，并非应用确认授权。

## 从 PDF / Word 建立内容包

工具全程本地运行，不调用 AI、不上传资料、不安装依赖。DOCX / TXT 使用 Python 标准库；文本 PDF 使用现有 `pypdf`（本次使用 Codex 已有运行时验证）。旧 `.doc` 请先另存为 `.docx`。图片扫描件或无文本层 PDF 会停止并提示人工 OCR、核对；本版没有 OCR。

1. `python tools/prepare_exam_pack.py extract 我的试卷.docx extracted.txt`（PDF 同样适用，需已有 pypdf）。工具拒绝覆盖已有输出文件。
2. 对照原卷编辑提取文本，添加下面的精确分区标记，每个标记单独一行。每份文件只放一套，删除其他题型、页眉、广告和答案。不能确定的内容留空。
3. 从 `tools/exam-sample.json` 的 paper 对象复制元数据到独立 `metadata.json`，删除 `sample` 或设为 false，使用真实日期、套次与 ID，例如 `cet6-2025-06-14-1`；不要复制测试样本的题目。补上合法资料来源和使用权声明。
4. `python tools/prepare_exam_pack.py convert extracted.txt my-pack.json --metadata metadata.json`。工具生成待校对 JSON，**始终设置 reviewed=false**，不推断答案、不自动拼接缺失内容。
5. 对照原卷逐一核实文章、46—55 题、选项、写作要求、中文原文；在 JSON 中填写有来源的参考答案 `referenceAnswer`、`answerSource` 和可精确匹配原文的 `evidence`。如有不同来源冲突，保留原材料，维持待校对，不任意挑答案。参考译文须填 `referenceSource`。
6. 确认年份、套次及来源全部一致后，人工设置 `reviewed=true`。在题库 → 导入资料 → 选择 JSON，核对预览后保存。

文本标记示例（省略部分只表示文档说明，不能直接导入成完整试卷）：

```text
[READING 1]
第一篇英文正文
[Q46]
题干
A. 选项一
B. 选项二
C. 选项三
D. 选项四
[Q47]
继续至 Q50
[READING 2]
第二篇正文
[Q51]
继续至 Q55
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

参考范文使用可选字段 `referenceWriting`、`referenceWritingSource`，原格式 version=1 不变，旧包缺省为空。有范文但无来源时作文保持待校对。参考内容在提交原稿后的“对照”页显示，并随新作答保存快照；导入修订不会回填或改变既有作答。

## 格式与修订

根对象：`format=CET6ExamPack`、`version=1`、`papers=[...]`。完整字段及阅读题结构见明确标记的原创功能样本。上限 4 MiB，最多 40 套。`stableId` 必须与日期、套次一致；题目 ID 由套卷 ID / 题型 / 篇章 / 原题号确定。不能把未举行考次写成真题。

重复导入完全相同版本不会增加套卷；同 ID 同 revision 不同内容会拒绝。修正须核对来源后增加 revision，旧版本不能覆盖新版。结构缺失、证据不匹配、无使用权声明或未人工校对的内容显示“待校对”。按所选题型检查，阅读结构完整时不因缺少翻译而阻塞阅读；未就绪的题型不能开练。没有参考答案允许练习，提交后明确显示“暂无”，不冒充官方判题。

个人内容存入应用内部 `exam-content-v1.json`，原子写入。作答开始时将该篇正文、题目、来源和参考答案快照存入现有 Room v7 `CoachPractice`，后续改题不影响旧记录。提交前界面不显示参考答案；这不是防作弊加密系统。

## 备份边界

“我的”学习备份 v4 继续包含全部训练快照、答案、反馈和错因，兼容 v1—v3。个人内容包独立在题库管理中导出 / 导入；尚未开始训练的题库内容不在旧学习备份里。两个文件都需要保留。空题库无需导出。不读取或备份凭证。

## 阅读方式（用户于 2026-10-18 修订）

先在真实试卷上完成，进入对应套卷选择“录入两篇答案 · 每篇 1—5”。每篇各输入五个选项（如 BCADB），两篇均按 1、2、3、4、5 排列，也可逐题修改；? 可占位未答题。无需填写46—55原题号，原题号仅在内部匹配原卷与参考答案。修改一篇不改变另一篇或旧记录。离线核对只使用有来源的参考答案，未提供参考答案的题目显示待核对，不给 AI 猜测加上正确答案标签。不会默认揭示正确选项。

逐题提示依次为题干线索、原文段落、证据句、选项与参考。最后一步需主动点击揭示。原文可只读对照，主要学习仍发生在手中的试卷。AI 可补充当前题当前层提示，完整响应存为学习参考；前三层请求不携带答案键，不默认请求整套完整解析。没有原文 / 证据来源时，不能承诺 AI 可以准确定位，需先补齐资料。

## 2026-10-18 本机资料核对

其他 agent 的 `raw-papers/`、`tools/exam-packs/` 与 `app/src/main/assets/exam-packs/` 保留原文件，但已 Git 忽略；assets/exam-packs 另外通过 Gradle 排除，构建后已检查 APK ZIP。不在 assets 中自动加载未审核内容。个人包通过系统文件选择器导入。

修正后的 `tools/extract_exam_pack.py` 仅输出 `.tools/private-exams/agent-extracted/` 待校对候选：兼容双栏选项，提取翻译，标记 reviewed=false，参考键附第三方 PDF URL / SHA-256。不会自动把扫描 OCR、O) 标签或无来源答案修成正确答案；既有输出拒绝覆盖。`tools/download_wehuster_papers.py` 仅审计现有缓存，自动网络获取因已审阅 robots 的 /static 禁止规则而关闭。

私有的 `RESOURCE_REPORT.md`、`OTHER_AGENT_REVIEW.md` 和逐文件 JSON 审计存于 `.tools/private-exams/`；范文和译文仍需逐字校对，不冒称官方唯一答案。
