# TODO (JVM)

规则，先读再加：

- 只记**已经决定、但还没做**的事；尚无结论的问题记在「待议」，由维护者定了再移上来。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（哪个文件或文档）。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 兼容性代码（弃用别名、为旧版本保留的行为）不记在这里，记在 [compat-debt.md](compat-debt.md)：v10 删除的 `CommandComponent.Header`、`CommonComponent.Header`、`me.ahoo.wow.openapi.BatchResult` 别名与 state 路由的 `TENANT_ID_ONLY` 命名都在那里。
- TypeScript 视图引擎的 TODO 在 [typescript/wow-view-engine/docs/design/todo.md](../typescript/wow-view-engine/docs/design/todo.md)。

## 下一个补丁

## 以后

- **内联模式渲染完整的内置路由**：`OpenAPIComponentContext.default(inline = true)` 渲染 `DefaultRouteContributors.all()` 时，Schema 生成因 `AggregationExpression` 的循环引用而失败（`INLINE_ALL_SCHEMAS cannot be fulfilled`）；9.6.0 之前就如此，生产代码不开内联。判据：要么内联模式能渲染完整的内置路由（循环引用的类型保留为组件引用），有测试；要么在 `OpenAPIComponentContext.default` 的 KDoc 与 `open-api.md` 写明内联模式不支持含循环引用的内置路由，并删掉这一条。落点：`wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/context/OpenAPIComponentContext.kt`、`wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/schema/OpenAPISchemaBuilder.kt`。
- **组件 Builder 的媒体类型参数名统一**（v10）：`ApiResponseBuilder.content(mediaTypeName = …)` 与 `RequestBodyBuilder.content(name = …)` 参数名不同；改名会破坏以命名实参调用的代码，只能在 v10 做。判据：两者参数名相同，迁移指南写明。落点：`wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/ApiResponseBuilder.kt`、`…/RequestBodyBuilder.kt`。

## 待议

