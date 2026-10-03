---
title: 写好一份定义
description: 从查询描述写视图定义：描述给的事实、措辞的键、为受众收窄能力、record.rowFields、系统视图与看板，以及用 admit 自检。
---

# 写好一份定义

本页回答：**手里有一份服务端的查询描述，怎样写出一份给对的人看、准入干净的定义？**

[入门](./view-engine-getting-started.md)里的定义只列了五个字段、两个系统视图，够把列表跑起来。本页接着写同一个销售订单（聚合 `sales-order`），把每一项选择摊开：列哪些字段、叫什么、收窄什么、随定义发布哪些视图和看板，以及怎样在测试里证明它站得住。定义在模型里的位置见[核心概念](./view-engine-concepts.md#定义)。

## 1. 先有描述符，并把它提交

定义的事实来自服务端的查询描述：快照查询是 `GET /{聚合}/snapshot/schema`，事件流是 `GET /{聚合}/event/schema`。把它存成 JSON，放在定义旁边，和定义一起提交，并在注释或测试里记下它的 `version`：

<!-- typecheck: skip — 导入的 JSON 是提交在旁边的描述快照，入门第 4 步有它的节选 -->

```ts
// src/views/salesOrderDescriptor.ts
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
// GET /sales-order/snapshot/schema，version sha256:a30adffc…
import snapshot from './salesOrderDescriptor.json';

export const salesOrderDescriptor = snapshot as unknown as QueryModelDescriptor;
```

为什么提交一份快照，而不是在运行时去读：

- **定义在模块加载时同步建好。** 页面不等网络，测试读的是同一份，结果可复现。
- **漂移在评审里看得见。** 服务端升级后重新取一次覆盖它，`version` 变了，改动摆在 diff 里；准入会把每一处受影响的字段与视图报出来（第 7 节）。
- **运行时仍读当下的描述。** 数据源提供 `describe` 时，引擎在第一次查询前再向服务端读一次，按这次部署当下的能力收窄（第 4 节）；快照只决定事实。

## 2. 事实、能力与选择

[`defineView(descriptor, spec)`](../../reference/typescript/wow-view-engine/definitions.md#api-defineView) 把描述符里的东西分成两类，加上定义自己的一类：

| 哪一类 | 包括 | 从哪来 | 定义能做什么 |
|---|---|---|---|
| 事实 | 路径、值类型、枚举值、时间怎样存（语义）、敏感级别、弃用、数组的元素结构 | 提交的快照，固化进定义 | 只能选、命名；写了快照没有的路径或值是准入错误 |
| 能力 | 操作符、排序、聚合、检索、上限 | 数据源当下的描述，随存储变 | 不写就随部署；写了只能收窄 |
| 选择 | 列哪些字段、次序、措辞、单元格与语气、收窄、行的身份、看板的时刻、系统视图 | 定义 | 全部由你决定 |

同一个模型在 MongoDB 与 Elasticsearch 上能力不同（比如数组元素里的检索只有 Elasticsearch 有），所以能力不从快照固化：没收窄的那部分，同一份定义部署到哪种存储，就得到那种存储的能力。

字段的种类从事实推出：有枚举值或你写了 `options` 是 `enum`；语义是纪元时间或格式化时间的是 `datetime`，日期的是 `date`；布尔、数字、字符串各归其类；写了 `elements` 的数组是 `elementMatch`。推不出的（对象、联合类型）要你写 `kind`，否则准入报 `definition.field.kind-unknown`。时刻以数存，但意思是时间，所以它只按日历分组（`DATE_HISTOGRAM`、`DATE_PART`），只有最早与最晚（`MIN`、`MAX`）。

## 3. 一份定义，逐项选择

场景：仓库与客服两拨人每天处理销售订单。仓库要清「待发货」，客服要按城市看销量。下面是给他们写的定义：

<!-- typecheck: file=salesOrders.ts -->
<!-- typecheck-context
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
declare const salesOrderDescriptor: QueryModelDescriptor;
-->

```ts
// src/views/salesOrders.ts
import { AggregationDateUnit } from '@ahoo-wang/wow-client';
import { defineView, text } from '@ahoo-wang/wow-view-engine';
import { SALES_ORDER_VIEWS } from './salesOrderViews';

export const SALES_ORDERS = 'sales-orders';

export const salesOrders = defineView(salesOrderDescriptor, {
  id: SALES_ORDERS,
  // 宿主登记数据源时用的键。
  source: 'sales-order',
  title: text('orders.title'),
  // 分析的计数单位：「12 张订单」，而不是「12 条记录」。
  recordNoun: text('orders.noun'),
  // 看板的日期筛选指的是下单时间。
  timeField: 'firstEventTime',
  // 只出现列出的字段，按这个次序：字段选择器与默认列都照它排。
  fields: {
    // 人们拿它查找、复制，从不按它分组。
    aggregateId: {
      label: text('orders.id'),
      cell: 'copyable',
      analysis: false,
    },
    'state.status': {
      label: text('orders.status'),
      cell: 'status',
      // 读者会遇到的每个值都写上措辞；语气只给好、坏、等待中的值。
      options: {
        CREATED: { label: text('orders.created'), tone: 'neutral' },
        PAID: { label: text('orders.paid'), tone: 'warning' },
        SHIPPED: { label: text('orders.shipped'), tone: 'success' },
        RECEIVED: { label: text('orders.received'), tone: 'success' },
      },
    },
    'state.address.province': text('orders.province'),
    'state.address.city': text('orders.city'),
    // 金额的合计有意义；平均每页多少钱没有。
    'state.totalAmount': {
      label: text('orders.total'),
      summary: ['SUM'],
      more: { numberFormat: { style: 'currency', currency: 'CNY' } },
    },
    'state.paidAmount': {
      label: text('orders.paidAmount'),
      summary: ['SUM'],
      more: { numberFormat: { style: 'currency', currency: 'CNY' } },
    },
    // 读者按天、按月看走势：用 wow-client 的枚举，不写字符串。
    firstEventTime: {
      label: text('orders.placedAt'),
      analysis: {
        dateUnits: [AggregationDateUnit.DAY, AggregationDateUnit.MONTH],
      },
    },
    // 一张订单的明细，每一项按商品读。
    'state.items': {
      label: text('orders.items'),
      elementTitle: 'productId',
      elements: {
        productId: text('orders.product'),
        quantity: text('orders.quantity'),
        price: text('orders.price'),
      },
    },
    // 只在详情里读的自由文本：不筛选、不排序、不分组。
    'state.address.detail': {
      label: text('orders.address'),
      cell: 'text',
      operators: [],
      sortable: false,
      analysis: false,
    },
  },
  record: {
    layouts: ['table', 'card'],
    // 宿主的「发货」读每一行的状态，不论视图显示不显示这一列（第 5 节）。
    rowFields: ['state.status'],
  },
  // 随定义发布的系统视图（第 6 节）。
  views: SALES_ORDER_VIEWS,
});
```

没写的也是选择：租户、所有者、国家与区县、应付余额，这两拨人都用不上，就不列。定义是一份**取舍**，不是描述符的副本；列得越长，选择器越难用。

几条常用的选择，各对着一个问题：

| 问题 | 选择 |
|---|---|
| 读者会读、筛、按它分组吗？ | 会就列，不会就不列。描述符多了字段，界面不会悄悄多一列 |
| 它是人们称呼一条记录的方式吗？ | `cell: 'copyable'`、`analysis: false`；它唯一、可排序、人们按它查找时，做 `record.rowKey` |
| 是只计数、不分组的 id 吗？ | `analysis: { groups: [] }`：仍能去重计数 |
| 是自由文本吗？ | `cell: 'text'`、`sortable: false`、`analysis: false`；没人按它筛选时 `operators: []` |
| 是类别吗？ | 每个读者看得到的值都写措辞（`options`），没人遇到的写 `false` 隐藏；只有好、坏、等待中的值带 `tone` |
| 是金额或数量吗？ | 只给读者真会读的合计（`summary`），币种与单位写在 `more.numberFormat` |
| 是时刻吗？ | 用 `analysis.dateUnits` 收到读者想的那几种粒度；以秒为单位读的（事件流的时间）写 `timePrecision: 'second'` |
| 是数组吗？ | 元素写在 `elements` 里，键相对元素；`elementTitle` 指出哪个元素字段说出「这是哪一项」，否则单元格只说「3 项」 |

字段很多时，用 `fieldGroups: [{ id, label, fields }]` 给选择器和记录详情分组，每组一个业务上的名字。

## 4. 措辞是键，收窄为了受众

### 措辞

每个标题、标签、选项、系统视图名与指标的显示名都写成 `text(key)`，措辞放在每种语言一张的表里，交给宿主（[`ViewHost`](../../reference/typescript/wow-view-engine/host.md#api-ViewHost) 的 `messages`，或引擎的 `text`）。定义、配置与存储里始终是键，只在显示的地方才说成话，所以一个应用一个引擎，换语言只是重画：不重开、不重查，也不把视图标成「已修改」。单语的宿主仍可以直接写字符串。

<!-- typecheck: file=words.ts -->

```ts
// src/views/words.ts
/** 每个键在宿主服务的每种语言里的措辞。`admit` 报出缺词的键（第 7 节）。 */
export const SALES_ORDER_WORDS = {
  'zh-CN': {
    'orders.title': '销售订单',
    'orders.noun': '订单',
    'orders.id': '订单号',
    'orders.status': '状态',
    'orders.created': '待付款',
    'orders.paid': '已付款',
    'orders.shipped': '已发货',
    'orders.received': '已签收',
    'orders.province': '省份',
    'orders.city': '城市',
    'orders.total': '金额',
    'orders.paidAmount': '实付',
    'orders.placedAt': '下单时间',
    'orders.items': '明细',
    'orders.product': '商品',
    'orders.quantity': '件数',
    'orders.price': '单价',
    'orders.address': '详细地址',
    // 系统视图与分析的措辞。
    'orders.toShip': '待发货',
    'orders.all': '全部订单',
    'orders.cityTotals': '各城市销售额',
    'orders.count': '订单数',
    'orders.perOrder': '客单价',
    'orders.day': '日期',
    // 看板。
    'overview.title': '订单概览',
    'overview.daily': '每日',
    'overview.today': '今天',
    'overview.dailyTotal': '每日销售额',
  },
  en: {
    'orders.title': 'Sales orders',
    'orders.noun': 'order',
    'orders.id': 'Order no.',
    'orders.status': 'Status',
    'orders.created': 'Awaiting payment',
    'orders.paid': 'Paid',
    'orders.shipped': 'Shipped',
    'orders.received': 'Delivered',
    'orders.province': 'Province',
    'orders.city': 'City',
    'orders.total': 'Amount',
    'orders.paidAmount': 'Amount paid',
    'orders.placedAt': 'Placed at',
    'orders.items': 'Items',
    'orders.product': 'Product',
    'orders.quantity': 'Quantity',
    'orders.price': 'Unit price',
    'orders.address': 'Street address',
    'orders.toShip': 'To ship',
    'orders.all': 'All orders',
    'orders.cityTotals': 'Sales by city',
    'orders.count': 'Order count',
    'orders.perOrder': 'Average order value',
    'orders.day': 'Day',
    'overview.title': 'Order overview',
    'overview.daily': 'Daily',
    'overview.today': 'Today',
    'overview.dailyTotal': 'Sales per day',
  },
} as const;
```

措辞是读者的话，一个意思一个词：不写路径（`state.totalAmount`）、常量（`PAID`）、类名，也不写存储的词（文档、集合、快照、聚合）。漏了措辞的字段不会报错到界面上，而是退回描述里的 `description`，再退回路径本身——读者于是看到一个程序里的词，准入为此记一条 `definition.field.unlabelled`。类别的值同理：没写措辞的值显示描述，没有描述就显示原始常量。

### 收窄

能力不写，就随部署：同一份定义在 Elasticsearch 上有短语检索、在 MongoDB 上没有；服务端调高上限，引擎跟着读到，不必重新部署。所以**只为受众收窄，不为复述存储收窄**：

- 收窄那些会误导读者的：订单号的平均值、按堆栈排序、按 id 分组。上面的 `analysis: false`、`operators: []`、`sortable: false` 都是这一类。
- 不复述描述已经不给的东西，也不替服务端写上限（`record.maxWindow`、`analysis.limits`），除非这群读者真要一个更低的。
- 收窄到快照之外（比如列出这个存储没有的操作符）是一条 warning，不是 error：另一种存储可能给。运行时，引擎再把你写的与当下描述取交集；只有你**写了**的能力被这个存储削掉时，才报 `capability.*` 的发现。

两类字段引擎替你守：带 `sensitivity` 的敏感字段自动退出分析（不做维度、指标、公式的操作数与指标条件），机密字段不能做任何比较与排序。你要决定的只是它出不出现——只在读者必须看到掩码值时列出，也别加一个能把掩码推回来的派生字段或条件。被弃用的路径换成它的消息里点名的那一条；确实要留，写 `deprecated: { message: '为什么' }`，否则一直有 warning。

## 5. 记录：行的身份与 `rowFields`

`record.rowKey` 是每一行的身份，缺省是描述符的 `identity`（Wow 快照是 `aggregateId`）。它必须是列出的字段、可以排序：每次记录查询都以它收尾，分页才不重不漏。人们按业务编号查找时可以换成它；但命令仍按聚合 id 寻址，这时把聚合 id 也列出来，放进 [`rowFields`](../../reference/typescript/wow-view-engine/definitions.md#api-RecordCapability)。

**引擎只查询视图显示的字段。** 一页要的只有：显示的列、卡片的字段、排序与汇总读的字段，以及行的身份。没显示的列不取，导出也不带——这让宽表的每一页都轻。代价是：宿主的代码（声明的操作的 `available`、批量操作、自定义单元格）读一个这个视图没显示的字段时，行上根本没有它。比如「发货」读 `state.status` 判断能不能发，而读者把状态列隐藏了，或者打开的系统视图本来就不显示状态：判断读到 `undefined`，按钮就一直是停用的。

所以凡是宿主代码要从行上读的字段，不论显示与否，都写进 `record.rowFields`：

```ts
import type { RecordCapability } from '@ahoo-wang/wow-view-engine';

export const record: Partial<RecordCapability> = {
  layouts: ['table', 'card'],
  // 「发货」读状态；行的身份总会取，不必写。
  rowFields: ['state.status'],
};
```

`rowFields` 里的每一项都必须是定义列出的字段（否则 `definition.record.row-field-unknown`），而且得是有值的路径，不能是搜索框这类无字段的种类（`definition.record.row-field-not-a-path`）。

## 6. 系统视图与看板

### 系统视图：读者每天打开的那几张

系统视图是定义随发布带上的起点：所有人可见、只读，读者另存为自己的再改。只写这群读者每天打开的那几张，不是所有可能的视图：一个队列一张，标题说它装着什么（「待发货」，而不是「订单列表 2」），按决定下一张先处理谁的字段排序，列按读的次序排，合计只放在有意义的地方。

一个小函数让同一份定义的视图完整而一致：

<!-- typecheck: file=salesOrderViews.ts -->

```ts
// src/views/salesOrderViews.ts
import {
  text,
  type AnalysisViewConfig,
  type FilterNode,
  type RecordViewConfig,
  type SystemView,
} from '@ahoo-wang/wow-view-engine';

const COLUMNS = [
  'aggregateId',
  'state.status',
  'state.address.city',
  'state.totalAmount',
  'firstEventTime',
];

function queue(
  filter: FilterNode[],
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    kind: 'record',
    filter: { op: 'and', children: filter },
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [{ field: 'firstEventTime', direction: 'DESC' }],
    pageSize: 20,
    summaries: [{ field: 'state.totalAmount', fn: 'SUM' }],
    layout: 'table',
    table: { columns: COLUMNS.map(field => ({ field })) },
    card: {
      title: 'aggregateId',
      fields: ['state.status', 'state.totalAmount'],
    },
    ...overrides,
  };
}

/** 各城市的销售额：最近 30 天，每次执行都按当下的日历求值。 */
const cityTotals: AnalysisViewConfig = {
  kind: 'analysis',
  filter: {
    op: 'and',
    children: [
      {
        field: 'firstEventTime',
        operator: 'BETWEEN',
        value: { type: 'relative', amount: 30, unit: 'day' },
      },
    ],
  },
  filterMode: 'simple',
  refresh: { interval: null },
  groups: [
    {
      type: 'TERMS',
      field: 'state.address.city',
      alias: 'city',
      label: text('orders.city'),
    },
  ],
  metrics: [
    { type: 'COUNT', alias: 'orders', label: text('orders.count') },
    {
      type: 'NUMERIC',
      alias: 'total',
      function: 'SUM',
      expression: { type: 'FIELD', field: 'state.totalAmount' },
      label: text('orders.total'),
    },
    {
      // 比率是指标之上的指标，不是字段。
      type: 'DERIVED',
      alias: 'perOrder',
      label: text('orders.perOrder'),
      expression: {
        type: 'BINARY',
        operator: 'DIVIDE',
        left: { type: 'METRIC_REF', metric: 'total' },
        right: { type: 'METRIC_REF', metric: 'orders' },
      },
      format: { style: 'currency', currency: 'CNY' },
    },
  ],
  sort: [{ alias: 'total', direction: 'DESC' }],
  limit: 20,
  layout: 'chart',
  table: { columns: [] },
  chart: {
    type: 'bar',
    cartesian: { x: 'city', series: [{ metric: 'total' }] },
  },
};

export const SALES_ORDER_VIEWS: SystemView[] = [
  {
    id: 'to-ship',
    title: text('orders.toShip'),
    // 写在系统视图上，不在配置里：队列整体读，看板的日期筛选不作用于它。
    timeField: null,
    // 付款最早的先发。
    config: queue(
      [{ field: 'state.status', operator: 'IN', value: ['PAID'] }],
      { sort: [{ field: 'firstEventTime', direction: 'ASC' }] },
    ),
  },
  { id: 'all', title: text('orders.all'), config: queue([]) },
  { id: 'city-totals', title: text('orders.cityTotals'), config: cityTotals },
];
```

- **id** 在定义内唯一、不含 `:`。引擎以 `system:<定义 id>:<视图 id>` 作它的实例 id，`systemInstanceId('sales-orders', 'to-ship')` 拼出同一个值，看板与路由按它引用。
- **时间是意图。** 一段时间写成跟着日历走的 `BETWEEN`（`{ type: 'preset', preset: 'yesterday' }`、`{ type: 'relative', amount: 7, unit: 'day' }`），绝不把今天的日期写进配置。类别给 `IN`、`NOT_IN`，没有 `EQ`。
- **`timeField`** 写在 [`SystemView`](../../reference/typescript/wow-view-engine/definitions.md#api-SystemView) 上：换一个时刻，或 `null` 让这张视图整体读、不受看板的日期筛选影响。
- **分析视图按问题命名**（「各城市销售额」），每个维度与指标有显示名，按回答问题的那个指标排序，图型选能回答问题的那种，而不是最醒目的那种。

### 看板：读者先扫一眼的那一页

看板是自己的一份定义（`kind: 'dashboard'`，没有数据源），它的系统视图就是系统看板。问自己：读者早上先看什么、按什么次序、哪个数点进去打开哪个队列。

<!-- typecheck: file=overview.ts -->

```ts
// src/views/overview.ts
import {
  emptyDashboardConfig,
  systemInstanceId,
  text,
  type AnalysisViewConfig,
  type DashboardDefinition,
} from '@ahoo-wang/wow-view-engine';
import { SALES_ORDERS } from './salesOrders';

/** 一个数：没有维度，范围内只有一行，所以 limit 为 1。 */
function card(
  metric: AnalysisViewConfig['metrics'][number],
  filter: AnalysisViewConfig['filter']['children'] = [],
): AnalysisViewConfig {
  return {
    kind: 'analysis',
    filter: { op: 'and', children: filter },
    filterMode: 'simple',
    refresh: { interval: null },
    groups: [],
    metrics: [metric],
    sort: [],
    limit: 1,
    layout: 'chart',
    table: { columns: [] },
    chart: { type: 'metric', metric: { metric: metric.alias } },
  };
}

const total = {
  type: 'NUMERIC',
  alias: 'total',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'state.totalAmount' },
  label: text('orders.total'),
} as const;

const dailyTotal: AnalysisViewConfig = {
  ...card(total),
  groups: [
    {
      type: 'DATE_HISTOGRAM',
      field: 'firstEventTime',
      alias: 'day',
      unit: 'DAY',
      label: text('orders.day'),
    },
  ],
  sort: [{ alias: 'day', direction: 'ASC' }],
  // 一天一行：给读者能选的最长窗口留够。
  limit: 92,
  chart: { type: 'line', cartesian: { x: 'day', series: [{ metric: 'total' }] } },
};

export const overview: DashboardDefinition = {
  id: 'overview',
  title: text('overview.title'),
  kind: 'dashboard',
  views: [
    {
      id: 'daily',
      title: text('overview.daily'),
      config: {
        ...emptyDashboardConfig(),
        // 看板唯一的日期筛选：经每份定义的 timeField 接到各块面板上。
        fields: [
          {
            name: 'window',
            label: text('orders.placedAt'),
            kind: 'datetime',
            default: { type: 'preset', preset: 'today' },
            required: true,
          },
        ],
        panels: [
          {
            id: 'today',
            kind: 'heading',
            content: text('overview.today'),
            layout: { x: 0, y: 0, w: 24, h: 1 },
          },
          {
            id: 'total',
            kind: 'view',
            title: text('orders.total'),
            bindings: [],
            layout: { x: 0, y: 1, w: 6, h: 2 },
            owned: { definitionId: SALES_ORDERS, config: card(total) },
          },
          {
            id: 'to-ship-count',
            kind: 'view',
            title: text('orders.toShip'),
            bindings: [],
            // 现在还在等的，不管哪天付的款。
            ignoresTime: true,
            layout: { x: 6, y: 1, w: 6, h: 2 },
            owned: {
              definitionId: SALES_ORDERS,
              config: card(
                { type: 'COUNT', alias: 'orders', label: text('orders.count') },
                [{ field: 'state.status', operator: 'IN', value: ['PAID'] }],
              ),
            },
            // 「在工作台中打开」打开队列本身，而不是一个计数。
            opens: systemInstanceId(SALES_ORDERS, 'to-ship'),
          },
          {
            id: 'daily-total',
            kind: 'view',
            title: text('overview.dailyTotal'),
            bindings: [],
            layout: { x: 0, y: 3, w: 24, h: 6 },
            owned: { definitionId: SALES_ORDERS, config: dailyTotal },
          },
          {
            id: 'to-ship',
            kind: 'view',
            title: text('orders.toShip'),
            bindings: [],
            // 它的系统视图写了 timeField: null，所以整体读。
            layout: { x: 0, y: 9, w: 24, h: 6 },
            instanceId: systemInstanceId(SALES_ORDERS, 'to-ship'),
          },
        ],
      },
    },
  ],
};
```

- 栅格 24 列，同一行的面板共用一个 `y`。
- **默认日期**是读者先查的那一段：日报开在「昨天」（一整天，已经结算），实时看板开在「今天」或一个短的相对窗口，一板待办的堆积不需要日期筛选。
- **看板的日期与面板自己的时段是「且」。** 看板的窗口经 `timeField` 到达每块面板，与面板视图自己的条件取交集：一张自带「最近 30 天」的视图放到 7 天的看板上，读的是两者的重叠。给看板做的视图别带自己的时段。整张视图到处都整体读，就在它的系统视图上写 `timeField: null`；只有某一块面板不跟随，就在面板上写 `ignoresTime: true`。
- **`opens`** 让「在工作台中打开」打开同一份定义的另一张视图：计数卡数的是队列列的东西时用它。
- **系统看板只引用系统视图**：系统视图对所有租户可见，共享视图只属于一个租户（[核心概念](./view-engine-concepts.md#系统视图的三种来源)）。看板自己拥有的分析不受这条约束。

## 7. 用 `admit` 自检

定义没有编译期的路径检查：路径是字符串，描述符才知道它对不对。补上这一道的是 `/testing` 的 [`admit`](../../reference/typescript/wow-view-engine/testing.md#api-admit)：它按引擎注册时的同一套规则准入你的声明——每个键在给定的措辞里说不说得出、定义自己的规则与每一份系统视图配置、看板对其他定义的引用，以及每份数据定义按它的 `source` 对着提交的描述快照收窄。全部站得住时返回 `[]`。

一个测试准入宿主注册的全部定义，覆盖宿主服务的每种语言：

<!-- typecheck-context
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
declare const salesOrderDescriptor: QueryModelDescriptor;
declare const describe: (name: string, body: () => void) => void;
declare const it: { each<T>(cases: readonly T[]): (name: string, body: (value: T) => void) => void };
declare function expect(value: unknown): { toEqual(expected: unknown): void };
-->

```ts
// src/views/admit.test.ts
import { admit } from '@ahoo-wang/wow-view-engine/testing';
import { overview } from './overview';
import { salesOrders } from './salesOrders';
import { SALES_ORDER_WORDS } from './words';

describe('the order views', () => {
  it.each(['zh-CN', 'en'] as const)('are admitted in %s', locale => {
    const words: Readonly<Record<string, string>> = SALES_ORDER_WORDS[locale];
    expect(
      admit(
        // 看板和它读的数据定义一起准入，引用才核对得到。
        [salesOrders, overview],
        // 按每份数据定义的 source：提交的快照。
        { 'sales-order': salesOrderDescriptor },
        { text: key => words[key] },
      ),
    ).toEqual([]);
  });
});
```

`admit` 与定义都不需要浏览器，在 Node 环境里跑；宿主的 Vitest 若是浏览器或 jsdom 项目，给定义单独一份 `environment: 'node'` 的配置。

每条发现带 `code`、`params`、指向定义里那一处的 `path`，以及它说的是哪份定义（`definition`）；`/ui` 的 `en`、`zhCN` 两套措辞为每个 code 写了一句话。在它点名的那项选择上改，不要靠放宽 `text`、不给描述符或过滤结果让它闭嘴：

| 发现 | 回头看 |
|---|---|
| `definition.text.unknown` | 这个键在这种语言里没有措辞：补上，或改正拼错的键 |
| `definition.field.undescribed`、`definition.option.undescribed` | 描述符里没有的路径或值。不要猜另一条路径；去掉它，说明缺什么、该从哪来（读模型的字段、模型的声明） |
| `definition.field.unlabelled` | 给字段一个读者的词 |
| `definition.field.deprecated` | 换成替代的路径，或保留并写明理由 |
| `capability.field.protected` | 列了敏感或机密字段：读者必须看到掩码时才留，并在测试里把这条写明 |
| `definition.field.*-wider`、`capability.field.*-narrowed` | 收窄到了这个存储没有的能力：去掉，或者只为另一种有它的部署保留，并写明 |
| `definition.record.row-key-*`、`definition.record.row-field-*` | 行的身份或 `rowFields` 不是列出的、可排序的、有值的字段 |
| `definition.descriptor.missing` | 没把这个 `source` 的描述快照交给 `admit` |
| `definition.view.*`、`filter.*`、`analysis.*`、`dashboard.*`、`record.*` | 某张系统视图或看板的配置：它点名的字段、操作符、别名或面板 |

有意留下的发现照原样断言，旁边写上理由，这样新出现的发现仍会让测试失败。

`admit` 判断不了的，是读者：列出的每个字段是不是他们读、筛、分组用的，次序对不对；措辞是不是他们的话、每种语言都有；每一处收窄是在保护读者还是在复述存储；系统视图与看板是不是开在他们最先要做的事上。这些在评审里看。

## 完整的可运行版本

- Storybook 的[接入导览](/storybook/?path=/docs/view-engine-接入导览--docs)：一份订单定义从声明到页面；[随部署收窄](/storybook/?path=/docs/view-engine-能力-随部署收窄--docs)把同一份定义配上两种存储的描述，看能力怎样跟着变。
- 它的源文件：[`ordersDefinition.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/ordersDefinition.ts)、[`ordersDescriptor.json`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/ordersDescriptor.json)，以及用 `admit` 核对它们的 [`integration.test.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/integration.test.ts)。
- 一个真实的宿主：补偿控制台的 [`src/views/`](https://github.com/Ahoo-Wang/Wow/tree/main/compensation/dashboard/src/views)，定义、每种语言的措辞与准入测试都在这里。
- 设计文档：[`defineView` 的合并规则](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/host-integration.md)、[采用服务端的能力描述](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/capabilities.md)。
- 让智能体照这套写定义：Skill [`wow-view-definition`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-view-definition/SKILL.md)。

## 下一步

| 接下来 | 阅读 |
|---|---|
| 定义、视图、看板与存储在模型里的位置 | [核心概念](./view-engine-concepts.md) |
| 把定义接进引擎、`ViewHost` 与一个操作 | [视图引擎入门](./view-engine-getting-started.md) |
| 换一套外观、接上宿主的主题 | [视图引擎的主题](./view-engine-theming.md) |
