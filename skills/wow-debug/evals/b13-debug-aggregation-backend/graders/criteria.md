---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 先证明 OpenAPI、路由和请求体已到达聚合级 QueryGateway 的 QueryType.AGGREGATION 过滤链
2. 沿 routed SnapshotQueryBackendFactory 与 Gateway 装配时绑定的 Backend 定位 aggregate 实现
3. 用异常、目标版本源码和实际 route binding 证明根因
4. 区分 Gateway 治理、Schema 解析与 Backend 编译执行边界，并在读取下游调用栈或源码后下结论
5. 保持只读，不新增控制器、重试或防御层
