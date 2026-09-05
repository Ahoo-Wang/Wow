# 查询模块分阶段重构设计：共享解析内核

日期：2026-09-05

状态：分阶段方向与第一阶段范围已确认；本书面设计待审阅。

基线：`4a64789b6`，Wow `9.0.8`。

## 目标与阶段

以完整查询链路为评估范围，按收益分阶段重构，提高架构质量、性能、代码质量和可维护性。第一阶段优化两种后端共用的解析内核，保持现有职责边界和查询合同。

| 阶段 | 范围 | 交付依据 |
|---|---|---|
| 一，本设计 | 动态字段反向解析、投影与聚合兼容级别汇总、回归与性能验证 | 消除请求路径的全字段扫描和不必要的临时集合 |
| 二，后续单独设计 | MongoDB/Elasticsearch 编译中的重复字段解析及对应边界 | 第一阶段结果、原生编译合同与测量数据 |
| 三，按证据选择 | 查询执行、分页及结果处理中的剩余瓶颈 | 端到端测量或可复现的行为问题 |

后续阶段是评估方向，不预先承诺修改 Gateway、Schema Provider、分页器或模块结构。

## 已确认的现状

主链路为：请求解码 → Gateway 获取 Schema 快照 → HTTP Guard/权限等 QueryFilter → Schema 解析准入 → Backend 原生编译与执行 → 脱敏 → 动态结果或类型转换。

- `QueryBackendBinding` 原子配对执行 Backend 与 Schema Provider，Factory 按物化聚合缓存 Binding，Spring 与 Schema HTTP 路由复用该组合。
- 每次 Gateway 订阅读取一次 Schema，构造独立 Context；过滤链、解析、Backend 和脱敏使用同一快照。
- `ResolvedQuery(query, schema)` 是执行边界；逻辑字段、物理字段、投影字段和返回字段有各自明确的用途。
- MongoDB 分页分别执行 count 和 find；Elasticsearch 列表大批量读取使用 PIT 与 `search_after`，已有完成、错误和取消时的资源释放逻辑。
- 现有测试覆盖 Schema 刷新、订阅隔离、准入、脱敏、游标及后端行为。探索阶段运行 `./gradlew :wow-query:test --console=plain`，386 项测试通过，无失败或跳过。这是功能基线，不是性能结论。

第一阶段的直接证据：

| 位置 | 当前成本 |
|---|---|
| [QueryFieldSchemaResolver.resolvedField](../../wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryFieldSchemaResolver.kt) | 精确反向索引未命中后遍历 `schema.fields`，为每个匹配的动态祖先构造派生 Schema，最后选择最长前缀 |
| [QuerySchemaResolver.resolve(Projection)](../../wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt) | include/exclude 分别映射、拼接后再映射兼容级别；原 Projection 始终透传 |
| 同文件的聚合解析 | 保存兼容级别列表、按指标类型创建两个过滤列表，再汇总级别 |
| [QuerySchemaResolverBenchmark](../../wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/query/QuerySchemaResolverBenchmark.kt) | 已有 identity、重写、动态字段和投影基准，但没有大 Schema 下反向动态解析、聚合或 Schema 构造成本的完整对照 |

## 方案选择

采用“共享解析内核优先”：沿用 `QueryModelSchema` 及其内部 Resolver，在现有位置消除重复工作，随后再处理后端编译。两种后端同时受益，变更可以独立验证和回退。

另一方案是先统一后端编译流程。该方案需要同时处理 MongoDB 与 Elasticsearch 的嵌套字段、投影和聚合差异，首阶段会扩大验证范围。因此本阶段不选择它。

不引入 Planner、统一 Engine、第二棵物理 Query AST、全局查询缓存或新依赖。现有 Gateway、Backend、Provider、Factory 与 Resolver 的职责足以承载本阶段优化。

## 第一阶段架构与数据流

生产代码集中在 `wow-query` 的两个现有内部实现：

- `QueryFieldSchemaResolver` 拥有字段索引及候选选择，只依赖所属 `QueryModelSchema`。
- `QuerySchemaResolver` 编排现有查询解析，直接累积兼容级别；`QueryFilterSchemaResolver` 继续负责过滤表达式解析。

不为了文件长度拆出新接口或服务。Backend 继续通过 `QueryModelSchema` 的现有方法获取物理字段，不感知索引实现。

索引随所属 Schema 构建，发布后只读。Schema 刷新产生新 Schema 和新索引；已有订阅继续持有原快照。索引不保存请求、动态子字段查询结果或可变 JSON 节点，其大小受 Schema 声明的绑定数量约束。

## 动态字段反向索引

### 查找顺序

保持 `resolve` 的现有顺序：

1. 逻辑字段声明及逻辑动态祖先解析。
2. 当前 capability 对应的精确 `resolvedFieldIndex`。
3. 已解析路径的动态祖先查找。
4. 现有未知字段、能力缺失与 Element scope 的兼容性判定。

仅替换第 3 步内部的全字段扫描。

### 索引与候选规则

在 Resolver 构造时建立私有动态反向索引，以 `(capability, binding.resolvedField)` 为键，保存声明逻辑字段、原字段 Schema 与 Binding。复用现有 `ResolvedField` 数据结构和标准 Map。

动态索引仅纳入 `dynamicChildren=true` 的声明。`ELEMENT_SCOPE` 不进入动态子字段索引，因为现有 `resolveDynamic` 会移除该能力；精确声明的 Element binding 仍由精确索引处理。

精确索引保留当前冲突检查：同 capability、同 resolved path 映射不同 physical path 时，构造 Schema 失败。同一路径与相同物理绑定的动态候选使用 `putIfAbsent` 保留声明迭代顺序中的第一项，等价于原有最长前缀扫描的并列选择。精确索引中的非动态声明不能遮蔽同路径的动态祖先候选，因此动态候选单独索引。

查询时从输入路径的最后一个点号开始，逐级向外查找严格祖先：

- 首个命中是最长有效前缀；只在点号边界匹配，`document.a` 不匹配 `document.ab.code`。
- 选择最终祖先后，才调用一次已有 `resolveDynamic`，生成派生 Schema 与 Binding。
- 无命中直接进入既有兜底，不保存未命中记录。
- 保持 logical/resolved/physical 三种 parent、相对路径、`elementDescendantDynamicFields` 与重写模式的现有处理。

例如，逻辑动态根 `state.labels` 绑定到 `document.labels` 和 `storage.labels`。查询经逻辑重写为 `document.labels.color` 后，后端物理查找命中 `document.labels`，得到 `storage.labels.color`；逻辑、已解析与物理路径保持区分。

该查找的 Map 探测次数由路径深度决定，不再遍历全部声明字段。子串构造和哈希仍有成本，因此不宣称总运行时间严格为常数或只取决于路径段数。索引构造时间和空间随动态绑定数增长，并纳入基准比较。

## 投影与聚合解析

兼容级别沿用 `EXACT < COMPATIBLE < INCOMPATIBLE`。使用现有代码已采用的 `maxOf` 直接累积，不增加兼容性累加器类型。

### 投影

- 按原有 include、exclude 顺序逐项解析，直接累积兼容级别，不保存中间解析结果列表。
- 始终返回原 Projection 对象。
- 保留 EventStream payload 与 bodyType 的投影约束以及现有校验行为。
- 不因已得到 `INCOMPATIBLE` 就提前跳过后续字段解析，避免改变后续解析异常是否可见。

### 聚合

- root filter、elements、groupBy 按现有顺序解析，并直接汇总兼容级别。
- metrics 保持先 Any、再 Numeric 的两轮校验顺序；每轮直接遍历并判断类型，删除 `filterIsInstance` 创建的临时列表。Count 沿用现有无需字段校验的行为。
- 数值表达式的递归 helper 返回兼容级别，不再接收可变级别列表；Binary 的左右表达式都执行解析，按现有顺序汇总。
- 原有 Any 指标基数限制、脱敏字段拒绝、未知字段兼容规则与嵌套 scope 校验保留。
- 继续只在 root filter 或 element filter 实际变化时复制聚合查询；未变化的 Query、元素和子树保持原对象复用。

本阶段不合并两类指标的校验顺序。直接遍历已能消除中间集合，同时保持原错误选择；无需为减少一次遍历引入额外状态或异常暂存。

## 错误、安全与兼容性

- 保持现有公开方法、参数、返回类型、构造合同与查询语义；不添加兼容桥或迁移层。
- Query JSON、Schema HTTP、OpenAPI/schema 生成合同、Cursor wire、存储布局和 Spring 配置均不变。
- `COMPATIBLE`、`STRICT` 的准入和异常类型保持不变。Schema 不可用时继续在 Backend 执行前失败。
- 不绕过 HTTP Guard、ABAC、脱敏或字段能力判断；未知字段不能被索引错误提升为 `EXACT`。
- 保持请求与结果节点的订阅隔离，维持 Reactor 的取消、重试和错误传播行为。
- 不修改 Schema 的公开可变性合同，不借机增加防御复制、校验或后端原生行为模拟。

## 验证设计

### 回归用例

在已有测试类中补充最小的合同用例，不复制解析器实现作为测试 oracle：

| 领域 | 必须守住的行为 |
|---|---|
| 动态反向查找 | resolved path 输入、最长祖先、同前缀首个候选、非动态与动态声明同路径、不同 capability、点号边界、未知路径 |
| 绑定与 scope | 精确绑定优先、物理路径冲突、嵌套 Element parent、动态子字段不继承 ELEMENT_SCOPE、跨容器路径拒绝 |
| 投影 | 空投影、include/exclude、混合兼容级别、原对象复用、EventStream payload/bodyType 约束 |
| 聚合 | Any/Numeric 混合、数值表达式、嵌套 elements、未知字段、脱敏拒绝、无重写和局部重写 |
| 生命周期 | 复用已有 Gateway/Provider 测试，确认每个订阅的 Schema 快照与 Context 隔离 |

以预期物理路径、兼容级别、异常类型和对象身份作为断言；沿用 JUnit、FluentAssert 和 Reactor 测试支持。

实施时先运行相关 Resolver 测试，再运行 `:wow-query:check`；随后运行两种后端的现有查询单元测试与集成测试，覆盖真实绑定、Projection、Sort、Cursor、Aggregation 的结果。Spring/WebFlux 查询相关测试验证共享解析变更未改变接入合同。无数据库服务时明确记录未运行项，不用 mock 结果替代集成通过结论。

### 性能对照

扩充现有 JMH 查询基准，保持同一份基准源码、同一 JDK/JVM 参数和相同运行参数，分别测量优化前后的生产实现。复用现有基准打包流程，不修改 Gradle 模块、依赖或基准构建插件。

覆盖以下场景；避免把互不相关的参数做笛卡尔积：

- 静态字段总数 32、256、2048：动态 resolved path 命中与未命中。
- 动态根数量 1、16、128：最长前缀和嵌套动态祖先。
- identity 与精确静态绑定：作为对照，监测常见路径回归。
- 投影宽度 1、16、64；聚合覆盖 Any/Numeric 混合和表达式。
- Schema 构造：分别测量无动态根和有动态根时的时间、分配量。

查询基准在计时前准备 Schema 和 Query；构造成本放入独立 benchmark，避免混入热路径指标。至少使用现有 3 forks、5 次预热、10 次测量配置，并启用 GC profiler，记录 `ns/op`、`gc.alloc.rate.norm`（B/op）、误差范围、环境与提交版本。

性能验收要求：目标路径获得可复现的耗时或分配量收益，静态精确与 identity 对照不出现可复现退化。误差范围重叠时不宣称耗时改善；对疑似退化做针对性复测或撤回对应优化。单独报告新增索引的构造与空间成本，不将解析微基准的改善比例当作数据库查询端到端改善比例。

JMH 原始结果保留在忽略的构建输出目录；交付中提供结果位置和复现命令，不提交生成输出。

## 修改范围与验收

预期生产修改仅涉及：

- `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryFieldSchemaResolver.kt`
- `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt`

测试与基准修改放入现有 `wow-query/src/test/` 和 `wow-benchmarks/src/jmh/`。如果实现需要改变公开接口、模块职责或新增依赖，说明原因并重新确认范围。

第一阶段完成条件：

1. 动态反向查找不再扫描全部 `schema.fields`，只物化最终动态候选，且原候选选择与冲突行为通过回归验证。
2. 投影与聚合不再创建仅用于汇总兼容级别的列表；查询重写和错误语义保持不变。
3. 本设计列出的合同通过对应测试验证；后端与接入验证的实际运行范围明确记录。
4. 提供同基准前后性能数据及索引构造成本；未测得的收益不作完成声明。
5. 第一阶段无公开协议、依赖、模块、发布流程或存储变更，可通过回退本阶段代码恢复原实现，无需数据迁移。

本设计审阅通过后，使用 `writing-plans` 制定第一阶段实施计划。第二阶段根据本阶段结果单独明确目标和验收条件。
