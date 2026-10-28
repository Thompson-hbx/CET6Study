# 现有版本真实 AI 验收（2026-10-28）

本轮使用手机原有 DEEPSEEK / deepseek-flash，用户明确批准最多十二次原创请求。运行 ID 为 `b589bb2c-9d70-4c8d-8f7d-72dcef60fb8e`。最初启动在进入批次前被模型加载中的 busy 检查拒绝，未创建 checkpoint、未调用模型；修复启动就绪等待后沿用同一 ID，十二例各请求一次，全部完整返回，无自动重试。授权额度已用完。

原始回复与提示：[JSON](../test-evidence/coach-evaluation-v2-20261028.json)；服务和语料哈希：[元数据](../test-evidence/coach-evaluation-v2-20261028.metadata.json)；固定规则的原始分数：[metrics](../test-evidence/coach-evaluation-v2-20261028.metrics.json)。未查看回复正文前的文本复核见 [金标二次复核](AI_EVALUATION_REVIEW_20261028.md)。金标和评分规则保持原样。

| 自动指标 | 结果 | 含义 |
| --- | --- | --- |
| 完整回复 / 评分器格式有效 | 12/12 | 不代表全部语义正确 |
| 非空引文逐字匹配 | 31/31 | 空引文漏译建议不计入该分母 |
| 阅读答案 / 全选项判定 | 4/4、16/16 | 仅这四个原创问题 |
| 正确文本对照无必改建议 | 2/2 | 仍有可选润色 |
| 金标错误区间严格覆盖 | 1/10 | 区间须完全相同；不能解释为真实漏改九处 |
| 列举修正匹配 | 1/27 | 分母是可定位、非重叠且有变化的全部建议，含可选润色 |
| 指定阅读证据区间覆盖 | 3/4 | 第一例引用了同段的前一句，未覆盖金标指定的后一整句 |

## 逐例意义复核

以下是开发助手的文本复核，独立保留，不回填自动分数，不代替盲评或人工评分校准。

| 用例 | 实际观察 |
| --- | --- |
| essay-library-basic | 两处 provides 和一处 can study 均作为必改指出并修正；模型引用了较长上下文。3/3 预设问题检出。 |
| essay-group-basic | Each student has、easier 均作为必改指出。2/2；其余五条是可选建议。 |
| essay-feedback-intermediate | depends on、provide useful information 修正了预设错误。2/2；额外建议中对冠词、限定词和搭配的理由过于绝对，不能当作可靠语法规则。 |
| essay-public-space-strong | 无必改。将 reliable choices 改为 consistent choices 等建议属表达偏好，不认定为质量提升。 |
| translation-community-omission | 第五句 segment 标为 missing，另给空 quote 的必改补译。1/1 意义错误检出；这种补译没有原文位置，现有采纳机制不会自动插入，需用户写入修改稿。 |
| translation-workshop-negation | Every participant → Not every participant，部分否定得到恢复。1/1。 |
| translation-route-number | three percent → 30 percent，数量错误得到修正。1/1。 |
| translation-bridge-control | 无必改；两条可选用词建议不代表原译错误。 |
| reading-pilot-scope | B，解释保留有限安排并继续收集反馈；引文为金标前一句，严格证据覆盖未得分。 |
| reading-correlation-long | C，区分相关与因果，逐项判定与原文一致。 |
| reading-price-contrast | A，正确区分两条本地线路和机场快线。 |
| reading-retention-policy | D，正确保留受访者的访问与出版许可限制。 |

结论：在这些原创用例中可观察到十个预设错误均被指出、四道阅读答案正确、两个正确对照未被标成必改；但不能由此宣称总体准确率为 100%。可选润色理由有过度概括，严格区间指标对长短引文和空引文漏译不适合作语义准确率，金标也未经独立人工校准。保留现有“模拟档位”和用户逐条决定的产品边界，不新增自动采纳。
