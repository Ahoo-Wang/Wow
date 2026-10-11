# TODO (JVM)

规则，先读再加：

- 只记**已经决定、但还没做**的事；尚无结论的问题记在「待议」，由维护者定了再移上来。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（哪个文件或文档）。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 兼容性代码（弃用别名、为旧版本保留的行为）不记在这里，记在 [compat-debt.md](compat-debt.md)，例如 state 路由的 `TENANT_ID_ONLY` 命名。
- TypeScript 视图引擎的 TODO 在 [typescript/wow-view-engine/docs/design/todo.md](../typescript/wow-view-engine/docs/design/todo.md)；wow-client、wow-react、wow-generator 的在 [typescript/TODO.md](../typescript/TODO.md)。

## 下一个补丁

## 以后

- **组件 Builder 的媒体类型参数名统一**（v10）：`ApiResponseBuilder.content(mediaTypeName = …)` 与 `RequestBodyBuilder.content(name = …)` 参数名不同；改名会破坏以命名实参调用的代码，只能在 v10 做。判据：两者参数名相同，迁移指南写明。落点：`wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/ApiResponseBuilder.kt`、`…/RequestBodyBuilder.kt`。
- **命令等待超时说出等的是哪个阶段**：等待阶段在时限内未到达时，`RequestTimeout` 的 `errorMsg` 是 Reactor 的内部文字（`Did not observe any item or terminal signal within …`），用户看不出原因（9.2.0 首发前第二轮审查 X-4：示例购物车没有投影器，等 `PROJECTED` 必然超时）。判据：超时的 `errorMsg` 写明等待的阶段与时限，例如「等待阶段 PROJECTED 在 10000ms 内未到达」；错误码与 HTTP 状态不变；有测试。落点：`wow-core/src/main/kotlin/me/ahoo/wow/command/WaitDeadline.kt`。
- **数组路径分组被拒时指向 `elements`**：对数组路径做 `TERMS` 分组（`state.items.productId`）时，报错只说字段需要声明的元素作用域，不说怎么写（第二轮审查 X-5）。判据：`QueryViolation.ElementScopeRequired` 的文案指出用聚合的 `elements` 展开该数组再分组；`QueryRestErrorContractTest` 的黄金文案随之更新并写进发布说明。落点：`wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryViolation.kt`。

## 待议

