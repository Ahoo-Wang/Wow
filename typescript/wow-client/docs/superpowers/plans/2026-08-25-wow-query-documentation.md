# Fetcher Wow 查询文档同步 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 同步 `@ahoo-wang/fetcher-wow` 的中英文 README、Skill API reference 与 Wiki，使其准确记录 `FilterExpression` 和快照聚合查询。

**Architecture:** 运行时代码是唯一事实来源，五个现有文档在原章节内做最小更新。README 与 Wiki 共享同一份常用聚合示例，完整类型清单只放 Skill API reference；旧 `Condition` 仅保留兼容说明。

**Tech Stack:** Markdown、TypeScript 示例、VitePress、Prettier、pnpm。

**Spec:** `packages/wow/docs/superpowers/specs/2026-08-25-wow-query-documentation-design.md`

## Global Constraints

- 只修改五个目标用户文档及本计划，不修改运行时代码、测试、生成器或依赖。
- 英文与中文 README、Wiki 必须保持相同结构和 API 事实。
- 不新增 `SnapshotAggregationQueryApi`；必须准确区分 `SnapshotQueryApi` 的可选成员与 `SnapshotQueryClient` 的必需方法。
- 不修改 `llms-full.txt`、`llms.txt`、`.vitepress/dist`、Wiki 导航或站点配置。
- 示例只使用 `packages/wow/src/index.ts` 最终可导出的公共符号。
- 提交前运行根 `pnpm test:unit` 与 `git diff --check`。

---

## File Structure

- Modify `packages/wow/README.md`: 英文包概览、快照聚合示例和方法签名。
- Modify `packages/wow/README.zh-CN.md`: 与英文 README 对齐的中文内容。
- Modify `skills/fetcher-wow-cqrs/references/api.md`: 完整聚合公共 API 参考和兼容边界。
- Modify `wiki/packages/wow.md`: 英文 Wiki 的 FilterExpression 主路径、聚合用法与导出表。
- Modify `wiki/zh/packages/wow.md`: 与英文 Wiki 对齐的中文内容。

### Task 1: 同步包 README

**Files:**

- Modify: `packages/wow/README.md`
- Modify: `packages/wow/README.zh-CN.md`

**Interfaces:**

- Consumes: `AggregationQuery<FIELDS>`, `AggregationGroupType`, `AggregationMetricType`, `AggregationFunction`, `SortDirection`, `SnapshotQueryClient.aggregate<Row>()`, `SnapshotQueryClient.aggregateStream<Row>()`.
- Produces: 面向 npm 用户的中英文聚合查询入口。

- [ ] **Step 1: 更新英文 README**

在特性列表增加快照聚合，在 `SnapshotQueryClient` import 与示例中加入：

```ts
type ProductSummary = {
  product: string;
  itemCount: number;
  totalQuantity: number;
};

const aggregationQuery: AggregationQuery = {
  filter: filter.eq('state.status', 'COMPLETED'),
  elements: [{ path: 'state.items' }],
  groupBy: [
    {
      type: AggregationGroupType.TERMS,
      field: 'productId',
      alias: 'product',
    },
  ],
  metrics: [
    { type: AggregationMetricType.COUNT, alias: 'itemCount' },
    {
      type: AggregationMetricType.NUMERIC,
      function: AggregationFunction.SUM,
      expression: { field: 'quantity' },
      alias: 'totalQuantity',
    },
  ],
  sort: [{ field: 'totalQuantity', direction: SortDirection.DESC }],
  limit: 10,
};

const summaries =
  await cartSnapshotQueryClient.aggregate<ProductSummary>(aggregationQuery);
const summaryStream =
  await cartSnapshotQueryClient.aggregateStream<ProductSummary>(
    aggregationQuery,
  );
```

在方法列表加入精确返回类型，并说明两者都请求 `snapshot/aggregation`，流式方法使用 SSE。

- [ ] **Step 2: 更新中文 README**

逐项翻译 Task 1 Step 1 的说明，保留完全相同的 TypeScript 示例、alias 和签名，不额外扩展中文专属行为。

- [ ] **Step 3: 格式检查 README**

Run:

```bash
pnpm exec prettier --check packages/wow/README.md packages/wow/README.zh-CN.md
```

Expected: `All matched files use Prettier code style!`

### Task 2: 同步 Skill API reference

**Files:**

- Modify: `skills/fetcher-wow-cqrs/references/api.md`

**Interfaces:**

- Consumes: `packages/wow/src/query/aggregation.ts`, `snapshot/snapshotQueryApi.ts`, `snapshot/snapshotQueryClient.ts`.
- Produces: 面向代理的完整聚合类型、枚举、调用方式与兼容边界。

- [ ] **Step 1: 更新目录与导入**

在目录的 `SnapshotQueryClient` 子项下加入 `Aggregation Methods`，并新增 `AggregationQuery` 顶级章节。统一 import 示例加入五个聚合枚举以及 `AggregationQuery`、`AggregationElement`、`AggregationGroup`、`AggregationMetric`、`DynamicDocument` 类型。

- [ ] **Step 2: 记录客户端方法**

在 `SnapshotQueryClient` 查询示例中复用 Task 1 的 `ProductSummary` 与 `aggregationQuery`，记录：

```ts
aggregate<Row extends DynamicDocument = DynamicDocument>(
  query: AggregationQuery<FIELDS>,
  attributes?: Record<string, any>,
  abortController?: AbortController,
): Promise<Row[]>;

aggregateStream<Row extends DynamicDocument = DynamicDocument>(
  query: AggregationQuery<FIELDS>,
  attributes?: Record<string, any>,
  abortController?: AbortController,
): Promise<ReadableStream<JsonServerSentEvent<Row>>>;
```

紧接签名说明 `SnapshotQueryApi` 上两成员可选以兼容既有实现，具体客户端方法必需。

- [ ] **Step 3: 添加完整 AggregationQuery 参考**

列出以下精确契约：

- `filter?`, `elements?`, `groupBy?`, 非空 `metrics`, `sort?`, `limit?`
- Group：`TERMS`, `HISTOGRAM(interval)`, `DATE_HISTOGRAM(unit, timeZone?)`
- Metric：`COUNT`；`NUMERIC(function, expression)`
- Function：`SUM`, `AVG`, `MIN`, `MAX`
- Date unit：`YEAR`, `QUARTER`, `MONTH`, `WEEK`, `DAY`, `HOUR`, `MINUTE`, `SECOND`
- Expression：可省略 `type` 的 `FIELD`

说明 `elements[].filter` 使用 `ElementFilterExpression`，结果泛型不做运行时解码。

- [ ] **Step 4: 格式检查 Skill reference**

Run:

```bash
pnpm exec prettier --check skills/fetcher-wow-cqrs/references/api.md
```

Expected: `All matched files use Prettier code style!`

### Task 3: 同步 Wiki 中英文页面

**Files:**

- Modify: `wiki/packages/wow.md`
- Modify: `wiki/zh/packages/wow.md`

**Interfaces:**

- Consumes: Task 1 的常用示例、Task 2 的准确 API 事实。
- Produces: 面向站点读者的当前查询主路径。

- [ ] **Step 1: 更新英文快照查询章节**

将 `all()` / `condition` 主示例替换为 `filter.matchAll()` / `filter` request；在方法表中加入：

```md
| `aggregate(query)` | `/snapshot/aggregation` | `Promise<Row[]>` | Aggregate snapshots |
| `aggregateStream(query)` | `/snapshot/aggregation` | `Promise<ReadableStream<JsonServerSentEvent<Row>>>` | Aggregate snapshots as SSE |
```

在表后加入 Task 1 的精简聚合示例。

- [ ] **Step 2: 更新英文 Query DSL 与导出表**

把 `FilterExpression` 作为主章节，列出 `filter` 的 logical、metadata、comparison、string、collection、presence、scope/search 与 relative-time 分类。将旧 Condition 表缩为一段兼容说明；主要导出表加入 `filter`、`AggregationQuery`、聚合枚举和两个客户端方法的说明。

- [ ] **Step 3: 更新中文 Wiki**

逐项同步 Steps 1–2，保持代码、表格行、端点和返回类型与英文一致，只翻译说明文字。

- [ ] **Step 4: 构建 Wiki**

Run:

```bash
pnpm build
```

Workdir: `wiki`

Expected: VitePress build exits `0`; generated `llms*.txt` and `.vitepress/dist` changes must not be committed.

### Task 4: 全量复核并提交

**Files:**

- Review: all files listed in File Structure plus this plan.

**Interfaces:**

- Consumes: Tasks 1–3 outputs.
- Produces: 可提交、可构建、与运行时契约一致的完整文档变更。

- [ ] **Step 1: 检查符号、禁用名和范围**

Run:

```bash
rg -n "AggregationQuery|aggregateStream|snapshot/aggregation|FilterExpression" packages/wow/README.md packages/wow/README.zh-CN.md skills/fetcher-wow-cqrs/references/api.md wiki/packages/wow.md wiki/zh/packages/wow.md
rg -n "SnapshotAggregationQueryApi" packages/wow/README.md packages/wow/README.zh-CN.md skills/fetcher-wow-cqrs/references/api.md wiki/packages/wow.md wiki/zh/packages/wow.md
git status --short
```

Expected: 五个文档均命中当前 API；禁用名无命中；状态中没有构建产物或其他 package 变更。

- [ ] **Step 2: 运行最终验证**

Run:

```bash
pnpm test:unit
git diff --check
```

Expected: 所有 package 单元测试通过，diff 无空白错误。

- [ ] **Step 3: 提交**

```bash
git add packages/wow/README.md packages/wow/README.zh-CN.md \
  packages/wow/docs/superpowers/plans/2026-08-25-wow-query-documentation.md \
  skills/fetcher-wow-cqrs/references/api.md \
  wiki/packages/wow.md wiki/zh/packages/wow.md
git commit -m "docs(wow): document snapshot aggregation queries"
```
