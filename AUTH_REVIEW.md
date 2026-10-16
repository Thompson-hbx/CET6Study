# 第一阶段认证审阅（2026-10-16）

## 已核对的事实

- 当前官方开源客户端流程使用动态注册、PKCE S256 和 `http://127.0.0.1:<port>/auth/callback`；不能擅自替换为 Android 自定义 scheme。现有代码在打开系统浏览器前绑定 loopback，授权与换码使用同一 URI。
- 官方 OIDC discovery 实际返回的 issuer、授权端点、token 端点和 JWKS URI 与现有代码一致；签名算法列表为 RS256。
- ID token 已执行签名、issuer、audience、有效期、nonce 和 sub 校验；随后已在 vivo 真机完成该验证链路，见 PHONE_TEST.md。
- 凭证以 Android Keystore AES-GCM 加密保存在私有 SharedPreferences；应用关闭备份，未发现硬编码密钥。真机覆盖安装及冷启动后恢复成功；密钥失效和设备迁移行为仍未实测。
- 模型发现使用套餐路由 `models[]`，过滤 `visibility=list`，显示 `display_name`、提交 `slug`；此结构不能与 API Key 路由的 `data[]` 混用。
- Responses 请求的 `input` 为数组，使用 `instructions`、`store=false`、`stream=true`，未携带文档列出的不支持参数。

## 已批准并实施的认证改动

1. 本地退出保留已验证的账号与 issued client ID 映射、设备 host ID，清除访问/刷新/身份令牌。修复前会删除整个 session，导致下次错误地走首次动态注册。
2. 增加“退出并尝试撤销”：从官方 discovery 获取 revocation endpoint，提交刷新令牌；临时故障有限重试，未确认撤销时明确提示并清除本地令牌。不把本地退出描述成远端撤销。
3. 刷新响应必须包含有效 access token、Bearer 类型、有效期及轮换 refresh token；成功后原子保存。按官方终止性错误码清除失效令牌并保留注册映射；暂时性网络故障保留凭证。
4. loopback listener 忽略无关路径和不匹配 state 的请求，使用总超时，避免浏览器探测请求提前结束授权；限制回调请求大小。
5. OIDC 多 audience 时校验 azp，控制令牌响应字段，避免未验证的新身份令牌覆盖已验证身份。

上述改动已获用户明确批准并实施；增加了回调真实 loopback socket 测试和刷新策略测试。签名算法仍保持 RS256，刷新后仍保留先前已验证的 ID token 作为提示，不把未经验证的新 ID token 用于身份更新。认证 HTTP 客户端禁止跟随重定向，并限制单次调用时间为 20 秒。补充 Android 12+ 的 dataExtractionRules，将凭证和 host ID 明确排除云备份及设备迁移；旧系统继续禁用备份。

单元测试覆盖回调协议和令牌策略；另已完成真机授权、Keystore 保存恢复、模型目录及一次推理请求。实际令牌到期轮换或远端撤销仍未实测。

## 边界与替代方案

- 官方回调协议有依据，但没有查到 Android 专用 SDK 或 Android 生命周期兼容保证。浏览器导航到 loopback 后仍可能需要手动切回 App；进程被回收后需要重新授权。不能承诺自动唤醒 Activity。
- 当前仅支持单个账号，未达到官方多账号管理建议；本阶段保留为明确的 MVP 限制。
- 本次真机账号已完成授权、模型发现及真实 `response.completed`；仅能确认本次请求可用，不推断其他账号、地区或后续额度资格。仅收到 scope 仍不代表调用成功。
- 如套餐路由拒绝该集成，可选：申请官方支持的接入资格，或另行批准开发以自有后端保管 API Key 的标准 Responses API 方案。Android APK 中不嵌入开发者 API Key，也不使用 ChatGPT 内部 backend-api 或借用其他应用 client ID。

## 官方依据

- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://developers.openai.com/siwc/token-sharing-open-source/token-reference
- https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
- https://auth.openai.com/.well-known/openid-configuration
- https://developer.android.com/identity/data/autobackup
