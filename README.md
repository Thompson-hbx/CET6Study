# 六级研习室 CET6Study

> 当前功能、这轮 19 项整改及未验证边界统一见 [STATUS.md](docs/STATUS.md)（2026-10-28）。下文的旧版本、资料数量及流水记录按标注日期作为历史理解。

现有版本的检查结果、真实 AI 评测和未验收边界见 [验收汇总](docs/RELEASE_ACCEPTANCE.md)。

## AI 服务配置

「我的 → AI 连接」支持 ChatGPT 登录、DeepSeek API 和自定义 Chat Completions 兼容接口。写作批改、翻译、阅读讲解及追问共用当前选择的服务；默认保留原 ChatGPT 连接方式。

- **DeepSeek**：选择 DeepSeek，输入你在 DeepSeek 平台创建的 API Key，点击「加密保存配置」，再刷新并选择模型，发送测试请求。官方服务地址固定为 `https://api.deepseek.com`，模型列表从服务端读取，不固定旧模型名。
- **自定义兼容接口**：填写服务商的 HTTPS Base URL（例如 `https://服务地址/v1`，不要附加 `/chat/completions` 或 `/models`）和 API Key，保存后刷新模型。若服务不支持模型列表，可直接填写模型 ID，再测试请求。只支持 Bearer Key 的 Chat Completions 文本 SSE 接口，不包含 Anthropic / Gemini 原生协议。
- 三种服务分别记住所选模型，DeepSeek 与自定义服务分别加密保存一份配置。Key 由 Android Keystore AES-GCM 加密，不进入学习 JSON 备份或系统迁移；清除本机配置不会撤销服务商 Key。更换自定义地址时必须重新输入 Key。
- API 请求按所选服务商规则计费，使用自己的 API 账户，独立于 ChatGPT 套餐。只有完整结束的文本才保存为学习反馈；推理过程不混入正文，中断、截断或过滤的回复不算完成。

协议参考：[DeepSeek 首次调用](https://api-docs.deepseek.com/zh-cn/)、[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[模型列表](https://api-docs.deepseek.com/api/list-models/)（2026-10-23 核对）。

AI 追问会区分等待回复与正文生成，输入栏旁可随时停止。停止或失败后保留原问题，可重新生成或放回输入框修改；半截回复不会保存。完整追问回复显示模型、保存时间并支持一键复制。原问题恢复按钮不会覆盖输入框里另写的草稿。

0.7.0-desk：底部导航为 写作 / 翻译 / 阅读 / 我的，每个练习在全屏工作台中完成。作文与翻译提交后可请求结构化批改：模拟档位（仅供参考）、在原稿上逐处画线、逐条采纳并生成修改稿、与上一稿逐词对照；翻译按句对照并给出重点词语；写作前可审题构思，翻译时可逐句问难词与句式。阅读在纸上做完后录入答案（可一次输入 10 个），核对后逐题讲解定位、同义替换和每个选项，并能跳回原文高亮；原文按段编号，可逐段翻译、拆句、看段意。每处讲解都可继续追问，只保存完整回复。学习库为 Room v8（新增对话表，旧数据原样保留），完整备份为 ZIP v1（学习字段 JSON v5），并兼容旧学习 JSON v1—v5。改动与验证记录见 [TODO](docs/TODO.md) 和 [ARCHITECTURE](docs/ARCHITECTURE.md)；下文保留此前版本的说明。

G4（0.6.0-paper-ink）默认进入真题目录，主导航为题库 / 训练 / 复盘 / 我的。支持个人结构化内容包导入、纸上做题后按篇内1—5输入五个选项、核对与分层提示，以及写译训练；旧自定义练习、单词与历史仍可访问，TTS 已移除。目录包含 8 个已举行考次、24 个整理套次；**APK 不内置第三方试卷正文**。本机私有资源已下载 46 份原卷/参考答案；个人内容包含 24 条，其中 14 套提供有来源的阅读参考键、写作任务和翻译中文，10 套待校对。已在模拟器通过系统选择器导入；不等于官方认证或全部资料校对完成，原创测试样本不是真题。PDF / Word 本地转换与个人题库备份边界见 [导入说明](docs/EXAM_IMPORT.md)。下文保留 G3 使用与验证历史。

> 项目最初以 G3.0 本地 AI 六级专项助手为基础，现已进入 G4 实施。历史设计见 [G3/G4 蓝图](docs/product/G3_BLUEPRINT.md)，当前待办见 [TODO](docs/TODO.md)，实现依据见 [ARCHITECTURE](docs/ARCHITECTURE.md)。历史工具禁令、冻结范围和固定任务门槛已解除；日常 Agent 约定见 [AGENTS.md](AGENTS.md)。

阅读页支持粘贴文章、追加题目、按题号独立作答、本地草稿和提交记录、AI 解析与追问、错因笔记和再练。写译页提供作文及中译英草稿、逐句批改与历史；首页可查看训练次数和个人错因。AI 内容只供学习参考。

原有词汇与学习数据继续保留。词汇、FSRS、TTS、听力和认证架构等后续改进不再受早期产品范围永久冻结，但涉及数据、授权与网络协议的变更必须保证兼容并据实验证。2026-10-18 已有 0.6.0 真机升级、阅读 AI 诊断与两次原创样本真实请求的证据；早期 0.5.x 验证记录属于历史，不代表当前最新构建状态。最新待办与未验证项见 [TODO](docs/TODO.md)。

## G3 历史使用方式

1. 默认进入阅读页，长按输入框粘贴文章；也可从其他应用分享纯文本到「六级研习室」，创建新草稿并保留旧稿。只有链接时需自行补充正文。填写题目和选项，点击「添加题目」，可继续追加并编辑题号。
2. 在「独立作答」按题号填写答案。输入后自动保存草稿；也可手动保存。输入区显示字符数，阅读正文和写译作答还显示约计英文词数；「本次内容已保存到本机」表示已与本地记录核对。点击「提交作答」可完全离线保存。
3. 在连接页登录并加载模型后，点击「保存作答并请求 AI 解析」。请求前先落盘作答；只有完整响应才保存为反馈。失败提示会说明可采取的下一步，未完成片段可展开查看；手动重试前保留旧的完整反馈，不自动连续请求。
4. 完成后记录或修改自己的错因，可追问证据和难句。需要修改作答时点击「重新练习 / 修改一版」，原记录保留；作文和翻译预填原作答，阅读留空以便再练。确认错因后，可在错因难句页点击「AI 生成针对练习」，生成带来源关联的新草稿；不会自动填入答案或算作一次作答。
5. 在「资料作答 / AI 讲解 / 错因难句 / 历史」中切换查看，讲解支持 Markdown 排版与完整文本复制，滚动后可用「训练导航」回到顶部。写译页切换作文和中译英，操作方式相同。历史支持按标题、错因或训练目标检索，可随时离线恢复。
6. 今日页可按确认的错因分类回顾，查看近期记录数，点击「回看错因与针对练习」直接打开原练习。训练入口下方提供数据备份/恢复；旧单词计划与记录可另行展开。JSON v4 含全部 G3 训练、草稿、反馈、难句收藏、错因、来源关联和训练目标，兼容导入 v1/v2/v3；不包含登录凭证。同标识内容冲突时拒绝恢复，保留本机数据。

## 技术栈

- Kotlin 2.0.21 / Android Gradle Plugin 8.7.3 / Gradle 8.9 / Java 字节码目标 17（本机使用现有 JDK 21）
- compileSdk 35、minSdk 26、targetSdk 35
- Jetpack Compose Material 3
- Room 2.7.2（学习记录保存在 SQLite）
- OkHttp + 原生 ServerSocket（OAuth 回调）、Android Keystore AES-GCM（Token 加密）
- 无服务端、无嵌入 API Key、无 ChatGPT Cookie

## 文件结构

```text
app/src/main/java/com/example/cet6/
├── MainActivity.kt                # 入口、分享接收；连接、单词等旧页面
├── ui/AppRoot.kt                  # 写作 / 翻译 / 阅读 / 我的 导航与返回栈
├── ui/WritingWorkspace.kt         # 写作工作台、批注批改、构思提纲
├── ui/TranslationWorkspace.kt     # 翻译工作台、逐句提示与逐句对照
├── ui/ReadingWorkspace.kt         # 答题卡、逐题讲解、分段原文
├── ui/CoachTalk.kt                # 讲解与追问请求、停止、失败重试
├── ui/StudyViewModel.kt           # 页面状态、业务动作
├── ui/CoachScreen.kt              # 自定义阅读练习（「自己的文章」）
├── ui/CoachState.kt               # 自动保存、提交、修改稿、断流重试
├── data/CoachTurns.kt             # 对话记录实体与锚点
├── data/CoachReviews.kt           # 批改 / 讲解协议解析
├── data/WritingCoach.kt 等        # 写作、翻译、阅读提示词
├── data/CoachPractice.kt          # 训练实体、Repository、提示词
├── data/StudyDatabase.kt          # Room v8、兼容迁移和 DAO
├── auth/ChatGptAuth.kt            # PKCE、loopback 回调、token交换/刷新
├── auth/OidcVerifier.kt           # OpenAI JWKS RS256 ID Token 验证
├── auth/SecureSessionStore.kt     # 加密保存单账号凭证
└── ai/ResponsesClient.kt          # 动态模型列表与 Responses SSE
```

## 本地构建

1. 使用 Android SDK Platform 35 和 JDK 17 或 21；本机已复用 `C:\Dev\Env` 中的 JDK 与 SDK。
2. 用 Android Studio 打开含 `settings.gradle.kts` 的 `CET6Study/` 目录。
3. 完整 Gradle Wrapper 已补齐。在 PowerShell 执行 `./gradlew.bat assembleDebug testDebugUnitTest lintDebug`；首次使用需下载已声明依赖。Gradle 发行包固定为 8.9，并校验官方 SHA-256。网络需要代理时，通过命令行传入 Java 的代理参数，不要提交个人代理设置。
4. 用 USB 调试将 `app/build/outputs/apk/debug/app-debug.apk` 安装到手机，或点击 Android Studio 的 Run。需要可正常访问 OpenAI 的网络环境。
5. 在「连接测试」选项卡点击 **Continue with ChatGPT**。系统浏览器完成 loopback 回调后，手动切回 App；本工程没有承诺自动唤醒 Activity。
6. 确认已授予套餐 scope，刷新并选择模型，编辑测试消息，点击「发送一条测试请求」。只有收到 `response.completed` 才算真实调用成功，scope 本身不能证明接入资格或额度可用。
7. 没有 Plus / Pro 等合格套餐、未授权 `chatgpt.tokens.use.direct`，或者应用不符合接入资格，都会造成登录成功但无法使用套餐调用。

**建议先用真机，而非模拟器**，确认浏览器是否能够回调设备自己的 `127.0.0.1`。使用 App 内嵌 WebView 不符合设计。若设备浏览器不支持或系统回收后台进程，会出现回调超时；这时不要尝试复制网页 Cookie 或访问非公开 ChatGPT 内部 API。

## 登录行为与安全

- 新安装使用 `client_id=dynamic_agent_client`、应用名 `CET6 Study`、持久 `ext_agent_host_id`；首次动态注册回调必须返回正式签发的 `client_id`。
- 后续授权重复使用正式 `client_id` 和当前设备的 host ID，且验证 ID token 的身份是否与原账号一致。
- 本机短期 HTTP listener 仅监听 `127.0.0.1` 的临时端口，路径固定 `/auth/callback`，浏览器启动前就绪；验证 `state`、`nonce` 和 PKCE S256。
- 使用 OpenAI JWKS 验证 ID token 的 RS256 签名，并检查 `iss`、`aud`、`exp`、`iat`、`nonce`、`sub`。**此最小原型当前只实现 RS256，若 OpenAI 未来切换算法必须更新实现。**
- Token 经 Android Keystore AES-256-GCM 加密后存放本机，禁止备份、禁止日志输出；没有在工程中硬编码 API Key。
- 请求套餐权限并检查实际授予 scope：`chatgpt.tokens.use.direct`，没有授权时禁止推理。
- 请求 `/v1/models` 返回账号可用 `models[]`，仅展示 `visibility=list`，使用 `slug` 作为 `model`。
- `/v1/responses` **强制** `store=false`、`stream=true`；解析流式 `response.output_text.delta`，直到 `response.completed`。不使用当前不支持的参数。
- Access Token 将要到期时序列化刷新；采用新 refresh token 代替旧的。MVP 只支持**一个账号**。
- 「退出连接」尝试通过官方 discovery 提供的撤销端点结束可续期会话，然后清除本机令牌；保留账号与已签发客户端映射。未确认远端撤销会明确提示，请到 ChatGPT 设置检查授权。

## 验收检查清单

- [ ] Android Studio Gradle Sync 成功
- [x] `assembleDebug` 生成 APK
- [x] 真机「Continue with ChatGPT」系统浏览器授权
- [x] 首次回调返回 issued client ID；`state` 校验通过
- [x] JWKS 验证身份，获得套餐授权 `chatgpt.tokens.use.direct`
- [x] `/v1/models` 返回可选择的模型
- [x] `/v1/responses` 产生 delta 并最终返回 `response.completed`
- [ ] 飞行模式仍能打开词库、阅读练习与历史记录
- [x] 重新启动 App，凭证可从加密本地存储恢复
- [ ] 临近过期时 Token 能够刷新；退出时凭证被清除

## 功能范围

当前实现以本地训练和 AI 学习反馈为主，支持逐题选项、原文证据核对、难句收藏和按确认错因生成练习。训练请求只发送当前练习相关内容，不会上传全部历史。

## 明确限制

- OpenAI 当前官方 DevKit 是 Node.js / React 本地套件，**不是 Android SDK**。本工程 Android loopback 集成未获官方兼容性保证，可按需在真机检查兼容性，不作为开发门槛。
- Sign in with ChatGPT 套餐授权仅对合格应用和用户可用；私人未公开源码项目能否获准不能事先保证。不要使用本项目向其他用户代理或转售 ChatGPT 额度。
- 已完成一次真机登录与套餐调用；真实 token 到期刷新、远端撤销及完整仪器测试仍未完成。**不要把它当作生产环境认证 SDK**。
- 只有文字推理得到支持；不含录音转写、语音合成、图像生成等能力。
- 不提供受版权保护的六级历年真题或第三方商业词库。

## 官方资料（2026-10-16 已查阅）

- https://developers.openai.com/siwc/token-sharing-open-source
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://github.com/openai/sign-in-with-chatgpt-devkit
