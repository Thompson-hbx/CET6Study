# 个人资料与学习数据备份

2026-10-18，用户明确授权将第三方真题、个人内容包和学习数据推送至现有远端。推送前已确认 `Thompson-hbx/CET6Study` 为私有仓库。上传仅作个人备份，不表示获得公开再分发许可。

- `raw-papers/`：原卷、参考答案、音频和原下载清单，包含范围外的 2022 年资料，未冒称全部已校对。
- `.tools/private-exams/`：个人内容包、来源记录、逐套报告、OCR 和提取中间结果。
- `tools/exam-packs/`：其他 agent 的原始候选版本，供追溯；候选不等于正式题库。
- `.tools/` 下明确选择的 `cet6_study.db` 快照及其 WAL/SHM 配套文件：旧手机 App 的历史学习记录（含 2026-11-07 迁移到 Windows 版前的最终快照，位于 `.tools/final-before-windows-20261107/`，该目录按 `.gitignore` 不进 Git）。不包含登录凭证或密钥。朱批 Windows 版的「搬旧本子」只读这些快照的副本，原文件不变。
- `.tools/private-backup-inventory.json`：上述资料与数据的逐文件 SHA-256、大小及校验范围。

`.gitattributes` 把这些私人资料标为 `-text`，不要改动它们的字节。`.tools/` 的其他内容（运行时、缓存、临时文件）被忽略；不要 `git add -f` 未经选择的 `.tools/` 内容。
