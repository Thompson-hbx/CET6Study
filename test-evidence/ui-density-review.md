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

## Compose UI 持续精修（2026-10-18，本轮）

按 `cet6study_ui_optimization_prompt.md` 实施，变更限于五个 UI / 主题文件：

- 导航选中态切换实心图标并加粗标签，补充顶栏 / 底栏分割线与 44dp 返回按钮；MenuBook 使用等价的 AutoMirrored 图标，避免新增弃用警告。
- 目录年份 / 月份触控区提升至 44dp / 40dp；继续入口和 PaperRow 使用胶囊提示，完成进度采用成功色与粗体。
- 写译标题信息卡内置 44dp 计时按钮，题干、原稿、参考译文、AI 建议与错因分别成卡。参考译文的浅色成功底搭配固定深墨文字，避免深色主题下白字失去对比度。作答初始 6 行，长文本继续扩展；48dp 提交 / 保存按钮水平并列，保存状态与字数同行。
- 答题槽位为 56×68dp、间距 8dp，窄屏允许换行；核对 / 保存录入草稿按钮并列。InteractiveStepPill 支持直接跳到任意提示层，同时保留上一步、下一步及 AI 提示，仍按 `level == 1..4` 仅渲染当前层正文。第 4 层仍须用户主动点击才展示参考。
- 原文新增英文词数徽标、分割线，保持可选中 Serif 正文与 28sp 行高。主题所有文本样式启用 `tnum`；答题槽序号改为固定两位数字，避免随系统区域格式变化。

### 验证范围

- 离线命令：`gradlew.bat --offline assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest`，结果见 [本轮构建日志](ui-polish-build.log)。
- 源码检查：27 个要求保留的核心文案仍存在；复核 diff，数据库 / Room schema、OAuth / Keystore、Responses、ExamCodec、SequentialAnswers、备份协议、依赖及现有回归测试源码未改。
- 本轮多次 `adb devices` 仅返回空设备列表，未发现在线的 emulator-5554。因此未覆盖安装、未执行设备隔离回归或 ui_density_capture.py 截图；本节不沿用上一轮截图作为本轮证据。浅深色实机效果、四层点击与单层显示、写译首屏按钮露出仍待在线设备验证，长题干或大字号下不保证首屏完整显示。
- 未连接设备，无法取得升级前后数据库快照，未执行本轮 `PRAGMA integrity_check` 或逐行保全比对。代码未改变数据层，但不能据此声称设备数据已验证零丢失。未读取凭证或个人真题包，未发送真实 AI 请求，未新增依赖、提交或推送。

- 本轮最终结果：Debug APK 与 AndroidTest APK 离线构建成功；77 项单测全部通过（0 失败 / 0 错误 / 0 跳过）；Lint 0 错误、7 警告，与上一轮记录的 7 项数量相同。增量检查曾发现两处已有题号 String.format 的 DefaultLocale 警告，已在本轮以 padStart 固定序号格式消除；其余警告为既有构建配置 / 依赖版本及 PageHeader 参数顺序。

## 模拟器补验与遮挡修正（2026-10-18）

用户授权启动模拟器后，使用已有 Pixel_7_API_36 / emulator-5554，覆盖安装而非卸载；以下结果补齐上一节中当时尚未执行的设备验证。

- 默认分辨率 1080×2400、density 420（约 411dp 宽）。题库浅色 / 深色均实际截图检查。临时 945×2100（360dp 宽）验证答题槽换行；已恢复默认分辨率与浅色模式。
- 现有 `LocalDataInstrumentation` 隔离套件通过，覆盖迁移、备份、G4 内容、离线保存、流式失败保全与手动重试；没有运行依赖旧导航文案的可选 `scenario=ui`，改用现有 `ui_density_capture.py` 做实际界面操作。见 [隔离回归日志](ui-polish-instrumentation.log)。
- 原创功能样本录入 `BA?AD` / `CADCB`，核对 9/10 题，未录入第 3 槽保持原位。直接点击线索 / 定位 / 证据 / 选项四个胶囊，断言仅有当前层标题；前三层未展示参考答案，4→2 回跳正常。
- 作文原稿为既有样本的 6 词 / 46 字符，未编辑；作文及空译文的提交 / 保存按钮无需滚动即完整可见。通过可点击父节点语义确认空译文提交禁用。第二条独立阅读样本保存后，回到后台再停止进程、重开应用，UI 恢复 9/10 录入。见 [UI 断言](ui-polish-ui-regression.log)。
- 截图发现短页面悬浮“返回训练导航”按钮压住保存操作右侧，已最小修正为滚动超过一屏且未到底部才出现；最终 APK 重新覆盖安装、隔离回归及四层点击验证，截图已更新。
- 最终离线 `assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest` 成功，77 项单测通过，Lint 0 错误 / 7 警告。
- 数据快照只读取学习数据库，保存到 Git 忽略的 `.tools/ui-polish-*`。升级前后 `PRAGMA integrity_check=ok`、Room v7；原有 18 条练习及其他所有表原行逐字段相同，仅新增 2 条明确标识的原创阅读样本（一条已提交、一条保存草稿）。见 [保全日志](ui-polish-data-preservation.log)。未读取凭证、清库或发送真实 AI 请求。

| 验证页面 | 本轮截图 |
| --- | --- |
| 题库双主题 | [浅色](ui-density-polish-library-light.png) / [深色](ui-density-polish-library-dark.png) |
| 两篇录入 | [空卡](ui-density-polish-input-empty.png) / [已录入](ui-density-polish-input-filled.png) / [360dp 窄屏](ui-density-polish-input-narrow.png) |
| 核对 / 逐层提示 | [核对](ui-density-polish-check.png) / [线索](ui-density-polish-hint1.png) / [定位](ui-density-polish-hint2.png) / [证据](ui-density-polish-hint3.png) / [选项](ui-density-polish-hint4.png) |
| 原文与持久化 | [原文](ui-density-polish-original.png) / [重开草稿](ui-density-polish-draft-reopened.png) |
| 写译首屏 | [作文](ui-density-polish-writing.png) / [翻译](ui-density-polish-translation.png) |

边界：这是模拟器与原创样本验证；未声称真机、大字号 / 所有题干长度或真实线上 AI 服务已验证。
