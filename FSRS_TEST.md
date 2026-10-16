# FSRS 接入验证记录 — 0.3.0-fsrs

日期：2026-10-16（Asia/Shanghai）。**状态：工程验证通过，新版真机安装被系统拒绝，真机验收未完成。**

## 构建与源码

- 版本 `0.3.0-fsrs` / versionCode 4，Room 4。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，16,652,236 字节。
- SHA-256：`42AABFE9F0793070E6B16D012BD3AF420BBF9AB6C422CED4D709767DA35C9E1E`。
- Windows / PowerShell、项目 Gradle 8.9 / JDK 21，未切换环境。
- `gradlew.bat --offline assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug --console=plain`：BUILD SUCCESSFUL（11s）；清理许可文件末尾空行后重新 assembleDebug 成功（1s），上方哈希为最终 APK。
- 33 项 JVM 测试通过，0 失败；Lint 0 errors / 7 原有 warnings；APK v2 签名及 16 KiB 对齐通过。
- 未新增 Maven 依赖或安装 Python 包。MIT Kotlin 源固定版本拷入；许可与原始/修改后哈希可查，见 third_party/bee-fsrs/README.md。
- auth/ 和 ai/ 相对 `0c97a55` 无差异。Manifest 仅增加 Android TTS 服务可见性查询，未更改认证、网络权限或备份排除规则。

## 算法验证

见 FSRS_AUDIT.md：优先候选存在数学/耦合问题，未直接采用。复用 bee-fsrs FSRS-6 引擎并对照其标注的官方 py-fsrs v6.3.1 修正差异，不实现 FSRS-7 或优化器。

- 38 条引擎 fixture 用例：31 条结果由运行固定官方 Python 源计算，7 条为 Kotlin 参数校验；全部通过。
- 30 次真实官方 Scheduler.review_card 学习/重学转换：三种首评分、短时步骤、进入长期复习、跨日、模糊、遗忘、重新学习；与 App wrapper 的 dueAt、stability、difficulty、phase、step 一致。
- 七天纯函数模拟通过，复制并恢复 state 后持续安排未来 dueAt；不是七天真实设备使用。
- 反向时钟与未知算法身份拒绝；旧词首次不认识转为 1 分钟重学，内容保留。
- 原简易调度实现已移除，生产写入仅走 FSRS wrapper + Repository 事务。

重跑 oracle：`python tools/generate_fsrs_oracle.py`。该命令读取公共固定源码，校验 scheduler Git blob，不读账号、token 或学习数据库；类型别名 shim 不改变上游算法。

## 数据兼容证据

- `python tools/check_schema_migrations.py`：实际 Kotlin 迁移 SQL 在主机 SQLite 上执行，1→4、2→4、3→4 均通过；列/类型/主键与 Room 4 schema 一致，旧数据行保留，没有虚构 FSRS memory。
- 新旧 words、reading_attempts、essays、daily_plans 的 Room schema 对比一致。
- 新增 word_schedules；word_reviews 增加默认 legacy/0 的评分与算法字段，旧布尔日志不冒充三档评分。
- 每日完成量按不同单词计数，逐次作答日志完整保留；短时任务不受每日目标数量屏蔽。
- 真机安装前停止应用，只复制学习数据库及 WAL/SHM 到忽略目录 `.tools/fsrs-before/`。快照 SQLite integrity_check=ok；version=3、words=81、word_reviews=1、daily_plans=1。未读取、复制或修改凭证。
- Android 仪器测试已编译：覆盖全部历史 Room schema 升级、数据保留、无虚构 memory、事务去重、状态重开、导入等；**新版尚未执行成功，不能算通过**。

## 真机安装结果与待测

ADB 在主包和测试包覆盖安装时均返回：

```text
INSTALL_FAILED_ABORTED: User rejected permissions
```

随后的仪器测试打印的是旧版 v1→v3 PASS，已排除，不能用作 FSRS 新版证据。没有卸载、清数据、修改安装权限或绕过系统拒绝。已请求用户确认手机端安装提示后再继续。

待测：

- [ ] 系统允许覆盖安装，确认 versionCode=4。
- [ ] 新版 Android Room 仪器测试实际通过。
- [ ] 主机快照与手机升级后数据逐项核对，原 dueAt/日志保留。
- [ ] 三档卡片、分钟到期、重启后状态、同日完成量去重，飞行模式下完整操作。
- [ ] 离线英文 voice 可用时的朗读及听测；不可用时明确降级，后台停止播放。
- [ ] 音标、许可弹窗与大字体页面检查。
- [ ] 连续七天真实使用；阅读联动、作文/翻译、完整备份/恢复及正式交付仍未完成。

## 词汇内容

CMUdict 许可与固定来源见 CONTENT_SOURCES.md。80 条唯一宽式美式音标及多读音数据校验通过，未引入商业词典释义或例句。用户自行导入的词若不在此子集中，仍可使用系统发音；当前不提供完整商业词典级音标覆盖。

## 后续真机命令

仅在用户允许手机端安装后执行，所有操作指定 vivo 当前用户 0：

```powershell
& 'C:\Dev\Env\Android\Sdk\platform-tools\adb.exe' -s '10AE5X1ZA3000YU' install -r '.\app\build\outputs\apk\debug\app-debug.apk'
# 检查 Success，失败立即停止，不能继续把旧版测试结果算为新版通过。
& 'C:\Dev\Env\Android\Sdk\platform-tools\adb.exe' -s '10AE5X1ZA3000YU' install -r '.\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
& 'C:\Dev\Env\Android\Sdk\platform-tools\adb.exe' -s '10AE5X1ZA3000YU' shell am instrument --user 0 -w com.example.cet6.test/com.example.cet6.LocalDataInstrumentation
```
