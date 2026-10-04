---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 报告后续 elements 路径相对前一层，第二个 expand 应为 lines，否则路径会重复拼接
2. 报告默认生成路由不包含 bounded context，正确路径为 /sales-order/snapshot/aggregation
3. 分别给出严重度、影响、触发条件和最小修复方向
4. 不把字段白名单、额外控制器或兼容层作为修复
