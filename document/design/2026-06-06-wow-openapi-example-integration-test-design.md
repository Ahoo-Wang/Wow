# wow-openapi Example Domain Integration Test Design

## 目标

引入 `example-domain` 和 `example-api` 依赖，补充真实场景的集成测试，验证完整的 OpenAPI 文档生成流程。

## 依赖变更

`wow-openapi/build.gradle.kts` 新增：

```kotlin
testImplementation(project(":example-domain"))
testImplementation(project(":example-api"))
```

## 测试文件

- 文件：`wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt`
- 可见性：`internal`
- 组织方式：单文件 + `@Nested` 内部分组
- `@BeforeEach`：一次 `RouterSpecs.build()` + `mergeOpenAPI()`，共享 `OpenAPI` 对象

## 测试数据

`example-domain` 提供的真实聚合：

| 聚合 | 特点 | 命令数 | 事件数 |
|------|------|--------|--------|
| Cart | `owner=AGGREGATE_ID`, `@StaticTenantId`, `@Tag("customer")` | 6 | 3 |
| Order | `resourceName="sales-order"`, `spaced=true`, `owner=ALWAYS` | 5 | 5 |
| DisabledRouteAggregate | `enabled=false`，被过滤 | 0 | 0 |

## 测试场景（19 个用例）

### 2.1 RouterSpecs Build（4 个）

| # | 测试方法 | 断言 |
|---|---------|------|
| 1 | `should discover order and cart aggregates` | MetadataSearcher 包含 Order 和 Cart |
| 2 | `should not discover disabled route aggregate` | 不包含 disabled_route_aggregate 路径 |
| 3 | `should generate expected route count` | 路由数量 ≥ 最低预期 |
| 4 | `should set info title to context name` | openAPI.info.title == "example-service" |

### 2.2 Aggregate Routes（4 个）

| # | 测试方法 | 断言 |
|---|---------|------|
| 5 | `should generate cart routes with aggregate id owner path` | 存在 /example/cart/ 路径 |
| 6 | `should generate order routes with spaced resource name` | 存在 /example/sales-order/ 路径 |
| 7 | `should set correct tags for cart` | Cart 路由 tag 包含 "customer" |
| 8 | `should set aggregate tags in open api` | openAPI.tags 非空 |

### 2.3 Command Routes（6 个）

| # | 测试方法 | 断言 |
|---|---------|------|
| 9 | `should generate create order as POST with empty action` | method=POST |
| 10 | `should generate change address as PUT` | method=PUT, path 含 "address" |
| 11 | `should generate ship order as POST with package action` | method=POST, path 含 "package" |
| 12 | `should generate pay order as POST with pay action` | method=POST, path 含 "pay" |
| 13 | `should generate add cart item as POST` | method=POST |
| 14 | `should generate view cart without request body` | requestBody 为 null |

### 2.4 Schemas（3 个）

| # | 测试方法 | 断言 |
|---|---------|------|
| 15 | `should generate create order schema with fields` | Schema 含 items/address/fromCart |
| 16 | `should generate order created schema` | Schema 存在 |
| 17 | `should generate shipping address schema` | 嵌套对象 Schema 存在 |

### 2.5 Components（2 个）

| # | 测试方法 | 断言 |
|---|---------|------|
| 18 | `should generate command header parameters` | parameters 含命令 header |
| 19 | `should generate command responses` | responses 非空 |

## 实现要点

- 路由查找通过 `pathPrefix` 过滤和 `id` 包含命令名匹配
- Schema 断言通过 `openAPI.components.schemas` 按名称查找
- 保留现有 32 个单元测试文件不变，本文件为纯新增
