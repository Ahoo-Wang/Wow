---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 报告重新校验只刷新接收请求实例的内存 Schema cache，不能证明其他副本已刷新
2. 报告 refresh 不修改 Elasticsearch mapping 或历史文档，模板变化后的历史数据仍可能需要显式迁移或 reindex
3. 要求在每个实例上调用 wowQuerySchema 并单独授权，或等待定期重新校验，记录每个目标实例的结果且保留失败时旧 cache 的语义
4. 把运行时 Schema、后端 mapping、历史数据和部署协调分为独立证据，不用单次 2xx 代替
5. 保持只读并给出最小修复方向
