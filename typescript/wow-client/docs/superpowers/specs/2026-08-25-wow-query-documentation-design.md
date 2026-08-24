# Fetcher Wow 查询文档同步设计

## 状态

已确认，待实施。

## 背景

`@ahoo-wang/fetcher-wow` 已完成两轮查询契约更新：查询条件以 Wow 8.11 的
`FilterExpression` 为主，并新增快照聚合查询类型及
`SnapshotQueryClient.aggregate()` / `aggregateStream()`。当前包 README 已介绍
`FilterExpression`，但尚未记录聚合 API；Skill API reference 同样缺少聚合；Wiki
仍以旧 `Condition` 示例为主。

## 目标

- 让包 README、Skill API reference 与 Wiki 同时覆盖最新快照聚合 API。
- 将 Wiki 的主要查询示例切换为 `FilterExpression`，旧 `Condition` 仅作为兼容 API
  说明。
- 中英文文档保持相同结构、示例和 API 事实。
- 所有签名、枚举值、端点和兼容性说明以 `packages/wow/src` 的当前导出为准。

## 非目标

- 不修改生成器文档、Wiki 导航、站点配置或构建产物。
- 不重写整个 Wow 文档，也不新增教程页面。
- 不修改任何运行时代码、类型、测试或依赖。
- 不引入聚合 DSL 或未实现的便捷构建器。

## 内容设计

### 包 README（英文与中文）

- 在特性列表中加入快照聚合查询。
- 在 `SnapshotQueryClient` 示例中加入一份可直接复制的聚合查询：按商品
  `TERMS` 分组，计算 `COUNT` 与 `SUM`，按合计值倒序并限制结果数。
- 展示通过结果行泛型声明 alias 对应字段，以及 `aggregateStream()` 的 SSE
  遍历方式。
- 在方法列表中补充两个聚合方法及其返回类型。

### Skill API reference

- 在目录和统一导入示例中加入聚合公共枚举、类型与 `DynamicDocument`。
- 在 `SnapshotQueryClient` 章节加入 JSON 与 SSE 调用示例。
- 新增 `AggregationQuery` 参考小节，完整列出 query 字段、Group、Metric、日期单位、
  数值函数与表达式类型。
- 说明 `metrics` 在类型层非空、结果行默认是 `DynamicDocument`，泛型仅提供静态
  类型，不做运行时解码。
- 说明 `SnapshotQueryApi` 中聚合成员为可选以兼容既有 mock/adapter，而具体
  `SnapshotQueryClient` 方法始终可调用；不引入 `SnapshotAggregationQueryApi`。

### Wiki（英文与中文）

- 将快照查询主示例改为 `filter.matchAll()` 与 filter-based request。
- 在方法表中加入 `aggregate()` / `aggregateStream()` 和
  `/snapshot/aggregation`。
- 新增精简的聚合查询示例，复用 README 中相同的请求形状。
- 将“查询 DSL”主章节改为 `FilterExpression`，列出当前 builder 分类；旧
  `Condition` 运算符表缩为兼容说明，避免继续把弃用 API 作为首选入口。
- 更新主要导出列表中的聚合类型与 `filter`。

## 示例契约

统一示例使用：

- 根过滤：`filter.eq('state.status', 'COMPLETED')`
- Element 展开：`state.items`
- 分组：相对 Element 的 `productId`，使用 `AggregationGroupType.TERMS`
- 指标：`AggregationMetricType.COUNT` 与
  `AggregationMetricType.NUMERIC` + `AggregationFunction.SUM`，后者汇总相对字段
  `quantity`
- 排序：`SortDirection.DESC`
- 结果行：`{ product: string; itemCount: number; totalQuantity: number }`

README 与 Wiki 只展示常用结构，完整枚举清单只在 Skill API reference 展开，避免
三处维护同一张大表。

## 兼容性边界

- 推荐新代码使用 `FilterExpression` 与 `filter.*`。
- 旧 `Condition` 仍可用于兼容旧 Wow 服务，不宣称已移除。
- `SnapshotQueryApi.aggregate?` 与 `aggregateStream?` 是可选成员；
  `SnapshotQueryClient.aggregate()` 与 `aggregateStream()` 是必需具体方法。
- JSON 与 SSE 共用 `POST snapshot/aggregation`，SSE 由
  `Accept: text/event-stream` 区分。

## 修改范围

- `packages/wow/README.md`
- `packages/wow/README.zh-CN.md`
- `skills/fetcher-wow-cqrs/references/api.md`
- `wiki/packages/wow.md`
- `wiki/zh/packages/wow.md`

## 验证

- 对照 `packages/wow/src/query/aggregation.ts`、`snapshotQueryApi.ts`、
  `snapshotQueryClient.ts` 和公共导出复核所有符号与签名。
- 运行 Prettier 检查、Wiki 构建、根 `pnpm test:unit` 与 `git diff --check`。
- 确认未修改 `llms-full.txt`、`llms.txt`、`.vitepress/dist` 或生成器文档。

## 完成标准

- 五个目标文档准确覆盖 `FilterExpression` 与快照聚合查询。
- 中英文示例语义一致，TypeScript 示例仅使用已导出的公共 API。
- 不出现 `SnapshotAggregationQueryApi`，也不暗示聚合方法在
  `SnapshotQueryApi` 上必需。
- 文档构建、格式和现有单元测试全部通过。
