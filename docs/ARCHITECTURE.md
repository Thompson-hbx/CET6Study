# 架构决策

- `CET6Study/` 是 Android 工程，applicationId 为 `com.example.cet6`；存在 OAuth、Responses SSE、Room 和 Compose，符合本次目标。未访问或合并其他项目。
- 继续 Kotlin、Compose、MVVM + Repository、Room，保留现有 OAuth、Keystore、网络参数、应用 ID 和数据库名。
- G3 训练使用独立 Room 表保存阅读、作文、翻译的输入、作答、完整反馈和用户错因。v4→v5 只新增表，v5→v6 追加难句收藏，v6→v7 追加来源练习和训练目标字段，不删除或重建旧表。
- 草稿本地自动保存，提交前先保存作答。只有点击 AI 按钮才上传本次材料；未完成流不写为完成反馈。离线可保存、浏览和复练。
- 请求失败的学习提示由 CoachFailures 映射现有传输异常，不修改 OAuth 或 Responses。只识别已知状态及错误码，不展示任意异常内容；临时片段与完整反馈分别显示，失败后保留旧反馈。重试由用户发起，不把额度错误当作能自动恢复的短时限流。分类参考官方错误说明 https://developers.openai.com/api/docs/guides/error-codes ，不据普通 API 文档推断 ChatGPT 套餐账单或具体配额。
- 阅读第一版以文章、可追加的题目文本和编号作答组成一次训练；参考答案可在题目中提供。无参考答案时 AI 只能给推断和证据，不能声称官方判题。
- 学习记录与凭证分开；备份只包含白名单学习字段。JSON v4 包含难句收藏、来源关联和训练目标，继续兼容导入 v1/v2/v3。
- 反馈排版复用 Markwon core / ext-tables 4.6.2（Apache-2.0），避免自行实现 Markdown 解析器；未接入图片加载插件。表格不便复制时可使用原始文本或完整反馈复制入口。实现参考 https://noties.io/Markwon/docs/v4/core/getting-started.html 和 https://noties.io/Markwon/docs/v4/ext-tables/ 。不重写认证网络层。现有词汇、备份等未提交工作保留。
- 唯一任务清单为 `docs/TODO.md`；旧报告保留测试事实，不构成阶段门槛。后续永久不使用 Notion。

- 训练 UI 将资料、讲解、错因和历史分区；切换时复位页面滚动，追问入口置于完整反馈前。此改动不修改数据库或认证接口。

- 针对性微练习复用现有 Responses 文本流，要求返回简单 JSON，不新增网络参数；完整响应通过字段和题型检查后保存为新草稿。生成失败不改写原练习，离线可继续原题再练。
- sourcePracticeId 关联来源记录，practiceGoal 保存当时确认的错因。关联不设外键，便于兼容备份合并；来源不可用时仍保留独立练习。写译修改版预填原作答，阅读再练不预填答案。
- 首页薄弱项回顾仅汇总已作答且有用户笔记的练习，不解析 AI 反馈进行自动归因。识别显式类别和“类别：说明”，其余保留为其他笔记；按设备时区的作答日期显示近两周记录数，不称作能力趋势。回顾按钮复用现有打开记录动作，并定位正确的阅读/写作/翻译错因页，无额外存储或学习算法。
- 纯文本分享复用阅读实体，不增加数据库版本；校验类型、非空及 60000 字符上限，链接仅作为待补正文的草稿，不抓取网页。冷启动等待本地草稿加载，导入前先保存当前编辑；请求中分享仅新增历史记录。单 Activity 使用 singleTask 将重复分享送到 onNewIntent，消费后移除分享内容，避免重建时重复导入。参考 Android 官方接收分享说明：https://developer.android.com/develop/ui/compose/sharing/receive 。
