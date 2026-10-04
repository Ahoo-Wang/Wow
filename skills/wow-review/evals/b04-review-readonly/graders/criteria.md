---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 先确认下游服务 origin/main、解析 merge-base 与完整 changed-file 集合
2. 阅读所有应用改动及其直接消费者、测试和配置，并仅将目标 Wow 版本源码作为依赖契约证据
3. 按严重度报告具有文件与行号证据的阻塞 finding，或明确无阻塞 finding
4. 保持工作区和远端状态不变，并报告未运行的检查
