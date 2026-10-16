---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 把 `StateAggregateTagsExtractor` 改为状态类上的一个 `@Tags` 成员（无参函数或属性），返回 `Map<String, Collection<String>>`（如 `List`），纯函数：不做 I/O、不 `suspend`、不标 `@Blocking`；去掉 `ReadOnlyStateAggregate` 参数
2. 说明推导出的键按键覆盖 `ApplyTags` 应用的标签，每次提交与加载后重新推导，状态变化替换旧值（不像 9.x 那样合并累积）；函数每次都要返回它拥有的每个键（无值时给空列表），`ApplyTags` 不能设置推导出的键；手工的 `priority` 不由推导函数返回，因此仍由 `ApplyTags` 管理
3. 否定「V10 不能从状态推导标签、改用 Saga 发 `ApplyTags`」的方案，指出它多一个版本、有延迟且不随状态回放
4. `StatusCapable` 可经 V10 的 `wow-models`（同包名）保留，或改为普通属性；状态的 `id` 构造参数可去掉；保留 `onSourcing` 为非 private
5. 以 V10 编译与领域测试（标签随改派变化、`priority` 保留）作为验收，并在目标提交上核实 `@Tags` 的签名；不添加 9.x 兼容接口或别名
