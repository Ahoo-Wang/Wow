---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 从精确目标源码核对 AggregationQuery、EventStreamQueryGateway、BackendFactory、QueryModel.EVENT_STREAM 与两个后端实现，不能把版本属性视为已发布证明
2. 使用 body 作为事件数组 Element，使用相对 name 聚合，仅对已声明 payload 字段使用 body.body 路径
3. 区分 Schema 事实、Gateway 固定策略和公共准入、Backend 原生编译执行；两个模型都能执行 QueryPolicy，Snapshot ABAC 在 EventStream 上不读取标签
4. 保留 scope/identity、所有 prepare 后的 AND 约束及空或失败 Policy 阻止 Backend；原始 Factory 入口绕过这些治理
5. 核对生成的 EventStream 聚合及 Schema 路由，不发明路径或自动把 HTTP 认证 scope 赋给 JVM 调用
6. 普通查询、Gateway 方法测试或路由生成不能证明真实 Backend 聚合与 HTTP Bean 装配；列出按实际 target 的验证并保持只读
