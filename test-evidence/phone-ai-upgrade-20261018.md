# 真机升级与 AI 连接验证（2026-10-18）

- 设备：vivo V2359A，ADB 在线；命令明确指定真机，未对模拟器安装。
- 构建：`gradlew.bat --offline assembleDebug` 返回 BUILD SUCCESSFUL。
- 安装：`adb install -r app/build/outputs/apk/debug/app-debug.apk` 返回 Success；包管理器确认版本从 0.5.0-practice / code 9 升级至 0.6.0-paper-ink / code 15。
- 安装授权：用户已明确允许点击本项目新安装包的安装确认；本次安装没有出现确认弹窗，未修改手机安全设置。
- 启动：MainActivity 成功启动，UI 层次包含题库、训练、复盘、我的及考次目录。
- 数据：升级前后学习数据库快照位于 Git 忽略的 `.tools/phone-ai-before-20261018/` 与 `.tools/phone-ai-after-20261018/`。两份 `PRAGMA integrity_check` 均为 ok；所有既有表逐行逐字段一致：81 词、3 次复习、3 条计划、1 条调度、8 条训练，其余业务表为空。未读取凭证文件。
- AI：升级后“我的”显示已连接。进入现有连接测试页，使用界面所选 GPT-6.1-Sol，发送预置短消息“请只回答：六级研习室 API 测试成功。”，界面返回“六级研习室 API 测试成功。”，状态为“API 测试成功：已收到 response.completed。”。
- 成功判据复核：StudyViewModel.ask 仅在 ai.stream 正常返回后写入上述状态；ResponsesClient 检查收到 response.completed，提前断流会报错。

边界：本轮验证现有 ChatGPT 集成的覆盖升级、启动、学习数据保全和单次真实推理；未验证所有训练场景、令牌到期刷新或异常网络。本轮未新增 AI 协议、依赖或另一手机 App 的对接，未修改原有 UI 工作、提交或推送。数据库快照在真实 AI 测试之前采集，连接测试未要求保存作文或训练记录。
