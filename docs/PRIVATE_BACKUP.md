# 个人资料与学习数据备份

2026-10-18，用户明确授权将第三方真题、个人内容包和学习数据推送至现有远端。推送前已通过 GitHub 查询确认 `Thompson-hbx/CET6Study` 为私有仓库。本次上传仅作个人备份，不表示获得公开再分发许可。

- `raw-papers/`：原卷、参考答案、音频和原下载清单，包含范围外的2022年资料，未冒称全部已校对。
- `.tools/private-exams/`：个人内容包、来源记录、逐套报告、OCR和提取中间结果。当前14套可用于有来源核对，10套仍待校对；原状态保留。
- `tools/exam-packs/` 与 `app/src/main/assets/exam-packs/`：保留其他 agent 的原始候选版本供追溯。候选不等于正式题库；Gradle继续排除assets/exam-packs，不随APK分发。
- `.tools/` 下明确选择的 `cet6_study.db` 快照及其WAL/SHM配套文件：历史学习记录与本轮模拟器快照；不包含shared_prefs、登录凭证或密钥。原始SQLite快照用于维护，不能当作App的JSON学习备份直接导入。
- `.tools/private-backup-inventory.json`：此次资料与数据的逐文件SHA-256、大小及校验范围。数据库完整性在临时副本上检查，原文件保持不变。

`.tools` 的工具运行时、缓存、临时脚本及其他未选择文件仍忽略。备份文件由本次授权明确加入版本控制，不自动收集系统目录或认证数据。
