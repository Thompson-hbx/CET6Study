# 六级研习室 CET6Study / Android 技术验证版

> **状态：第一阶段工程验证版，已在 vivo 真机完成真实登录、模型发现与一次 Responses 套餐调用。构建、测试及剩余待测项见 [BUILD_REPORT.md](BUILD_REPORT.md) 与 [PHONE_TEST.md](PHONE_TEST.md)。**
> 目标：在 Android 真机验证 Sign in with ChatGPT（OAuth + 套餐权限）与流式 Responses API。
> 同时提供离线单词学习、原创阅读练习，以及联网作文批改入口。

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
├── MainActivity.kt                # 4个可操作页面
├── ui/StudyViewModel.kt           # 页面状态、业务动作
├── data/StudyDatabase.kt          # Room实体、DAO、样例词汇/复习算法
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

目前：
- 8 个原创演示单词、简单间隔复习算法。**不是正式六级词库。**
- 1 篇原创演示阅读理解、离线判题、历史记录及可选 AI 讲解。
- 作文编辑、AI 批改和最近的本地批改历史；AI 无法代表官方考试评分。

下一步：词库内容授权/导入、实际练习数据结构、错题分类、学习报告、用户可导出的备份与迁移、联网重试、细粒度错误处理、测试覆盖。

## 明确限制

- OpenAI 当前官方 DevKit 是 Node.js / React 本地套件，**不是 Android SDK**。本工程 Android loopback 集成未获官方兼容性保证，必须真机验证。
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
