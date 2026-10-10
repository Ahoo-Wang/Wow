# TODO (JVM)

规则，先读再加：

- 只记**已经决定、但还没做**的事；尚无结论的问题记在「待议」，由维护者定了再移上来。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（哪个文件或文档）。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 兼容性代码（弃用别名、为旧版本保留的行为）不记在这里，记在 [compat-debt.md](compat-debt.md)：v10 删除的 `CommandComponent.Header`、`CommonComponent.Header`、`me.ahoo.wow.openapi.BatchResult` 别名与 state 路由的 `TENANT_ID_ONLY` 命名都在那里。
- TypeScript 视图引擎的 TODO 在 [typescript/wow-view-engine/docs/design/todo.md](../typescript/wow-view-engine/docs/design/todo.md)。

## 下一个补丁

## 以后

- **组件 Builder 的媒体类型参数名统一**（v10）：`ApiResponseBuilder.content(mediaTypeName = …)` 与 `RequestBodyBuilder.content(name = …)` 参数名不同；改名会破坏以命名实参调用的代码，只能在 v10 做。判据：两者参数名相同，迁移指南写明。落点：`wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/ApiResponseBuilder.kt`、`…/RequestBodyBuilder.kt`。

## 待议

- **`x-wow-query-fields` 只用推断的查询 Schema**：OpenAPI 里聚合查询请求体的字段枚举（`{context}.{aggregate}.{Aggregate}AggregatedFields`）由进程级的静态 `InferredQuerySchemaSource` 推断，不读应用配置的查询 Schema 来源；应用声明了优先级更高的 Schema 时，文档列出的字段可能与运行时能查询的字段不一致。这关系到查询能力的语义（[wow-openapi 重构设计](../documentation/designs/2026-10-09-wow-openapi-refactor-design.md) §6 未处理），由维护者在几个方案中选定后再成为条目：沿用推断并在文档里写明；改为读取运行时的查询 Schema 目录；或两者合并。落点：`wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/component/QueryComponents.kt`。
