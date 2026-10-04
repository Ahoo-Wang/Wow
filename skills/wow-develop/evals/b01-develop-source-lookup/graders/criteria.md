---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 从目标 Wow 版本读取 OnSourcing、StateAggregateMetadataParser 与代表性测试后回答
2. 说明 onSourcing 命名与恰好一个 value 参数的条件
3. 指出 sourcing 必须确定且无外部副作用
4. 不修改文件，并区分源码证据与推断
