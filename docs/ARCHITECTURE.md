# 架构实现记录

本文描述既有实现，不对后续开发设置约定、审批或技术路线限制。

## 多 AI 服务接入（2026-10-23）

- `StudyViewModel.streamAi` 按 `AiProvider` 分派到原 `ResponsesClient` 或新增 `ChatCompletionsClient`；训练和追问共用 `aiReady` 门槛，不再依赖 ChatGPT 套餐权限。ChatGPT OAuth 与 Responses 协议保持原实现。
- DeepSeek 固定官方 Base URL；自定义服务仅接受无账号、查询参数及片段的 HTTPS Base URL，保留服务商版本路径。模型通过 `GET /models` 的 `data[].id` 获取，也允许手动填写；模型偏好按服务隔离，原 ChatGPT `model` 偏好继续可读。
- `ChatCompletionStream` 复用 `SseReader`，只输出首个 choice 的 `delta.content`，忽略 `reasoning_content`；收到 `finish_reason=stop` 与 `[DONE]` 且正文非空才成功。长度截断、内容过滤、工具调用、错误事件和流提前结束均失败；协程取消会关闭 OkHttp 调用。禁用重定向和连接失败自动重试。
- `SecureApiStore` 将每个 API 服务的地址与 Key 一起用 Android Keystore AES-256-GCM 加密；与 OAuth、Room、学习备份分开，系统云备份和设备迁移显式排除。Key 输入只用 Compose `remember`，不进入保存的界面状态；更换地址时不复用旧 Key。没有新增依赖或数据库迁移。


- `CET6Study/` 是 Android 工程，applicationId 为 `com.example.cet6`；存在 OAuth、Responses SSE、Room 和 Compose，符合本次目标。未访问或合并其他项目。
- 当前实现采用 Kotlin、Compose、MVVM + Repository、Room、OAuth 与 Keystore。未来允许有依据地重构；触及应用 ID、数据库、认证或网络协议时需迁移方案与针对性回归，不以历史选型构成永久禁令。
- G3 训练使用独立 Room 表保存阅读、作文、翻译的输入、作答、完整反馈和用户错因。v4→v5 只新增表，v5→v6 追加难句收藏，v6→v7 追加来源练习和训练目标字段，不删除或重建旧表。
- 草稿本地自动保存，提交前先保存作答。只有点击 AI 按钮才上传本次材料；未完成流不写为完成反馈。离线可保存、浏览和复练。
- 请求失败的学习提示由 CoachFailures 映射现有传输异常，不修改 OAuth 或 Responses。只识别已知状态及错误码，不展示任意异常内容；临时片段与完整反馈分别显示，失败后保留旧反馈。重试由用户发起，不把额度错误当作能自动恢复的短时限流。分类参考官方错误说明 https://developers.openai.com/api/docs/guides/error-codes ，不据普通 API 文档推断 ChatGPT 套餐账单或具体配额。
- 阅读第一版以文章、可追加的题目文本和编号作答组成一次训练；参考答案可在题目中提供。无参考答案时 AI 只能给推断和证据，不能声称官方判题。
- 学习记录与凭证分开；备份只包含白名单学习字段。JSON v4 包含难句收藏、来源关联和训练目标，继续兼容导入 v1/v2/v3。
- 反馈排版复用 Markwon core / ext-tables 4.6.2（Apache-2.0），避免自行实现 Markdown 解析器；未接入图片加载插件。表格不便复制时可使用原始文本或完整反馈复制入口。实现参考 https://noties.io/Markwon/docs/v4/core/getting-started.html 和 https://noties.io/Markwon/docs/v4/ext-tables/ 。不重写认证网络层。现有词汇、备份等未提交工作保留。
- `docs/TODO.md` 记录当前待办和未验证项；旧报告保留历史事实，不构成阶段门槛。过去关于 Notion 和“唯一任务文件”的限制已解除，外部服务操作仍需具备用户授权。


- 训练 UI 将资料、讲解、错因和历史分区；切换时复位页面滚动，追问入口置于完整反馈前。此改动不修改数据库或认证接口。

- 针对性微练习复用现有 Responses 文本流，要求返回简单 JSON，不新增网络参数；完整响应通过字段和题型检查后保存为新草稿。生成失败不改写原练习，离线可继续原题再练。
- sourcePracticeId 关联来源记录，practiceGoal 保存当时确认的错因。关联不设外键，便于兼容备份合并；来源不可用时仍保留独立练习。写译修改版预填原作答，阅读再练不预填答案。
- 首页薄弱项回顾仅汇总已作答且有用户笔记的练习，不解析 AI 反馈进行自动归因。识别显式类别和“类别：说明”，其余保留为其他笔记；按设备时区的作答日期显示近两周记录数，不称作能力趋势。回顾按钮复用现有打开记录动作，并定位正确的阅读/写作/翻译错因页，无额外存储或学习算法。
- 纯文本分享复用阅读实体，不增加数据库版本；校验类型、非空及 60000 字符上限，链接仅作为待补正文的草稿，不抓取网页。冷启动等待本地草稿加载，导入前先保存当前编辑；请求中分享仅新增历史记录。单 Activity 使用 singleTask 将重复分享送到 onNewIntent，消费后移除分享内容，避免重建时重复导入。参考 Android 官方接收分享说明：https://developer.android.com/develop/ui/compose/sharing/receive 。

## G4 内容与训练（2026-10-18）

- `ExamContent.kt` 是已核对考次及逻辑内容模型，按实际日期生成 24 个套卷占位。套次为第三方资料整理标签，非官方统一编码，仍需逐卷校对。`ExamCodec.kt` 负责 JSON v1 校验、稳定标识、内容快照与本地原子文件存储；不增加 Room 表或迁移。
- 应用内部 `exam-content-v1.json` 是个人题库单一持久来源。导入先预览、不完整标为待校对；经人工核对且结构完整才能练习。合并仅允许同内容同版本或更高 revision；整批验证后原子替换，读取失败时禁止导入覆盖。
- 新训练将当前题目的材料、来源、内容版本、原题号、选项、参考答案与证据写入 `CoachPractice`。`questions` 中格式标记 `CET6ExamAttempt` 区分结构化快照和旧版纯文本；旧版实体、备份 v1—v4 保持兼容。AI 提示词复用原路径，训练快照中包含参考来源，界面在提交前隐藏参考内容。
- 阅读题 ID 由套卷 stableId / READING / 篇章 code / 题号派生；整套录入的练习键使用 READING/all，保留此前分篇快照的识别能力。作答 ID 继续为 UUID，因此多次练习不会互相覆盖；`sourcePracticeId` 保持练习之间的关联，不混作题库 ID。再次打开相同内容版本会继续已有草稿。
- 内容修订影响未来练习；已开始练习的内容快照保持原版，待校对不能自动变为官方内容。人工校对标记与使用权字段均是导入者声明，程序只能做结构与精确证据匹配，无法自动确认法律授权或识别语义混卷。
- “我的”学习备份包含练习快照；未作答题库必须通过题库管理单独导出。选择双文件明确边界，避免改动历史备份协议。工具见 `docs/EXAM_IMPORT.md`。
- `ExamScreens.kt` 提供目录、连续写译与参考对照；`PaperAnswerScreen.kt` 提供纸上完成后的整套 / 分篇答案录入、参考核对、四层提示和只读原文对照；仍用 `CoachState` 保存、提交、追问与错因动作。`MainActivity` 仅组织四项导航和历史入口，认证网络实现未改动。计时器为可选界面计时，非考试约束或持久学习统计。

- 2026-10-18 用户修订：阅读不再默认在 App 内做题。核对仅使用有来源答案，未知参考答案不判对错；前三级本地提示不展示正确选项。`ReadingGuidance` 只从导入证据精确定位段落，找不到就声明未知，不猜证据。第四级由用户主动揭示选项和答案。`CoachState.readingHint` 单独构造当前题当前层的引导提示，沿用既有请求回调和失败保全；前三层请求不附带选项答案键，逐层完整结果追加到旧反馈字段并保留历史。

- 阅读录入显示每篇1—5与独立五选项输入框；`SequentialAnswers`只按篇内顺序映射原题号，原题号保留在既有作答格式和快照中，便于兼容备份与来源追溯。更新一篇不覆盖另一篇，未知/超长输入不落盘，?保留空位。界面和核对列表均按篇内1—5显示。

## 0.7.0 写译工作台与对话（2026-10-21）

- 讲解与追问保存在 `coach_turns`（`CoachTurns.kt`），按 practiceId + anchor 归属：`review`、`plan`、`q:题号`、`para:篇章×100+段号`、`over:篇章`、`sent:句号`。Room v7→v8 只执行 CREATE TABLE / CREATE INDEX，不改旧表。
- `CoachTalk` 负责这些请求：只写 turn，不改写练习行，所以生成期间编辑不会被覆盖；只有收到完整回复才保存（用户问题与回复一起写入），停止或失败不留下半截内容。停止通过取消协程关闭 OkHttp 调用（`ResponsesClient` 中的取消监听），请求参数与 OAuth 流程未改。旧的 `CoachState.submit / readingDiagnosis / readingHint` 及 feedback 字段继续可读，旧记录在新页面以「早期批改 / 早期讲解」显示。
- 批改、阅读讲解、结构梳理使用逐行 JSON（`WritingCoach`、`TranslationCoach`、`ReadingCoach` 中的提示词，`CoachReviews.kt` 解析）。`JsonLines` 按括号扫描完整对象，容忍代码围栏和多行排版，流式过程中只显示已完整的对象；没有任何可识别对象时按 Markdown 原文显示。用户对每条建议的采纳 / 忽略存于该 turn 的 state 字段。
- `TextMarks` 先找原文完全一致的位置，再做空白、引号和大小写宽松匹配，并保证多条引用不重叠；找不到的建议标为「未定位」，采纳时不改原稿。修改稿通过 `CoachState.revise` 新建草稿，原稿与批改保持不变；`WordDiff` 做逐词对照。
- `PassageLayout` 按句末标点重排 PDF 提取文本中的换行，空行始终分段；阅读器、提示词段号和原文定位共用这一分段。`TranslationCoach.sentences` 去掉中文原文的打印换行后按句号、问号、叹号分句，供翻译编号和逐句对照。
- 导航由 `AppRoot` 管理：四个首页标签和一个以字符串保存的返回栈，进程重建后可恢复。上次所在标签和所选模型存于 `study_ui` SharedPreferences（不含凭证）。
- 新导航不再进入的旧页面已删除：考次目录 `ExamLibraryScreen`，以及写译训练页 `ExamTrainingScreen`、`WritingAndTranslationScreen`、`WritingFeedbackPanel`。`CoachScreen` 只用于「自己的文章」；从它的历史打开的真题阅读记录仍交给 `PaperAnswerScreen`，不在可编辑页面显示含参考答案的快照。
- 备份 JSON v5 增加 `turns`；校验每条 turn 的类型、锚点、长度以及所引用的练习存在于同一备份；同标识内容冲突时整份拒绝。v1—v4 照旧导入。
