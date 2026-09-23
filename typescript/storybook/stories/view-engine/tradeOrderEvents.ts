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

import type { Fetcher } from '@ahoo-wang/fetcher';
import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  EventStreamQueryClient,
} from '@ahoo-wang/fetcher-wow';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type FieldOption,
  type FilterNode,
  type RecordViewConfig,
} from '@ahoo-wang/fetcher-view-engine';
import { TRADE_ORDER_AGGREGATE } from './tradeOrder.js';

export const TRADE_ORDER_EVENTS = 'trade-order-events';

// Wow's names for what the analysis side may group and compute by.
const { TERMS, HISTOGRAM, DATE_HISTOGRAM } = AggregationGroupType;
const { AVG, MIN, MAX } = AggregationFunction;
const { HOUR, DAY, WEEK, MONTH } = AggregationDateUnit;

const API = 'com.linyikj.trading.api.order';

/**
 * The event types of a `TradeOrder` stream — the schema's `enumValues` for
 * `body[].bodyType`, all twenty-nine — each named for what it says happened
 * to the order. The schema's own titles cannot be used: it gives every
 * event shape the same title and description (those of the payment event).
 */
const EVENT = {
  created: `${API}.create.OrderCreated`,
  confirmed: `${API}.confirm.OrderConfirmed`,
  reviewPassed: `${API}.review.OrderItemReviewPassed`,
  reviewRejected: `${API}.review.OrderItemReviewRejected`,
  amendmentPending: `${API}.amendment.OrderAmendmentPending`,
  changed: `${API}.amendment.OrderChanged`,
  addressChanged: `${API}.address.ShippingAddressChanged`,
  sourceChanged: `${API}.source.OrderSourceChanged`,
  cancelled: `${API}.cancel.OrderCancelled`,
  closed: `${API}.close.OrderClosed`,
} as const;

/** The events that change what was ordered, or where it goes. */
const AMENDMENTS = [EVENT.changed, EVENT.addressChanged, EVENT.sourceChanged];

const EVENT_TYPES: FieldOption[] = [
  { value: EVENT.created, label: '下单' },
  { value: `${API}.draft.OrderDraftSaved`, label: '草稿保存' },
  { value: `${API}.draft.OrderDraftUpdated`, label: '草稿更新' },
  { value: EVENT.reviewPassed, label: '商品评审通过', tone: 'success' },
  { value: EVENT.reviewRejected, label: '商品评审驳回', tone: 'danger' },
  { value: EVENT.amendmentPending, label: '转入待修改', tone: 'warning' },
  { value: EVENT.changed, label: '订单变更' },
  { value: EVENT.addressChanged, label: '收货地址变更' },
  { value: EVENT.sourceChanged, label: '订单来源变更' },
  { value: EVENT.confirmed, label: '订单确认', tone: 'success' },
  { value: `${API}.payment.PaymentReceived`, label: '收到付款' },
  {
    value: `${API}.payment.FinancePaymentSucceededRecorded`,
    label: '财务记录支付成功',
  },
  { value: `${API}.payment.OrderFundsProcessed`, label: '款项已处理' },
  {
    value: `${API}.payment.OrderFundsDuplicateIgnored`,
    label: '重复款项已忽略',
  },
  { value: `${API}.payment.OrderProcessingStarted`, label: '开始处理' },
  {
    value: `${API}.refund.FinanceRefundSucceededRecorded`,
    label: '财务记录退款成功',
  },
  { value: `${API}.finance.FinanceFactIgnored`, label: '财务事实已忽略' },
  { value: `${API}.delivery.DeliveryOrderPrepared`, label: '发货单已备货' },
  {
    value: `${API}.delivery.DeliveryOrderCreatedConfirmed`,
    label: '发货单已创建',
  },
  {
    value: `${API}.delivery.DeliveryOrderShippedConfirmed`,
    label: '已发货',
  },
  {
    value: `${API}.delivery.DeliveryOrderSignedConfirmed`,
    label: '已签收',
    tone: 'success',
  },
  {
    value: `${API}.delivery.DeliveryOrderCancelledConfirmed`,
    label: '发货单已取消',
    tone: 'warning',
  },
  {
    value: `${API}.invoicing.FinanceAdvanceInvoiceQuantityRecorded`,
    label: '预开票数量已记录',
  },
  {
    value: `${API}.invoicing.FinanceFulfillmentInvoiceQuantityRecorded`,
    label: '履约开票数量已记录',
  },
  {
    value: `${API}.invoicing.FinanceInvoiceUsageRecorded`,
    label: '开票用量已记录',
  },
  {
    value: `${API}.invoicing.SalesBillingSourceReadyConfirmed`,
    label: '开票来源就绪',
  },
  {
    value: `${API}.invoicing.SalesOrderFulfillmentEligibilityDeclared`,
    label: '履约开票资格已声明',
  },
  { value: EVENT.cancelled, label: '订单取消', tone: 'danger' },
  { value: EVENT.closed, label: '订单关闭', tone: 'neutral' },
];

// The row key leads, as the table pins it first whatever the order says.
// The request id stays a field, not a column: beside the command id it is a
// second opaque id, and the table then runs past the page.
const COLUMNS = [
  'id',
  'createTime',
  'aggregateId',
  'body',
  'version',
  'commandId',
];

/**
 * The streams that carry an event of one of `types`: a condition on an event
 * is an element match on `body`, the one way the schema reaches inside it.
 */
function carrying(...types: string[]): FilterNode {
  return {
    field: 'body',
    operator: 'ELEMENT_MATCH',
    value: {
      op: 'and',
      children: [{ field: 'body.bodyType', operator: 'IN', value: types }],
    },
  };
}

function recordView(
  filter: FilterNode[],
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    kind: 'record',
    filter: { op: 'and', children: filter },
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [{ field: 'createTime', direction: 'DESC' }],
    pageSize: 20,
    layout: 'table',
    summaries: [],
    table: { columns: COLUMNS.map(field => ({ field })) },
    card: {
      title: 'aggregateId',
      fields: ['body', 'createTime', 'version', 'commandId'],
    },
    ...overrides,
  };
}

/** A count of events by `groups`, whose chart plots the first group. */
function analysisView(
  overrides: Partial<AnalysisViewConfig> & Pick<AnalysisViewConfig, 'groups'>,
): AnalysisViewConfig {
  return {
    kind: 'analysis',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    metrics: [{ alias: 'count', type: 'COUNT', label: '事件数' }],
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

/** How many orders the events of a bucket belong to. */
const ORDERS = {
  alias: 'orders',
  type: 'DISTINCT_COUNT',
  label: '涉及订单',
  expression: { type: 'FIELD', field: 'aggregateId' },
} as const;

/**
 * The event stream of `trade_order` as the service's own query schema
 * describes it (`GET /trade_order/event/schema`, model `EVENT_STREAM`),
 * converted by hand: every field here is one the schema lists, with the
 * operators, sorting and aggregation its capabilities admit, and no more.
 *
 * One record is one event stream — what one command appended to one order,
 * with its version and time — and its events sit in `body`, an array the
 * schema scopes (`ELEMENT_SCOPE`): a condition on an event is an element
 * match, and an analysis of events expands `body` and counts events rather
 * than streams. A stream often holds two: an order created and confirmed at
 * once, a line rejected and the order sent back for amendment.
 *
 * Left out on purpose:
 * - `contextName` and `aggregateName`: this resource is one aggregate of one
 *   service, so both are the same on every stream;
 * - `tenantId`, `ownerId` and `spaceId`: the same on every stream the
 *   service holds;
 * - `header`: an open map with no capability at all — the operator who sent
 *   the command lives there, so it can be read in a stream's detail but not
 *   filtered or grouped by;
 * - each event's payload (`body[].body`): a union of twenty-nine event
 *   shapes with no capability;
 * - a search field: the model declares no full-text capability.
 */
export const tradeOrderEventsDefinition: DataViewDefinition = {
  id: TRADE_ORDER_EVENTS,
  title: '事件流分析台',
  kind: 'data',
  source: TRADE_ORDER_AGGREGATE,
  fieldGroups: [
    {
      id: 'identity',
      label: '标识',
      fields: ['aggregateId', 'id', 'commandId', 'requestId'],
    },
    {
      id: 'event',
      label: '事件',
      fields: ['body', 'version', 'createTime'],
    },
  ],
  fields: [
    {
      // The order's aggregate id, which is its order number.
      name: 'aggregateId',
      label: '订单号',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    {
      name: 'id',
      label: '事件流 ID',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    {
      name: 'commandId',
      label: '命令 ID',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    {
      name: 'requestId',
      label: '请求 ID',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    { name: 'version', label: '版本', kind: 'number', sortable: true },
    {
      // TEMPORAL_EPOCH in milliseconds; exact match, range and sort.
      name: 'createTime',
      label: '事件时间',
      kind: 'datetime',
      sortable: true,
    },
    {
      // Presence and `ELEMENT_SCOPE`: a stream always holds an event, so
      // only the match is worth offering.
      name: 'body',
      label: '事件',
      kind: 'elementMatch',
      operators: ['ELEMENT_MATCH'],
      // A stream is read by what happened in it: each event by its type,
      // in the type's own words; the page asks for the types alone.
      elementTitle: 'bodyType',
      elements: [
        {
          name: 'bodyType',
          label: '事件类型',
          kind: 'enum',
          options: EVENT_TYPES,
        },
        // The schema declares no values for these, so they stay text.
        { name: 'name', label: '事件名', kind: 'string' },
        { name: 'revision', label: '事件修订', kind: 'string' },
        { name: 'id', label: '事件 ID', kind: 'string' },
      ],
    },
  ],
  record: { rowKey: 'id', paging: 'paged', layouts: ['table', 'card'] },
  // AGGREGATE_TERMS groups by value, AGGREGATE_NUMERIC bands and computes,
  // AGGREGATE_TEMPORAL buckets by date. The ids that are one per stream —
  // the stream's, the command's, the request's — have terms too, and are
  // left out: a group of one answers nothing.
  analysis: {
    count: true,
    having: true,
    expressions: true,
    fields: [
      {
        field: 'aggregateId',
        groups: [TERMS],
        functions: [],
        distinctCount: true,
      },
      {
        field: 'version',
        groups: [TERMS, HISTOGRAM],
        functions: [AVG, MIN, MAX],
        percentile: true,
      },
      {
        field: 'createTime',
        groups: [DATE_HISTOGRAM],
        functions: [MIN, MAX],
        dateUnits: [HOUR, DAY, WEEK, MONTH],
      },
    ],
    elements: [
      {
        path: 'body',
        aggregations: ['bodyType', 'name', 'revision'].map(field => ({
          field,
          groups: [TERMS],
          functions: [],
        })),
      },
    ],
    limits: { maxLimit: 1000 },
  },
  views: [
    { id: 'recent', title: '最近的事件', config: recordView([]) },
    {
      // A template: fill in the order number and read its history in order.
      // Left blank it reads every history, one after another.
      id: 'history',
      title: '订单历史',
      config: recordView(
        [{ field: 'aggregateId', operator: 'EQ', value: '' }],
        {
          sort: [
            { field: 'aggregateId', direction: 'ASC' },
            { field: 'version', direction: 'ASC' },
          ],
        },
      ),
    },
    {
      id: 'review-rejected',
      title: '评审驳回',
      config: recordView([carrying(EVENT.reviewRejected)]),
    },
    {
      id: 'amended',
      title: '改单',
      config: recordView([carrying(...AMENDMENTS)]),
    },
    {
      id: 'cancelled',
      title: '取消与关闭',
      config: recordView([carrying(EVENT.cancelled, EVENT.closed)]),
    },
    {
      id: 'by-type',
      title: '事件类型分布',
      config: analysisView({
        elements: [{ path: 'body' }],
        groups: [
          {
            type: 'TERMS',
            field: 'body.bodyType',
            alias: 'type',
            label: '事件类型',
          },
        ],
        layout: 'chart',
      }),
    },
    {
      // The last thirty days, newest first — a table, since a chart would
      // read them right to left.
      id: 'daily',
      title: '每日事件量',
      config: analysisView({
        groups: [
          {
            type: 'DATE_HISTOGRAM',
            field: 'createTime',
            alias: 'day',
            unit: 'DAY',
            label: '日期',
          },
        ],
        metrics: [{ alias: 'count', type: 'COUNT', label: '事件流数' }, ORDERS],
        sort: [{ alias: 'day', direction: 'DESC' }],
        limit: 30,
      }),
    },
    {
      // The orders that were handled most — reviewed, changed, sent back —
      // and when they last were.
      id: 'busiest',
      title: '变动最多的订单',
      config: analysisView({
        groups: [
          {
            type: 'TERMS',
            field: 'aggregateId',
            alias: 'order',
            label: '订单号',
          },
        ],
        metrics: [
          { alias: 'count', type: 'COUNT', label: '变动次数' },
          {
            alias: 'latest',
            type: 'NUMERIC',
            function: MAX,
            expression: { type: 'FIELD', field: 'createTime' },
            label: '最近一次',
          },
        ],
      }),
    },
  ],
};

/**
 * A fresh engine over the service `fetcher` points at. The event stream
 * query client is the source as it is — `paged`, `cursor` and `aggregate`
 * are `ViewSource`'s three methods. A page holds at most a hundred streams:
 * an event carries its payload, and an order's creation carries the order.
 */
export function createTradeOrderEventsEngine(fetcher: Fetcher): ViewEngine {
  const source = new EventStreamQueryClient({
    basePath: TRADE_ORDER_AGGREGATE,
    fetcher,
  });
  return new ViewEngine({
    definitions: [tradeOrderEventsDefinition],
    store: new MemoryViewStore({ instances: [] }),
    resolveSource: () => source,
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 100 },
  });
}
