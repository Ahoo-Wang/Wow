# Choices

In depth: [Writing a Definition](https://wow.ahoo.me/guide/typescript/view-engine-definitions.html) (facts, capabilities and choices, words, narrowing, `rowFields`, system views, boards, `admit`) and the [definitions reference](https://wow.ahoo.me/reference/typescript/wow-view-engine/definitions.html). Confirm members against the installed typings (`DefineViewSpec`, `FieldSpec`): a patch release never breaks the public surface, a minor release may. This page is about deciding what to write.

## A definition, choice by choice

A customer-service team works through paid orders and asks which channels sell. The descriptor is the committed snapshot of `GET {base}/order/snapshot/schema`:

<!-- typecheck: skip — the JSON is the committed snapshot beside the module -->

```ts
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import snapshot from './order.descriptor.json'; // version sha256:…

export const orderDescriptor = snapshot as unknown as QueryModelDescriptor;
```

<!-- typecheck: file=orders.ts -->
<!-- typecheck-context
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
declare const orderDescriptor: QueryModelDescriptor;
-->

```ts
import { AggregationDateUnit } from '@ahoo-wang/wow-client';
import { defineView, text } from '@ahoo-wang/wow-view-engine';

export const ORDERS = 'orders';

/**
 * The words of every key the order views use, per language the host serves:
 * the definition's below, its system views' and the board's
 * (`views-and-boards.md`). `admit` reports any key without words.
 */
export const ORDER_WORDS = {
  'zh-CN': {
    'orders.title': '订单',
    'orders.noun': '订单',
    'orders.id': '系统编号',
    'orders.no': '订单号',
    'orders.status': '状态',
    'orders.pendingPayment': '待付款',
    'orders.paid': '已付款',
    'orders.shipped': '已发货',
    'orders.signed': '已签收',
    'orders.cancelled': '已取消',
    'orders.channel': '渠道',
    'orders.app': 'App',
    'orders.miniProgram': '小程序',
    'orders.web': '网站',
    'orders.warehouse': '发货仓',
    'orders.paidAmount': '实付',
    'orders.paidAt': '付款时间',
    'orders.placedAt': '下单时间',
    'orders.remark': '买家备注',
    'orders.level': '会员等级',
    'orders.regular': '普通会员',
    'orders.silver': '银卡会员',
    'orders.gold': '金卡会员',
    'orders.items': '商品',
    'orders.sku': '商品编码',
    'orders.itemTitle': '商品名称',
    'orders.qty': '件数',
    // System views and their analysis words.
    'orders.toShip': '待发货',
    'orders.all': '全部订单',
    'orders.count': '订单数',
    'orders.perOrder': '客单价',
    'orders.channelSales': '各渠道实付',
    'orders.day': '日期',
    // The board.
    'overview.title': '订单概览',
    'overview.daily': '每日',
    'overview.today': '今天的订单',
    'overview.dailyPaid': '每日实付',
  },
  en: {
    'orders.title': 'Orders',
    'orders.noun': 'order',
    'orders.id': 'System ID',
    'orders.no': 'Order no.',
    'orders.status': 'Status',
    'orders.pendingPayment': 'Awaiting payment',
    'orders.paid': 'Paid',
    'orders.shipped': 'Shipped',
    'orders.signed': 'Delivered',
    'orders.cancelled': 'Cancelled',
    'orders.channel': 'Channel',
    'orders.app': 'App',
    'orders.miniProgram': 'Mini program',
    'orders.web': 'Web',
    'orders.warehouse': 'Warehouse',
    'orders.paidAmount': 'Amount paid',
    'orders.paidAt': 'Paid at',
    'orders.placedAt': 'Placed at',
    'orders.remark': 'Buyer note',
    'orders.level': 'Member level',
    'orders.regular': 'Regular',
    'orders.silver': 'Silver',
    'orders.gold': 'Gold',
    'orders.items': 'Items',
    'orders.sku': 'SKU',
    'orders.itemTitle': 'Product',
    'orders.qty': 'Quantity',
    'orders.toShip': 'To ship',
    'orders.all': 'All orders',
    'orders.count': 'Order count',
    'orders.perOrder': 'Average order value',
    'orders.channelSales': 'Amount paid by channel',
    'orders.day': 'Day',
    'overview.title': 'Order overview',
    'overview.daily': 'Daily',
    'overview.today': 'Today\'s orders',
    'overview.dailyPaid': 'Amount paid per day',
  },
} as const;

export const orders = defineView(orderDescriptor, {
  id: ORDERS,
  // The key the host registers the order snapshots under.
  source: 'order',
  title: text('orders.title'),
  recordNoun: text('orders.noun'),
  // A board's date filter means when the order was paid.
  timeField: 'state.paidAt',
  fields: {
    // What the team looks an order up by: copied, never grouped.
    'state.orderNo': { label: text('orders.no'), cell: 'copyable', analysis: false },
    'state.status': {
      label: text('orders.status'),
      cell: 'status',
      // Every value the team can meet, worded, with the tone it reads in.
      options: {
        PENDING_PAYMENT: { label: text('orders.pendingPayment') },
        PAID: { label: text('orders.paid'), tone: 'warning' },
        SHIPPED: { label: text('orders.shipped'), tone: 'success' },
        SIGNED: { label: text('orders.signed'), tone: 'success' },
        CANCELLED: { label: text('orders.cancelled'), tone: 'neutral' },
      },
    },
    'state.channel': {
      label: text('orders.channel'),
      options: {
        APP: text('orders.app'),
        MINI_PROGRAM: text('orders.miniProgram'),
        WEB: text('orders.web'),
      },
    },
    'state.warehouse': text('orders.warehouse'),
    // A total of what was paid means something; an average per page does not.
    'state.paidAmount': {
      label: text('orders.paidAmount'),
      summary: ['SUM'],
      more: { numberFormat: { style: 'currency', currency: 'CNY' } },
    },
    // The units the team reads a trend in: wow-client's enum, not strings.
    'state.paidAt': {
      label: text('orders.paidAt'),
      analysis: {
        dateUnits: [AggregationDateUnit.DAY, AggregationDateUnit.MONTH],
      },
    },
    'state.createdAt': text('orders.placedAt'),
    // Free text the buyer wrote: read in the detail, never filtered or grouped.
    'state.remark': {
      label: text('orders.remark'),
      cell: 'text',
      operators: [],
      sortable: false,
      analysis: false,
    },
    // A category inside a nested object: its path, and every value worded.
    'state.buyer.level': {
      label: text('orders.level'),
      options: {
        REGULAR: text('orders.regular'),
        SILVER: text('orders.silver'),
        GOLD: text('orders.gold'),
      },
    },
    // The lines of an order, named by product.
    'state.items': {
      label: text('orders.items'),
      elementTitle: 'title',
      elements: {
        sku: { label: text('orders.sku'), cell: 'copyable' },
        title: text('orders.itemTitle'),
        qty: text('orders.qty'),
      },
    },
    // The id commands address: listed so the actions can read it, last in
    // the picker; the system views leave it out of their columns.
    aggregateId: { label: text('orders.id'), cell: 'copyable', analysis: false },
  },
  record: {
    // Looked up (and linked, `?id=`) by the order number.
    rowKey: 'state.orderNo',
    layouts: ['table', 'card'],
    // What the host's actions read whatever columns a view shows.
    rowFields: ['aggregateId', 'state.status', 'state.warehouse'],
  },
});
```

What is left out is a choice too: the tenant, the buyer's phone, ID card and income (the audience needs none of them), and `state.oldStatus` (deprecated in favour of `state.status`). The definition declares no `kind` the facts already give: an enum, or `options` you write, makes `enum`; an array with `elements` is `elementMatch`; an object or a union the facts cannot tell needs a `kind` (`definition.field.kind-unknown`).

## Fields

| Question | Choice |
| --- | --- |
| Does this audience read, filter or group by it? | List it; otherwise leave it out. A longer list is not a better definition. |
| In what order do they read it? | The order of `fields` is the order of the field picker and the default columns. |
| Is it how they name a record? | `cell: 'copyable'`, `analysis: false`; make it `record.rowKey` when it is unique and sortable and people look records up by it. Commands still address the aggregate id: with a business key as `rowKey`, list the id and put it in `record.rowFields` so actions read it off the row (`wow-view-host`). |
| Is it an id you count but never group by? | `analysis: { groups: [] }`: it still feeds a distinct count. |
| Is it free text? | `cell: 'text'`, `sortable: false`, `analysis: false`; `operators: []` when no one filters by it; a search box (`search: { fields, mode }`) when people look for words in it. |
| Is it a category? | Word every value (`options`), with a `tone` only where the value is good, bad or waiting (`success`, `danger`, `warning`, `neutral`); hide a value nobody meets with `false`. |
| Is it an amount or a count? | `summary` only for a total the audience reads (`SUM`), not every function the store has; `more: { numberFormat }` (an `Intl.NumberFormat` options object) for currency or units. |
| Is it a moment? | Nothing to narrow but `analysis: { dateUnits }` to the units the audience thinks in, as wow-client's `AggregationDateUnit` values; a config's `DATE_HISTOGRAM` names its `unit` as the string (`'DAY'`). A table cell shows it to the minute (the whole time is its tooltip and the record detail); `timePrecision: 'second'` where seconds are what people read it for — an event stream's time. |
| Does the host's code read it whatever the view shows? | `record.rowFields`: an action's rule or the command's id. A page fetches only what the view shows. |
| Is it an array? | Its entries in `elements` (keys relative to the entry), `elementTitle` for the entry field that names one. |
| Does the scenario want a field the descriptor lacks? | Do not write it. Say what is missing and where it would come from (a read-model field, a model declaration, a projection that computes it). |

Field groups (`fieldGroups: [{ id, label, fields }]`) arrange the picker and the record detail for a long definition; give each a business label.

## Narrowing

Capabilities left open follow the source at run time (a phrase search on Elasticsearch and none on MongoDB; a raised server limit without a deploy). Narrow only what would mislead the audience — an average of order numbers, a sort by a stack trace, a group by an id — never to repeat what the descriptor withholds, and never restate a server limit (`record.maxWindow`, `analysis.limits`) unless the audience needs a lower one.

## Protected, deprecated, missing

- A field with `sensitivity` never becomes a dimension, a metric input, an expression operand or a metric filter; a confidential one (`comparable: false`) takes no condition and no sort. The engine enforces both. Decide whether it appears at all; show a masked value only when the audience must, and never add a derived field or condition that reveals it.
- A field with `deprecated` is replaced by the path its message names. Keep one only with `deprecated: { message: '…why…' }` and flag it in the report.
- An alias in the descriptor is not a path: write the canonical `path`.

## Words

Labels are the audience's words in every language the host serves, written as keys (`text('orders.paidAmount')`) and said by the engine where they are shown. Keep the words beside the definition (one table per language, or one per file merged by scope) and hand them to the host (`wow-view-host`) for `ViewHost`'s `messages` and the engine's `text`. A literal string is still allowed for a single-language host.

Never a path (`state.paidAmount`), a constant (`PAID`, `OrderPaid`), a class name, or a storage word (document, collection, index, snapshot, aggregate, payload).

Analysis words follow the engine's vocabulary; do not coin synonyms in display names or titles:

| 中文 | English |
| --- | --- |
| 维度 | Dimension |
| 指标 | Metric |
| 记录数 | Record count |
| 显示名 | Display name |
| 前 N 组 | Top N groups |
| 展开 / 明细项 | Expand / line items |
| 合计行 | Totals row |
| 只保留 | Keep only |
| 总和（SUM；「合计」只指合计行） | Sum |

A metric's display name says what it measures for the business (「实付」「客单价」「退款率」), not the field and function (`SUM(paidAmount)`). In Chinese UI words, do not build terms on 看, 按 or 在 (「按渠道看」 is a sentence, 「各渠道实付」 is a name).

## Event streams

An event-stream record is one command's appended events: root fields such as `aggregateId`, `id`, `version`, `createTime`, `commandId`, and `body`, the array of events. The descriptor's `variants` (`element: 'body'`, `discriminator: 'bodyType'`) lists each event type's payload fields relative to the event.

<!-- typecheck-context
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
declare const orderEventDescriptor: QueryModelDescriptor;
-->

```ts
import { defineView, text } from '@ahoo-wang/wow-view-engine';

// Every `orderEvents.*` key has words per language beside it, as `ORDER_WORDS` above.
export const orderEvents = defineView(orderEventDescriptor, {
  id: 'order-events',
  source: 'order/event',
  title: text('orderEvents.title'),
  recordNoun: text('orderEvents.noun'),
  timeField: 'createTime',
  fields: {
    // The row key: the identity the store appends to every sort.
    id: { label: text('orderEvents.id'), cell: 'copyable', analysis: false },
    aggregateId: { label: text('orderEvents.order'), cell: 'copyable', analysis: { groups: [] } },
    // Events land seconds apart: the table keeps the seconds.
    createTime: { label: text('orderEvents.time'), timePrecision: 'second' },
    body: {
      label: text('orderEvents.events'),
      elementTitle: 'bodyType',
      elements: {
        bodyType: {
          label: text('orderEvents.type'),
          options: {
            'com.example.order.OrderCreated': text('orderEvents.created'),
            'com.example.order.OrderPaid': text('orderEvents.paid'),
            'com.example.order.OrderCancelled': text('orderEvents.cancelled'),
          },
        },
        // Only on OrderPaid, relative to the event.
        'body.paidAmount': text('orderEvents.paidAmount'),
      },
    },
  },
});
```

In a config, element fields are full paths from the root (`body.bodyType`, `body.body.paidAmount`). A condition on a payload keeps the event type and the payload in one element predicate, so both hold for the same event; two root conditions would match a stream where one event is the type and another has the amount:

```ts
import type { FilterNode } from '@ahoo-wang/wow-view-engine';

export const LARGE_PAYMENTS: FilterNode = {
  field: 'body',
  operator: 'ELEMENT_MATCH',
  value: {
    op: 'and',
    children: [
      { field: 'body.bodyType', operator: 'IN', value: ['com.example.order.OrderPaid'] },
      { field: 'body.body.paidAmount', operator: 'GT', value: 1000 },
    ],
  },
};
```

A metric that counts one event type puts the same `ELEMENT_MATCH` in its own `filter`.
