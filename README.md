# 六级研习室 CET6Study

G4（0.6.0-paper-ink）默认进入真题目录，主导航为题库 / 训练 / 复盘 / 我的。支持个人结构化内容包导入、纸上做题后按篇内1—5输入五个选项、核对与分层提示，以及写译训练；旧自定义练习、单词与历史仍可访问，TTS 已移除。目录包含 8 个已举行考次、24 个整理套次；**APK 不内置第三方试卷正文**。本机私有资源已下载 46 份原卷/参考答案；个人内容包含 24 条，其中 14 套提供有来源的阅读参考键、写作任务和翻译中文，10 套待校对。已在模拟器通过系统选择器导入；不等于官方认证或全部资料校对完成，原创测试样本不是真题。PDF / Word 本地转换与个人题库备份边界见 [导入说明](docs/EXAM_IMPORT.md)。下文保留 G3 使用与验证历史。

> G3.0：个人本地优先的 AI 六级专项助手。产品基准见 [本地蓝图](docs/product/G3_BLUEPRINT.md)，唯一任务清单见 [TODO](docs/TODO.md)，架构见 [ARCHITECTURE](docs/ARCHITECTURE.md)。一次性产品导入已完成，后续永久不使用 Notion。

阅读页支持粘贴文章、追加题目、按题号独立作答、本地草稿和提交记录、AI 解析与追问、错因笔记和再练。写译页提供作文及中译英草稿、逐句批改与历史；首页可查看训练次数和个人错因。AI 内容只供学习参考。

保留原有词汇学习和数据，但不再扩展词库、FSRS 或 TTS。原 OAuth、Keystore 和 Responses 网络调用实现保持不变。vivo 已运行验证 0.5.0 的阅读、追问、写译批改与针对练习；模拟器最新为 0.5.5，已检查分享、薄弱项回顾、异常保全、长反馈、实际文件备份恢复，以及输入统计与保存恢复，当前进度见 [TODO](docs/TODO.md)。

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
├── MainActivity.kt                # 今日、单词、阅读、写译、连接
├── ui/StudyViewModel.kt           # 页面状态、业务动作
├── ui/CoachScreen.kt              # 阅读、作文、翻译训练及历史
├── ui/CoachState.kt               # 自动保存、提交、断流重试
├── data/CoachPractice.kt          # 训练实体、Repository、提示词
├── data/StudyDatabase.kt          # Room v7、兼容迁移和 DAO
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
