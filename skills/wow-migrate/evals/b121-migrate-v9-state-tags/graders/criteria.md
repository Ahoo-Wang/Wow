---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 把 `StateAggregateTagsExtractor` 改为状态上的访问属性：`region` 与 `assignee` 两个属性标 `@Access`（或无参函数），纯函数：不做 I/O、不 `suspend`、不标 `@Blocking`；去掉该接口与 `ReadOnlyStateAggregate` 参数；说明 V10 没有 `@Tags`、`ApplyTags`
2. 说明访问属性在每次提交与加载后从当前状态重新推导，改派即替换旧值（不像 9.x 那样把推导结果合并进保存的标签）
3. 手工的 `priority` 是数据：改为状态字段 `@Access` 的 `priority`，由工单自己的领域命令与事件设置（如 `SetTicketPriority` → `TicketPrioritySet`），由领域校验、日志记录；数据迁移时在 migration-map.json 里把 9.x 打标签事件以 `"tags": true` 映射到该事件，`priority` 为 `APPLIED`，`region`、`assignee` 为 `DERIVED`
4. 否定「V10 不能从状态推导、改用 Saga 发打标签命令」的方案，指出它多一个版本、有延迟且不随状态回放
5. 把按标签过滤的查询策略改写为访问策略（`accessPolicy { read { … }; write { … } }`，如 `TicketStateAccess.REGION isIn caller.claims("region")`），并指出 9.x 默认放行的情形（调用者没有该属性、工单缺这个键）在 V10 默认拒绝，需要用户决定
6. `StatusCapable` 可经 V10 的 `wow-models`（同包名）保留，或改为普通属性；状态的 `id` 构造参数可去掉；保留 `onSourcing` 为非 private
7. 以 V10 编译与领域测试（`expectAccess`：属性随改派变化、`priority` 由其命令设置）作为验收，并在目标提交上核实 `@Access` 与 `accessPolicy` 的签名；不添加 9.x 兼容接口或别名
