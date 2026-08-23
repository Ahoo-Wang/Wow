# FilterExpression 实现层清理设计

## 背景

Wow 8.11 已引入 `FilterExpression`，但 `Condition` 兼容逻辑仍贯穿查询执行链、WebFlux guard、ABAC、MongoDB、Elasticsearch 和字段映射解析。当前实现通过私有 legacy wrapper 把 `Condition` 伪装成 `FilterExpression`，再由各层识别并回退到旧转换器。这使两套查询模型同时存在于实现层。

本次变更将 `Condition` 限定为调用入口兼容 API。每次执行最多进行一次 `Condition -> FilterExpression` 适配，适配后所有实现统一使用 `FilterExpression`。

## 目标

- 保留 JVM 调用入口兼容：废弃的 `Condition` 类型、构造器、DSL、查询属性和 `count(Condition)` 等调用继续可用。
- 保留 HTTP 旧 `condition` 请求兼容。
- 每次查询执行最多进行一次实际 legacy 适配。
- 查询过滤链、请求 guard、ABAC、MongoDB、Elasticsearch 和字段映射只处理 `FilterExpression`。
- 使用独立、公开的 FilterExpression 表达文档 ID 与聚合元数据条件，不再降级为普通逻辑字段比较。
- 删除旧 Condition converter、删除条件 guard 和实现层 legacy 分支。
- HTTP 请求必须恰好选择一种查询表示，不能同时或都不提供 `condition` 与 `filter`。

## 非目标

- 不恢复或推广旧 Condition OpenAPI/JSON Schema。
- 不为实现层 SPI、Condition converter 子类或 ABAC 的旧 Condition 扩展点保留源码或二进制兼容。
- 不引入新的通用查询包装层、工厂或配置项。
- 不改变 FilterExpression 的存储后端语义。

## 总体设计

### 兼容区

`wow-api` 和调用入口 DSL 是唯一允许保留 Condition API 的区域，包括：

- `Condition`、`Operator`、`ConditionCapable`、`RewritableCondition`；
- `SingleQuery`、`ListQuery`、`PagedQuery` 的废弃构造器、属性、复制和重写入口；
- Condition DSL 及 snapshot/event count 的废弃重载；
- HTTP 对旧 `condition` JSON 的解析；
- `RewriteRequestCondition` 等现有入口名称的必要废弃别名。

具体查询 DTO 继续用私有 legacy wrapper 保存由 Condition 入口构造的值，以维持执行前只序列化 `condition`、不序列化 `filter` 的旧 wire shape。typed 查询只序列化 `filter`。

### 单次执行适配

在 `wow-api` 提供唯一 resolver：

- 普通 `FilterExpression` 原样返回；
- 私有 legacy wrapper 递归转换成真实 `FilterExpression`；
- resolver 是幂等的，typed 输入不产生新对象或二次转换。

各执行路径在最早可用边界解析并立即使用解析结果：

1. WebFlux 在请求过滤条件重写前解析，再追加 tenant/owner/space filter。
2. 通过 Spring 查询代理执行的 JVM 查询在 `QueryHandler` 创建 `QueryContext` 前解析。
3. 直接调用存储 `QueryService` 时，在 filter normalization/编译前解析。

后续边界可以安全调用同一 resolver；typed 输入为无操作，因此同一次执行最多发生一次实际 legacy 转换。

### Condition 映射

| Condition operator | FilterExpression |
|---|---|
| `ALL` | `MatchAllFilter` |
| `AND` / `OR` / `NOR` | 递归映射为 `AndFilter` / `OrFilter` / `NorFilter` |
| `ID` / `IDS` | `IdFilter` / `IdsFilter` |
| `AGGREGATE_ID` / `AGGREGATE_IDS` | `AggregateIdFilter` / `AggregateIdsFilter` |
| `TENANT_ID` / `OWNER_ID` / `SPACE_ID` | `TenantIdFilter` / `OwnerIdFilter` / `SpaceIdFilter` |
| `TRUE` / `FALSE` | 对应字段上的布尔 `EqualFilter` |
| `EQ` / `NE` / 范围比较 | 对应 typed predicate |
| `IN` / `NOT_IN` / `BETWEEN` / `ALL_IN` | 对应集合 typed predicate |
| `CONTAINS` / `STARTS_WITH` / `ENDS_WITH` | 保留 `ignoreCase` 的字符串 predicate |
| `NULL` / `NOT_NULL` / `EXISTS` | `IsNullFilter` / `IsNotNullFilter` / `ExistsFilter` 或 `NotExistsFilter` |
| `DELETED` | `DeletionFilter` |
| `ELEM_MATCH` | 单个子节点直接作为 predicate，多个子节点组合为 `AndFilter` |
| `MATCH` | 有字段时生成指定 fields 的 `SearchFilter`，空字段时生成全字段搜索 |
| 相对时间 operators | 对应相对时间 filter，并保留 zone、pattern、time、days |

空逻辑节点、空 `ELEM_MATCH`、非法字段和值类型在适配阶段拒绝。

### 独立元数据 Filter

以下类型作为公开、可序列化的 `FilterExpression` 一等成员：

| FilterExpression | FilterOperator | JSON 载荷 |
|---|---|---|
| `IdFilter` | `ID` | `{"op":"ID","value":"..."}` |
| `IdsFilter` | `IDS` | `{"op":"IDS","values":["..."]}` |
| `AggregateIdFilter` | `AGGREGATE_ID` | `{"op":"AGGREGATE_ID","value":"..."}` |
| `AggregateIdsFilter` | `AGGREGATE_IDS` | `{"op":"AGGREGATE_IDS","values":["..."]}` |
| `TenantIdFilter` | `TENANT_ID` | `{"op":"TENANT_ID","value":"..."}` |
| `OwnerIdFilter` | `OWNER_ID` | `{"op":"OWNER_ID","value":"..."}` |
| `SpaceIdFilter` | `SPACE_ID` | `{"op":"SPACE_ID","value":"..."}` |

这些类型加入 Jackson polymorphic subtype、`FilterOperator`、Filter DSL、OpenAPI 和 JSON Schema。它们表达稳定的查询语义，而不是暴露存储字段：

- `IdFilter` / `IdsFilter` 查询存储文档 ID；
- `AggregateIdFilter` / `AggregateIdsFilter` 在 snapshot 查询中解析到文档 ID，在 event stream 查询中解析到 `aggregateId` 字段；
- tenant/owner/space filter 解析到对应消息元数据字段；
- plural filter 的空值在 legacy 适配时归一化为 `MatchNoneFilter`，公开 typed 载荷要求 values 非空。

MongoDB 与 Elasticsearch converter 直接编译这些类型；不先改写成 `EqualFilter` / `InFilter`，也不让 mapping resolver 猜测物理字段。

兼容反向转换把七种 Filter 还原为对应的 `Condition` operator，确保废弃的 `condition` 属性与 `toCondition()` 仍保留语义，而不是退化成带物理字段名的 EQ/IN。

### 集合相等兼容

旧入口允许 `Condition.eq/ne(field, collection)`，而新 HTTP filter 合同要求 EQ/NE 值为标量。为保留旧行为：

- `EqualFilter` / `NotEqualFilter` 的运行时模型允许由 legacy resolver 生成数组值；
- 新 HTTP `filter` 请求继续在 trust boundary 强制 EQ/NE 标量；
- OpenAPI/JSON Schema 继续声明 typed filter 的标量约束；
- 不增加 `LegacyEqualFilter` 等实现层兼容类型。

## 实现层清理

### wow-query

- `QueryService.count(FilterExpression)` 和 `QueryHandler.count(FilterExpression)` 成为主方法；废弃的 Condition 重载只做单向入口适配。
- `AbstractQueryHandler`、`QueryContext`、snapshot/event handler 删除 Condition context 和运行时类型分支。
- `FilterNormalizer` 删除 `legacyConditionOrNull` 与 Condition 删除范围判断，只处理 typed tree。
- `AbacQueryFilter` 只生成和解析 FilterExpression；删除 `resolveCondition`、`toCondition` 等实现扩展兼容。
- Filter DSL 为七种独立元数据 Filter 提供直接构造入口。
- 删除 `AbstractConditionConverter` 与 `DeleteConditionGuard`。

### wow-webflux

- `QueryBodyExtractor` 对 single/list/paged 强制恰好存在 `condition` 或 `filter`。
- count 请求强制恰好匹配 legacy `operator` 或 typed `op` 形态；空对象不再隐式表示 match-all。
- legacy 请求仍按 Condition 解析并保留到执行边界。
- request rewrite、count handler 和 `HttpQueryGuardFilter` 删除 Condition 分支，只处理已解析的 FilterExpression。
- `HttpQueryGuardFilter` 对 `IdsFilter` / `AggregateIdsFilter` 应用现有 `maxConditionValues` 限制。
- 非法互斥状态继续映射为 `400` 和 `ILLEGAL_ARGUMENT`。

### wow-mongo

- `AbstractMongoConditionConverter` 更名为 `AbstractMongoFilterConverter`。
- event/snapshot converter 统一按 Filter 命名并只接受 `FilterExpression`。
- 删除全部旧 Condition override；字段映射通过现有 `FieldConverter` 完成。
- 两类 converter 分别编译独立元数据 Filter，保留 snapshot/event stream 的 ID 语义差异。
- 默认 deletion scope 继续由 `FilterNormalizer` 处理。

### wow-elasticsearch

- `AbstractElasticsearchConditionConverter` 更名为 `AbstractElasticsearchFilterConverter`。
- event/snapshot converter 统一按 Filter 命名并只接受 `FilterExpression`。
- `ElasticsearchIndexMappingResolver` 删除 Condition resolve 路径。
- mapping resolver 将独立元数据 Filter 视为已确定语义，不做普通字段 mapping 推断。
- `ElasticsearchEventStore` 的内部查询从 Condition DSL 改为 Filter DSL。
- `_id`、`aggregateId`、nested path、全文检索和 mapping usage 规则保持不变。

### OpenAPI 与 Schema

公开合同继续只展示 typed `filter` 和 raw FilterExpression count body。旧 `condition` 仅作为运行时兼容入口，不加入新生成客户端合同。typed schema 新增七种独立元数据 Filter 及对应 discriminator；同步更新静态 JSON Schema、OpenAPI 快照和生成客户端可见合同。

## 错误处理

- HTTP 查询缺少两种表示、同时提供两种表示或混用 discriminator 时抛出 `IllegalArgumentException`，由现有异常处理映射为 `400 / ILLEGAL_ARGUMENT`。
- JVM legacy 输入在执行适配阶段校验；失败时抛出 `IllegalArgumentException`，不进入 query filter 或存储层。
- 不为无法映射的 legacy 值做静默降级，不回退到 Condition converter。
- 相同校验适用于 snapshot 与 event stream 查询。

## 测试策略

### wow-api

- 表驱动覆盖所有 legacy Operator 到 FilterExpression 的映射。
- 覆盖七种独立元数据 Filter 的构造、JSON round-trip、空 plural 校验及 legacy 映射。
- 覆盖七种独立元数据 Filter 到对应 Condition operator 的兼容反向转换。
- 覆盖相对时间、search 和 element match。
- 覆盖 collection EQ/NE 兼容及 typed/legacy 单字段序列化。
- 证明 typed resolver 为同一对象，legacy resolver 返回非 wrapper typed tree。

### wow-query

- 更新 `QueryServiceCompatibilityTest`，证明 Condition 重载单向委托到 FilterExpression 主方法。
- 验证 QueryHandler 创建的 context 只包含 FilterExpression。
- 删除 Condition converter/guard 测试，保留 FilterNormalizer 的 deletion 与相对时间覆盖。
- ABAC 测试只断言 typed filter。

### wow-webflux

- single/list/paged 分别覆盖 condition-only、filter-only、both、neither。
- count 覆盖 legacy、typed、混用和空对象。
- 验证 legacy 请求经 request scope rewrite 后传给 QueryHandler 的是 typed filter。
- 验证 plural 元数据 Filter 受 `maxConditionValues` 约束。
- 保留新 HTTP filter 拒绝 collection EQ/NE、旧 condition 接受该语义的回归测试。

### wow-mongo 与 wow-elasticsearch

- converter 测试全部改用 FilterExpression。
- 覆盖七种独立元数据 Filter 在 snapshot/event stream 中的不同物理查询、删除范围、nested 和相对时间语义。
- 运行已有 snapshot/event query service 测试与 Elasticsearch integration test 的相关子集。

## 验证命令

```bash
./gradlew :wow-api:check :wow-query:check :wow-mongo:check :wow-elasticsearch:check :wow-webflux:check :wow-schema:check :wow-openapi:check
./gradlew detekt
```

如果完整相关模块检查成本过高，先运行失败点对应的单测，再运行上述模块级检查；完成声明必须以模块级检查结果为准。

## 完成条件

- 非兼容入口源码中不再 import 或运行时判断 `Condition`。
- MongoDB、Elasticsearch 不再存在 Condition converter 或 Condition override。
- 七种独立元数据 Filter 可通过 JVM、HTTP、OpenAPI 和 JSON Schema 使用，并由两种存储直接编译。
- 每条执行路径在 filter chain/storage 前得到真实 FilterExpression。
- HTTP XOR、legacy JVM/HTTP 调用和 collection equality 回归测试通过。
- 相关模块 check 与 detekt 通过。
