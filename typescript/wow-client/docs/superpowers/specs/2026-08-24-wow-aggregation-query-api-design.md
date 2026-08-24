# Fetcher Wow 聚合查询 API 对齐设计

## 状态

已确认，待实现。

## 背景

Wow `origin/main` 的 `728942085`（`feat(query): add snapshot elements aggregation (#3017)`）新增了快照聚合查询。服务端公共契约由 `AggregationQuery.kt` 定义，并通过同一个 `POST snapshot/aggregation` 路由提供两种响应：普通 JSON 数组和逐行 SSE。

Fetcher 3.17.1 的 `packages/wow` 尚未定义该查询结构，也没有对应的快照查询方法。本次只对齐 `packages/wow`；生成器已经能读取当前 Wow 的查询字段元数据，不属于本次范围。

## 目标

- 为 Wow 当前 `AggregationQuery` 线协议提供直接、类型安全的 TypeScript 表达。
- 通过 `SnapshotQueryApi` 的可选聚合成员与现有 `SnapshotQueryClient` 同时提供 JSON 与 SSE 查询，同时保持 `SnapshotQueryApi` 的既有实现者兼容。
- 允许调用方通过泛型声明聚合 alias 对应的结果行类型，默认仍为 `DynamicDocument`。
- 复用 Fetcher 已有过滤器、排序、装饰器、SSE 提取和取消请求能力。

## 非目标

- 不修改 `packages/generator`、集成测试工作区、Skill 或 Wiki。
- 不新增聚合 DSL、构建器或运行时查询校验。
- 不新增独立的聚合客户端、工厂方法或配置项。
- 不在客户端推断 alias、补齐服务端默认值或校验后端字段类型。

## 公开类型

新增 `packages/wow/src/query/aggregation.ts`，由现有 `query/index.ts` 导出。

### 枚举

- `AggregationGroupType`：`TERMS`、`HISTOGRAM`、`DATE_HISTOGRAM`
- `AggregationMetricType`：`COUNT`、`NUMERIC`
- `AggregationExpressionType`：`FIELD`
- `AggregationDateUnit`：`YEAR`、`QUARTER`、`MONTH`、`WEEK`、`DAY`、`HOUR`、`MINUTE`、`SECOND`
- `AggregationFunction`：`SUM`、`AVG`、`MIN`、`MAX`

所有枚举值与 Wow JSON 字符串完全一致。

### 查询结构

`AggregationQuery<FIELDS extends string = string>` 包含：

- 可选根 `filter: FilterExpression<FIELDS>`
- 可选 `elements: AggregationElement[]`
- 可选 `groupBy: AggregationGroup[]`
- 必填且类型层非空的 `metrics: [AggregationMetric, ...AggregationMetric[]]`
- 可选 `sort: FieldSort[]`
- 可选 `limit: number`

除 `metrics` 外不在客户端补默认值，省略字段时由 Wow 使用其默认值。

`AggregationElement` 包含必填 `path: LogicalField` 和可选 `filter: ElementFilterExpression`。Element filter 使用现有受限类型，因此在类型层排除 ID、租户、所有者、空间、删除和搜索等根级过滤器。

Element 第一层路径是快照绝对路径，后续路径及每层 filter 都相对当前展开元素；group 与 metric 字段也可能相对最内层 Element。因此这些字段保持通用 `LogicalField`（即 `string`），不错误套用只描述绝对聚合字段的 `FIELDS` 枚举。`FIELDS` 只约束根 filter。

`AggregationGroup` 是三种判别联合：

- `TermsAggregationGroup`
- `HistogramAggregationGroup`
- `DateHistogramAggregationGroup`

`AggregationMetric` 是两种判别联合：

- `CountAggregationMetric`
- `NumericAggregationMetric`

`NumericAggregationMetric.expression` 使用当前唯一的 `FieldAggregationExpression`。其 `type` 为可选的 `AggregationExpressionType.FIELD`，因为 Wow 的 Jackson 默认实现同时接受 `{ field }` 与 `{ type: 'FIELD', field }`。Group 和 Metric 的 `type` 必填。

## 客户端 API

在 `SnapshotQueryApi<S, FIELDS>` 上增加可选聚合成员：

```ts
aggregate?<Row extends DynamicDocument = DynamicDocument>(
  query: AggregationQuery<FIELDS>,
  attributes?: Record<string, any>,
  abortController?: AbortController,
): Promise<Row[]>;

aggregateStream?<Row extends DynamicDocument = DynamicDocument>(
  query: AggregationQuery<FIELDS>,
  attributes?: Record<string, any>,
  abortController?: AbortController,
): Promise<ReadableStream<JsonServerSentEvent<Row>>>;
```

`SnapshotQueryClient<S, FIELDS>` 继续只实现 `SnapshotQueryApi<S, FIELDS>`，但其 `aggregate()` 与 `aggregateStream()` 具体方法保持必需，调用方获得的客户端无需空值判断。

这是 3.17.1 的兼容性边界：下游可能自行实现 `SnapshotQueryApi` 作为 mock 或 adapter。可选成员保留聚合键而不要求既有实现补齐方法；客户端仍直接暴露必需聚合方法，调用方无需新建客户端或工厂。

`SnapshotQueryEndpointPaths` 增加：

```ts
static readonly AGGREGATION = 'snapshot/aggregation';
```

`QueryClientFactory` 无需修改；它已经返回 `SnapshotQueryClient`，因此现有工厂创建的客户端自然获得新方法。

## 数据流

`aggregate()` 使用现有 `@post` 装饰器向 `snapshot/aggregation` 发送原始 `AggregationQuery`，沿用默认 JSON 结果提取，返回 `Row[]`。

`aggregateStream()` 发送相同请求体和路径，并设置 `Accept: text/event-stream`，复用 `JsonEventStreamResultExtractor`，返回逐行 `JsonServerSentEvent<Row>` 流。

两个方法都沿用现有 `attributes` 与 `AbortController` 参数，不转换请求体、不补默认值，也不增加新的中间层。

## 错误边界

Fetcher 不重复实现 Wow 已负责的结构校验，包括数量上限、alias 唯一性、sort alias 引用、interval、时区和 limit 范围。TypeScript 能静态表达的约束保留在类型中，其余交由服务端。

服务端结构错误、后端字段或 mapping 错误按现有 Fetcher 行为抛出；客户端不吞掉错误、不降级为空结果，也不统一不同后端的错误文本。JSON、SSE 和取消请求都沿用现有错误处理路径。

结果泛型只提供静态类型，不增加运行时解码。调用方声明的 `Row` 应与查询中 group/metric alias 生成的实际字段一致。

## 修改范围

实现预计只涉及：

- `packages/wow/src/query/aggregation.ts`
- `packages/wow/src/query/index.ts`
- `packages/wow/src/query/snapshot/snapshotQueryApi.ts`
- `packages/wow/src/query/snapshot/snapshotQueryClient.ts`
- `packages/wow/test/query/aggregation.test.ts`
- `packages/wow/test/query/snapshot/snapshotQueryApi.test.ts`
- `packages/wow/test/tsconfig.types.json`

不修改 `QueryClientFactory`、生成器或其他 package。

## 测试与验证

最小测试集：

- 验证所有新增枚举的线协议字符串。
- 用一份覆盖 Element、三种 Group、两种 Metric、排序和 limit 的查询验证实际对象形状。
- 类型检查根 filter 的 `FIELDS` 约束、Element filter 的受限操作符、非空 metrics，以及两个方法的泛型返回类型。
- 类型检查 `SnapshotQueryApi` 包含两个聚合键但不要求它们，保护既有下游 API 实现者；同时保留客户端 JSON 与 SSE 返回类型断言。
- 在现有端点测试中验证 `AGGREGATION === 'snapshot/aggregation'`。

验证命令：

```bash
pnpm --filter @ahoo-wang/fetcher-wow test
pnpm --filter @ahoo-wang/fetcher-wow build
pnpm --filter @ahoo-wang/fetcher-wow lint
pnpm test:unit
git diff --check
```

## 完成标准

- TypeScript 公共类型能表达 Wow 当前所有聚合查询变体及默认省略形式。
- 现有快照客户端能从同一路由获取 JSON 数组或 SSE 行流。
- 调用方可以显式声明结果行类型，未声明时返回 `DynamicDocument`。
- 既有 `SnapshotQueryApi` 实现者无需添加聚合方法；客户端聚合方法保持必需。
- 不增加新客户端、DSL、运行时验证、依赖或生成器改动。
- `packages/wow` 构建、测试、类型检查和 lint 通过，根单元测试通过。
