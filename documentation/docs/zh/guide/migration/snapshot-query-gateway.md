---
title: 快照查询网关迁移与生产门禁
description: 升级旧查询服务、校验 MongoDB/Elasticsearch 语义并完成可回滚切流。
outline: deep
---

# 快照查询网关迁移与生产门禁

本页适用于已经使用 `SnapshotQueryService` 的服务，以及准备把快照查询从 MongoDB 切换到
Elasticsearch 的服务。完成编译、启动或单元测试不等于生产可用；生产准入需要真实索引、历史数据、
授权、容量、故障和回滚证据。

## 兼容性边界

| 场景 | 行为 |
|---|---|
| 旧 `SnapshotQueryService` / `Condition` | 经过 Gateway 的授权与结果策略，但仍由原后端 converter 编译，保留历史后端语义 |
| `IListQuery.limit == 0` | 默认仍为无限流；配置全局 `maxRecords` 后会在达到预算时以 `INCOMPLETE_RESULT` 结束 |
| 旧 projection/sort 动态路径 | projection 与 sort 继续由所选后端验证和编译 |
| 旧投影同时 include 与 exclude | 后端同时执行 include 与 exclude，不静默丢弃任一部分 |
| `DeletionState.DELETED` / `ALL` | 兼容层授予旧接口所需的删除查询权限；新 Gateway 调用方必须显式持有 `query:snapshot:deletion` |
| 新 Gateway 的 storage route 未配置查询后端 | 应用可以启动；实际 Gateway 查询返回 `BACKEND_NOT_READY` |
| 旧 `NoOpSnapshotQueryService` | 为保持公共兼容性，仍返回空结果或 0；生产检查必须先确认 route，不能把空结果当作“确实无数据” |
| 递归对象、Map 和动态状态 | Schema 将其作为不可查询的 opaque 字段；如需查询内部字段，应提供显式 `QuerySchemaProvider` |

`LegacyConditionExpression` 与 `QueryProjection.Legacy` 只服务于进程内兼容层，不是远程 Query JSON 协议。
新客户端应使用 `PredicateExpression`、`SearchExpression`、`ElementMatchExpression` 等后端中立类型。

## 第一阶段：固定基线

升级前记录并保留以下证据：

1. 当前 Wow 版本、应用 commit、聚合列表、bounded-context alias 和 storage route。
2. 每个聚合的事件数、快照数、active/deleted 数量、最大版本和最新事件时间。
3. MongoDB collection 的索引，或 Elasticsearch 实际 index/alias 的 mapping、settings、文档数和健康状态。
4. 代表性 single/list/paged/count 查询的请求、总数、顺序、投影和结果摘要。
5. 可恢复备份、恢复演练结果、允许停写/双写的责任人和回滚窗口。

快照是派生数据，事件流仍是真相来源，但这不等于可以跳过备份。重建失败、错误路由或新旧 writer 并存
仍可能破坏当前查询与回滚路径。

## 第二阶段：配置安全边界

### 授权

生产调用必须把认证后的身份转换为 `QueryAuthority` 并写入 Reactor Context。不要从请求体信任
`tenantId`、`ownerId`、`spaceId` 或权限集合。

```kotlin
gateway.streamRecords(query)
    .contextWrite(
        QueryContexts.withAuthority(
            QueryAuthority(
                subjectId = principal.id,
                tenantId = principal.tenantId,
                ownerId = principal.ownerId,
                spaceIds = principal.spaceIds,
                permissions = principal.permissions
            )
        )
    )
```

认证映射必须保留 `spaceIds` 三态：`null` 表示不施加空间限制，空集合表示没有任何空间权限，非空集合表示
allowlist。不要把“没有空间权限”转换成 `null`。

至少用两个租户和无权限主体验证：跨租户查询被拒绝、scope 只能收窄、deleted/ALL 无权限时被拒绝、
字段策略在 filter/sort/projection 上都生效。应用自定义 `QueryPolicy` 时，任一 `DENY` 应保持最高优先级。

### 预算

默认无限预算只用于保持旧 `limit == 0` 行为，不是推荐的生产配置。按服务容量提供 `QueryLimits` Bean，
并对外层 HTTP 请求体、并发数和响应大小继续限流。

```kotlin
@Bean
fun queryLimits() = QueryLimits(
    maxPageSize = 200,
    maximumBudget = QueryBudget(Duration.ofSeconds(5), 10_000)
)
```

先迁移所有依赖无限流的调用。预算终止发生在已输出部分记录之后时，调用方只能丢弃并从头重试。

## 第三阶段：校验后端

### MongoDB

- 为 equality/range/sort 的高频组合建立与真实过滤顺序匹配的索引，并通过 `explain` 验证。
- 全文查询要求请求字段集合与 collection 唯一 text index 的字段集合完全一致。
- 对象数组使用 `$elemMatch`；用至少两层嵌套数组做语义回归。
- 禁止依赖超大 `skip`。分页必须有稳定排序；批量导出使用有界 stream。
- 用 null、missing、空数组和空字符串分别验证 presence 操作符。

### Elasticsearch

先解析实际 snapshot index/alias；名称使用 bounded-context alias。对每个实际 index 获取 mapping 与 settings，
不要只检查 template。

- exact/filter/sort 字段必须是可索引的 keyword、boolean、number 或 date；sort 还需要 doc values。
- text 字段同时用于 exact 时，必须只有一个满足严格 exact 语义的 keyword 子字段，或通过自定义
  `ElasticsearchSnapshotQueryBackend` 的 `exactSubfields` 明确指定。
- 全文字段必须是 indexed text；当前 Gateway 只接受 standard analyzer 语义。
- `ElementMatchExpression` 对应字段必须为 `nested`，普通 object mapping 会返回 `BACKEND_NOT_READY`。
- 新 Gateway 明确拒绝 `NE`、`NOT_IN`、`IS_NULL`、`IS_NOT_NULL`、`EXISTS`、`IS_EMPTY` 等无法在现有
  `_source`/mapping 上可靠区分 null 与 missing 的 presence 查询；不要降级成
  `must_not exists` 等可能扩大结果的表达式。
- page 的 `from + size` 默认不超过 10000；更大结果集使用有界 stream，stream 通过 PIT 与
  `search_after` 保持同一次读取视图。

:::warning 已有索引
更新 `wow.*.snapshot` template 只影响之后创建的索引。已有字段类型不正确时，应创建新索引/别名，应用
正确 mapping 后重建或受控 reindex；不要尝试把已有 text 字段原地改成 keyword。
:::

如果需要自定义 PIT 页大小、keep-alive、result window、mapping cache TTL 或 exact 子字段，请提供自己的
后端 Bean；自动配置会回退，不再创建第二个同类型 Bean。mapping/settings 默认按实际 index 缓存 30 秒；
元数据传输失败应按 `BACKEND_FAILURE` 监控，而不是按 mapping 未就绪处理。

## 第四阶段：重建与对账

仅在同一后端升级且旧查询 mapping 已满足要求时，Gateway 本身不要求新增 presence metadata。切换后端、
修正字段类型或新建索引时，必须重建历史快照。

1. 在隔离环境创建目标 collection/index 与 mapping。
2. 固定 writer 策略，避免旧、新存储同时成为不受控主写者。
3. 使用生成的 batch snapshot regeneration 路由，或经审查的同等事件回放流程，从 EventStore 重建目标快照。
4. 分批记录 `afterId`、成功、跳过、失败和耗时，失败批次必须可重复执行。
5. 按聚合对账总数、active/deleted、tenant、aggregateId、version、state hash 和时间字段。
6. 对代表性旧查询同时读取旧/新后端，比较结果集合、总数、顺序、投影和错误码。

不要用“目标索引存在”或“batch 返回 2xx”代替对账。`version_offset` 策略可能让快照查询有意落后；需要
当前状态读模型时，迁移期间与迁移后都应使用并验证 `strategy: all`。

## 第五阶段：灰度与回滚

先灰度单个聚合或小流量，不要同时切换全部 route。观察窗至少覆盖峰值流量、PIT 生命周期、慢查询、超时、
预算终止和后端重连。

回滚前必须明确：

- 旧后端是否持续同步，还是需要补写灰度期间的新快照；
- 何时停止新 writer，如何等待在途 `SNAPSHOT` 阶段完成；
- route、index alias 和应用版本分别如何回退；
- 新写入出现后是否仍允许直接恢复旧数据备份；
- 谁有切流、回滚和删除旧索引的权限。

观察窗结束前不要删除旧索引、旧 collection、兼容代码或重建检查点。

## 错误与监控

监控 `QueryException.code` 和 `stage`，至少按聚合、后端和操作统计延迟、拒绝、超时、预算终止、后端未就绪、
部分流失败及物化失败。日志不得记录原始 filter、RAW 内容、authority、完整 state 或后端凭据。

| 错误 | 处理 |
|---|---|
| `INVALID_QUERY` / `POLICY_DENIED` | 修正请求或权限，不自动重试 |
| `UNSUPPORTED_QUERY` | 改写查询或选择支持该语义的后端 |
| `BACKEND_NOT_READY` | 停止切流，修复 route/index/mapping/index 后再验证 |
| `DEADLINE_EXCEEDED` / `BUDGET_EXCEEDED` | 收窄查询或调整经过容量验证的预算 |
| `INCOMPLETE_RESULT` | 丢弃已经收到的部分流，从头重试 |
| `RESULT_INVALID` / `MATERIALIZATION_FAILED` | 视为 Schema/数据兼容事故，停止灰度 |
| `BACKEND_FAILURE` | 根据后端健康和幂等策略做有界重试；计数分片失败也属于该错误 |

Gateway 当前不替应用定义 SLO、告警和 trace/span 命名。没有生产监控面板、告警演练和 on-call runbook 时，
不能仅凭框架测试通过宣布生产准入。

## Go / No-Go 清单

以下项目必须全部有当前环境证据：

- [ ] 应用 commit、依赖和配置已冻结，完整 CI 通过。
- [ ] 所有 route 都解析到预期后端；不支持动态查询的 route 已显式排除。
- [ ] 自定义授权、scope、删除权限和字段权限已做负向测试。
- [ ] `QueryLimits`、HTTP 限流和并发上限已按容量测试配置。
- [ ] MongoDB index 或 Elasticsearch mapping/settings 已按实际 collection/index 审计。
- [ ] 历史快照已重建，并与 EventStore/旧后端完成逐聚合对账。
- [ ] single/list/paged/count、null/missing、nested、全文、排序和投影已做语义对比。
- [ ] 峰值负载、后端超时、分片失败、连接中断、PIT 取消和部分流失败已演练。
- [ ] 监控、告警、on-call runbook、灰度负责人和回滚负责人已确认。
- [ ] 回滚已演练，旧数据与旧 route 在观察窗内保持可用。

任一项缺失时，结论应为 `NO-GO` 或 `MISSING EVIDENCE`，而不是“有条件通过”。
