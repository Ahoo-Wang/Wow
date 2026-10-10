# TODO (JVM)

规则，先读再加：

- 只记**已经决定、但还没做**的事；尚无结论的问题记在「待议」，由维护者定了再移上来。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（哪个文件或文档）。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 兼容性代码（弃用别名、为旧版本保留的行为）不记在这里，记在 [compat-debt.md](compat-debt.md)：v10 删除的 `CommandComponent.Header`、`CommonComponent.Header`、`me.ahoo.wow.openapi.BatchResult` 别名与 state 路由的 `TENANT_ID_ONLY` 命名都在那里。
- TypeScript 视图引擎的 TODO 在 [typescript/wow-view-engine/docs/design/todo.md](../typescript/wow-view-engine/docs/design/todo.md)。

## 下一个补丁

- **自动配置里嵌套的 `@Configuration` 不受外层条件约束**（9.6.1 发布审查，#4075）：宿主的组件扫描覆盖 starter 的包时，`@AutoConfiguration` 类里嵌套的 `@Configuration` 会被扫描注册——早于所有自动配置，而且不经过外层类的 `@Conditional…`。`AutoConfigurationExcludeFilter` 只排除列在 `AutoConfiguration.imports` 里的顶层类。#4075 的「关闭的视图存储路由仍在文档里」就是这样触发的（视图存储的 Customizer 先于 Wow 的注册）；主要影响包名在 `me.ahoo.wow.*` 下的 Wow 自己的测试与服务宿主，应用很少碰到。判据：扫描覆盖 starter 包的宿主里，嵌套配置的 Bean 与只靠自动配置时相同（外层条件不满足时不出现，注册顺序不变），有一个守住这一点的测试（例如一个扫描 `me.ahoo.wow` 的测试宿主断言这些 Bean 的有无与顺序）；ABI 只有 `+` 行。可选做法：嵌套类改为顶层的 package-private 类、由外层 `@Import`；或给嵌套类加与外层相同的条件。落点：`wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/openapi/OpenAPIAutoConfiguration.kt`、`…/bi/BiAutoConfiguration.kt`、`…/query/QuerySchemaCatalogAutoConfiguration.kt`、`view-store/wow-view-store-starter/src/main/kotlin/me/ahoo/wow/viewstore/starter/ViewStoreAutoConfiguration.kt`。

## 以后

- **内联模式渲染完整的内置路由**：`OpenAPIComponentContext.default(inline = true)` 渲染 `DefaultRouteContributors.all()` 时，Schema 生成因 `AggregationExpression` 的循环引用而失败（`INLINE_ALL_SCHEMAS cannot be fulfilled`）；9.6.0 之前就如此，生产代码不开内联。判据：要么内联模式能渲染完整的内置路由（循环引用的类型保留为组件引用），有测试；要么在 `OpenAPIComponentContext.default` 的 KDoc 与 `open-api.md` 写明内联模式不支持含循环引用的内置路由，并删掉这一条。落点：`wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/context/OpenAPIComponentContext.kt`、`wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/schema/OpenAPISchemaBuilder.kt`。
- **组件 Builder 的媒体类型参数名统一**（v10）：`ApiResponseBuilder.content(mediaTypeName = …)` 与 `RequestBodyBuilder.content(name = …)` 参数名不同；改名会破坏以命名实参调用的代码，只能在 v10 做。判据：两者参数名相同，迁移指南写明。落点：`wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/ApiResponseBuilder.kt`、`…/RequestBodyBuilder.kt`。

## 待议

- **`x-wow-query-fields` 只用推断的查询 Schema**：OpenAPI 里聚合查询请求体的字段枚举（`{context}.{aggregate}.{Aggregate}AggregatedFields`）由进程级的静态 `InferredQuerySchemaSource` 推断，不读应用配置的查询 Schema 来源；应用声明了优先级更高的 Schema 时，文档列出的字段可能与运行时能查询的字段不一致。这关系到查询能力的语义（[wow-openapi 重构设计](../documentation/designs/2026-10-09-wow-openapi-refactor-design.md) §6 未处理），由维护者在几个方案中选定后再成为条目：沿用推断并在文档里写明；改为读取运行时的查询 Schema 目录；或两者合并。落点：`wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/component/QueryComponents.kt`。
