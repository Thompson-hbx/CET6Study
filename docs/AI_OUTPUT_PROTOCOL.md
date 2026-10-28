# AI 结构化输出的后端边界

写译批改、阅读讲解和段落梳理仍使用现有逐行 JSON。`CoachOutputSchema` 在解析已知类型时验证字段；不修改模型原回复，也不自动修正错误字段。

- 整数字段必须是可安全表示的 JSON 整数，不能把字符串、小数或溢出值强转成档位、句号或段号。
- 已提供的已知文本字段必须为字符串；选项判定必须是 `correct` 或 `wrong`，不能将缺失或未知值默认判错。批改级别、句子判定也检查已知枚举。
- 单值结果（如评分、定位和主旨）不能重复；句号、选项和段号不能重复。段落梳理的编号必须存在于本地给定的段落数内。
- 一条已知记录不合格时，整份结果回退原文，不暴露可采纳的修改。数据库仓库也会拒绝该结果的采纳/忽略更新，原回复及已有状态保持不变。

兼容性保持：原有最小可用记录、缺省的可选字段，以及未知扩展类型和字段继续接受。`structured` 表示有可识别的结构化内容，不保证模型输出了提示词要求的全部段落，也不代替引用核验、事实评测或纠错质量评测。底层 JSONObject 兼容语法未改成完整严格 JSON Schema 实现。

回归语料位于 `app/testdata/coach-protocol-regression-v1.json`，含 67 个异常用例、4 个兼容形状和 11 条已获授权生成的真实回复。后者只在离线解析，无新增请求或额度开销。JVM 测试与 Android 仪器测试共用同一语料，避免两个 `org.json` 实现产生未被发现的字段强转差异。

```powershell
.\gradlew.bat --offline testDebugUnitTest --tests com.example.cet6.CoachOutputSchemaTest
adb -s <emulator> shell am instrument --user 0 -w -e scenario coach-output-schema com.example.cet6.test/com.example.cet6.LocalDataInstrumentation
```

完整隔离仪器套件也包含语料和仓库状态保护检查。验证记录见 [backend-output-schema-20261024.json](../test-evidence/backend-output-schema-20261024.json)，当前状态见 [STATUS.md](STATUS.md)。

## 采纳状态的写入保护

批改的采纳/忽略更新在 Room 事务内读取最新状态。`decodeForUpdate` 检查编号类型、有效范围、互斥性、重复键及多余记录；解析失败不会当作空状态写回。异常状态连同原批改原样保留。空状态、空对象和仅有 accepted 字段的旧格式继续支持；普通展示仍使用原解码接口。

有未知字段的状态保留原值并拒绝更新，避免新版本或异常元数据在重写时被丢弃。历史和备份继续保留这些原始状态，恢复或后续维护时可以核对。18 类状态由 JVM 和隔离 Android 仓库检查；另用两个仓库实例同时更新 40 条建议，验证数据库事务本身保持所有决策。

```powershell
.\gradlew.bat --offline testDebugUnitTest --tests com.example.cet6.ReviewDecisionStateTest
adb -s <emulator> shell am instrument --user 0 -w -e scenario coach-decision-state com.example.cet6.test/com.example.cet6.LocalDataInstrumentation
```

证据：[backend-decision-state-20261024.json](../test-evidence/backend-decision-state-20261024.json)。

## HTTP 流式响应的字段边界

`ChatCompletionStream` 不再依赖 JSONObject 的字符串/整数强转：所选回复的文本必须是字符串，选项编号必须是非负 JSON 整数；同一事件不能出现重复选项编号，避免后一段覆盖前一段。错误的增量对象会立即拒绝。正常 reasoning/usage 元数据、空 content 以及旧的结束事件形状继续支持；成功仍要求 stop 后收到 `[DONE]`。

Responses 读流提取为内部 `readResponsesStream`，便于不打开 OAuth 会话就验证真实解析路径；事件类型和文本增量也检查实际字段类型，仍须收到 `response.completed`。未知扩展事件、SSE event 名回退以及缺少 Content-Type 时的创建事件校验保持原规则。失败不当作完整结果返回，不新增网络重试或模型调用。

Android 的 `getString` 会把对象转成字符串，JVM 参考实现对同样输入抛错；已在修复前的模拟器检查中复现前者被接受的问题。两端现在共用 `app/testdata/ai-stream-regression-v1.json`：38 个 Chat 与 33 个 Responses 用例，含 59 个无效/未完成响应和 12 个兼容输出。覆盖错误字段类型、重复编号、结束原因、断流、HTTP 429 和 MIME/创建事件校验。这些是原创离线记录，不代表真实 OAuth 到期或服务商异常已实测。

```powershell
.\gradlew.bat --offline testDebugUnitTest --tests com.example.cet6.AiStreamTest
adb -s <emulator> shell am instrument --user 0 -w -e scenario ai-stream com.example.cet6.test/com.example.cet6.LocalDataInstrumentation
```

证据：[backend-ai-stream-20261024.json](../test-evidence/backend-ai-stream-20261024.json)。
