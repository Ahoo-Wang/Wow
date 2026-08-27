# Fetcher Wow 聚合 ANY 指标对齐设计

## 状态

已确认，待实施计划。

## 背景

本次以 Wow `main@0e3dfbadc` 为协议基线。相对 Wow `v8.13.0`，唯一新增的公开查询
线协议是聚合指标 `ANY`；同一区间的其他查询提交只调整服务端反序列化和 Query
Schema 校验，不需要 Fetcher 增加对应 API。

Fetcher `3.18.0` 已提供 `AggregationMetric` 判别联合与 `aggregation.*` 函数式
DSL，但当前仅覆盖 `COUNT` 和 `NUMERIC` 指标。

## 目标

- 对齐 Wow 的 `ANY` 聚合指标线格式。
- 保持现有字段泛型推导与普通对象 DSL 风格。
- 复用现有字段路径和聚合 alias 校验。
- 同步公开 API 的包文档、Skill API 与双语 Wiki。
- 验证 Generator 能消费 Wow 当前 OpenAPI，但不主动修改 Generator。

## 非目标

- 不新增或调用 `snapshot/schema`、`snapshot/schema/refresh`。
- 不增加客户端 Schema 缓存、能力校验、结果选择逻辑或动态 DSL。
- 不修改 `SnapshotQueryApi`、`SnapshotQueryClient` 或查询端点。
- 不修改 Generator、集成测试工作流或 Wow 镜像版本；若真实 Generator 验证失败，
  回到设计阶段重新确认范围。
- 不新增依赖、文件级抽象、链式 Builder 或兼容别名。
- 不再次更新 monorepo 版本；当前未发布版本保持 `3.18.0`。

## 公开 API

在现有 `packages/wow/src/query/aggregation.ts` 中扩展：

```ts
export enum AggregationMetricType {
  COUNT = 'COUNT',
  NUMERIC = 'NUMERIC',
  ANY = 'ANY',
}

export interface AnyAggregationMetric<FIELDS extends string = string> {
  type: AggregationMetricType.ANY;
  field: LogicalField<FIELDS>;
  alias: string;
}

export type AggregationMetric<FIELDS extends string = string> =
  | CountAggregationMetric
  | NumericAggregationMetric<FIELDS>
  | AnyAggregationMetric<FIELDS>;
```

`aggregation` 增加一个无状态构造函数：

```ts
aggregation.any('productName', 'productName');
```

它返回：

```json
{
  "type": "ANY",
  "field": "productName",
  "alias": "productName"
}
```

实现直接复用 `aggregationField(field)` 和 `aggregationAlias(alias)`。不新增通用
validator，也不复制路径或 alias 规则。

## Wow 源码语义

`ANY` 是指标而不是分组：它在当前聚合范围或每个现有分组中返回一个非空标量，
不会增加分组键或拆分结果行。

字段解析遵循现有聚合相对路径规则：没有 Element 时是快照绝对路径；有 Element 时，
字段相对最内层 Element。Wow Query Schema Resolver 要求字段具备
`AGGREGATE_TERMS` 能力且 `cardinality` 不是 `MANY`，因此集合字段不属于有效输入。

后端实现有意不同：

- MongoDB 使用 `$max` accumulator。
- Elasticsearch 使用 `terms` aggregation，`size = 1`。

所以契约只保证返回一个非空值，不保证选择哪个值。调用方不得依赖跨后端、数据变化
前后或重复执行时返回相同代表值。缺失、全为 `null`、未映射或空数据集时结果为
`null`，结果 alias 仍存在。字符串、数字和布尔值按现有后端中立结果转换返回。

按 `ANY` alias 排序属于 metric sort，继续受 Wow 的昂贵查询护栏控制。

Fetcher 不具备且本次不引入运行时 Query Schema，因此只校验本地可准确判断的字段路径
语法和 alias 规则。字段能力、基数、存储映射及昂贵查询策略仍由 Wow 服务端判定，错误
沿现有 Fetcher 请求流程返回。

## 数据流

1. 调用方使用 `aggregation.any(field, alias)` 构造普通对象。
2. `AggregationQuery` 通过现有 `SnapshotQueryClient.aggregate()` 或
   `aggregateStream()` 原样序列化。
3. Wow 按 Query Schema 解析字段并选择 MongoDB 或 Elasticsearch 实现。
4. 结果继续由调用方通过现有 `Row extends DynamicDocument` 泛型声明；Fetcher 不从
   alias 自动推导返回行类型。

## 修改范围

实现阶段只修改现有文件：

- `packages/wow/src/query/aggregation.ts`
- `packages/wow/test/query/aggregation.test.ts`
- `packages/wow/README.md`
- `packages/wow/README.zh-CN.md`
- `skills/fetcher-wow-cqrs/references/api.md`
- `wiki/packages/wow.md`
- `wiki/zh/packages/wow.md`

若实现发现必须修改列表外的源码、配置或 Generator，立即停止并重新确认设计。

## 测试与验证

包级测试覆盖：

- `AggregationMetricType` 包含 `ANY`。
- `aggregation.any()` 生成精确线格式。
- 字段泛型限制保持有效。
- 非法字段路径、含点 alias 与 `__wow` 保留前缀继续被现有校验拒绝。
- `ANY` 与 `COUNT`、`NUMERIC` 能共存于同一非空 metrics tuple。

验证顺序：

1. `pnpm --filter @ahoo-wang/fetcher-wow test`
2. `pnpm build`
3. `pnpm test:unit`
4. `pnpm --dir wiki build`
5. 对变更 Markdown 运行 Prettier check，并执行 `git diff --check`
6. 构建 Generator 后，以 Wow
   `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json` 运行真实 CLI
   生成；只验证兼容性，不因成功验证产生 Generator 源码 diff

实现完成不等于发布。本设计不授权创建 PR、合并、发布或部署。
