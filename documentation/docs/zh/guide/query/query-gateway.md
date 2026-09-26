---
title: 查询网关
description: 聚合级请求准备、作用域、授权、校验和响应处理的固定顺序。
---

# 查询网关

`SnapshotQueryGateway<S>` 与 `EventStreamQueryGateway` 是业务查询入口。Spring Registrar 装配时按聚合取得路由后的 Backend 与 `QuerySchemaCatalog` 的 Schema Provider 并固定使用，每次查询不重新路由。Gateway 还以同一份 Schema 和自身的 `QueryEntryPolicy` 生成能力描述（`describe(entry)`），HTTP 描述与准入共用一份预算。

## 固定执行顺序

每次订阅独立执行以下步骤。第 0 到 6 步由同一个 `QueryAdmission` 执行（Gateway 每种查询调用它一次），任何入口都跳不过其中一步。加载路由的选择（URL 中的聚合 id 与版本范围）在第 3 步与调用方 scope 一起作为操作约束追加，它不是调用方 scope：`QueryFilter` 看不到也删不掉它，审计也不把它列为 scope 字段。

0. 准入入口并检查预算：从 Reactor Context 读取一次查询入口，入口为 `HTTP` 的查询必须符合 `wow.query.http.*` 预算（`QueryEntryPolicy`）。这一步作用于提交的原始 Query，发生在任何 Schema 或存储操作之前。
1. 从 Provider 取得一个 Schema；开启 `wow.query.require-authenticated-scope=true` 时，已认证 scope 未固定 `tenantId` 的 `HTTP` 查询在此被拒绝。
2. 按顺序执行 `QueryFilter.prepare`，每个 Filter 只返回一个准备后的逻辑 Query。
3. 追加 Reactor Context 中的调用方 scope。
4. Snapshot 与 EventStream Gateway 通过公共策略链追加已配置 `QueryPolicy` 的规则条件；普通 Filter 不能把它提前删除。
5. 追加模型默认范围：Snapshot 在 Query 未声明删除范围时补充 `DELETION = ACTIVE`，EventStream 不补充删除条件。
6. 准入收尾：把字段别名替换为规范字段，为游标查询追加模型唯一排序字段，按 Schema 校验 Query，规范化（相对时间、派生操作符、逻辑化简）并解析每个字段引用，得到 `AdmittedQuery`。
7. 以 `AdmittedQuery` 调用一个 Backend 原语：`stream`、`page`、`count` 或 `aggregate`。single、list、paged、cursor 都建立在 `stream` 与 `page` 之上。
8. 对返回记录按同一 Schema 脱敏。
9. 按需进行 typed 物化。
10. `QueryObserver` 观察完成、错误或取消。

```mermaid
flowchart LR
    Entry["入口 + 预算"] --> Provider["Schema"]
    Provider --> Prepare["QueryFilter.prepare"]
    Prepare --> Scope["调用方 scope"]
    Scope --> Policy["QueryPolicy.evaluate"]
    Policy --> Default["模型默认范围"]
    Default --> Admission["收尾 · 校验 · 解析"]
    Admission --> Backend["Backend 原语(AdmittedQuery)"]
    Backend --> Mask["Mask"]
    Mask --> Result["ObjectNode / typed result"]
    Result --> Observer["Terminal observer"]
```

整个订阅只使用一个 Schema 版本：准入与脱敏都用它；Backend 的编译器读取准入按它解析、由 `AdmittedQuery` 携带的字段，不再重新查找 Schema。Schema 获取失败或 prepare 空完成都是错误，Backend 不执行。retry/repeat 会重新订阅并重新取得 Schema。count 返回 Long，不执行结果 Mask；aggregation 在执行前拒绝受保护的分组/metric/expression，不依靠修改聚合结果掩盖泄漏。

## 请求准备扩展

`QueryContext<Q>` 只包含 `query`、`namedAggregate`、`schema`、`queryType` 与 `entry`。Filter 没有 continuation、结果对象或结果处理权限。它只负责准备请求，不能包围或重复调用 Backend：

```kotlin
interface QueryFilter {
    fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q>
}
```

`SnapshotQueryFilter` 与 `EventStreamQueryFilter` 限定适用模型；普通 `QueryFilter` 可供两者使用。`@Order` 决定准备顺序。改写后的请求使用逻辑路径，仍须通过准入。

## 请求准备与强制约束

| 扩展点 | 返回值 | 组合规则 |
| --- | --- | --- |
| `QueryFilter.prepare` | 准备后的 Query | 后续 prepare 可以替换其 Query/filter |
| `QueryPolicy.evaluate` | 附加的逻辑 FilterExpression 或错误 | Gateway 在全部 prepare 之后以 AND 合并 |

两者都能构造过滤条件。允许被覆盖的请求准备使用 QueryFilter；必须在全部 prepare 后仍生效的条件使用 QueryPolicy。例如，必须满足的 `state.visible = true`、数据生命周期限制和权限条件都属于 Policy。QueryPolicy 的规则领域是通用的，执行权限限于追加约束或拒绝查询。内置模型默认值、Schema 校验、Backend 执行和结果处理保留原有职责。

策略与 `QueryFilter` 一样按 `@Order` 顺序逐个执行，未标注顺序的保持注册顺序。策略条件以 AND 合并，所以顺序只决定先报哪个策略的错误、审计中哪条记录在前。

## 请求作用域与策略

WebFlux Handler 用 `QueryRequestScope` 解析 tenant/owner/space，把结果放入 Reactor Context，再调用 Gateway。路由把查询入口标为 `HTTP`，因此 Gateway 在准入第 0 步检查 `wow.query.http.*` 预算。`HttpQueryGuard` 只在 Gateway 之外保留 HTTP 适配器自己的职责：响应行数上限、`limit=0` 默认值、空闲超时与缓冲；它不是 Gateway Filter。

JVM 调用可以显式提供受信 scope：

```kotlin
queryGateway.dynamicList(query)
    .contextWrite { context ->
        context.withQueryScope(TenantIdFilter("tenant-1"))
    }
```

`withQueryScope` 组合已有 scope。身份认证仍由应用承担；不要把未验证的请求字段当作身份。`QueryContext` 还带有操作类型（`queryType`）与查询入口，过滤器或策略可据此区分 count 与 list、HTTP 查询与进程内查询。Snapshot 与 EventStream 的 Spring 注册器都会装配 `QueryPolicy`，公共 Gateway 统一执行策略。策略可读取 `QueryContext` 判断适用范围；不适用时返回 `MatchAllFilter`。`AbacQueryPolicy` 仅对 Snapshot 解析 Principal 标签并生成标签条件，在其他模型上返回 `MatchAllFilter`，不读取标签。其他策略可直接实现 `QueryPolicy.evaluate`，表达数据生命周期或业务查询约束，无需提供 Principal 标签。

策略返回附加的逻辑过滤条件或错误，由 Gateway 在固定阶段以 AND 合并，再应用模型默认值和公共校验；不能替换 Query、调用 Backend 或变换结果。返回 `Mono.empty()` 是协议错误，不能用于表达“不适用”。策略失败会终止查询，Backend 不执行。完整权限合同见[数据权限](../data-access.md)。

### 范围来源

调用方范围的每一部分都有来源：`AUTHENTICATED`（来自凭证，或由受信组件担保）或 `DECLARED`（请求自报，即路径变量或请求头）。两者都限制查询，只有已认证的范围构成安全边界。

- `QueryRequestScope` 返回 `QueryScope(authenticated, declared)`。`DefaultQueryRequestScope` 把聚合的静态租户记为已认证，从请求读取的一切记为自报；`CoSecQueryRequestScope` 相同。
- 当某个值由受信组件掌控（例如会剥离客户端自带租户请求头的认证网关），继承 `AbstractQueryRequestScope` 并覆盖 `tenantIdProvenance`、`ownerIdProvenance` 或 `spaceIdProvenance`，返回 `AUTHENTICATED`。
- 进程内调用方用 `withQueryScope(QueryScope(authenticated = TenantIdFilter(tenantId)))` 写入已认证范围；`withQueryScope(filter)` 记为自报。
- `wow.query.require-authenticated-scope=true` 拒绝已认证范围未固定 `tenantId` 的 Snapshot 或 EventStream `HTTP` 查询：返回 `403`，错误码 `IllegalAccessQueryScope`，发生在任何后端 I/O 之前。自报的租户仍会过滤查询，但不满足这项检查。开关默认关闭，保持旧行为，即信任自报范围。

## 查询入口

每个查询都在 Reactor context 中带着一个入口：`HTTP`、`IN_PROCESS` 或 `UNSPECIFIED`。内置的 REST 查询路由在它们共用的那一处连同请求范围一起写入 `HTTP`。Gateway 在查询被订阅时读取一次入口。

- `UNSPECIFIED` 按进程内处理，现有的 `QueryGateway` 调用方不受影响。设置 `wow.query.require-explicit-entry=true` 后，未声明入口的查询会被拒绝。
- 在 `QueryFilter`、`QueryPolicy` 或缓存加载器内部发起的查询，不应继承 HTTP 调用方的范围与入口。用 `asInProcessQuery()` 包裹，它会清掉两者并以 `IN_PROCESS` 执行：

```kotlin
snapshotQueryGateway.dynamicList(lookup).asInProcessQuery()
```

## 被拒绝的查询

客户端写错的查询返回 HTTP 400 与 `ErrorInfo`。请求本身与预算的问题，`errorCode` 为 `IllegalArgument`；模型不提供的字段与能力，`errorCode` 为 `QuerySchemaValidation`。`errorMsg` 用文字说明错在哪里，`bindingErrors` 带一条可供程序判断的记录：

```json
{
  "errorCode": "QuerySchemaValidation",
  "errorMsg": "Unknown logical field [state.missing].",
  "bindingErrors": [{ "name": "state.missing", "msg": "Unknown logical field [state.missing].", "code": "UNKNOWN_FIELD" }]
}
```

`code` 取自 `QueryErrorCodes`，也作为 OpenAPI 中 `BindingError.code` 的枚举公开。代码只增不改名，遇到未知代码按一般错误处理。请求体的问题，`name` 是 JSON 路径（无路径时为 `body`）；准入的问题，`name` 是逻辑字段的绝对路径（元素作用域内的字段写完整路径，例如 `state.items.price`；模型级问题为空）。

| 代码 | 含义 |
|---|---|
| `INVALID_JSON`、`BODY_NOT_OBJECT`、`EMPTY_BODY` | 请求体不是 JSON 对象 |
| `UNKNOWN_PROPERTY`、`UNKNOWN_TYPE`、`UNKNOWN_VALUE`、`INVALID_VALUE` | JSON 与查询类型不符：未知属性、未知 `op` 或指标类型、未知枚举值，或取值类型错误、缺失 |
| `INVALID_REQUEST` | 其他请求规则，`msg` 写明是哪条 |
| `CURSOR_SORT_DUPLICATE`、`CURSOR_SORT_TOO_MANY` | 游标排序重复了某个字段（`name`），或追加身份字段作为唯一排序后超出字段上限 |
| `INVALID_CURSOR` | 游标令牌不是为这个模型与有效排序签发的（`name` 为 `cursor`） |
| `SIZE_OUT_OF_RANGE`、`FILTER_TOO_LARGE` | 入口预算（`wow.query.http.*`）限定的数量超出范围：limit、页码、页大小、分页窗口或游标页大小（`name` 为 `limit`、`pagination.size` 等），或过滤与 HAVING 的节点数、取值数（`name` 为 `filter`、`having`） |
| `EXPENSIVE_OPERATOR_DISABLED`、`COUNT_REQUIRES_FILTER` | 入口不允许高开销运算：`msg` 写明的运算符或聚合特性，或匹配全部记录的计数查询 |
| `RESIDUAL_GROUPS_EXCEEDED` | 查询服务自行计算 HAVING 或按指标排序时读到的分组数超过 `wow.query.http.max-residual-groups`；可能在响应开始流式输出后才出现 |
| `EXPLICIT_ENTRY_REQUIRED` | 仅进程内：网关要求每个查询写明入口 |
| `UNKNOWN_FIELD`、`UNSUPPORTED_CAPABILITY`、`ELEMENT_SCOPE_REQUIRED`、`VALUE_MISMATCH`、`NOT_COLLECTION`、`NOT_SINGLE_STRING`、`MODEL_SEARCH_UNSUPPORTED`、`CURSOR_NOT_ALLOWED`、`PROTECTED_AGGREGATION`、`PROTECTED_COMPARISON`、`MISSING_KEY_REQUIRES_STRING`、`ANY_REQUIRES_SINGLE_VALUE`、`INCOMPLETE_PROJECTION`、`METRIC_FILTER_SEARCH`、`METRIC_FILTER_ELEMENT_MATCH`、`METRIC_FILTER_ARRAY_FIELD`、`NOT_PROJECTABLE`、`EVENT_PROJECTION_TYPE_REQUIRED`、`TEMPORAL_REPRESENTATION_REQUIRED`、`TEMPORAL_CONFIGURATION_CONFLICT`、`PARALLEL_ARRAY_SORT`、`ARRAY_EQUALITY`、`FIRST_LAST_REQUIRES_SINGLE_VALUE`、`FIRST_LAST_REQUIRES_ORDER_BY`、`TEMPORAL_AGGREGATION_UNSUPPORTED`、`SORT_TOO_MANY`、`SORT_FIELD_DUPLICATE`、`IDENTITY_UNDEFINED` | 模型在准入时拒绝 |
| `STORAGE_UNSUPPORTED` | 存储无法执行查询用到的特性（`msg` 写明）：存储声明不支持的，在准入时拒绝；原生查询语言表达不了的，由后端在任何 I/O 之前拒绝 |

`UNKNOWN_FIELD` 所在行之前的代码，`errorCode` 为 `IllegalArgument`；该行及之后的为 `QuerySchemaValidation`。客户端改请求也无法解决的失败（脱敏策略执行失败、存储的记录或后端返回的行破坏完整性、存储超时或分片失败、其他存储或驱动错误）是服务端故障：HTTP 500，`errorCode` 为 `InternalServerError`，不带 `bindingErrors`。存储或驱动错误的文案为 `Query storage failed.`；它自己的消息可能引用查询取值，只留在服务端，查询日志中也会隐去。服务端故障可以重试，修改查询没有帮助。

## 结果与观察

Backend 每次订阅返回独占 ObjectNode。框架固定在 typed 物化前执行 Mask，没有通用结果 Filter。`QueryObserver` 只有终止回调，不能替换结果或错误；普通 observer 异常被记录，不能重试查询或触发第二次 Backend 执行。默认实现为 `QueryLogObserver`。

设置 `audits = true` 的 observer 还会在每次订阅终止时收到一个 `QueryAudit`，其中包括：

- 查询入口、模型及其内容哈希 `modelVersion`；
- 调用方所提交查询的形状 `fingerprint`：逐节点遍历查询得出，而不是对 JSON 按键名脱敏；包括运算符、字段、每个运算符给出的取值个数、排序、投影、分组与指标，以及分页方式与大小。任何取值都不在其中，包括区间上下界、天数、偏移、时区、检索文本、`HAVING` 界限、常量、别名、页码与游标，只在取值上不同的查询得到相同的指纹；
- 调用方范围限制的字段 `scopeFields`，以及实际施加了限制的 `policies`；
- 返回的行数 `rows`、响应中带有的脱敏字段 `maskedFields`（按准入后的投影计算，别名已换成规范字段；准入前即被拒绝的查询为空）、结果 `outcome`，以及失败时的 `errorCode`（有规则代码时一并给出）；
- 订阅上下文 `context`，应用从中读取主体：身份不归 Wow 管。

审计中永远不含过滤条件的取值，`toString()` 也不输出上下文，因此记录审计不会把个人数据写进日志。不做审计的 observer 没有额外开销：只有需要时 Gateway 才构建审计。

直接调用 Factory 的 Backend 会绕过这些治理步骤，见[查询后端](./query-backend.md)。Mask 与 Schema 的细节见[字段脱敏](./masking.md)和[查询模型 Schema](./query-model-schema.md)。
