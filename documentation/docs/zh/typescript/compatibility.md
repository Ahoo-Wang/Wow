---
title: 兼容性与版本
description: Wow 的 TypeScript 包支持哪些 Wow 服务端、Node.js、React、TypeScript 和 Fetcher 版本，以及怎样让它们的版本保持一致。
---

# 兼容性与版本

## 发布状态 {#release-status}

Wow 10 的包随每个 Wow 10 版本由 CI 发布，并带有 [npm provenance](https://docs.npmjs.com/generating-provenance-statements)。预发布版本发布到对应种类的 dist-tag：`alpha`、`beta`，候选版本为 `next`（`pnpm add @ahoo-wang/wow-client@alpha`）；`latest` 指向最新的稳定版本。

## Wow 服务端 {#wow-servers}

Wow 10 的包与 Wow 10 的服务通信，并从 Wow 10 的 Manifest 生成。路由、请求头、等待、problem 和查询语言都与 9.x 不同，所以 9.x 的服务需要 9.x 的包，前端要与它的服务一起迁移到 10（[从 9.x 迁移](../migrate/)）。

在 10 之内，使用与前端所调用的服务同一版本的包。较新的服务会增加它的 Manifest 所增加的东西：重新生成就能看到。生成的类型没有指明的字段，仍然会出现在 JSON 中，类型会忽略它。

## 版本 {#versions}

这些包与 Kotlin 模块共用一个版本号，从同一个 tag 发布。破坏性改变只在 `x.Y.0` 版本中发布，并在[发布说明](https://github.com/Ahoo-Wang/Wow/releases)的 “Breaking” 下列出。

- **应用中的每个 Wow 包使用同一个版本。** `wow-generator`、`wow-react` 和视图引擎以 `~x.y.z` 把 `@ahoo-wang/wow-client` 作为 peer 依赖，所以它们的次版本不同时，包管理器会发出警告。
- **用同一版本的生成器生成**，即与生成代码所编译依赖的 `wow-client` 相同的版本。

### 版本范围 {#version-ranges}

版本号跟随 Wow，而不是语义化版本：次版本（`x.Y.0`）可能改变 TypeScript API，只有补丁版本（`x.y.Z`）才保证兼容，[实验性声明](#experimental-declarations)除外。`pnpm add` 和 `npm install` 默认保存插入符范围（`^x.y.z`），这会让之后的安装在没有任何人决定的情况下拿到下一个次版本。改为保存波浪号范围，它只接受同一个次版本的补丁。pnpm 从 `pnpm-workspace.yaml` 读取这个设置：

```yaml
savePrefix: '~'
```

npm 从项目的 `.npmrc` 读取：

```ini
save-prefix=~
```

或者固定确切版本：

```bash
pnpm add --save-exact @ahoo-wang/wow-client
pnpm add -D --save-exact @ahoo-wang/wow-generator
```

有意地迁移到下一个次版本，所有 Wow 包一起，在读过它发布说明中的 “Breaking” 部分之后。Fetcher 的 peer 依赖遵循语义化版本，所以插入符范围适合它们：打开波浪号设置时，用显式的范围添加它们（`pnpm add @ahoo-wang/fetcher@^<version>`）。

### 实验性声明 {#experimental-declarations}

数据集查询、聚合查询及其表达式，连同只由它们引发的查询错误码，是实验性的，与它们的 Kotlin 声明一样（`@ExperimentalWowApi`，见 [API 级别](../reference/api-levels#experimental-declarations)）。它们的 TypeScript 声明带有 `@experimental`，编辑器悬停时会显示。10.0 API 的冻结与补丁版本的兼容都不包括它们：10.x 的任何版本都可能改动或移除它们，并在发布说明中写明。下面的列表由各包的源码生成，附上导入每个声明的入口：

<!-- experimental:begin -->
<!-- 由 .github/scripts/docs-reference.mjs 依据各包的源码生成；用 `pnpm docs:reference` 重新生成。请勿手改。 -->

| 声明 | 种类 | 入口 |
| --- | --- | --- |
| `DatasetClient` | class | `@ahoo-wang/wow-client` |
| `DatasetEntry` | interface | `@ahoo-wang/wow-client` |
| `QueryModelClient.aggregate` | method | `@ahoo-wang/wow-client` |
| `aggregation` | const | `@ahoo-wang/wow-client/dsl` |
| `AGGREGATION_LIMITS` | const | `@ahoo-wang/wow-client/dsl` |
| `AggregationDatePart` | enum | `@ahoo-wang/wow-client/dsl` |
| `AggregationDateUnit` | enum | `@ahoo-wang/wow-client/dsl` |
| `AggregationElement` | interface | `@ahoo-wang/wow-client/dsl` |
| `AggregationExpression` | type | `@ahoo-wang/wow-client/dsl` |
| `AggregationExpressionOperator` | enum | `@ahoo-wang/wow-client/dsl` |
| `AggregationExpressionType` | enum | `@ahoo-wang/wow-client/dsl` |
| `AggregationFunction` | enum | `@ahoo-wang/wow-client/dsl` |
| `AggregationGroup` | type | `@ahoo-wang/wow-client/dsl` |
| `AggregationGroupInput` | type | `@ahoo-wang/wow-client/dsl` |
| `AggregationGroupScope` | enum | `@ahoo-wang/wow-client/dsl` |
| `AggregationGroupScopeOptions` | interface | `@ahoo-wang/wow-client/dsl` |
| `AggregationGroupType` | enum | `@ahoo-wang/wow-client/dsl` |
| `AggregationMetric` | type | `@ahoo-wang/wow-client/dsl` |
| `AggregationMetricOptions` | interface | `@ahoo-wang/wow-client/dsl` |
| `AggregationMetricType` | enum | `@ahoo-wang/wow-client/dsl` |
| `AggregationQuery` | interface | `@ahoo-wang/wow-client/dsl` |
| `AnyAggregationMetric` | interface | `@ahoo-wang/wow-client/dsl` |
| `BinaryAggregationExpression` | interface | `@ahoo-wang/wow-client/dsl` |
| `ComparisonOperator` | enum | `@ahoo-wang/wow-client/dsl` |
| `ConstantAggregationExpression` | interface | `@ahoo-wang/wow-client/dsl` |
| `CountAggregationMetric` | interface | `@ahoo-wang/wow-client/dsl` |
| `DateDiffAggregationExpression` | interface | `@ahoo-wang/wow-client/dsl` |
| `DateDiffUnit` | enum | `@ahoo-wang/wow-client/dsl` |
| `DateHistogramAggregationGroup` | interface | `@ahoo-wang/wow-client/dsl` |
| `DateHistogramAggregationOptions` | interface | `@ahoo-wang/wow-client/dsl` |
| `DatePartAggregationGroup` | interface | `@ahoo-wang/wow-client/dsl` |
| `DatePartAggregationOptions` | interface | `@ahoo-wang/wow-client/dsl` |
| `DerivedAggregationMetric` | interface | `@ahoo-wang/wow-client/dsl` |
| `DerivedExpression` | type | `@ahoo-wang/wow-client/dsl` |
| `DerivedExpressionDsl` | interface | `@ahoo-wang/wow-client/dsl` |
| `DerivedExpressionType` | enum | `@ahoo-wang/wow-client/dsl` |
| `DerivedWindow` | type | `@ahoo-wang/wow-client/dsl` |
| `DistinctCountAggregationMetric` | interface | `@ahoo-wang/wow-client/dsl` |
| `EdgeAggregationMetric` | interface | `@ahoo-wang/wow-client/dsl` |
| `EdgeAggregationOptions` | interface | `@ahoo-wang/wow-client/dsl` |
| `ExpressionFilter` | type | `@ahoo-wang/wow-client/dsl` |
| `FieldAggregationExpression` | interface | `@ahoo-wang/wow-client/dsl` |
| `filter.expression` | method | `@ahoo-wang/wow-client/dsl` |
| `FilterOperator.EXPRESSION` | enum member | `@ahoo-wang/wow-client/dsl` |
| `HavingDsl` | interface | `@ahoo-wang/wow-client/dsl` |
| `HavingExpression` | type | `@ahoo-wang/wow-client/dsl` |
| `HavingExpressionType` | enum | `@ahoo-wang/wow-client/dsl` |
| `HistogramAggregationGroup` | type | `@ahoo-wang/wow-client/dsl` |
| `HistogramAggregationOptions` | interface | `@ahoo-wang/wow-client/dsl` |
| `NumericAggregationMetric` | interface | `@ahoo-wang/wow-client/dsl` |
| `PercentileAggregationMetric` | interface | `@ahoo-wang/wow-client/dsl` |
| `PercentileAggregationOptions` | interface | `@ahoo-wang/wow-client/dsl` |
| `QueryErrorCodes.ANY_REQUIRES_SINGLE_VALUE` | property | `@ahoo-wang/wow-client/dsl` |
| `QueryErrorCodes.FIRST_LAST_REQUIRES_ORDER_BY` | property | `@ahoo-wang/wow-client/dsl` |
| `QueryErrorCodes.FIRST_LAST_REQUIRES_SINGLE_VALUE` | property | `@ahoo-wang/wow-client/dsl` |
| `QueryErrorCodes.METRIC_FILTER_ARRAY_FIELD` | property | `@ahoo-wang/wow-client/dsl` |
| `QueryErrorCodes.METRIC_FILTER_ELEMENT_MATCH` | property | `@ahoo-wang/wow-client/dsl` |
| `QueryErrorCodes.METRIC_FILTER_SEARCH` | property | `@ahoo-wang/wow-client/dsl` |
| `QueryErrorCodes.MISSING_KEY_REQUIRES_STRING` | property | `@ahoo-wang/wow-client/dsl` |
| `QueryErrorCodes.PREVIOUS_UNSHIFTABLE` | property | `@ahoo-wang/wow-client/dsl` |
| `QueryErrorCodes.PROTECTED_AGGREGATION` | property | `@ahoo-wang/wow-client/dsl` |
| `QueryErrorCodes.RESIDUAL_GROUPS_EXCEEDED` | property | `@ahoo-wang/wow-client/dsl` |
| `QueryErrorCodes.TEMPORAL_AGGREGATION_UNSUPPORTED` | property | `@ahoo-wang/wow-client/dsl` |
| `QueryErrorCodes.TOO_MANY_GROUPS` | property | `@ahoo-wang/wow-client/dsl` |
| `RunningFunction` | enum | `@ahoo-wang/wow-client/dsl` |
| `TermsAggregationGroup` | type | `@ahoo-wang/wow-client/dsl` |
| `TermsAggregationOptions` | interface | `@ahoo-wang/wow-client/dsl` |
| `WindowFunction` | enum | `@ahoo-wang/wow-client/dsl` |
| `useAggregateQuery` | function | `@ahoo-wang/wow-react` |
| `UseAggregateQueryOptions` | interface | `@ahoo-wang/wow-react` |
| `UseAggregateQueryReturn` | interface | `@ahoo-wang/wow-react` |

<!-- experimental:end -->

### 支持窗口 {#support-window}

这些包遵循 Wow 其余部分的支持策略，见[安全策略](https://github.com/Ahoo-Wang/Wow/blob/main/SECURITY.md)：修复进入最新的稳定版本线，较旧版本线的修复逐个决定。

## 运行时与 peer 依赖 {#runtimes-and-peers}

| 要求 | 版本 | 说明 |
| --- | --- | --- |
| Node.js | `>=22.12.0` | 在每个包的 `engines` 中声明；生成器的 CLI 也需要它 |
| 浏览器 | 当前的常青浏览器 | 这些包使用 `fetch`、`ReadableStream`、`TextDecoderStream` 和 `AbortSignal.any`（Chrome 116、Firefox 124、Safari 17.4） |
| React 和 React DOM（`wow-react`、视图引擎的界面） | `^19.0.0` | 不支持 React 18：构建会导入 `react/compiler-runtime` |
| React Router（只有 `wow-view-engine/react-router`） | `^7.0.0 \|\| ^8.0.0` | 可选的 peer 依赖：只有那个入口导入它 |
| TypeScript | `>=6.0` | CI 以使用者的身份，在 TypeScript 6.0 和最新的 7.x 上对打包后的包做类型检查；`moduleResolution` 为 `Bundler`、`NodeNext` 或 `Node16` |
| `@ahoo-wang/fetcher`、`fetcher-eventstream`、`fetcher-decorator` | `^5.1.5 \|\| ^6.0.0` | 由应用安装的 peer 依赖。CI 在最新的 6.x 上构建和测试，下限作业在 5.1.5 上重新运行测试。只有从 OpenAPI 文档生成的客户端才需要 `fetcher-decorator` |
| `@ahoo-wang/wow-client`（用于其他 Wow 包） | `~x.y.z` | 同一个次版本 |

pnpm 8 及以上和 npm 7 及以上会自己安装缺失的 peer 依赖；Yarn 不会，所以 Yarn 项目要自己添加每个 peer 依赖。

## 精确数字与投影的结果 {#exact-numbers-and-projected-results}

Kotlin 的 `Long`、`BigInteger` 或 `BigDecimal` 类型的 Manifest 字段（schema 格式为 `int64`、`bigint` 或 `decimal`），在生成的类型中是 `string`，数字与服务写出时完全相同；线上仍然传输 JSON 数字（[生成客户端](./generate#exact-numbers)）。9.x 生成的是 `number`。

用字面 `projection.include` 路径写成的查询，返回收窄到这些路径的记录；用 `listQuery` 或 `pagedQuery` 构建的查询，或只有排除项的查询，返回完整的记录。不带 `timeZone` 的日历过滤按 UTC 读取它的日期（[读取与查询](./queries#relative-time-and-time-zones)）。
