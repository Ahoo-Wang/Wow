# wow-openapi 单元测试全面重写设计

## 目标

全面重写 `wow-openapi` 模块的单元测试，采用行为驱动风格，移除外部测试模块依赖，为所有源文件补充测试覆盖。

## 设计决策

| 决策 | 选择 |
|------|------|
| 文件命名 | `*Test.kt`（保持一致，不使用 `BehaviorTest` 后缀） |
| 类可见性 | `internal` |
| 测试方法命名 | 反引号风格 `` `should ... when ...` `` |
| 断言库 | `me.ahoo.test.asserts.assert`（FluentAssert） |
| Fixture 策略 | 每个测试文件内部定义 `private` fixture，不依赖外部模块 |
| 文件映射 | 每个源文件对应一个测试文件（一对一） |
| License 头 | Apache 2.0 |

## 构建变更

`build.gradle.kts` 移除：

```kotlin
testImplementation(project(":example-domain"))
testImplementation(project(":example-transfer-domain"))
testImplementation(project(":wow-tck"))
```

## 删除文件

- `ModelConvertersTest.kt` — 内容拆分到 `BoundedContextSchemaNameConverterTest` 和 `WowSchemaConverterTest`

## 测试文件清单

### 根目录核心层（11 个文件，4 个重写 + 7 个新增）

#### `BatchResultTest`（重写）

- `should create batch result with after id and size`
- `should use default error code and message`
- `should implement ErrorInfo interface`

#### `RouterSpecsTest`（重写）

替换对 `ExampleService` 的依赖，内部定义 fixture BoundedContext。

- `should build and return non-empty routes`
- `should merge router specs into open api with context name as title`
- `should keep existing info when merging`
- `should replace default info title when merging`
- `should keep custom info title when merging`
- `should merge into existing open api preserving paths and components`
- `should set spec version to v31`
- `should deduplicate tags when merging`

#### `RouteSpecTest`（新增）

内部定义测试用 `RouteSpec` 实现。

- `should convert route spec to operation with correct fields`
- `should convert route spec list to path item with method mapping`
- `should map get method correctly`
- `should map post method correctly`
- `should map put method correctly`
- `should map delete method correctly`
- `should map patch method correctly`
- `should throw when encountering unsupported method`
- `should throw when detecting duplicate routes with same method`

#### `RouteIdSpecTest`（新增）

- `should build id with all segments`
- `should build id with prefix only`
- `should build id with tenant appended`
- `should build id with owner appended`
- `should build id with resource name and operation`
- `should build empty id when no segments set`
- `should set prefix from named aggregate`

#### `PathBuilderTest`（新增）

- `should append non-blank segment with separator`
- `should append segment starting with separator as-is`
- `should skip blank segment`
- `should build empty path when no segments appended`
- `should append multiple segments`

#### `TagsTest`（重写）

内部定义 `@Tag` 注解的 fixture interface。

- `should convert single tag annotation to tags`
- `should convert multiple tag annotations to tags`
- `should return empty tags for class without annotations`

#### `HttpsTest`（新增）

- `should have correct header constant`
- `should have correct status code constants`
- `should have correct method constants`
- `should have correct media type constants`

#### `OpenAPIExtensionsTest`（新增）

- `should add wow version extension to info`
- `should add context name extension to info`
- `should add context alias extension to info`

#### `ApiResponseBuilderTest`（新增）

- `should add header to api response`
- `should add content to api response`
- `should build response with multiple headers and content`

#### `RequestBodyBuilderTest`（新增）

- `should add content to request body`
- `should build request body with required flag`

#### `BatchRouteSpecTest`（新增）

- `should have batch result and request timeout responses`
- `should include batch after id and limit parameters`
- `should not append tenant path by default`

### 组件层（4 个文件，全部新增）

#### `CommonComponentTest`

- `should create error code header`
- `should create not found response`
- `should create conflict response`
- `should create bad request response`

#### `QueryComponentTest`

- `should create paged query parameters`
- `should create condition parameter`

#### `BatchComponentTest`

- `should create batch after id parameter`
- `should create batch size parameter`

#### `RouteSpecFactoryTest`

- `should have default route specs as empty list`

### 上下文层（4 个文件，全部新增）

#### `OpenAPIComponentContextTest`

- `should create default context with schema version`
- `should create default context with inline option`
- `should have correct component reference constants`

#### `DefaultOpenAPIComponentContextTest`

- `should register and retrieve schema`
- `should register and retrieve parameter`
- `should register and retrieve header`
- `should register and retrieve request body`
- `should register and retrieve response`
- `should return empty maps before any registration`

#### `CurrentOpenAPIComponentContextTest`

- `should set and get current context`
- `should clear current context when set to null`

#### `OpenAPIComponentContextCapableTest`

- `should return component context from capable implementation`

### 转换器层（2 个文件，1 个重写 + 1 个新增）

#### `BoundedContextSchemaNameConverterTest`（重写）

- `should not resolve name for string java type`
- `should not resolve name for string class`
- `should keep existing name when not blank`
- `should resolve name for list java type`
- `should resolve name for non-standard type with bounded context prefix`

#### `WowSchemaConverterTest`（新增，替代 `ModelConvertersTest`）

- `should return null when no current context`
- `should resolve schema from current context for class type`
- `should resolve schema from current context for java type`
- `should set schema name from annotated type name`

### 元数据层（5 个文件，全部重写）

#### `AggregateRouteMetadataParserTest`（重写）

内部定义 fixture 聚合类型。

- `should parse aggregate route metadata with default values when no annotation`
- `should parse aggregate route metadata with annotation`
- `should parse aggregate route metadata with custom resource name`
- `should parse aggregate route metadata with owner set`
- `should parse aggregate route metadata with spaced flag`

#### `AggregateRouteMetadataTest`（重写）

- `should be equal to same aggregate route metadata`
- `should not be equal to arbitrary object`
- `should not be equal to different aggregate route metadata`
- `should have hash code matching aggregate metadata`

#### `CommandRouteMetadataParserTest`（重写）

内部定义 fixture 命令类型。

- `should parse command route metadata with path and header variables`
- `should parse delete aggregate route method`
- `should decode command from path and header variables`
- `should throw when required variable is missing during decode`
- `should decode command with optional variables using defaults`
- `should decode command with nested path variables`
- `should decode command with field-level nested path variables`
- `should handle missed variable in route metadata`
- `should parse command route without annotation using default action`
- `should derive method from create command`

#### `CommandRouteMetadataTest`（重写）

- `should be equal to same command route metadata`
- `should not be equal to arbitrary object`
- `should not be equal to different command route metadata`
- `should have hash code matching command metadata`

#### `VariableMetadataTest`（重写）

- `should resolve variable type from field when field path is empty`
- `should return null variable type when field is null`
- `should resolve variable type for nested field path`
- `should provide field name as last element of field path`

### 聚合路由层（aggregate/，5 个文件，全部新增）

#### `AggregateRouteSpecTest`

- `should build path with tenant prefix when append tenant path`
- `should build path without tenant when static tenant id present`
- `should build path with owner prefix when owner is not never`
- `should build path without owner when owner is never`
- `should build path with id when append id path`
- `should build path with resource name and suffix`
- `should include tenant parameter when append tenant path`
- `should include owner parameter when append owner path`
- `should include id parameter when append id path`
- `should include space id parameter when spaced`

#### `AggregateRouteSpecFactoryTest`

- `should extend route spec factory interface`

#### `AggregateRouteSpecFactoryProviderTest`

- `should load factories via service loader`
- `should return empty list when no factories found`

#### `TenantOwnerAggregateRouteSpecTest`

- `should create default route spec without tenant and owner`
- `should create tenant route spec when static tenant id is blank`
- `should create owner route spec when owner is not never`
- `should not create tenant route spec when static tenant id is present`

#### `TenantOwnerRouteSummarySpecTest`

- `should build summary with operation only`
- `should build summary with tenant appended`
- `should build summary with owner appended`
- `should build summary with tenant and owner appended`

### 聚合命令路由层（aggregate/command/，3 个文件，全部新增）

#### `CommandComponentTest`

- `should create command type header parameter`
- `should create tenant id header parameter`
- `should create owner id header parameter`
- `should create command common header parameters`

#### `CommandFacadeRouteSpecTest`

- `should have post method`
- `should have wow command send path`
- `should have command related parameters`
- `should have request body`

#### `CommandRouteSpecTest`

- `should build id with aggregate and command operation`
- `should have correct method from command route metadata`

### 聚合事件路由层（aggregate/event/ + state/，7 个文件，全部新增）

#### `EventComponentTest`

- `should create head event stream parameter`
- `should create event stream schema`

#### `LoadEventStreamRouteSpecTest`

- `should have get method`
- `should have event stream path suffix`
- `should have sse accept header`

#### `CountEventStreamRouteSpecTest` / `ListQueryEventStreamRouteSpecTest` / `PagedQueryEventStreamRouteSpecTest` / `EventCompensateRouteSpecTest`

各 2-3 个测试，验证 method、path、responses 关键属性。

#### `ResendStateEventRouteSpecTest`

- `should have post method`
- `should have resend path suffix`

### 聚合快照路由层（aggregate/snapshot/，10 个文件，全部新增）

每个文件 2-3 个测试，验证：
- HTTP 方法正确
- 路径后缀正确（`snapshot`、`snapshot/state`）
- 响应包含预期 schema

### 聚合状态路由层（aggregate/state/，5 个文件，全部新增）

#### `LoadAggregateComponentTest`

- `should create aggregate id path parameter`

#### `LoadAggregateRouteSpecTest` / `LoadVersionedAggregateRouteSpecTest` / `LoadTimeBasedAggregateRouteSpecTest` / `AggregateTracingRouteSpecTest`

各 2-3 个测试。

### 全局路由层（global/，6 个文件，全部新增）

#### `GlobalRouteSpecFactoryTest`

- `should extend route spec factory`

#### `GlobalRouteSpecFactoryProviderTest`

- `should load factories via service loader`
- `should return empty list when no factories found`

#### `CommandWaitRouteSpecTest`

- `should have post method and wait path`

#### `GenerateGlobalIdRouteSpecTest`

- `should have get method and text plain accept`

#### `GenerateScriptRouteSpecTest`

- `should have get method and sql media type`

#### `GetWowMetadataRouteSpecTest`

- `should have get method and metadata path`

## 统计

| 类别 | 数量 |
|------|------|
| 源文件总数 | ~63 |
| 测试文件总数 | ~62 |
| 重写现有测试 | ~10 |
| 新增测试文件 | ~50 |
| 删除测试文件 | 1（`ModelConvertersTest`） |

## 验证标准

- `./gradlew :wow-openapi:test` 全部通过
- 无外部测试模块依赖（`example-domain`、`example-transfer-domain`、`wow-tck`）
