# wow-schema 重构设计

日期：2026-10-09。状态：已实施（2026-10-09，见 §9）。随 9.5.0 发布。

依据：对 `wow-schema`（`main` 代码 3.6k 行、测试 5.5k 行）及其调用方 `wow-openapi`、`wow-spring-boot-starter`、`wow-tck`、`wow-bi` 做的只读审查，基于 `main` `c19a9354a`。文中路径都相对 `wow-schema/src/main/kotlin/me/ahoo/wow/schema/`，行号指该提交。

## 1. 第一性原理：这个模块是什么

`wow-schema` 只做一件事：**把 JVM 类型映射成它的 JSON 线格式 Schema**。线格式由 `JsonSerializer`（Jackson）决定，Schema 必须如实描述它。

它有三类调用方，都只消费这一映射：

| 调用方 | 消费什么 |
|---|---|
| `wow-openapi` | 用 Schema 组装 OpenAPI 组件与引用 |
| 查询模型（starter、TCK、BI） | `JsonQueryModelSource` 把 Schema 读成 `QueryTypeFact` |
| 应用与工具 | `SchemaGeneratorBuilder` 生成 Schema 做校验、文档 |

必须守住的不变量：

1. **输出是契约。** 生成的 Schema（OpenAPI 组件、查询事实）变化会改变客户端生成和查询能力。重构必须逐字节不变，用 golden 证明；只有明确的缺陷修复可以改变输出。
2. **一次生成的状态只属于这次生成。** 生成器、Module、Provider 可以被多个线程、多个生成器同时使用；任何可变状态都不能跨生成共享。
3. **框架类型的 Schema 来自内置资源。** `META-INF/wow-schema/*.json` 描述消息、事件流、快照等包装类型的线格式，不依赖反射看到的实现类结构。

## 2. 诊断

| # | 根因 | 证据 | 后果 |
|---|---|---|---|
| D1 | **跨生成器共享可变状态** | `KotlinCustomDefinitionProvider` 是全局 `object`，用 `mutableSetOf` 做重入保护（`kotlin/KotlinCustomDefinitionProvider.kt:42`），任何一次生成结束都会把它清空（`:120`）。`InferredQuerySchemaSource` 在 `boundedElastic` 上并发调用 `describe`，每次调用都新建生成器 | **已复现**：每个线程使用独立的生成器生成 `KotlinFixture`，1 线程 0/300 次丢失 getter-only 属性，2 线程 284/600，8 线程 2071/2400。Kotlin 计算属性会随机从查询模型和 OpenAPI 里消失 |
| D2 | **公开 API 远大于实际契约** | ABI 有 63 个公开类；模块外只用到约 10 个（`SchemaGeneratorBuilder`、`OpenAPISchemaBuilder`、`InlineSchemaCapable`、`JsonQueryModelSource`、`AggregatedDomainEventStream`、`ServerSentEventNonNullData`、`Types.isStdType`、两个命名函数、`JavaTypeResolver`） | Provider、Check、Resolver、`JsonSchema`、`WowSchemaLoader`、`SchemaMerger` 都是实现细节，却成了对外契约，任何内部整理都会表现为 ABI 破坏 |
| D3 | **OpenAPI 适配层放错模块** | `openapi` 包（`OpenAPISchemaBuilder`、`OpenAPISchemaConverter`、`SchemaMerger`、`SchemaReferenceRegistry`、`StandaloneSchemaEmbeddingRebaser`）只有 `wow-openapi` 使用；`JavaTypeResolver` 依赖 Jackson 2 的 `JavaType`，只服务于 `wow-openapi` 的 swagger `ModelConverter` | 纯 JSON Schema 模块的代码同时依赖 Swagger 模型和 Jackson 2，OpenAPI 的组件、引用合并规则分在两个模块 |
| D4 | **Builder 有时序耦合，还会改写参数** | 必须先调 `build()` 才能读 `requiredTypeContent`（`SchemaGeneratorBuilder.kt:102`，`:179`）；`OpenAPISchemaBuilder` 改写调用方传入的 builder，静默覆盖其 `schemaNamingModule`（`openapi/OpenAPISchemaBuilder.kt:41`）；`openapi31` 没有任何地方读取（`:45`），文档却写它控制可空性；`SchemaGeneratorConfigFactory.kt:36` 的 `forFields()` 什么也没做 | 调用顺序错了会失败，参数被改写会让调用方的配置悄悄失效，文档描述了一个不存在的行为 |
| D5 | **Provider 重复、粒度过碎** | 8 个只有 3 行的 `object` 都在做"类型 → 内置资源"（Range ×3、Money ×2、AggregateId 等）；`MessageDefinitionProvider` 与 `AbstractStateAggregate` 是同一个算法（加载模板，用生成的定义替换某个属性并保留其描述），都靠反射 `genericSuperclass` 取类型（`typed/*.kt:34`）；每次调用都从 classpath 重新读取并解析模板（`typed/TypedCustomDefinitionProvider.kt:25`）；关键字有两套写法：`JsonSchema.toPropertyName()` 默认 2020-12，而 `context.getKeyword` 跟随配置的版本；`WowDefinitionProviderRegistry` 是一个只用一次的列表；`FilterExpressionDefinitionProvider` 既是 Provider 又是 Module | 加一个内置类型要新增一个类；同一规则写多遍，版本语义不一致 |
| D6 | **查询遍历写了三遍** | `JsonSchemaWalker` 的 `sourcedMetadataNodes`（`query/JsonSchemaWalker.kt:315`）、`effectiveNodes`（`:380`）、`members`（`:405`）各自实现一遍"`$ref` + 组合"展开与环检测 | 引用解析规则要在三处保持一致 |
| D7 | **小缺陷** | `it.name === …javaGetter!!.name` 用引用相等比较字符串（`kotlin/KotlinCustomDefinitionProvider.kt:71`）；`JsonSchema.kt:94` 是死语句；`requiredTypeContent` 拼写错误 | 依赖 JVM 字符串驻留，属于偶然正确 |

做得好的部分不动：命名策略（`naming/`）、Kotlin 可空/只读/必填检查的语义、`TypedDefaultValueDefinitionProvider`、`JsonTypeNode` 的交并语义、`FilterExpression` 的规范 Schema 资源。

## 3. 目标架构

```
wow-schema   me.ahoo.wow.schema
├── SchemaGeneratorBuilder / WowOption        公开入口
├── WowModule                                  Wow 注解与框架类型（公开 Module，内部实现）
├── kotlin/      KotlinModule                  Kotlin 语义（公开 Module，内部实现）
├── jackson/     WowJacksonModule              Jackson 忽略规则
├── joda/money/  JodaMoneyModule
├── naming/      SchemaNamingModule、WowSchemaNamingStrategy 及命名函数
├── definition/  内置资源与包装类型的 Provider（全部 internal）
├── query/       JsonQueryModelSource（公开）；walker、类型节点（internal）
└── web/、typed/ 占位类型：ServerSentEvent*、AggregatedDomainEventStream（公开）

wow-openapi  me.ahoo.wow.schema.openapi（包名保持不变，物理迁入）
├── OpenAPISchemaBuilder / InlineSchemaCapable 公开
├── JavaTypeResolver                           Jackson 2 → classmate，只服务 swagger ModelConverter
└── OpenAPISchemaConverter / SchemaMerger / SchemaReferenceRegistry / StandaloneSchemaEmbeddingRebaser（internal）
```

依赖方向：`wow-openapi → wow-schema → wow-query / wow-core`。`wow-schema` 的代码只读 Swagger 注解（Kotlin 检查用到 `@Schema`），不再引用 Swagger 模型和 Jackson 2。类路径不会因此变小：victools 的 swagger-2 模块本身依赖 `swagger-core-jakarta`。这一步的收益在内聚，不在依赖体积。

### 3.1 公开面（解决 D2）

只保留应用会写、或者其他模块需要的声明：

- `SchemaGeneratorBuilder`、`WowOption`；
- builder 接受的 Module：`WowModule`、`KotlinModule`、`JodaMoneyModule`、`WowJacksonModule`、`SchemaNamingModule`；
- 命名：`WowSchemaNamingStrategy` 和 `wow-openapi` 使用的命名函数。`Types.isStdType` 并入命名函数，作为 `Class<*>` 能否获得 Wow 命名的判定，不再单独公开 `Types`；
- `JsonQueryModelSource`；
- 占位类型：`AggregatedDomainEventStream`、`ServerSentEvent`、`ServerSentEventNonNullData`。

其余一律 `internal`，包括所有 Provider、Check、Resolver、`JsonSchema`、`WowSchemaLoader`。公开类从 63 个降到约 15 个。

### 3.2 每次生成的状态（解决 D1）

- `KotlinCustomDefinitionProvider` 的重入保护按 `SchemaGenerationContext` 划分（弱引用表，生成结束后随上下文回收），不再有全局集合，也不再需要 `resetAfterSchemaGenerationFinished`。这个保护不能去掉：去掉后，带 getter-only 属性的递归类型会栈溢出。单线程语义与原来完全相同，所以 golden 不变。
- 回归测试：多线程、每个线程一个生成器，并发生成 `KotlinFixture` 和一个带 getter-only 属性的递归类型，断言每次输出都与单线程相同。修复前这个测试会失败。
- 一个生成器不能跨线程共享：victools Jackson 模块的属性排序器不是线程安全的。`build()` 的文档写明"每个线程一个生成器"；生产代码本来就是每次 `describe` 新建生成器。

### 3.3 Builder（解决 D4）

- `build()` 只返回 `SchemaGenerator`，不再在 builder 上留下 `typeContext`。新增 `buildConfig(): SchemaGeneratorConfig`；需要 `TypeContext` 的调用方（`OpenAPISchemaBuilder`）从 config 自己创建。
- `OpenAPISchemaBuilder` 不再改写传入的 builder：它在 `copy()` 出来的副本上设置命名模块，再构建自己的配置。
- builder 仍是可变的 fluent builder。应用代码会调用这些 setter，有的还忽略返回值（例如 `OpenAPIComponentContext.default`），改成不可变会悄悄改变这些代码的行为。
- `SchemaGeneratorConfigFactory` 并入 builder，删除无效的 `forFields()` 调用。
- 应用会调用的 fluent 方法保持源码兼容。`openapi31(...)`、`openapi31`、`typeContext`、`requiredTypeContent` 标为 `@Deprecated("Scheduled for removal in 10.0.0. …")`，记入 `docs/compat-debt.md`；`openapi31` 保持无效果，文档改为如实描述（可空形状由 `Option.NULLABLE_ALWAYS_AS_ANYOF` 与 Kotlin 模块决定）。

### 3.4 Provider 收敛（解决 D5）

```kotlin
internal class BundledDefinitionProvider(private val type: Class<*>, resource: String = type.simpleName)
internal class WrappedDefinitionProvider(
    private val type: Class<*>,
    private val slot: String,            // MessageRecords.BODY 或 StateAggregateRecords.STATE
    private val constBodyType: Boolean,  // 消息额外把 bodyType 写成 const
)
```

- 8 个"类型 → 资源"的 `object` 合成 `BundledDefinitionProvider` 的 8 个实例；`Command`、`DomainEvent`、`StateAggregate`、`Snapshot`、`StateEvent` 五个 Provider 合成 `WrappedDefinitionProvider` 的 5 个实例。类型由构造参数传入，不再反射父类泛型。
- `WowSchemaLoader` 缓存解析后的模板，每次使用时 `deepCopy`。
- 关键字一律用 `context.getKeyword(...)`，删除 `JsonSchema` 包装类。
- `WowModule` 直接列出它注册的 Provider，删除 `WowDefinitionProviderRegistry`；`FilterExpressionDefinitionProvider` 只做 Provider，字段的子类型跳过规则由 `WowModule` 注册。

### 3.5 查询遍历（解决 D6）

`JsonSchemaWalker` 抽出一个展开器：给定节点，按"自身 → 本地 `$ref`（带环检测）→ `allOf`/`anyOf`/`oneOf` 分支"产出 `(node, source)` 序列。`effectiveNodes`、`sourcedMetadataNodes`、`members` 都改为在这个序列上过滤或折叠。描述元数据的优先级规则和 `JsonTypeNode` 的交并语义保持不变。

## 4. 兼容性

- **生成输出**：S0 的 golden 覆盖内置资源、e2e 输出、`wow-openapi` 的 `example-domain-openapi.snapshot.json` 和查询事实。除 S1 的缺陷修复外，每个阶段的 golden 必须不变。S1 只让并发场景下的输出等于单线程输出，单线程 golden 不变。
- **ABI**：S2、S3、S5 删除或改变公开声明，在 9.5.0 中作为破坏性变更发布，PR 打 `breaking-change` 标签，并写 `## Breaking` 段落。不保留只为二进制链接存在的垫片。
- **源码**：应用会写的 API 不变。`OpenAPISchemaBuilder` 保持包名 `me.ahoo.wow.schema.openapi`，所以 import 不需要改；直接使用它、但只依赖 `wow-schema` 的应用要加上 `wow-openapi` 依赖（启用 `openapi-support` 的应用已经有这个依赖）。弃用的 builder 成员按 §3.3 保留到 v10。

## 5. 实施阶段

| 阶段 | 内容 | 证明 |
|---|---|---|
| S0 | golden 测试：所有内置资源、e2e 类型、`KotlinFixture`、递归类型，以及代表性状态类型的查询事实（序列化成 JSON 后比对）；加一个包依赖测试 | 新增测试在 `main` 上通过 |
| S1 | D1 修复与并发回归测试；D7 的 `===` | 修复前并发测试失败、修复后通过；golden 不变 |
| S2 | 公开面收缩（§3.1），更新 ABI dump | ABI diff 只删除或改为 internal；golden 不变 |
| S3 | Builder（§3.3），更新文档与 compat-debt | golden 不变；`pnpm check:compat-debt` 通过 |
| S4 | Provider 收敛（§3.4） | golden 不变 |
| S5 | OpenAPI 适配层与 `JavaTypeResolver` 迁入 `wow-openapi`，对应测试一起迁移；去掉 `wow-schema` 对 `swagger-core-jakarta` 的直接声明（由 swagger-2 模块传递） | `wow-openapi` 快照不变；包依赖测试断言 `wow-schema` 的 main 代码不引用 `io.swagger.v3.oas.models` 和 `com.fasterxml.jackson.databind` |
| S6 | Walker 展开器（§3.5） | 查询事实 golden 不变；`JsonQueryModelSourceTest` 全部通过 |

每个阶段一个 PR，按顺序合并。S4、S6 与 S2、S3 没有依赖，可以并行。

## 6. 不做的事

- 不改变任何内置资源的内容和生成的 Schema 形状。
- 不重写描述元数据的优先级规则；它的行为由现有测试锁定，S6 只消除重复遍历。
- 不替换 victools，也不改 `WowJacksonModule` 的忽略判定逻辑。它保存 `ObjectMapper` 字段的做法与上游 `JacksonSchemaModule` 一致，属于配置期状态，不在生成期间共享，不算 D1。

## 7. 决定

用户 2026-10-09 确认：

1. 竞态修复随 9.5.0 发布，不出 9.4.1 补丁。
2. S2、S3 的 ABI 破坏放进 9.5.0。
3. OpenAPI 适配层和 `JavaTypeResolver` 迁到 `wow-openapi`。
4. 先合并本设计文档，再按 S0–S6 实施。

## 8. 9.5.0 发布说明条目

- **修复**：并发生成 Schema 时，Kotlin 只读计算属性会随机缺失（影响查询模型与 OpenAPI）。
- **破坏性（ABI）**：`wow-schema` 的 Provider、Check、Resolver、`JsonSchema`、`WowSchemaLoader`、`Types`、`SchemaMerger` 不再公开；`OpenAPISchemaBuilder` 及相关类迁入 `wow-openapi`（包名不变）。
- **弃用**：`SchemaGeneratorBuilder.openapi31`、`typeContext`、`requiredTypeContent`，在 10.0.0 移除。

## 9. 实施记录

| 阶段 | PR | 结果 |
|---|---|---|
| 设计 | #4044 | 本文档 |
| S0 | #4045 | 80 个 Schema golden（默认与 Draft 2020-12 两种配置）、OpenAPI golden、68 个查询事实 golden、包依赖 DAG 测试 |
| S1 | #4046 | 重入保护按生成划分；并发回归测试修复前失败、修复后通过；golden 不变 |
| S4 | #4047 | 新增 225 行、删除 827 行；golden 不变；ABI 只有删除 |
| S6 | #4048 | 三处遍历合成一个展开器（`expand`）和一个引用解析（`referencedNode`）；查询事实 golden 不变 |
| S3 | #4049 | `buildConfig()`、`copy()`；`openapi31`、`typeContext`、`requiredTypeContent` 弃用并记入 compat-debt；ConfigFactory 并入 builder |
| S5 | #4050 | 迁移前先在 `wow-openapi` 用迁移前的代码生成 OpenAPI golden，迁移后不变；main 代码只有重命名，外加可见性调整 |
| S2 | #4051 | 公开类从 63 个降到 19 个 |

实施中对设计的修正：

- S1：重入保护不能删除，它防止带 getter-only 属性的递归类型栈溢出。改为按 `SchemaGenerationContext` 划分，所以不再需要"每个 Module 一个实例"。
- S1：一个生成器跨线程共享时，victools 的 `JsonPropertySorter` 会抛 `ConcurrentModificationException`。这是上游的限制，契约改为"每个线程一个生成器"。
- S3：builder 保持可变。原因见 §3.3。
- S5：迁移的单元测试需要的几个 fixture 复制到了 `wow-openapi` 的测试里。组件名前缀随之从 `wow.schema.` 变成 `wow.`，因为前缀取决于各模块测试 classpath 上的元数据。
- S6：`members` 的遍历顺序决定了 `omitted` 的顺序，而 `omitted` 会出现在输出里，所以 `members` 保留自己的遍历，只共用引用解析。
