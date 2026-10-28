---
title: 定义视图
description: 依据 Wow 查询模型的描述写一份视图定义：事实、能力与选择，措辞是键，收窄，行必须带的字段，系统视图与仪表盘，并用 admit 检查。
---

# 定义视图

定义说明一份数据集能怎样观察。本页为一个销售订单的快照模型逐项写出一份定义，并说明测试怎样证明引擎会准入它。背后的概念见[视图引擎的核心概念](./#definitions)。

## 1. 从描述开始，并把它提交 {#_1-start-from-the-description-and-commit-it}

每个 Wow 查询模型都描述自己：`GET /{context}/{aggregate}/{model}/query` 返回它的描述，`ETag` 是描述的版本。生成的查询客户端用 `describe()` 读取它。把描述存成 JSON 放在定义旁边，两者一起提交：

<!-- typecheck: skip — the imported JSON is the description committed beside it -->

```ts
// src/views/salesOrderDescriptor.ts
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client/dsl';
// GET /sales/sales-order/snapshot/query, version sha256:a30adffc…
import snapshot from './salesOrderDescriptor.json';

export const salesOrderDescriptor = snapshot as unknown as QueryModelDescriptor;
```

提交一份副本，而不是在运行时去请求，原因是：

- **定义在模块加载时同步构建**，测试读的也是同一个文件；
- **漂移在评审里看得见**：服务变化后，重新取一次覆盖旧副本，diff 和准入测试会列出它影响的每个字段和视图；
- **运行时仍会读取实时的描述**：在第一次查询之前，引擎通过数据源把能力收窄到这次部署允许的范围。

## 2. 事实、能力与选择 {#_2-facts-capabilities-and-choices}

[`defineView(descriptor, spec)`](../../reference/typescript/wow-view-engine/definitions.md#api-defineView) 把三类东西分开：

| 类别 | 是什么 | 来自 | 定义能做什么 |
| --- | --- | --- | --- |
| 事实 | 路径、值类型、枚举值、时间怎样保存、敏感级别、弃用、数组的元素 | 提交的描述 | 只能选择和命名；写了描述里没有的路径或值，就是准入错误 |
| 能力 | 操作符、排序、聚合、检索、上限 | 实时的描述；随存储变化 | 不写就随部署；写了只能收窄 |
| 选择 | 列哪些字段、什么顺序、什么措辞、单元格与语气、收窄、行的身份、系统视图 | 定义 | 全部由你决定 |

字段的种类从事实推出：枚举值或你写的 `options` 得到 `enum`；时间得到 `datetime` 或 `date`；布尔、数字和字符串各归其类；带元素的数组是 `elementMatch`。推不出来的，例如对象，需要你写 `kind`。

## 3. 一份定义，逐项选择 {#_3-a-definition-choice-by-choice}

仓库团队每天清理待发货的订单；客服按城市看销量。为他们写的定义：

<!-- typecheck: file=salesOrders.ts -->
<!-- typecheck-context
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client/dsl';
declare const salesOrderDescriptor: QueryModelDescriptor;
-->

```ts
// src/views/salesOrders.ts
import { AggregationDateUnit } from '@ahoo-wang/wow-client/dsl';
import { defineView, text } from '@ahoo-wang/wow-view-engine';
import { SALES_ORDER_VIEWS } from './salesOrderViews';

export const SALES_ORDERS = 'sales-orders';

export const salesOrders = defineView(salesOrderDescriptor, {
  id: SALES_ORDERS,
  // The key the host registers the source under.
  source: 'sales-order',
  title: text('orders.title'),
  // What an analysis counts in: "12 orders", not "12 records".
  recordNoun: text('orders.noun'),
  // A board's date filter means when the order was placed.
  timeField: 'firstEventTime',
  // Only what is listed appears, in this order: the field picker and the
  // default columns follow it.
  fields: {
    // Looked up and copied, never grouped by.
    aggregateId: {
      label: text('orders.id'),
      cell: 'copyable',
      analysis: false,
    },
    'state.status': {
      label: text('orders.status'),
      cell: 'status',
      // Every value a reader meets, worded; a tone only for a value that is
      // good, bad or waiting.
      options: {
        CREATED: { label: text('orders.created'), tone: 'neutral' },
        PAID: { label: text('orders.paid'), tone: 'warning' },
        SHIPPED: { label: text('orders.shipped'), tone: 'success' },
        RECEIVED: { label: text('orders.received'), tone: 'success' },
      },
    },
    'state.address.province': text('orders.province'),
    'state.address.city': text('orders.city'),
    // A total of the amounts means something; an average per page does not.
    'state.totalAmount': {
      label: text('orders.total'),
      summary: ['sum'],
      more: { numberFormat: { style: 'currency', currency: 'CNY' } },
    },
    'state.paidAmount': {
      label: text('orders.paidAmount'),
      summary: ['sum'],
      more: { numberFormat: { style: 'currency', currency: 'CNY' } },
    },
    // Readers follow trends by day and by month: wow-client's enum, not strings.
    firstEventTime: {
      label: text('orders.placedAt'),
      analysis: {
        dateUnits: [AggregationDateUnit.DAY, AggregationDateUnit.MONTH],
      },
    },
    // An order's lines, each named by its product.
    'state.items': {
      label: text('orders.items'),
      elementTitle: 'productId',
      elements: {
        productId: text('orders.product'),
        quantity: text('orders.quantity'),
        price: text('orders.price'),
      },
    },
    // Free text read in the detail only: never filtered, sorted or grouped.
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
    // The host's "Ship" reads each row's status, whether or not the view
    // shows that column (section 5).
    rowFields: ['state.status'],
  },
  // The system views that ship with the definition (section 6).
  views: SALES_ORDER_VIEWS,
});
```

没写的也是选择：租户、所有者、区县、应付余额。定义是一份**取舍**，不是描述的副本：列得越长，字段选择器越难用。

| 问题 | 选择 |
| --- | --- |
| 读者会读它、按它过滤或分组吗？ | 会就列，不会就不列。 |
| 它是人们称呼一条记录的方式吗？ | `cell: 'copyable'`、`analysis: false`；它唯一且可排序时，作为 `record.rowKey`。 |
| 是只计数、从不分组的 ID 吗？ | `analysis: { groups: [] }`：它仍能用于去重计数。 |
| 是自由文本吗？ | `cell: 'text'`、`sortable: false`、`analysis: false`；没人按它过滤时写 `operators: []`。 |
| 是类别吗？ | 为读者会遇到的每个值写措辞（`options`）；只有好、坏或等待中的值才带 `tone`。 |
| 是金额或数量吗？ | 只给读者会读的合计（`summary`）；币种和单位写在 `more.numberFormat`。 |
| 是时刻吗？ | 把 `analysis.dateUnits` 收窄到读者习惯的粒度。 |
| 是数组吗？ | 它的元素写在 `elements` 里；`elementTitle` 指出哪个元素字段说明“这是哪一项”。 |

字段很多的定义用 `fieldGroups` 给字段分组，每组一个业务上的名字。

## 4. 措辞是键；为受众收窄 {#_4-words-are-keys-narrow-for-the-audience}

每个标题、标签、选项和视图名都写成 `text(key)`，措辞放在每种语言一张的表里，由宿主交给 [`ViewHost`](../../reference/typescript/wow-view-engine/host.md#api-ViewHost)。定义、配置和存储里只有键，所以切换语言只是重画：

<!-- typecheck: file=words.ts -->

```ts
// src/views/words.ts
/** The words of every key, in each language the host serves. `admit` reports a key without words (section 7). */
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
    // The system views' and the analysis' words.
    'orders.toShip': '待发货',
    'orders.all': '全部订单',
    'orders.cityTotals': '各城市销售额',
    'orders.count': '订单数',
    'orders.perOrder': '客单价',
    'orders.day': '日期',
    // The board.
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

措辞是读者的话：绝不是路径、常量、类名或存储的词。没写措辞的字段先退回描述，再退回它的路径，准入会记下这一点。

**为受众收窄，绝不为复述存储而收窄。** 不写的能力随部署：同一份定义在 Elasticsearch 上提供短语检索，在 MongoDB 上没有。收窄会误导读者的东西，例如订单号的平均值、按 ID 分组。不要重复描述已经不提供的东西，也不要重复服务端的上限。运行时，引擎会再把你写的与实时描述取交集。

敏感字段由引擎替你守住：敏感字段不参与分析，机密字段不能比较也不能排序。你要决定的只是列不列它。

## 5. 行必须带的字段 {#_5-the-fields-a-row-must-carry}

行的身份 `record.rowKey` 默认是描述里的身份，快照是 `aggregateId`。它必须是列出的、可排序的字段：每次记录查询都以它作为排序的最后一项，所以分页既不重复也不遗漏。

**引擎只查询视图显示的东西**：可见的列、卡片的字段、排序和汇总读取的字段，以及身份。隐藏的列不会取回。所以当宿主代码从行上读一个字段时，例如检查状态的操作，这个字段在不显示它的视图里可能不存在。把宿主代码读取的每个字段都列进 `record.rowFields`：

```ts
import type { RecordCapability } from '@ahoo-wang/wow-view-engine';

export const record: Partial<RecordCapability> = {
  layouts: ['table', 'card'],
  // "Ship" reads the status; the row's identity is always fetched.
  rowFields: ['state.status'],
};
```

### 看板布局 {#a-kanban-layout}

记录要依次经过几个阶段时，提供第三种布局 `'kanban'`：卡片按一个枚举字段的选项排成一栏一栏，每栏有自己的条数和自己的“加载更多”：

```ts
import type { RecordCapability } from '@ahoo-wang/wow-view-engine';

export const pipeline: Partial<RecordCapability> = {
  // A lane's next cards are its next page: a cursor reads them best.
  paging: 'cursor',
  layouts: ['kanban', 'table', 'card'],
};
```

每一栏都是一条独立的查询，所以用游标读取下一批卡片最合适。把卡片移到另一栏，是一个设置分栏字段的声明式操作（[操作](./actions#kanban)）。

## 6. 系统视图与仪表盘 {#_6-system-views-and-boards}

系统视图随定义发布：所有人可见、只读，读者修改之前先另存为自己的。只写读者每天打开的那几张，标题说明它装着什么（“待发货”），按决定下一条处理谁的字段排序：

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
    sort: [{ field: 'firstEventTime', direction: 'desc' }],
    pageSize: 20,
    summaries: [{ field: 'state.totalAmount', fn: 'sum' }],
    layout: 'table',
    table: { columns: COLUMNS.map(field => ({ field })) },
    card: {
      title: 'aggregateId',
      fields: ['state.status', 'state.totalAmount'],
    },
    ...overrides,
  };
}

/** Sales by city: the last 30 days, resolved against the calendar on every run. */
const cityTotals: AnalysisViewConfig = {
  kind: 'analysis',
  filter: {
    op: 'and',
    children: [
      {
        field: 'firstEventTime',
        operator: 'between',
        value: { type: 'relative', amount: 30, unit: 'day' },
      },
    ],
  },
  filterMode: 'simple',
  refresh: { interval: null },
  groups: [
    {
      type: 'terms',
      field: 'state.address.city',
      alias: 'city',
      label: text('orders.city'),
    },
  ],
  metrics: [
    { type: 'count', alias: 'orders', label: text('orders.count') },
    {
      type: 'numeric',
      alias: 'total',
      function: 'sum',
      expression: { type: 'field', field: 'state.totalAmount' },
      label: text('orders.total'),
    },
    {
      // A ratio is a metric over metrics, not a field.
      type: 'derived',
      alias: 'perOrder',
      label: text('orders.perOrder'),
      expression: {
        type: 'binary',
        operator: 'divide',
        left: { type: 'metric-ref', metric: 'total' },
        right: { type: 'metric-ref', metric: 'orders' },
      },
      format: { style: 'currency', currency: 'CNY' },
    },
  ],
  sort: [{ alias: 'total', direction: 'desc' }],
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
    // On the system view, not in its config: a queue is read whole, and a
    // board's date filter never reaches it.
    timeField: null,
    // The earliest paid ships first.
    config: queue(
      [{ field: 'state.status', operator: 'in', value: ['PAID'] }],
      { sort: [{ field: 'firstEventTime', direction: 'asc' }] },
    ),
  },
  { id: 'all', title: text('orders.all'), config: queue([]) },
  { id: 'city-totals', title: text('orders.cityTotals'), config: cityTotals },
];
```

- **id** 在定义内唯一，不含 `:`。它的实例 ID 是 `system:<definition>:<view>`，仪表盘和路由用 `systemInstanceId` 拼出它。
- **时间是意图。** 一段时间随日历移动（`{ type: 'relative', amount: 7, unit: 'day' }`）；绝不把今天的日期写进配置。
- 系统视图上的 **`timeField: null`** 让它不受仪表盘日期筛选的影响：队列要整体读。
- **分析按它回答的问题命名**，并选择能回答这个问题的图。

仪表盘是一份独立的定义，`kind: 'dashboard'`，没有数据源；它的系统视图就是系统仪表盘。问一问读者先看什么、按什么顺序、哪个数字点进去打开哪个队列：

<!-- typecheck: file=overview.ts -->

```ts
// src/views/overview.ts
import {
  emptyDashboardConfig,
  inRows,
  systemInstanceId,
  text,
  type AnalysisViewConfig,
  type DashboardDefinition,
} from '@ahoo-wang/wow-view-engine';
import { SALES_ORDERS } from './salesOrders';

/** One number: no dimension, one row over everything in range, so a limit of 1. */
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
  type: 'numeric',
  alias: 'total',
  function: 'sum',
  expression: { type: 'field', field: 'state.totalAmount' },
  label: text('orders.total'),
} as const;

const dailyTotal: AnalysisViewConfig = {
  ...card(total),
  groups: [
    {
      type: 'date-histogram',
      field: 'firstEventTime',
      alias: 'day',
      unit: 'day',
      label: text('orders.day'),
    },
  ],
  sort: [{ alias: 'day', direction: 'asc' }],
  // A row a day: room for the longest window a reader picks.
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
        // The board's one date filter: it reaches each panel through that
        // definition's timeField.
        fields: [
          {
            name: 'window',
            label: text('orders.placedAt'),
            kind: 'datetime',
            default: { type: 'preset', preset: 'today' },
            required: true,
          },
        ],
        panels: inRows(
          [
            {
              id: 'today',
              kind: 'heading',
              content: text('overview.today'),
              layout: { w: 24, h: 1 },
            },
          ],
          [
            {
              id: 'total',
              kind: 'view',
              title: text('orders.total'),
              bindings: [],
              layout: { w: 6, h: 2 },
              owned: { definitionId: SALES_ORDERS, config: card(total) },
            },
            {
              id: 'to-ship-count',
              kind: 'view',
              title: text('orders.toShip'),
              bindings: [],
              // What waits now, whenever it was paid.
              ignoresTime: true,
              layout: { w: 6, h: 2 },
              owned: {
                definitionId: SALES_ORDERS,
                config: card(
                  { type: 'count', alias: 'orders', label: text('orders.count') },
                  [{ field: 'state.status', operator: 'in', value: ['PAID'] }],
                ),
              },
              // "Open in workbench" opens the queue itself, not a count.
              opens: systemInstanceId(SALES_ORDERS, 'to-ship'),
            },
          ],
          [
            {
              id: 'daily-total',
              kind: 'view',
              title: text('overview.dailyTotal'),
              bindings: [],
              layout: { w: 24, h: 6 },
              owned: { definitionId: SALES_ORDERS, config: dailyTotal },
            },
          ],
          [
            {
              id: 'to-ship',
              kind: 'view',
              title: text('orders.toShip'),
              bindings: [],
              // Read whole: its system view says timeField: null.
              layout: { w: 24, h: 6 },
              instanceId: systemInstanceId(SALES_ORDERS, 'to-ship'),
            },
          ],
        ),
      },
    },
  ],
};
```

- `inRows` 根据面板的尺寸逐行排布面板，所以增加面板不需要重新编号。
- **仪表盘的日期与面板自己的时段合并**：仪表盘的时间窗口通过 `timeField` 作用到每个面板，并与视图自己的条件取交集。`ignoresTime` 让某个面板不跟随。
- **`opens`** 让“在工作台中打开”打开另一个视图，例如一个计数所统计的那个队列。
- **系统仪表盘只展示系统视图**，它们每个租户都能读到。仪表盘自有的分析不受这条约束。

## 7. 用 `admit` 检查 {#_7-check-it-with-admit}

路径只是一个字符串；只有描述知道它对不对。`/testing` 中的 [`admit`](../../reference/typescript/wow-view-engine/testing.md#api-admit) 按引擎注册它们时的规则准入你的声明：每个键都有措辞，每个系统视图和仪表盘都有效，每份数据定义都收窄到它提交的描述。全部成立时返回 `[]`。一个测试准入宿主注册的全部东西，覆盖它服务的每种语言：

<!-- typecheck-context
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client/dsl';
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
        // A board is admitted with the data definitions it reads, so its
        // references are checked.
        [salesOrders, overview],
        // By each data definition's source: the committed snapshots.
        { 'sales-order': salesOrderDescriptor },
        { text: key => words[key] },
      ),
    ).toEqual([]);
  });
});
```

它不需要浏览器：在 Node 环境里运行。每条发现带一个 `code`、一个指向定义中那一处的 `path`，以及它说的是哪份定义。在它点名的那项选择上修正，而不是让它闭嘴：

| 发现 | 回头看 |
| --- | --- |
| `definition.text.unknown` | 这个键在这种语言里没有措辞 |
| `definition.field.undescribed`、`definition.option.undescribed` | 描述里没有的路径或值：去掉它，不要猜另一个 |
| `definition.field.unlabelled` | 给字段一个读者的词 |
| `definition.field.deprecated` | 换成替代的路径，或保留并写明理由 |
| `capability.field.protected` | 列了敏感字段：只在读者必须看到掩码值时保留，并断言这条发现 |
| `definition.record.row-key-*`、`definition.record.row-field-*` | 身份或某个 `rowFields` 项不是列出的、可排序的、有值的字段 |
| `definition.view.*`、`filter.*`、`analysis.*`、`dashboard.*` | 某个系统视图或仪表盘的配置 |

有意保留的发现要原样断言，并写上理由，这样新出现的发现仍会让测试失败。`admit` 判断不了的是读者：字段、措辞、收窄和系统视图对他们是不是对的。这要靠评审。

## 可运行的示例 {#working-examples}

- ops 控制台的定义、措辞和操作，以及 `parked.test.ts` 中的准入测试：[`platform/ops/console/src/views/`](https://github.com/Ahoo-Wang/Wow/tree/main/platform/ops/console/src/views)。
- Storybook 的[接入导览](/storybook/?path=/docs/view-engine-接入导览--docs)，一份订单定义从声明到页面。
