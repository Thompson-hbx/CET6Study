# v2 原创评测语料二次复核（2026-10-28）

本次按题目、原文和现有金标逐例复核，未查看本轮模型回复正文，未修改固定语料、偏移或可接受改法列表。复核者为当前开发助手；这不是盲评、独立人工校准或官方六级评分。

| 用例 | 复核结果与范围 |
| --- | --- |
| essay-library-basic | 两处 `The library provide` 均有主谓一致错误；`can studies` 应使用动词原形。两处重复引文须分别定位。 |
| essay-group-basic | `Each student have` 应为 `has`；`more easier` 重复比较级，可改为 `easier` 或 `much easier`。 |
| essay-feedback-intermediate | `depends of` 应为 `depends on`；`a useful informations` 的冠词及不可数名词用法错误。`a useful piece of information` 等合理改法未穷尽，不能据严格字面未匹配判为错误建议。 |
| essay-public-space-strong | 题意、字数和主要语法未发现预设必改错误；允许可选润色，不应把偏好表达算作必改。不能由此确认整体作文评分。 |
| translation-community-omission | 确实漏译“为第一次到访的老年人提供免费使用指导”。金标把补译附加在末句，是人为定位锚点；模型通过独立漏译记录或新句指出时，严格 span 指标可能漏计，必须单独作意义复核。 |
| translation-workshop-negation | “并非每位”不能译为 `Every participant`；`Not every` / `Not all` 能保留部分否定。 |
| translation-route-number | 百分之三十被误译为百分之三；`thirty percent` / `30 percent` 正确。 |
| translation-bridge-control | 四句的主要信息和否定范围均有对应，未发现预设意义遗漏；合理表达差异不自动视为错误。 |
| reading-pilot-scope | B。继续两个晚间的有限安排并收集反馈；并未全馆开放，也未证明成绩提高。 |
| reading-correlation-long | C。访问频率与成绩相关，原文明确未作因果推断。 |
| reading-price-contrast | A。覆盖两条本地线路，不包括机场快线，也不保证座位。 |
| reading-retention-policy | D。展览结束后仍遵循受访者原有访问与出版许可。 |

十个预设语言/意义错误、两个正确文本对照与四道阅读参考键通过本轮文本复核。仍需独立评分者确认替代改法、必改/可选边界及漏译定位规则。后续报告同时保留固定金标的严格机器分数和逐例意义核对，不能把人工解释回填为原始分数。此文件不改变运行器固定 SHA-256。
