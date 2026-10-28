# AI 批改评测

当前离线评分规则为 v2，原创语料也有独立版本。规则版本与语料版本不是同一件事；结果写入 scoring_version、dataset_version 和金标内容哈希。旧金标、完整回复及原始分数保留，未事后按模型答案改高金标成绩。

## 语料与授权

- `app/testdata/coach-evaluation-v1.json`：11 个短用例，已经用户批准各请求一次。DeepSeek/deepseek-flash 11/11 完整返回，没有自动重试。这轮授权已用完。
- `app/testdata/coach-evaluation-v2.json`：12 个新原创用例，包含 4 篇 173—186 词完整作文、4 组至少四句的翻译，以及 4 篇约 277—294 词阅读；共 10 个预设语言/意义错误、2 个正确文本对照，含重复引文和长文证据定位。2026-10-28 获得新的十二次授权后，已各请求一次并全部完整返回；本次授权已用完。

新语料的金标是作者初稿，尚未经独立评分者校准，不是正式六级试卷或已验证的标准能力测验。语法、翻译意义和阅读选项须经人工复核；整体论证质量、自然表达和未列入的合理改法继续需要人工判断。后续追加真实请求需要另行授权；仪器测试已增加 v2 十二例入口和零真实请求的离线入口，v1 入口继续保留以兼容历史流程，但旧授权已用完，不能直接再次运行。

## 离线计分

```powershell
# 新规则 + 新语料；predictions.json 必须覆盖全部 12 个 caseId，包括失败项
python tools/evaluate_coach.py predictions.json --gold app/testdata/coach-evaluation-v2.json
# 精确重现历史旧规则
python tools/evaluate_coach.py test-evidence/coach-evaluation-deepseek-flash-20261024.json --scoring-version 1
python -m unittest discover -s tools -p 'test_coach_evaluation*.py'
```

输入仍为 `[{"caseId":"...","response":"完整原始回复"}, ...]`，请求失败可带 error 字段，不省略该例。CLI 默认规则 v2，原 Python evaluate 接口和显式 `--scoring-version 1` 保留旧行为。格式失败与请求失败分开计数；混合损坏 JSON、错误字段类型、重复键/选项不会拿部分正确内容计为成功。金标偏移先与源文核对，空分母返回 null。

新版将几种含义分开：

| 指标/记录 | 可支持的判断 |
| --- | --- |
| quote_accuracy | 非空引文是否逐字出现在对应源文，不验证证据是否支持结论 |
| exact_gold_span_match_rate / miss_rate | 引文与预设错误区间的严格匹配/未匹配比例，不等同语义精确率或真实漏改率 |
| applicable_gold_span_match_rate / miss_rate | 非重叠分配后可用于修改的严格区间匹配，单独反映整段与局部建议的冲突 |
| enumerated_fix_match_rate / gold_fix_coverage | 可定位且有变化的修改是否匹配列出的修正；未列出的改法标为需复核，不直接判为错误 |
| required / optional / no_ops | 分开记录必改、可选润色和 quote 与 fix 相同的建议 |
| reading_answer_accuracy / full_options_reading_accuracy | 正确选项是否一致，以及是否对全部选项作出唯一判定 |
| evidence_span_coverage | 逐字引文是否覆盖预设证据区间，不替代逻辑蕴含判断 |
| clean_control_success | 正确文本对照获得有效记录且未被声称需要必改；仍受金标校准程度限制 |

重复的相同引文按出现顺序一一分配；位置有歧义或局部/整段修改重叠时保留复核记录。引文的字面命中与修改时的非重叠放置分开，避免把被整段建议遮住的局部诊断当作没有提及。评分器只验证它消费的字段，App 全协议校验另由 JVM/Android 语料测试负责。

## 回归与历史可比性

2026-10-25 先复现 5 个评分缺口，再通过 28 项 Python 工具检查，其中 22 项属于评分器。受控满匹配输入只是评分器测试，不能写成模型能力通过。已批准的 CI 评测步骤现包含两版评分器测试，远端仍未运行。

对已有 11 条回复进行了离线 v2 重算，文件为 `test-evidence/coach-evaluation-deepseek-flash-20261024.metrics-v2.json`。相同的 6 个预设错误均有严格字面匹配，但受重叠建议影响，只有 4 个能分配到非重叠修改区间；列举修正匹配仍为 4/7，另记录到 1 条无变化建议。它们是新规则下对原回复的解释，不是一次新的模型评测或质量提升。原 v1 金标哈希与旧分数均逐项核对一致。

证据：[backend-evaluation-v2-20261025.json](../test-evidence/backend-evaluation-v2-20261025.json)。

## 2026-10-24 首轮实测

原始提示/完整回复、服务元数据、严格指标均保存在 `test-evidence/coach-evaluation-deepseek-flash-20261024*.json`。金标与提示不包含私人学习记录；阅读提示没有提供金标答案。

| 指标 | 本次严格结果 |
| --- | --- |
| 逐字引用 | 17 / 18（94.4%） |
| 金标错误定位精确率 | 6 / 14（42.9%） |
| 六个预设错误遗漏率 | 0 / 6 |
| 已定位不重叠修改中的金标修正匹配 | 4 / 7（57.1%） |
| 阅读选项一致率 | 4 / 4 |
| 完整回复 / 格式失败 | 11 / 0 |

这些数值是严格规则下的首次基线。写作样本都是短句，产品按完整作文批改，会正确提醒字数/结构不足；这些整段建议及可选润色未在局部语法金标中列出，故定位精确率不能解释为“其他建议全部错误”。第一例模型将 `It provide` 改成指代复数 libraries 的 `They provide`，语言上合理，但未列入原金标（仅列 `It provides`）。本轮保留原金标和原始分数，不根据模型输出事后提高成绩。整段问题抢占局部引文的定位范围也使采纳模拟分母受影响。

唯一逐字不匹配引用是把句首 `The number of readers` 写成小写 `the number of readers`；界面的宽松定位可识别，逐字指标仍按失败计。模型还输出了一条 quote 与 fix 相同的可选建议，说明“协议解析成功”不能替代学习价值核验。下一版须用完整作文、区分必改/可选建议、经过人工校准的替代修正与重复/重叠引文处理，再开展新的对照；本次授权的 11 次已全部用完，未额外请求。

## 2026-10-28 运行器收尾验证

v2 运行器已通过 Android 构建、Lint、模拟器专项和默认完整隔离套件。31 项 Python 检查通过，覆盖未完成 checkpoint 与矛盾状态拒绝计分。此次 Gradle 任务大多为 UP-TO-DATE；现有 JVM 报告为 149 项、零失败，Lint 为 0 错误 / 7 个既有警告，不将缓存命中写成重新执行全部单测。

安装普通调试包及测试包后，可在已确认的模拟器上复跑零真实请求专项：

```powershell
adb -s emulator-5554 shell am instrument --user 0 -w -e scenario coach-evaluation-offline com.example.cet6.test/com.example.cet6.LocalDataInstrumentation
```

专项只使用假请求，不创建 Activity 或 AI 客户端，不修改学习记录；会在应用 cache 下写入原创提示预览 `evaluation-v2-prompts.json` 和随机名称的离线 checkpoint。它验证十二例提示、金标隔离、失败/超时/超长回复、取消、请求前后写入失败及相同目录禁止重放。默认完整隔离套件也包含这些检查。

真实入口为 `scenario=coach-evaluation-v2`，需要另行批准十二个原创请求，再传入匹配的 `consent=12-full-original-cases` 和 UUID `run-id`。命令参数只是运行防误触条件，不代替用户授权。语料 SHA-256 固定；修改语料后须重新审核范围。输出位于应用外部文件目录 `evaluation-v2-<run-id>/`，含元数据和逐例 predictions。请求前先持久化 `in-flight`，完成或失败后再落盘；落盘失败会终止批次，相同目录不能重放。失败不自动重试，取消后保留已收到的诊断片段。

`in-flight` 表示结果不确定，不得视为未发送。中断批次可能不足十二例，评分器会拒绝不完整或未确认结果；须人工检查，不能换一个 UUID 自动补跑。防重放限于同设备、同输出目录，删除目录或换运行 ID 不受该检查保护。

此次无真实模型请求、无手机安装或学习数据清除，金标仍是未经独立校准的初稿。证据：[backend-evaluation-runner-20261028.json](../test-evidence/backend-evaluation-runner-20261028.json)。

## 2026-10-28 十二例真实评测完成

用户另行批准最多十二次请求；DEEPSEEK / deepseek-flash 十二例各执行一次，无重试。首次准备在批次创建前被模型列表加载的 busy 状态拦下，零模型请求；修复为有超时且可取消的 Compose 状态等待后，沿用同一运行 ID 完成。离线检查覆盖等待就绪与取消，新增十二条真实回复的 JVM/Android 兼容语料后共 94 例通过。

固定评分为：引文 31/31、阅读 4/4、正确对照 2/2，金标严格区间覆盖 1/10；长短引文差异和空引文漏译使严格定位指标不能解释为语义漏改率。逐例复核观察到全部十个预设错误被指出，但可选润色中有过度概括，不能宣称整体准确率 100%。原始回复、未修改的严格分数与意义复核见 [本轮结果](AI_EVALUATION_RESULTS_20261028.md)。固定语料中的 provisional/no-live 元数据保留为生成时状态，不改字节以维持运行授权哈希；最新运行事实以结果文件为准。
