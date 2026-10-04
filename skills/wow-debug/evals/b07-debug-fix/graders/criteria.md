---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 先读取下游 OrderSaga、直接测试与目标 Wow 版本的注册契约
2. 用聚焦运行时注册测试或等价变更前证据确认缺少 StatelessSaga 标注
3. 仅为下游 OrderSaga 恢复必要标注，不修改 Wow 框架源码
4. 重跑同一聚焦验证，并报告复现、修复和剩余风险
