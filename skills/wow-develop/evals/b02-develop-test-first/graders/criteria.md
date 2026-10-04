---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 先读取下游 Cart 与 CartSpec 并新增两个明确命名的失败场景
2. 保留达到容量后增加已有商品的行为，同时拒绝第 11 种商品
3. 只修改下游 Cart.kt 与 CartSpec.kt 的必要部分
4. 运行聚焦 CartSpec，并报告变更前后证据与准确结果
