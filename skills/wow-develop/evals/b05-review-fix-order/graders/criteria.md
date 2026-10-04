---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 应用 seed patch 后先识别下游 Cart 容量判断的 off-by-one finding
2. 先补能失败的边界回归测试，再修改应用实现
3. 运行聚焦 CartSpec 与 git diff --check，不修改 Wow 框架源码
4. 复审修复后的 diff，并将原 finding 与修复验证分开报告
