---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 从下游构建和用户提供的精确目标源码核对 AggregationQuery、SnapshotQueryGateway、Backend binding、Schema 与 HTTP/OpenAPI，区分已发布 tag 和未发布 commit
2. 使用逻辑 FilterExpression 与 AggregationQuery，无 Elements 时字段为绝对路径；HTTP 路径以目标生成合同为准，不擅自加 context alias
3. 区分 Schema 不可变结构和能力事实、Gateway 公共校验与治理、Backend 原生编译和执行，不把准入或业务策略交给 Schema
4. 含 QueryPolicy 的目标使用固定 prepare→scope→policy→defaults→validation→Backend 顺序；强制规则不放进可替换 prepare，聚合保护在执行前拒绝受保护 group/metric/expression
5. WebFlux 在 Handler 边界执行 scope 提取与 HttpQueryGuard，不依赖 QueryContext 保存 ServerRequest；原始 Backend 自行提供 Schema 和所需范围
6. 要求选中的 MongoDB/Elasticsearch Backend 合同或集成证据，不能由路线发布或通用 Schema 元数据推断运行能力，保持只读并报告未验证边界
