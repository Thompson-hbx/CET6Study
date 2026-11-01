# 架构实现记录

本文描述既有实现，不对后续开发设置约定、审批或技术路线限制。

## 真机高刷验证（2026-10-29）

- V2359A（API 36，1260×2800，最高 120Hz）上，使用同一 Apple 界面的优化前包作基线，与最终优化包比较。均为同 applicationId、同签名调试包，覆盖安装；原安装包无内置个人 JSON/PDF 资产，旧 APK 已留本地副本。没有卸载、清除数据、修改系统刷新率/省电设置、作答提交或真实 AI 请求。
- 写作目录相同预热与滑动动作，前后各两轮：窗口目标周期从 11.111ms（90Hz）变为 8.333ms（120Hz）；`DisplayPresentTime` 相邻间隔中位数从两轮均 11.049ms 变为两轮均 8.286ms，绘制开始、交换缓冲与完成时间的节奏相符。可以确认这组目录滚动采样从约 90 帧提升到约 120 帧，不能外推成所有页面长期稳定 120 帧。
- 帧处理耗时中位数仍为 6ms，未证明每帧处理成本下降。设备的优化后 `FrameDeadline` 距 `IntendedVsync` 有数秒异常偏移，因此不采信汇总中的“0 掉帧”作为无卡顿证明，也不将 `gfxinfo` 帧计数除以手势时长当作 FPS。
- 最终包的窗口报告 `preferredRefreshRate=120.00001`，手机 APK SHA-256 与本地构建输出一致，四个根标签切换及目录/「我的」布局已查看。详见 [真机采样记录](../test-evidence/phone-smoothness-20261029.json) 和 `phone-smoothness-catalog-2026-10-29.png`、`phone-xiye-me-2026-10-29.png`。

## 刷新率与滚动开销（2026-10-29）

- `MainActivity` 在窗口附着与恢复前台时，通过 `preferredRefreshRate` 请求当前分辨率下设备支持的最高刷新率，不指定显示模式 ID、不更改系统刷新率或省电设置；系统仍可按温控、功耗及设备策略决定实际刷新率。参考 [Android 刷新率偏好](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#preferredRefreshRate)。高刷新率可能增加显示耗电；目前没有高刷实体机可验证。
- `GlassSurface` 复用主题相关的 `HazeStyle`，使用 Haze 1.1.1 的 `HazeInputScale.Fixed(0.66f)` 缩小背景采样；采样宽高各为原来的 66%，前景图标与文字仍按原分辨率绘制，28dp 模糊和底色保持不变。参考 [该版本采样说明](https://chrisbanes.github.io/haze/1.1.1/usage/#input-scale)。根标签切换复用一个 `HazeState`，避免按标签和路由反复创建捕获状态。
- `TaskHome` / `ReadingHome` 按题库与历史快照缓存目录分组、校验和状态；阅读条目复用已查到的练习，避免在可见条目重新组成时再次扫描历史并解析快照 JSON。题库、草稿或批改结果变化时相应缓存会失效，不修改原筛选、排序、判题或保存逻辑。
- 60Hz / API 36 模拟器的调试包采样：写作目录预热后，每轮 12 次 400ms 交替滑动，前后各两轮。优化前中位数 25/27ms、P99 61/61ms、掉帧率 13.86%/19.59%；优化后中位数 27/27ms、P99 53/48ms、掉帧率 17.17%/15.00%。掉帧率重叠、中位数未改善，仅这次样本的 P99 较低，不能据此宣称整体 FPS 已稳定提高。根切页状态复用在这组采样后加入，未做单独切页性能基准。
- 追加交叉对照开始前模拟器退出，未形成追加结果；随后保留 userdata、以无窗口冷启动现有 AVD，只进行功能回归。性能摘要与局限见 [smoothness-20261029.json](../test-evidence/smoothness-20261029.json)。正式性能结论仍需代表性真机和接近发布配置的测量。

## 可选界面风格与应用品牌（2026-10-29）

- 「我的 → 界面风格」列出已经实现的风格，首版只收录 Apple，并显示当前选择。`theme/UiStyle.kt` 用稳定的 `apple` 标识与显示名分开；`StudyViewModel` 经原有 `study_ui` 偏好读取/保存 `ui_style`，未设置或已不支持的标识回到 Apple。`CET6Theme` 是风格分发入口，并向子组件提供 `LocalUiStyle`；当前 Apple 的配色、字体、圆角及批注色在 `AppleTheme` 内组装。后续公司风格需补对应主题与组件实现，再加入注册项，不提前展示空选项。
- 用户选择「习页」与蓝色翻页 A 方案作为当前品牌：Manifest 显示名、「我的」页名称与图标、AI 连接页默认测试消息已同步。原有 `com.example.cet6`、签名、数据库文件及备份格式保留，覆盖安装继续使用已有数据。
- 图标使用原生矢量前景与纯色背景，新增 Android 8+ 自适应图标及 Android 13+ 单色图层；应用内使用同一翻页图形。参考 [Android 自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。初轮图像生成对比稿仅用于讨论，正式资源位于 `res/drawable/ic_study*.xml` 与 `res/mipmap-anydpi/ic_launcher.xml`。名称与图形可以分别继续调整。
- 验证：新增风格设置与品牌后的构建、174 项 JVM、模拟器完整隔离套件及 `scenario ui` 通过；启动图标资源合并后的构建与 Lint 再次通过，最终仍为 0 错误 / 6 个既有警告。已查看「我的 → 界面风格」、重启后的默认 Apple 选择、应用内图标和桌面显示名；APK 标识核对仍为 `com.example.cet6`。截图见 `test-evidence/xiye-me-2026-10-29.png` 与 `test-evidence/apple-style-picker-2026-10-29.png`。

## Apple 风格界面：练习目录、工作页与毛玻璃（2026-10-29）

- 练习目录按考次放入圆角分组，套次名、两行题目预览、状态和进入箭头统一复用 `ListRow`；全部 / 未做 / 进行中使用现有 `TrackSegments`，筛选条件、排序和继续草稿的行为不变。
- 写作、翻译工作页的作答 / 批改 / 修改改为等宽分段控件，保留完成、禁用与 `Role.Tab` 语义；自拟题目的输入复用无边框 `FieldRow`。正文、译文增加分组标题，保留英文衬线正文；翻译仍支持固定原文与键盘弹出时的上下对照。阅读答题卡使用分段切换和填充式输入框，核对后的原文与逐题讲解放入两个圆角分组，定位、段号和判题逻辑沿用原实现。
- 新增 `components/Glass.kt`，通过 Haze 1.1.1 捕获页面背景，只对标签栏和工作页底部操作栏进行 28dp 模糊，叠加 72% 主题底色、细描边及阴影，图标与文字保持清晰。四个根页继续把底部余量放在滚动内容内；工作页的滚动内容也延伸至浮层下方，并按实际操作栏高度留出滚动余量。
- Haze 1.1.1 与当前 Kotlin 2.0.21 / Compose 1.7 系列匹配，不升级 Compose BOM 或构建插件。参考 [官方用法](https://chrisbanes.github.io/haze/1.1.1/usage/) 与 [该版本构建版本表](https://github.com/chrisbanes/haze/blob/1.1.1/gradle/libs.versions.toml)。这实现的是背景毛玻璃，不包含 iOS Liquid Glass 的折射效果；不能模糊时使用不透明主题底色，避免透字。
- 验证：174 项 JVM 通过，Lint 0 错误 / 6 个既有警告；模拟器（API 36）的完整隔离套件通过，`scenario ui` 在浅色 / 1.0 字号和深色 / 1.3 字号下各通过一次。检查了目录和三个工作页的浅色、深色大字号布局，以及写作、翻译弹出键盘的基本布局。之前的 `UI element not found: 阅读` 本轮未复现，其根因仍未定位，不称作已修复。
- 截图见 `test-evidence/apple-glass-*-2026-10-29.png`。仅向模拟器覆盖安装普通调试包与测试包，没有实体机安装、真实 AI 请求、数据库迁移或备份格式变更。Android 12 以下的不透明回退和低端实体机的模糊性能未实测。

## Apple 风格界面：导航、今日与复盘（2026-10-29）

- 导航栏改为 iOS 样式：`ScreenTopBar` 不再用 Material `TopAppBar`，高 44dp，左侧蓝色 ‹ 加上一页标题（由 `AppRoot` 经 `LocalBackLabel` 提供，路由标题集中在 `routeTitles`），标题居中；`SimpleScreen` 只在内容滚动后显示分隔线。读屏仍读作「返回」。
- 底部标签栏改为 iOS 26 悬浮胶囊：左右留边、带阴影和细描边，选中项衬浅色内胶囊；线条图标、选中变实心（今日 ☀、练习 ✎、复盘 ↺、我的 👤），10sp 标签。页面内容继续滚到胶囊下方。胶囊不透明，因为 Compose 本身做不到 Liquid Glass 的背景模糊，半透明会透出底下文字。
- 「今日」：继续卡片带技能图标；今天要做改为彩色图标行，数量放右侧；近 7 天为带技能图标的统计卡。「复盘」同样改为图标行。写作、翻译、阅读工作页只换了导航栏，正文仍是旧样式。
- 验证：174 项 JVM、Lint（0 错误 / 6 既有警告）、模拟器完整隔离套件与 `scenario ui` 通过；截图更新于 `test-evidence/apple-style-*-2026-10-29.png`。

## Apple 风格界面：设计令牌与「我的」页面组（2026-10-29）

- 全局令牌改为 iOS 风格：`theme/Color.kt` 用 iOS 系统色（分组背景 `#F2F2F7` / 深色 `#000000`，单元格白色 / `#1C1C1E`，0.5dp 分隔线）；系统蓝、绿和次要文字在不足 4.5:1 时改用更深的可访问变体（浅色主色 `#0066CC`，深色主色 `#409CFF`）。`Type.kt` 按 iOS 文本样式（Large Title 34、Headline 17、Body 17、Footnote 13 …）映射到 Material 角色，系统无衬线字体代替 San Francisco（不能随 Android 应用分发）。`Shape.kt` 圆角改为 iOS 分组单元格尺寸。
- 共享组件：`ScreenTopBar` 改为居中标题 + 蓝色「‹ 返回」；`SectionLabel` 改为与单元格文字对齐的脚注灰字；`ListRow` 改为 44dp 行高、细 › 箭头，可选前置 `IconTile`；`SectionCard` 去掉边框和阴影。底部导航改为 iOS 式标签栏（无指示胶囊、选中项着色），仍是 `Role.Tab` 可选项，界面脚本照常识别。
- 新增 `components/Grouped.kt`：`Group`（页眉 / 白色分组 / 页脚）、`ValueRow`、`ActionRow`（蓝色或红色操作行）、`ToggleRow`、`CheckRow`、`FieldRow`（行内无边框输入，Key 仍不进 saved state）、`IconTile`。
- 「我的」改为设置列表样式（概览单元格 + 彩色图标行）；AI 连接、备份与恢复、题库管理改为分组表单（服务选择用勾选列表，按钮改为操作行，深度批改用开关行）。其他页面目前只随全局令牌变色，结构尚未重做。
- 验证：174 项 JVM、Lint（0 错误 / 6 既有警告）、模拟器完整隔离套件与 `scenario ui` 通过；浅色和深色截图见 `test-evidence/apple-style-*-2026-10-29.png`。未在实体机查看。

## 页面 ViewModel：备份与题库管理（2026-10-29）

- 「备份与恢复」：`BackupViewModel` 用 `combine` 合并备份、学习计划、练习、对话和题库五个服务的状态，得到 `BackupUiState`；原来散在按钮上的启用条件（导出、恢复预览、继续/确认合并）集中到 `BackupUiState.from`，由 `BackupUiStateTest` 在 JVM 上覆盖。`BackupCard(vm, standalone)` 保留为绑定入口（「我的」页和旧版今日页共用同一个 ViewModel），界面是无状态的 `BackupPanel(state, onIntent)`。
- 「题库管理」：`ExamLibraryViewModel` 暴露 `ExamLibrary.state.flow`，`ExamManagement(state, onIntent)` 无状态，由 `ExamManagementRoute` 绑定。
- `StudyViewModel.services` 改为 `internal`，页面 ViewModel 统一从这里取服务（AI 连接页随之改用 `vm.services.ai`）；不再被任何页面使用的备份/题库委托方法和属性已删除，仪器测试仍在用的 `backupBusy`、`backupStatus`、`backupRestorePending` 等保留。
- 验证：174 项 JVM（新增 6 项）、Lint（0 错误 / 6 既有警告）、模拟器完整隔离套件与 `scenario ui` 通过；模拟器上手动打开备份页、题库管理页和旧版今日页的备份卡片，数量与按钮状态正常。恢复预览对话框需经系统文件选择器，未手动走完；启动续恢复场景（`restore-startup-*`）本次未跑。

## 页面 ViewModel：AI 连接（2026-10-29）

- 「我的 → AI 连接」是第一个按页面拆分的界面：`ConnectionViewModel` 直接暴露 `AiGateway.state.flow`（`StateFlow<AiState>`），用 `ConnectionIntent` 接收操作，用 `ConnectionEffect.ApiKeySaved` 通知页面清空已保存的 Key 输入；`ConnectionScreen(state, effects, onIntent)` 本身无状态，由 `ConnectionRoute` 以 `collectAsStateWithLifecycle` 绑定。
- 页面从 `MainActivity` 移到 `ui/ConnectionScreen.kt`，`ApiConnectionSettings` / `DeepReviewSettings` 改为接收 `AiState` 与 intent；`LegacyScreens.connection` 删除。界面文案与布局不变，正在输入的 Base URL、Key、测试消息仍只留在页面内存（Key 不进 saved state）。
- `ConnectionViewModel` 通过 `viewModel {}` 放在 Activity 的 ViewModelStore，网关取自同一 store 里的 `StudyViewModel`（`internal val ai`），两者同时清除。改为进程级容器仍待后续页面一起处理。
- 验证：168 项 JVM、Lint（0 错误 / 6 既有警告）、模拟器完整隔离套件与 `scenario ui` 通过；模拟器上手动进入 AI 连接页，切换服务、无效配置保存提示和返回后「我的」摘要同步均正常。未发起真实 AI 请求，未在实体机安装。

## 服务层（domain）拆分（2026-10-29）

- 新增 `com.example.cet6.domain`，不依赖 Compose：`AiGateway`（服务配置、OAuth、模型、流式请求）、`PracticeService`（原 `CoachState` 的草稿/提交/提示/诊断/微练习）、`ConversationService`（原 `CoachTalk` 的 turn 请求）、`StudyPlanService`（词库、FSRS 复习、每日计划）、`ExamLibrary`（个人题库读取、导入预览、导出）、`BackupService`（完整备份导出、预览、可续恢复）和 `AppGate`（统一判断能否发起 AI 请求，替代 ViewModel 里重复的 `canRequest`）。`StudyServices` 负责创建与接线，并按原顺序完成“先续恢复、再读题库、最后加载草稿和词库”的启动流程。
- 各服务的状态是不可变 data class，经 `ServiceState` 同时暴露为 `StateFlow`（供后续按页面拆分的 ViewModel 收集）和同步监听；`ui/ServiceMirror.kt` 用同步监听把状态镜像为 Compose state，旧页面读取不会滞后。
- `AiConnectionState`、`CoachState`、`CoachTalk` 改为薄适配层，保留原构造函数、属性和方法；`StudyViewModel` 只做委托，公开接口不变。逻辑、中文提示文案、Room schema、备份格式均未改。`CoachTalk.Pending/Failure` 移至 `ConversationService`，`isDeepReviewKind` 移至 `domain`。
- `StudyServices` 目前随 ViewModel 的 `viewModelScope` 存活，新 ViewModel 仍从磁盘重新启动；改为进程级容器会改变恢复与重启语义，留到按页面拆分 ViewModel 时再验证。
- 验证：168 项 JVM、Lint（0 错误 / 6 既有警告）、模拟器完整隔离套件、`scenario ui` 与 `check_draft_process.ps1` 四种模式通过；未在实体机安装，未发起真实 AI 请求。

## AI 连接职责拆分（2026-10-27）

- `AiConnectionState` 持有服务配置、模型偏好、OAuth 可用状态、模型列表、测试回复和共享请求状态；其协程仍使用 `StudyViewModel.viewModelScope`。`StudyViewModel` 保留原公开读取/操作接口及 `streamCoach`，训练与追问继续共享同一请求门槛；旧作文写入通过回调留在 ViewModel 的数据层。
- 原 `study_ui` 偏好键、API/会话加密存储、超时、传输协议和取消行为沿用原实现，没有数据库或存储格式迁移。ViewModel 在此次拆分时从 630 行降至 446 行；学习、题库和备份操作仍待后续按职责逐步迁出。
- `AiConnectionStateChecks` 使用唯一命名的隔离偏好、内存 Room 和不转发网络的 HTTP 拦截器，验证服务间模型偏好、加密配置重新读取、完整回复落盘、断流/429 保全、请求中配置锁定及取消后释放。重建组件检查不等于进程死亡恢复。另通过既有真实 Activity/ViewModel 生命周期回归；证据见 [backend-ai-state-20261027.json](../test-evidence/backend-ai-state-20261027.json)。
- 重跑：构建并向模拟器安装普通包/测试包后，运行 `adb -s <emulator-serial> shell am instrument --user 0 -w -e scenario ai-connection com.example.cet6.test/com.example.cet6.LocalDataInstrumentation`。默认隔离套件也包含此检查；不需要真实凭证或模型额度。

下列按日期保留早期实现记录；当前备份、协议与计时边界以 [STATUS.md](STATUS.md) 及其关联文档为准。

## 多 AI 服务接入（2026-10-23）

- `AiConnectionState.streamAi`（2026-10-27 从 ViewModel 迁出）按 `AiProvider` 分派到原 `ResponsesClient` 或新增 `ChatCompletionsClient`；训练和追问共用 `aiReady` 门槛，不再依赖 ChatGPT 套餐权限。ChatGPT OAuth 与 Responses 协议保持原实现。
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
