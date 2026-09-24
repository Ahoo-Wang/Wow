# Fetcher Wow 查询 DSL 3.18 设计

## 状态

已确认，待实施计划。

## 背景

Wow `main` 的查询请求契约已经新增搜索模式、相对日期过滤、可配置时间单位和聚合
算术表达式。`@ahoo-wang/fetcher-wow` 目前只覆盖较早的 `FilterExpression` 与字段型
聚合指标，且聚合请求主要依赖调用方手写判别联合对象。

本次以 Wow 当前线协议为事实来源，为 TypeScript 使用者提供无状态、可组合、类型
推导稳定的函数式查询 DSL。旧 `Condition` 体系继续服务现有调用方，但不参与新 DSL
的兼容设计。

## 目标

- 对齐 Wow 最新的过滤请求类型：搜索模式、五个相对日期操作符和时间单位。
- 对齐聚合常量、二元算术表达式及四则运算符。
- 通过纯函数 DSL 减少调用方手写 `op`、`type` 和递归 JSON 的负担。
- 分别约束聚合请求的根字段与展开后的聚合字段。
- 在能够准确判断的构造边界尽早报告无效参数。
- 以 `3.18.0` 更新 monorepo 的锁步版本。

## 非目标

- 不新增或调用 `snapshot/schema`、`snapshot/schema/refresh`。
- 不根据运行时 Schema 生成字段值类型，也不构建字段类型图。
- 不修改、删除或迁移 `Condition`、legacy `Operator`、operator locale。
- 不新增链式 Builder、可变查询对象、查询解析器或完整客户端验证器。
- 不修改 generator、viewer、react；若实现时出现直接编译依赖，必须回到设计阶段
  重新确认范围。
- 不为 `filter.ts` 或 `aggregation.ts` 保留旧签名、兼容重载或别名。

## 方案选择

采用单一命名对象上的无状态函数式 DSL：`filter.*` 与 `aggregation.*`。每个函数直接
返回 Wow 可序列化的普通对象，不保存状态，也不引入类。

未采用的方案：

- 链式 Builder：需要维护泛型状态和调用顺序，隐藏的可变状态也会增加测试面。
- 仅导出判别联合：实现最少，但复杂聚合表达式需要大量重复 JSON，不符合易用目标。

## Filter DSL

### 公开类型

`FilterOperator` 增加：

- `YESTERDAY`
- `NEXT_MONTH`
- `LAST_YEAR`
- `THIS_YEAR`
- `NEXT_YEAR`

新增：

```ts
enum SearchMode {
  TERMS = 'TERMS',
  PHRASE = 'PHRASE',
}

enum TimeUnit {
  NANOSECONDS = 'NANOSECONDS',
  MICROSECONDS = 'MICROSECONDS',
  MILLISECONDS = 'MILLISECONDS',
  SECONDS = 'SECONDS',
  MINUTES = 'MINUTES',
  HOURS = 'HOURS',
  DAYS = 'DAYS',
}
```

`SearchFilter` 增加可选 `mode`。`RelativeTimeFilterOptions` 增加可选 `timeUnit`。
`CalendarFilter` 联合类型覆盖全部 Wow 相对日期操作符。底层请求类型保留 Wow 的可选
默认字段，DSL 返回值则显式填入确定的默认值。

### 构造函数

`search` 只保留 options 形式：

```ts
filter.search('event sourcing', {
  mode: SearchMode.PHRASE,
  fields: ['state.title', 'state.description'],
});
```

options 省略时，DSL 生成 `mode: TERMS` 与空 `fields`。不保留
`search(query, ...fields)`。

新增 `yesterday`、`nextMonth`、`lastYear`、`thisYear`、`nextYear`。所有相对时间函数
继续使用同一个 options 对象：

```ts
filter.yesterday('state.createdAt', {
  zoneId: 'Asia/Shanghai',
  timeUnit: TimeUnit.MILLISECONDS,
});
```

options 未提供时，DSL 显式生成 `timeUnit: MILLISECONDS`；`zoneId` 与 `datePattern`
仍按需输出。`ElementFilterExpression` 在类型层继续排除 metadata、deletion、search
等只允许出现在根级的过滤器。

## Aggregation DSL

### 公开类型

`AggregationExpressionType` 包含 `FIELD`、`CONSTANT`、`BINARY`。新增：

```ts
enum AggregationExpressionOperator {
  ADD = 'ADD',
  SUBTRACT = 'SUBTRACT',
  MULTIPLY = 'MULTIPLY',
  DIVIDE = 'DIVIDE',
}
```

`AggregationExpression<FIELDS>` 是字段、常量和递归二元表达式的判别联合。
`AggregationGroup<FIELDS>` 与 `AggregationMetric<FIELDS>` 同样携带字段泛型。
`FieldAggregationExpression.type` 必须显式为 `FIELD`，不保留旧的可省略形式。

顶层查询使用：

```ts
AggregationQuery<ROOT_FIELDS, AGGREGATION_FIELDS = ROOT_FIELDS>
```

`filter` 使用 `ROOT_FIELDS`；`groupBy` 与数值表达式使用
`AGGREGATION_FIELDS`。Element 链可能在每一层改变相对字段作用域，没有运行时 Schema
类型图时无法可靠静态表达，因此 `elements` 不伪造逐层字段安全。

### 构造函数

单一扁平 `aggregation` 对象提供：

- Element：`element(path, filter?)`
- 表达式：`field`、`constant`、`add`、`subtract`、`multiply`、`divide`
- 分组：`terms`、`histogram`、`dateHistogram`
- 指标：`count`、`sum`、`avg`、`min`、`max`

常用签名保持短小；参数较多的分组使用 options 对象：

```ts
aggregation.terms('category', 'category');
aggregation.histogram('price', { interval: 10, alias: 'priceBand' });
aggregation.dateHistogram('createdAt', {
  unit: AggregationDateUnit.DAY,
  alias: 'day',
  timeZone: 'Asia/Shanghai',
});
```

`dateHistogram` 未提供 `timeZone` 时，DSL 显式生成 `UTC`；相对时间过滤器未提供
`zoneId` 时仍省略该字段，交由 Wow 的查询时间上下文解释。

完整示例：

```ts
const revenue = aggregation.multiply(
  aggregation.field<ItemFields>('price'),
  aggregation.field<ItemFields>('quantity'),
);

const query: AggregationQuery<OrderFields, ItemFields> = {
  filter: filter.eq('state.status', 'PAID'),
  elements: [aggregation.element('state.items', filter.gt('quantity', 0))],
  groupBy: [aggregation.terms('category', 'category')],
  metrics: [aggregation.count('orders'), aggregation.sum(revenue, 'revenue')],
};
```

DSL 与底层判别联合类型同时导出。高级调用方可以直接构造请求对象，但不会获得额外的
运行时校验。

## 数据流与校验

DSL 返回的对象由现有 QueryClient 原样序列化并发送；不修改 URL、请求方法、header、
SSE 或响应处理。

Filter 构造函数沿用并扩展现有边界校验：

- 逻辑字段格式、JSON 标量和非空集合
- 搜索文本、`SearchMode`、`TimeUnit`
- 日期、时区、日期格式与正整数天数
- Element 内不允许的根级过滤器

Aggregation 构造函数校验：

- 字段与单段 alias 格式，以及保留的 `__wow` 前缀
- 常量必须是有限数值
- histogram interval 必须有限且大于零
- date histogram 时区必须是非空字符串

DSL 参数错误统一抛 `TypeError`。跨节点约束由 Wow 服务端负责，包括指标/分组/排序
数量、alias 全局唯一、排序引用、表达式深度和节点总数。客户端不新增
`aggregation.query()` 来复制这套整体验证。

## 修改范围

运行时与测试：

- `packages/wow/src/query/filter.ts`
- `packages/wow/src/query/aggregation.ts`
- `packages/wow/test/query/filter.test.ts`
- `packages/wow/test/query/aggregation.test.ts`
- 必要时更新现有 query/client/index 测试中的新签名调用，但不修改其运行时逻辑

文档：

- `packages/wow/README.md`
- `packages/wow/README.zh-CN.md`
- `skills/fetcher-wow-cqrs/references/api.md`
- `wiki/packages/wow.md`
- `wiki/zh/packages/wow.md`

版本由 `pnpm update-version 3.18.0` 锁步更新根、全部 workspace 包和
`integration-test` 的 `package.json`；现有脚本不修改 lockfile。

## 测试策略

先为新行为添加失败测试，再修改实现：

- Filter：新操作符、搜索模式、七个时间单位、确定性默认输出和非法参数。
- Aggregation：每个叶节点、递归四则表达式、分组/指标函数及非法参数。
- 类型：根字段与聚合字段分别约束；Element 根级禁用过滤器继续被拒绝。
- 传输：现有 SnapshotQueryClient 测试证明新聚合对象未经转换直接成为 request body。

验证命令：

- Wow 包定向 Vitest
- `pnpm build`
- `pnpm test:unit`
- `pnpm lint`
- `pnpm --dir wiki build`
- Prettier 检查与 `git diff --check`

## 完成标准

- Filter 和 Aggregation 请求形状与 Wow 当前公开线协议一致。
- 文档示例只使用实际导出的 `3.18.0` API，中英文事实一致。
- `Condition` 及其现有调用方没有变化。
- 没有兼容重载、别名、链式 Builder、Schema 客户端或生成器改动。
- 全部构建、单元测试、lint、文档和 diff 检查通过。
