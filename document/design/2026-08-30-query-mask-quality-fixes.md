# V9 Query Mask 质量修复设计

## 目标

修复 PR #3099 中已经验证的正确性、安全性和文档问题，使静态注解 Mask 在 V9 Query Gateway 中保持失败关闭、可刷新且无多余兼容层。

## 设计边界

- 这是 V9 API，不保留旧 Kotlin data class 构造器、默认参数或 `copy` 的二进制 ABI。
- 删除仅为 V8 Java 构造器形状添加的 secondary constructor 与 `@JvmOverloads`，V9 调用方使用包含 Mask 字段的新构造合同。
- 不引入 KSP、新依赖、全局注册表或新的缓存抽象。
- 只修复 Mask 相关根因及其直接文档、测试，不做无关重构。

## 聚合字段解析

Element 聚合中的字段按当前 Element 根拼接。`expand("body")` 下的 `body.data` 必须解析为 `body.body.data`，不能因字段文本已含 `body.` 就误判为绝对路径。

恢复聚合字段原有的相对解析语义，并保留 Mask 新增的聚合拒绝规则：group、字段 metric 和 expression 引用 masked 字段时解析为 `INCOMPATIBLE`；`COUNT` 不受影响。

## 请求级 Query Schema 执行上下文、Masker 生命周期与快速路径

统一复用现有 `QueryModelSchemaProvider` 作为 Schema capability，不新增 Mask 专用 Provider、Backend
capability、`QueryExecution` DTO 或公共 Schema 参数。`SchemaMaskQueryFilter` 仍是最外层结果装饰器：它在
结果 publisher 每次 subscription 时从 Provider 取得一次当前 Schema，先把该对象写入统一的 Query Schema
subscription context，再订阅 Backend publisher，并用同一对象构建结果 Masker。

所有 `QueryModelSchemaProvider.resolve(...)` 扩展先读取该 subscription context；不存在固定值时才调用
`schema()`。因此受管 Gateway 中的 Query 解析、Backend 执行所使用的物理字段，以及结果 Mask 都绑定到同一
Schema 对象。`refresh()` 可以发布下一代 Schema，但不得让一个在途请求跨代解析和脱敏。直接调用 Backend
时没有请求上下文，继续从 Provider 读取当前 Schema。

该上下文归属 `wow-query` 的 Query Schema 能力，只保存本次 subscription 的 `QueryModelSchema`，不放在
Mask 包内，也不使用全局注册表、`ThreadLocal`、锁或跨请求可变状态。`count` 只有一次 Backend Schema
解析，不需要额外固定；aggregation 继续复用现有 `ResolvedAggregationQuery` 把解析后的 query 与同一 Schema
交给 compiler，不进入结果 Masker。

Provider 已负责发布和缓存当前 Schema，Gateway 不再永久缓存首次返回的
`Mono<Optional<SchemaMasker>>`。

Gateway 仅按 `QueryModelSchema` 对象身份缓存最近一次 Masker 编译结果：

- Schema 对象未变化时复用 `SchemaMasker`；
- `refresh()` 发布新 Schema 对象后重新编译；
- 根 Schema 没有 masked 字段时缓存空结果，并直接返回原 publisher，不增加逐条 `map` 或 JSON 遍历；
- Schema 加载与规则编译异常继续沿现有 Gateway error handling 传播，不吞掉也不降级为未脱敏结果。

实现使用 JDK 原子引用或等价的仓库既有原语，不增加缓存接口或失效协议。

## Mask 执行异常

`CompiledMask.mask(value)` 抛出的非 fatal 异常统一转换为固定消息的
`QuerySchemaValidationException`，并保留 cause 供内部诊断。HTTP 响应与默认请求日志只呈现固定外层消息，
不得包含原始 masked value 或自定义异常消息；JVM `Error` 继续原样传播。返回 `null` 仍使用现有固定校验错误。

## 继承注解合并

在共享的注解合并根因处修复，而不是在 Query Schema 再维护一套 Mask 专用扫描器：

- 当前成员上的同类注解继续覆盖父级；
- 不同父接口提供完全相同的注解实例时去重；
- 不同父接口提供同类但参数不同的注解时全部保留，使现有 Schema 冲突检查稳定地失败关闭；
- 函数继承扫描同时识别父 Kotlin property getter，覆盖 Java getter 实现 Kotlin `@get:Mask` 属性的场景。

注解遍历顺序不得改变结果。共享合并行为通过 wow-core 单元测试约束，Mask 到 Schema 的端到端行为通过 wow-schema 测试约束。

## 扩展合同与文档

`MaskStrategy` / `CompiledMask` 文档明确：编译结果会被并发复用，执行必须线程安全、非阻塞且返回非 null 字符串。Strategy 注解类型不匹配时提供包含注解类与 Strategy 类的明确错误，不引入复杂泛型解析框架。

Query Schema 文档把 unavailable fallback 限定为直接 Schema 解析路径。受管 Query Gateway 在返回结果前需要 Schema 进行 Mask，因此 Schema 不可用时失败关闭，不得返回原始敏感值。中英文文档和项目查询技能保持一致。

## 验证

修复按 TDD 完成，至少覆盖：

1. Element 聚合 `body.data` 到 `body.body.data` 的回归测试，以及失败过的 Mongo EventStream 集成测试。
2. Gateway 在 unmasked 到 masked、Mask 参数变化和 Event body type 变化后的 refresh 行为；并发 refresh 下
   Provider 解析与 Masker 从统一 subscription context 读取同一 Schema 对象；直接 Backend 调用在无上下文时
   仍读取 Provider 当前值；根无 Mask 快速路径仍不映射结果。
3. 同参数、多参数冲突、父接口顺序反转、本地覆盖和 Java getter 实现 Kotlin property 的继承注解行为。
4. `KeepMask` 的 Unicode code point、Strategy 抛错转换为固定安全消息并经 Gateway ErrorHandler 传播，
   以及 Java 调用方使用 V9 新构造合同。
5. 相关模块检查、静态分析、文档构建和完整集成测试。

## 完成标准

- 原 CI 聚合失败消失；所有新回归测试先红后绿。
- Schema refresh 后不复用旧 Mask 规则，也不存在未脱敏降级路径。
- 单次结果 subscription 只使用一个 Schema generation，Query 解析与 Mask 之间不存在私有旁路上下文，
  MaskStrategy 失败不会向响应或默认日志泄露原始值。
- 冲突注解与未知 Event body type 均失败关闭。
- API 与文档明确 V9 源码和二进制边界，代码中没有为 V8 构造器保留兼容债务。
- PR 分支基于最新 `main`，全量验证通过后才推送；不自动合并 PR。
