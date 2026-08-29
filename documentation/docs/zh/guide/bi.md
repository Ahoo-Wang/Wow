---
title: 商业智能
description: 从 Wow 命令与状态事件流生成并运行 ClickHouse 读模型。
---

# 商业智能

## 传统架构 VS 事件溯源

传统报表管线通常从可变业务表抽取数据，还要推断“发生了什么变化”。Wow BI 则把不可变命令与状态事件
Kafka topic 投影到 ClickHouse。事件存储仍是事实源，ClickHouse 是可重建的分析读模型。

<p align="center" style="text-align:center">
  <img width="95%" src="/images/eventstore/eventsourcing.svg" alt="事件溯源与传统数据存储对比"/>
</p>

这一归属边界直接决定操作方式：

- 应用元数据定义目标 schema 与 view graph；
- Kafka retention 与 offset 决定可重放范围；
- ClickHouse 对象 Comment 与 ownership registry 标识当前 BI deployment 管理的对象；
- BI 行必须与事件/状态源完成对账，才能接受切换。

不要从 BI 表反写领域状态，也不要把 SQL 生成成功当作 ClickHouse 已追平的证据。

<p align="center" style="text-align:center">
  <img width="95%" src="/images/bi/bi.svg" alt="Wow 商业智能数据链路"/>
</p>

## 生成与获取 ETL 脚本

### 结构化结果 API

`BiScriptGenerator` 返回 SQL、明确 operation 与 diagnostics：

```kotlin
val options = BiScriptOptions(
    database = "bi_db",
    consumerDatabase = "bi_db_consumer",
    topology = ClickHouseTopology.Standalone,
    consumerGroupNamespace = "orders-blue",
)
val generator = BiScriptGenerator(options)
val preparation = generator.prepare(namedAggregates)
val result: Mono<BiScriptResult> = inspector
    .inspect(options, BiScriptOperation.Deploy, preparation)
    .map { inspection ->
        generator.generate(preparation, BiScriptOperation.Deploy, inspection)
    }
```

`BiScriptResult` 公开 `script`、`diagnostics`、`operation` 与 `destructive`；statement 边界是 internal，因此
执行器仍必须按渲染顺序运行 SQL，并在第一条失败时停止。`Reset(true)` 是唯一破坏性操作，要求可用的
deployment inspection，以及“新 Kafka consumer generation 可以从 earliest 重放”的明确确认。

只要生成任何 aggregate consumer，就必须配置 `consumerGroupNamespace`，以隔离同一 Kafka 集群上不同
应用的 BI ownership scope。

Diagnostics 是稳定的结构化审阅面：

| Code | 含义 |
|---|---|
| `RAW_JSON_FALLBACK` | 使用 scoped raw JSON 保存值，而不是 typed projection |
| `MAX_DEPTH_REACHED` | expansion 在 `maxExpansionDepth` 停止 |
| `INSPECTION_UNAVAILABLE` | 未获得权威 catalog observation 就生成了目标 SQL |
| `ORPHANED_DATA_TABLE` | 因归属/对账不足以安全删除，保留受管数据 |
| `CLUSTER_INTERNAL_REPLICATION_REQUIRED` | cluster 的 `internal_replication` 必须由外部配置 |
| `COMPUTED_OBJECT_DRIFT` | view/materialized-view 定义不同，已规划对账 |

`UnsupportedTypeStrategy.RAW_JSON` 是默认策略；`FAIL` 会拒绝不支持或无法验证的 shape，不生成 fallback
column。

### HTTP 路由

`wow.bi.script.enabled=true` 时，WebFlux 暴露 `POST /wow/bi/script`。请求体必填；`{}` 表示使用服务端选项
执行 `DEPLOY`。需要 diagnostics 和 destructive 标记时请求 JSON：

```bash
curl --fail-with-body \
  -X POST http://localhost:8080/wow/bi/script \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"operation":"DEPLOY"}'
```

JSON 响应为 `{ "script", "destructive", "diagnostics" }`；所有响应还带
`Wow-BI-Diagnostic-Count`。`Accept: application/sql` 只返回 SQL，不能作为破坏性操作的审批界面。

请求字段可以降低 `maxExpansionDepth`，并覆盖部分非 null 生成选项；但 `consumerGroupNamespace` 与 offset
storage 由服务端拥有。权威 inspector 会拒绝请求覆盖 `database`、`consumerDatabase` 或 `topology`，避免
inspection 与 generation 指向不同物理范围。`RESET` 必须提交 `replayFromEarliestConfirmed=true`；该字段
对 `DEPLOY` 非法。

默认 `NoOpBiDeploymentInspector` 明确返回 `Unavailable`。它只适合首次/离线 `DEPLOY` 预览并产生诊断，
不能清理旧对象、恢复已有 consumer identity 或执行 `RESET`。生产对账必须配置
`wow.bi.script.inspector.type=CLICKHOUSE` 与 ClickHouse endpoints，或提供自定义权威
`BiDeploymentInspector`。部署网关必须保护该操作路由。

## 生成的 SQL 契约

当前 renderer 拥有 protocol `3`、layout `7`。每个受管 ClickHouse 对象都带 `wow-bi:` JSON Comment，记录
deployment/configuration/topology fingerprint、对象 kind、适用时的 aggregate owner 与 consumer identity。
`__wow_bi_deployment` anchor 记录 deployment phase 与 registry revision。未知 protocol/layout 或不一致
registry 会 fail-closed；对象名称本身不能证明归属。

### Kafka Offset 生命周期

`KafkaOffsetStorage.BROKER` 是默认值。ClickHouse Kafka engine consumer group 包含 deployment consumer
identity。普通 `DEPLOY` 会保留稳定当前布局中观测到的 identity；确认的 `RESET` 创建或继续一个从 earliest
开始的 reset generation。

`KafkaOffsetStorage.KEEPER` 会增加 `kafka_keeper_path`、`kafka_replica_name` 与
`allow_experimental_kafka_offsets_storage_in_keeper=1`。Keeper 可用性、复制、Kafka retention 与 earliest
offset 行为都是外部前提，generator 无法证明。

### 部署拓扑

`ClickHouseTopology.Standalone` 直接创建 `ReplacingMergeTree` store。Cluster 模式创建
`ReplicatedReplacingMergeTree` local store 和 `Distributed` facade，并使用 `ON CLUSTER` 以及配置的
installation/cluster macro。运维方负责匹配的 ClickHouse cluster 配置与 `internal_replication`。

修改 database、consumer database、consumer-group namespace 或 topology 都会改变持久契约，必须按部署/
切换计划处理，不能视为无害的 SQL 重新生成。

### 聚合命令

每个 aggregate 的 command topic 会建立 Kafka queue table、consumer materialized view、物理 command store
与公开 `..._command` view。稳定列包括消息/聚合身份、owner/space/request 字段、aggregate version、
create/void 标记、command body 与 create time。公开 view 使用 `FINAL` 读取 store。

### 全量状态事件

state topic 使用相同的 queue → materialized view → store 模式。公开 `..._state` view 暴露完整状态事件
记录；`..._state_event` 使用一基 event sequence 展开 event body 数组及其元数据。存储的 `state` JSON 会
保留用于权威恢复。

### 最新状态

`..._state_last_store` 从 state store 填充，并按 tenant/aggregate identity 排序；公开
`..._state_last` view 通过 `FINAL` 得到最新版本。它是派生的 latest-state 读模型，不是源事件流。用于业务
切换前，应对账 aggregate 数量、最大 version、deleted 状态与代表性 replay。

### 根展开视图

expansion planner 读取已配置的 Jackson wire shape，在 `state_last` 上创建 root view。scalar 与经过验证的
structural property 生成 typed column。每个 root row 仍保留 `__state`，并在 `__path` 使用空 RFC 6901
pointer，因此 typed projection 不会删除恢复源。

### 子展开视图

每个经过验证的 object collection 都生成 child view。`arrayJoin` 为每个 element 生成一行，同时保留
parent identity、`__state`、零基 `__index` 与 `/orders/2/lines/5` 一类 RFC 6901 `__path`。Property segment
把 `~` 转义为 `~0`，把 `/` 转义为 `~1`。

### 可空类型与原始值

可空性按结构传播：nullable scalar → `Nullable(T)`，nullable element → `Array(Nullable(T))`，nullable map
value → `Map(String, Nullable(T))`。当 typed extraction 不能区分 missing、显式 `null` 与 nullable
container 时，view 增加 scoped `__raw__<property>` convenience column。

## 结构化类型与无损语义

### 空值传播规则

- nullable object ancestor 会让 typed descendant 全部 nullable；
- 只有 nullable ancestor 获得 raw companion，descendant 不重复生成；
- nullable object element 在 child view 中保留当前 raw element；
- 未标注的 Java reference type 按可能 nullable 处理，已证明的 Kotlin/Java non-null 契约保持 non-null；
- whole-value fallback 本身已是 raw，不再生成第二列。

### 权威状态恢复通道

`__state` 直接投影存储的 state string，不经解析与重新序列化，因此是 lexical authority。用 `__path`
source-slice 需要的 subtree/token；root row 使用空 path，child row 携带完整 pointer。`__raw__*` 便于查询，
但不是 lexical-authoritative。

`__raw__`、`__state`、`__path`、`__index` 与 `__cursor__` 名称/前缀由 generator 保留。

### Scoped Raw 查询便利值

在很多查询中，`JSONExtractRaw` 可以区分 missing（`''`）、显式 null（`'null'`）以及空数组/对象
（`'[]'`/`'{}'`）。ClickHouse 可能规范化数字拼写，因此精确恢复仍必须使用 `__state` + `__path`。

### 不支持的类型

object-valued map、非 String 或 nullable map key、未解析泛型与平台特定 object 无法安全投影。
`RAW_JSON` 生成 diagnostic 并保留 scoped raw value；`FAIL` 停止生成。达到最大 expansion depth 时使用
相同恢复规则，并生成 `MAX_DEPTH_REACHED`。

### 不透明 Jackson 形状

只有已配置 Wow `JsonSerializer` 能证明声明 object shape 与 JSON object 一致时，才允许递归展开。
polymorphic/abstract/sealed object、`@JsonValue`、`@JsonUnwrapped`、`@JsonAnyGetter`、自定义 serializer/
converter 以及其他无法验证的 shape 都保持 opaque，作为 raw 保存或由 `FAIL` 拒绝。

### 无损标量映射

| Wire 值 | 代表性 JVM 值 | ClickHouse 投影 |
|---|---|---|
| String | `String`、`Char`、普通 enum | `String` |
| Integer | 整数 primitive、`Year` | 精确 signed integer / `Int32` |
| Boolean | `Boolean` | `Bool` |
| Number | `Float`、`Double` | `Float32`、`Float64` |
| UUID string | `UUID` | `UUID` |
| ISO/string time | Java time/date/duration 值 | `String` |
| 任意精度数 | `BigDecimal` | scoped raw + `__state` 恢复 |

如果已配置 serializer 改变上述 wire shape，而 resolver 又无法证明新映射，该 property 会变为 opaque。

执行生成 SQL 前先阅读 [BI 部署与恢复](./bi-operations)。

<!-- Sources: wow-bi BiScriptGenerator/Options/PreparationPlanner, renderer package, expansion planner/type package,
BiDeploymentInspection, and expected_bi_*_script.sql; WebFlux GenerateBIScriptHandlerFunction -->
