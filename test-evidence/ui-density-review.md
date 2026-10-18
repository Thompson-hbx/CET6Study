# UI 文案减负验证（2026-10-18）

本轮只调整 Compose 文案与显示密度。未改数据库结构、试卷资料、OAuth、Responses 或保存/提交逻辑。CoachState 仅缩短三条成功提示。

## 主要删除与缩短

| 原文案 / 原显示 | 最终处理 |
| --- | --- |
| 一份试卷，一段专注的时间。 | 删除 |
| 让每次练习留下收获 / 自己的学习节奏 | 删除 |
| 把纸上的答案带进来 / 先看结果，再找依据 | 删除 |
| Writing / Translation，与试卷名称重复的训练标题 | 删除；训练页以试卷名称为主标题 |
| 每篇录入说明、每个输入框的固定例子 | 合并到输入框占位提示，格式错误时保留错误说明 |
| 核对已录入的答案 | 核对 |
| 从第 1 层提示开始 / 下一层：定位原文等 | 查看提示 / 下一步 |
| 让 AI 补充本层提示 | AI 提示 |
| 回到纸上再练 · 新建录入记录 | 再练 |
| 保存作答并请求 AI 解析/批改 | AI 解析 / AI 批改 |
| 完成并保存 / 查看作答与修改对照 | 提交 / 查看对照 |
| 复盘页两段统计口径解释 | 删除；保留记录数、时间区间、错因和历史 |

四层提示每次只显示当前层，可用“上一步 / 下一步”回看。完整原文、证据、选项、答案来源和已保存 AI 讲解保留。错因编辑与再练位于最终选项对照层；未新增弹窗或折叠组件。漏答、答案或来源缺失均明确显示“待核对”及原因，保存失败、资料校对状态和临时反馈提示保留。

写译输入区初始高度由 12 行减为 8 行，长作答仍可继续输入，字数和提交按钮更容易出现在首屏。

## 模拟器截图

均来自现有 emulator-5554。阅读和写译使用明确标识的原创功能样本，未冒充真题。录入页前后作答数量不同，用于验证空题与部分提交；布局对照不代表同一作答结果。

| 页面 | 修改前 | 最终截图 |
| --- | --- | --- |
| 题库 | [前](ui-density-before-library.png) | [后](ui-density-after-library.png) |
| 答案录入 | [前](g4-sequential-input.png) | [后](ui-density-after-input.png) |
| 核对 | [前](ui-density-before-check.png) | [后](ui-density-after-check.png) |
| 当前题待核对 | — | [未录入状态](ui-density-after-pending.png) |
| 第一层提示 | [前](ui-density-before-hint.png) | [后](ui-density-after-hint1.png) |
| 后续提示 | — | [定位](ui-density-after-hint2.png) / [证据](ui-density-after-hint3.png) / [选项与来源](ui-density-after-hint4.png) |
| 作文 | [前](g4-writing-draft.png) | [后](ui-density-after-writing.png) |
| 翻译 | [前](g4-translation-light.png) | [后](ui-density-after-translation.png) |

## 验证结果与边界

- `--offline assembleDebug testDebugUnitTest lintDebug` 成功：77 项单元测试，0 失败；Lint 0 错误、7 项既有警告。见 [构建日志](ui-density-build.log)。
- `assembleDebugAndroidTest` 成功；现有模拟器隔离回归通过，覆盖迁移、草稿/历史/证据、离线保存、失败保全和备份兼容。见 [回归日志](ui-density-instrumentation.log)。
- ADB 实际录入两篇、提交 9/10 题，验证空位不挪动、未答题待核对，以及四层提示每次只出现一层。见 [UI 断言记录](ui-density-ui-regression.log)。
- 实际重开后阅读录入恢复；作文原稿、字数、保存状态及提交入口可见，翻译空稿提交禁用。所有最终截图已人工查看。
- 升级前后原有 14 条练习的学习内容一致；新增 2 条原创样本测试记录。1 条原作文样本的更新时间变化，原作答已恢复并核对一致。词汇、计划、历史等其他表保持一致；SQLite `integrity_check=ok`。见 [保全记录](ui-density-data-preservation.log)。原始数据库快照放在 Git 忽略的私有目录。
- 本轮未发送真实 AI 请求，未操作登录或授权；未声称真机验证。没有安装新依赖，没有提交或推送。
