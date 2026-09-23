/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Fetcher } from '@ahoo-wang/fetcher';
import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  SnapshotQueryClient,
} from '@ahoo-wang/fetcher-wow';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type FieldDefinition,
  type FilterNode,
  type RecordViewConfig,
} from '@ahoo-wang/fetcher-view-engine';

/**
 * The Wow trading service the trade order stories start on. Each story takes
 * it as its `host` arg, so the Controls panel can point it anywhere without a
 * restart; set `STORYBOOK_WOW_TRADING_HOST` to change where it starts.
 *
 * It starts on a local port-forward of the dev cluster's trading service,
 * since a browser outside the cluster cannot resolve its name; inside the
 * cluster, point it at `http://trading-service.dev.svc.cluster.local`.
 */
export const DEFAULT_TRADING_HOST: string =
  import.meta.env.STORYBOOK_WOW_TRADING_HOST ?? 'http://localhost:8088';

export const TRADE_ORDER = 'trade-order';

/** The trading service's `TradeOrder` aggregate. */
export const TRADE_ORDER_AGGREGATE = 'trade_order';

/** The statuses of an order waiting on an operator: a review or a revision. */
export const AWAITING = ['PENDING_REVIEW', 'PENDING_AMENDMENT'];

// Wow's names for what the analysis side may group and compute by.
const { TERMS, HISTOGRAM, DATE_HISTOGRAM } = AggregationGroupType;
const { SUM, AVG, MIN, MAX } = AggregationFunction;
const { HOUR, DAY, WEEK, MONTH } = AggregationDateUnit;

/** Every amount of an order is kept in yuan (the schema's `currency`). */
const YUAN = { style: 'currency', currency: 'CNY' } as const;

// What an operator reads an order by, in the width of the page: the paid
// amount and the last update are one column away (`待付款` shows the first).
const COLUMNS = [
  'state.orderNo',
  'state.status',
  'state.customerId.name',
  'state.items',
  'state.payableAmount',
  'state.paymentStatus',
  'state.deliveryStatus',
  'firstEventTime',
];

function recordView(
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
    layout: 'table',
    summaries: [{ field: 'state.payableAmount', fn: 'SUM' }],
    table: { columns: COLUMNS.map(field => ({ field })) },
    // A card is scanned for which order it is and where it stands: the
    // order number titles it — a queue is often one customer's orders, so
    // the customer would title every card alike — and the customer, the
    // status and the money lead the body.
    card: {
      title: 'state.orderNo',
      fields: [
        'state.customerId.name',
        'state.status',
        'state.items',
        'state.payableAmount',
        'state.paymentStatus',
        'firstEventTime',
      ],
    },
    ...overrides,
  };
}

/** A count by `groups`, whose chart plots the first group. */
function analysisView(
  overrides: Partial<AnalysisViewConfig> & Pick<AnalysisViewConfig, 'groups'>,
): AnalysisViewConfig {
  return {
    kind: 'analysis',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    metrics: [{ alias: 'count', type: 'COUNT', label: '订单数' }],
    sort: [{ alias: 'count', direction: 'DESC' }],
    limit: 20,
    layout: 'table',
    table: { columns: [] },
    chart: {
      type: 'bar',
      cartesian: {
        x: overrides.groups[0].alias,
        series: [{ metric: 'count' }],
      },
    },
    ...overrides,
  };
}

/** What the orders of a group are worth: the sum of what they are to pay. */
const PAYABLE = {
  alias: 'payable',
  type: 'NUMERIC',
  function: SUM,
  expression: { type: 'FIELD', field: 'state.payableAmount' },
  label: '应付金额',
} as const;

/** An amount of the order, in yuan, summed in the footer. */
function amount(name: string, label: string): FieldDefinition {
  return {
    name,
    label,
    kind: 'number',
    sortable: true,
    numberFormat: YUAN,
    summary: ['SUM', 'AVG', 'MIN', 'MAX'],
  };
}

const AMOUNTS: [string, string][] = [
  ['state.payableAmount', '应付金额'],
  ['state.paidAmount', '已付金额'],
  ['state.totalProductAmount', '商品总金额'],
  ['state.advancePaymentAmount', '预付款金额'],
  ['state.discountAmount', '优惠金额'],
  ['state.couponDiscountAmount', '优惠券优惠'],
  ['state.scoreDeductionAmount', '积分抵扣金额'],
  ['state.deductibleDeposit', '可抵扣定金'],
  ['state.freightAmount', '运费'],
  ['state.serviceFeeAmount', '服务费'],
  ['state.refundedAmount', '已退款金额'],
];

/** The three sub-statuses of payment and delivery, as the schema spells them. */
function progress(
  none: string,
  partial: string,
  completed: string,
): FieldDefinition['options'] {
  return [
    { value: 'NONE', label: none, tone: 'neutral' },
    { value: 'PARTIAL', label: partial, tone: 'warning' },
    { value: 'COMPLETED', label: completed, tone: 'success' },
  ];
}

/**
 * Trade orders, written by hand from the service's own schema
 * (`GET /trade_order/snapshot/schema`): every field here is one the schema
 * lists, with the operators, sorting and aggregation its capabilities admit,
 * and no more. The labels follow the schema's titles where it has them; the
 * grouping and the choice of what an order operator needs are this
 * definition's.
 *
 * An order's lines are `state.items`, an array the schema scopes
 * (`ELEMENT_SCOPE`): a cell reads them as their products, a condition on a
 * line is an element match, and an analysis of products expands the array
 * and counts lines rather than orders.
 *
 * Left out on purpose:
 * - `state.orderItems`: the schema lists it beside `state.items` with the
 *   same shape, but the service never fills it — every order holds its lines
 *   in `items`;
 * - `state.id` and `aggregateId`: both equal the order number, which is the
 *   row key under the name an operator uses;
 * - the recipient's name, phone and street (`state.shippingAddress`), and the
 *   invoice title, tax number and bank account (`state.invoiceInfo`):
 *   personal and financial data a list has no need to show — the province
 *   and city are enough to see where orders go, and the detail reads the
 *   whole record;
 * - `state.draft`: the draft an order was placed from, a copy of what the
 *   order itself holds;
 * - the receipts and the finance invoice ledgers (`paymentReceipts`,
 *   `refundReceipts`, `finance*InvoiceSnapshots`): finance's bookkeeping,
 *   summed into the paid and refunded amounts, and empty on every order the
 *   service holds;
 * - `state.source.type`: an enum of every business document the platform
 *   knows, of which an order only ever comes from a sales order;
 * - `state.operatingEntityId`: the service writes none yet;
 * - the snapshot's bookkeeping (`version`, `snapshotTime`, `deleted`,
 *   `eventId`, operators, tenant/owner/space, `tags`);
 * - a search field: the model declares no full-text capability.
 */
export const tradeOrderDefinition: DataViewDefinition = {
  id: TRADE_ORDER,
  title: '快照控制台',
  recordNoun: '订单',
  kind: 'data',
  source: TRADE_ORDER_AGGREGATE,
  // The pickers list the fields under these, in this order.
  fieldGroups: [
    {
      id: 'identity',
      label: '标识',
      fields: [
        'state.orderNo',
        'state.buyerOrderNo',
        'state.customerId.name',
        'state.customerId.id',
      ],
    },
    {
      id: 'status',
      label: '状态',
      fields: [
        'state.status',
        'state.paymentStatus',
        'state.deliveryStatus',
        'state.orderType',
        'state.deliveryStrategy',
        'state.channel',
      ],
    },
    { id: 'items', label: '商品', fields: ['state.items'] },
    {
      id: 'amount',
      label: '金额',
      fields: [
        ...AMOUNTS.map(([name]) => name),
        'state.requestPoints',
        'state.deductedPoints',
        'state.couponId',
      ],
    },
    {
      id: 'shipping',
      label: '收货',
      fields: [
        'state.shippingAddress.recipientAddress.province',
        'state.shippingAddress.recipientAddress.city',
      ],
    },
    {
      id: 'time',
      label: '时间',
      fields: ['firstEventTime', 'eventTime', 'state.autoCancelAt'],
    },
  ],
  fields: [
    {
      name: 'state.orderNo',
      label: '订单号',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    {
      name: 'state.buyerOrderNo',
      label: '买家订单号',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    {
      // `customerId` is an object of an id and a name; only its members
      // are queryable, and the name is what an operator reads.
      name: 'state.customerId.name',
      label: '客户',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.customerId.id',
      label: '客户 ID',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    {
      name: 'state.status',
      label: '订单状态',
      kind: 'enum',
      sortable: true,
      cell: 'status',
      options: [
        { value: 'DRAFT', label: '草稿', tone: 'neutral' },
        { value: 'PENDING_REVIEW', label: '待评审', tone: 'warning' },
        { value: 'PENDING_AMENDMENT', label: '待修改', tone: 'warning' },
        { value: 'CONFIRMED', label: '已确认', tone: 'success' },
        { value: 'PROCESSING', label: '处理中', tone: 'success' },
        { value: 'COMPLETED', label: '已完成', tone: 'neutral' },
        { value: 'CANCELLED', label: '已取消', tone: 'danger' },
      ],
    },
    {
      name: 'state.paymentStatus',
      label: '支付状态',
      kind: 'enum',
      sortable: true,
      cell: 'status',
      options: progress('未支付', '部分支付', '已付清'),
    },
    {
      name: 'state.deliveryStatus',
      label: '履约状态',
      kind: 'enum',
      sortable: true,
      cell: 'status',
      options: progress('未发货', '部分发货', '已发齐'),
    },
    {
      name: 'state.orderType',
      label: '订单类型',
      kind: 'enum',
      sortable: true,
      options: [
        { value: 'NORMAL', label: '普通订单' },
        { value: 'DIRECT_SELL', label: '直销订单' },
        { value: 'GROUP_PURCHASE', label: '团购订单' },
      ],
    },
    {
      name: 'state.deliveryStrategy',
      label: '发货策略',
      kind: 'enum',
      sortable: true,
      // The draft's description of the same enum says what each means.
      options: [
        { value: 'TOGETHER', label: '集齐再发' },
        { value: 'READY_STOCK', label: '先到先发' },
      ],
    },
    {
      // Free text the creating client writes (`trade-web`, …): no fixed set.
      name: 'state.channel',
      label: '销售渠道',
      kind: 'string',
      sortable: true,
    },
    {
      // `ELEMENT_SCOPE` alone: a condition on a line is an element match.
      name: 'state.items',
      label: '商品',
      kind: 'elementMatch',
      operators: ['ELEMENT_MATCH'],
      // An order is read by what it sells: each line by its product's model
      // code, and the page asks for the codes alone. The product name leads
      // with the same code and runs on through the brand and the category —
      // eighty characters a line, which pushes every other column off the
      // page; it is one detail away, and what the product ranking groups by.
      elementTitle: 'skuId.code',
      elements: [
        {
          name: 'commercialInfo.productName',
          label: '商品名称',
          kind: 'string',
        },
        { name: 'skuId.code', label: '货号', kind: 'string' },
        { name: 'skuId.brandName', label: '品牌', kind: 'string' },
        { name: 'commercialInfo.specification', label: '规格', kind: 'string' },
        { name: 'qty', label: '数量', kind: 'number' },
        {
          name: 'realPrice',
          label: '实际单价',
          kind: 'number',
          numberFormat: YUAN,
        },
        {
          name: 'totalRealPrice',
          label: '实际总价',
          kind: 'number',
          numberFormat: YUAN,
        },
        {
          name: 'reviewStatus',
          label: '评审状态',
          kind: 'enum',
          options: [
            { value: 'NOT_REQUIRED', label: '无需评审', tone: 'neutral' },
            { value: 'PENDING', label: '待评审', tone: 'warning' },
            { value: 'APPROVED', label: '评审通过', tone: 'success' },
            { value: 'REJECTED', label: '评审驳回', tone: 'danger' },
          ],
        },
        { name: 'allDelivered', label: '已全部发货', kind: 'boolean' },
      ],
    },
    ...AMOUNTS.map(([name, label]) => amount(name, label)),
    {
      name: 'state.requestPoints',
      label: '申请积分',
      kind: 'number',
      sortable: true,
    },
    {
      name: 'state.deductedPoints',
      label: '抵扣积分',
      kind: 'number',
      sortable: true,
      summary: ['SUM'],
    },
    {
      name: 'state.couponId',
      label: '优惠券 ID',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    {
      name: 'state.shippingAddress.recipientAddress.province',
      label: '收货省份',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.shippingAddress.recipientAddress.city',
      label: '收货城市',
      kind: 'string',
      sortable: true,
    },
    {
      // TEMPORAL_EPOCH in milliseconds: the first event is the order's
      // creation.
      name: 'firstEventTime',
      label: '下单时间',
      kind: 'datetime',
      sortable: true,
      summary: ['MIN', 'MAX'],
    },
    {
      name: 'eventTime',
      label: '最近更新',
      kind: 'datetime',
      sortable: true,
      summary: ['MIN', 'MAX'],
    },
    {
      // An integer the schema gives no semantic type, but the service
      // writes epoch milliseconds — the engine's default for a time.
      name: 'state.autoCancelAt',
      label: '自动取消时间',
      kind: 'datetime',
      sortable: true,
    },
  ],
  record: {
    rowKey: 'state.orderNo',
    paging: 'paged',
    layouts: ['table', 'card'],
    // The service refuses a page reaching past its 10,000th row.
    maxWindow: 10_000,
  },
  // What the schema lets the service aggregate: AGGREGATE_TERMS groups by
  // value, AGGREGATE_NUMERIC bands and sums, AGGREGATE_TEMPORAL buckets by
  // date. `autoCancelAt` has no temporal capability, so it buckets nowhere.
  analysis: {
    count: true,
    having: true,
    expressions: true,
    // The service refuses an aggregation asking for more than 1,000 groups.
    limits: { maxLimit: 1000 },
    fields: [
      ...[
        'state.status',
        'state.paymentStatus',
        'state.deliveryStatus',
        'state.orderType',
        'state.deliveryStrategy',
        'state.channel',
        'state.customerId.name',
        'state.shippingAddress.recipientAddress.province',
        'state.shippingAddress.recipientAddress.city',
      ].map(field => ({ field, groups: [TERMS], functions: [] })),
      {
        field: 'state.customerId.id',
        groups: [],
        functions: [],
        distinctCount: true,
      },
      ...AMOUNTS.map(([field]) => ({
        field,
        groups: [HISTOGRAM],
        functions: [SUM, AVG, MIN, MAX],
        percentile: true,
      })),
      {
        field: 'state.deductedPoints',
        groups: [HISTOGRAM],
        functions: [SUM, AVG, MAX],
      },
      ...['firstEventTime', 'eventTime'].map(field => ({
        field,
        groups: [DATE_HISTOGRAM],
        functions: [MIN, MAX],
        dateUnits: [HOUR, DAY, WEEK, MONTH],
      })),
    ],
    elements: [
      {
        path: 'state.items',
        aggregations: [
          ...[
            'commercialInfo.productName',
            'skuId.code',
            'skuId.brandName',
            'reviewStatus',
          ].map(field => ({ field, groups: [TERMS], functions: [] })),
          { field: 'qty', groups: [], functions: [SUM, AVG, MAX] },
          { field: 'totalRealPrice', groups: [], functions: [SUM, AVG, MAX] },
        ],
      },
    ],
  },
  views: [
    {
      // The queue an operator works: the orders waiting on a review or a
      // revision, the longest waiting first.
      id: 'awaiting',
      title: '待处理',
      config: recordView(
        [{ field: 'state.status', operator: 'IN', value: AWAITING }],
        { sort: [{ field: 'firstEventTime', direction: 'ASC' }] },
      ),
    },
    {
      // Confirmed and not paid in full: the ones closest to cancelling
      // themselves first, with when they will.
      id: 'unpaid',
      title: '待付款',
      config: recordView(
        [
          { field: 'state.status', operator: 'IN', value: ['CONFIRMED'] },
          {
            field: 'state.paymentStatus',
            operator: 'IN',
            value: ['NONE', 'PARTIAL'],
          },
        ],
        {
          sort: [{ field: 'state.autoCancelAt', direction: 'ASC' }],
          table: {
            columns: [
              'state.orderNo',
              'state.customerId.name',
              'state.items',
              'state.payableAmount',
              'state.paidAmount',
              'state.paymentStatus',
              'state.autoCancelAt',
              'firstEventTime',
            ].map(field => ({ field })),
          },
        },
      ),
    },
    {
      id: 'cancelled',
      title: '已取消',
      config: recordView([
        { field: 'state.status', operator: 'IN', value: ['CANCELLED'] },
      ]),
    },
    { id: 'all', title: '全部订单', config: recordView([]) },
    {
      id: 'by-status',
      title: '按状态分布',
      config: analysisView({
        groups: [
          {
            type: 'TERMS',
            field: 'state.status',
            alias: 'status',
            label: '订单状态',
          },
        ],
        metrics: [{ alias: 'count', type: 'COUNT', label: '订单数' }, PAYABLE],
        layout: 'chart',
      }),
    },
    {
      // The last thirty days, newest first — a table, since a chart would
      // read them right to left.
      id: 'daily',
      title: '每日下单',
      config: analysisView({
        groups: [
          {
            type: 'DATE_HISTOGRAM',
            field: 'firstEventTime',
            alias: 'day',
            unit: 'DAY',
            label: '日期',
          },
        ],
        metrics: [{ alias: 'count', type: 'COUNT', label: '订单数' }, PAYABLE],
        sort: [{ alias: 'day', direction: 'DESC' }],
        limit: 30,
      }),
    },
    {
      id: 'by-customer',
      title: '客户排行',
      config: analysisView({
        groups: [
          {
            type: 'TERMS',
            field: 'state.customerId.name',
            alias: 'customer',
            label: '客户',
          },
        ],
        metrics: [{ alias: 'count', type: 'COUNT', label: '订单数' }, PAYABLE],
        sort: [{ alias: 'payable', direction: 'DESC' }],
        chart: {
          type: 'bar',
          cartesian: { x: 'customer', series: [{ metric: 'payable' }] },
        },
      }),
    },
    {
      // Lines, not orders: the analysis expands `items` and counts each.
      id: 'by-product',
      title: '商品排行',
      config: analysisView({
        elements: [{ path: 'state.items' }],
        groups: [
          {
            type: 'TERMS',
            field: 'state.items.commercialInfo.productName',
            alias: 'product',
            label: '商品',
          },
        ],
        metrics: [
          { alias: 'count', type: 'COUNT', label: '订单行' },
          {
            alias: 'qty',
            type: 'NUMERIC',
            function: SUM,
            expression: { type: 'FIELD', field: 'state.items.qty' },
            label: '数量',
          },
          {
            alias: 'amount',
            type: 'NUMERIC',
            function: SUM,
            expression: { type: 'FIELD', field: 'state.items.totalRealPrice' },
            label: '金额',
          },
        ],
        sort: [{ alias: 'amount', direction: 'DESC' }],
        chart: {
          type: 'bar',
          cartesian: { x: 'product', series: [{ metric: 'amount' }] },
        },
      }),
    },
  ],
};

/**
 * A fresh engine over the service `fetcher` points at. The snapshot query
 * client is the source as it is: `ViewSource` is three of its methods. Saved
 * views live in memory, so they last as long as the story does.
 */
export function createTradeOrderEngine(fetcher: Fetcher): ViewEngine {
  const source = new SnapshotQueryClient({
    basePath: TRADE_ORDER_AGGREGATE,
    fetcher,
  });
  return new ViewEngine({
    definitions: [tradeOrderDefinition],
    // The service pages at most 100 rows at a time; an export pages at the
    // runtime's largest size, so that is the largest this source takes.
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 100 },
    store: new MemoryViewStore({ instances: [] }),
    resolveSource: () => source,
  });
}

export function tradingFetcher(host: string): Fetcher {
  return new Fetcher({ baseURL: host });
}
