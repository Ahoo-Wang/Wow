---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 读取下游 OrderProjector、目标 Wow 版本的处理器注册/调用契约与相关测试
2. 区分重试责任、持久化唯一性或 upsert 与重复投递语义
3. 说明单元测试不能单独证明运行时投递、重试和幂等
4. 不修改文件，并列出最低的运行时或集成证据
