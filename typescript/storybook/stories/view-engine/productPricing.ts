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
  type FilterNode,
  type NumberFormat,
  type RecordViewConfig,
  type ViewSource,
} from '@ahoo-wang/fetcher-view-engine';

/**
 * The Wow pricing service the product pricing scenes start on: a local
 * port-forward of `pricing-service`, since a browser outside the cluster
 * cannot resolve its in-cluster address
 * (`http://pricing-service.dev.svc.cluster.local`), which is the one to use
 * from inside it. Each story takes it as its `host` arg, so the Controls
 * panel can point it anywhere without a restart; set
 * `STORYBOOK_WOW_PRICING_HOST` to change where it starts.
 */
export const DEFAULT_PRICING_HOST: string =
  import.meta.env.STORYBOOK_WOW_PRICING_HOST ?? 'http://localhost:8089';

export const PRODUCT_PRICING = 'product-pricing';

/** The pricing service's `ProductPricing` aggregate. */
export const PRODUCT_PRICING_AGGREGATE = 'product_pricing';

// Wow's names for what the analysis side may group and compute by.
const { TERMS, HISTOGRAM, DATE_HISTOGRAM } = AggregationGroupType;
const { AVG, MIN, MAX } = AggregationFunction;
const { DAY, WEEK, MONTH } = AggregationDateUnit;

/**
 * A price is money. The schema says `DECIMAL` and nothing about the
 * currency; the service prices its catalogue in yuan.
 */
export const PRICE_FORMAT: NumberFormat = {
  style: 'currency',
  currency: 'CNY',
};

/** How soon a price delivers, as the schema's delivery types name them. */
export const DELIVERY_TYPES = [
  { value: 'SPOT', label: '现货' },
  { value: 'PROXY_SPOT', label: '代理现货' },
  { value: 'FORWARD', label: '期货' },
  { value: 'NONE', label: '未定义' },
];

/** A pricing's status, the schema's `enumValues`, each worn as a badge. */
export const PRICING_STATUSES = [
  { value: 'ACTIVE', label: '生效中', tone: 'success' as const },
  { value: 'INACTIVE', label: '已停用', tone: 'neutral' as const },
  { value: 'EXPIRED', label: '已过期', tone: 'warning' as const },
];

/**
 * The prices still in force today. A deadline is kept as the last
 * millisecond of its day, so "on or after the start of today" is "valid
 * through today at least".
 */
const IN_FORCE: FilterNode[] = [
  { field: 'state.status', operator: 'IN', value: ['ACTIVE'] },
  {
    field: 'state.deadline',
    operator: 'GTE',
    value: { type: 'preset', preset: 'today' },
  },
];

/**
 * The columns an operator reads a price by: which pricing and product,
 * which delivery, from how many, at what price, until when, and whether it
 * is in force — all in view beside the host's navigation without scrolling.
 * The brand, the delivery cycle's weeks and the times wait in the picker,
 * on the card and in a record's detail: a column more would slide the
 * deadline under the status, which the table holds at the right edge as
 * the last column. The row key leads, as the table pins it first.
 */
const COLUMNS = [
  'state.id',
  'state.skuId.code',
  'state.deliveryTime.type',
  'state.minOrderQuantity',
  'state.price',
  'state.deadline',
  'state.status',
];

/**
 * A product's tiers read together: the product, then the delivery from the
 * soonest, then the quantity from the smallest — so the price falls down
 * the page as the quantity rises.
 */
const BY_PRODUCT = [
  { field: 'state.skuId.code', direction: 'ASC' as const },
  {
    field: 'state.deliveryTime.deliveryCycle.start',
    direction: 'ASC' as const,
  },
  { field: 'state.minOrderQuantity', direction: 'ASC' as const },
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
    sort: [{ field: 'eventTime', direction: 'DESC' }],
    pageSize: 20,
    layout: 'table',
    summaries: [],
    table: { columns: COLUMNS.map(field => ({ field })) },
    // A card is a price tag: the product titles it, and what it costs, from
    // how many and until when lead the body.
    card: {
      title: 'state.skuId.code',
      fields: [
        'state.skuId.brandName',
        'state.price',
        'state.minOrderQuantity',
        'state.deliveryTime.type',
        'state.status',
        'state.deadline',
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
    metrics: [{ alias: 'count', type: 'COUNT', label: '定价数' }],
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

/** A function of the prices of a group, under its own name. */
function price(
  alias: string,
  fn: AggregationFunction,
  label: string,
): AnalysisViewConfig['metrics'][number] {
  return {
    alias,
    type: 'NUMERIC',
    function: fn,
    expression: { type: 'FIELD', field: 'state.price' },
    label,
  };
}

/**
 * `product_pricing` as the service's own query schema describes it
 * (`GET /product_pricing/snapshot/schema`, model `SNAPSHOT`), converted by
 * hand: every field here is one the schema lists, with the operators,
 * sorting and aggregation its capabilities admit, and no more. The schema
 * titles a few fields (定价金额, 最小起订量, 价格有效截止时间戳); the labels
 * here are the words a pricing operator uses, and the grouping and the
 * choice of what they need are this definition's.
 *
 * One record is one price tier: a product, a delivery (spot, proxy spot or
 * forward, with its cycle in weeks), a minimum order quantity, and the unit
 * price at that quantity, valid until its deadline. A product holds several
 * tiers, so the views read a product's tiers together.
 *
 * Kinds the schema does not say:
 * - `state.deadline` is an integer with no `semanticType`; the service
 *   writes epoch milliseconds, the last one of a day, so it is a `date`.
 * - `state.price` is a `DECIMAL`; it reads as yuan (`PRICE_FORMAT`).
 *
 * Left out on purpose:
 * - `aggregateId`: the same value as `state.id`, the row key;
 * - `contextName`, `aggregateName` and `tenantId`: one aggregate of one
 *   single-tenant service, the same on every record; `ownerId` and
 *   `spaceId` are empty on every record;
 * - the snapshot's bookkeeping — `eventId`, `snapshotTime`, `deleted`,
 *   `firstOperator` and `operator` (the system's `(0)` on every record) —
 *   and `tags`, an open map with no capability of its own;
 * - `state.skuId.searchCode` and `state.skuId.brandId`: the code and the
 *   brand again, as a search key and an internal id;
 * - `state.productCostVersion`: a version only means something beside the
 *   cost it versions, which `state.productCostId` names;
 * - a search field: the model declares no full-text capability, so the
 *   products are found by their code (`CONTAINS`) instead.
 */
export const productPricingDefinition: DataViewDefinition = {
  id: PRODUCT_PRICING,
  title: '快照控制台',
  recordNoun: '商品定价',
  kind: 'data',
  source: PRODUCT_PRICING_AGGREGATE,
  // The pickers list the fields under these, in this order.
  fieldGroups: [
    {
      id: 'product',
      label: '商品',
      fields: [
        'state.skuId.code',
        'state.skuId.brandName',
        'state.skuId.id',
        'state.skuId.bizId',
        'state.skuId.orderNo',
        'state.skuId.isComposite',
      ],
    },
    {
      id: 'price',
      label: '价格',
      fields: [
        'state.price',
        'state.minOrderQuantity',
        'state.status',
        'state.deadline',
      ],
    },
    {
      id: 'delivery',
      label: '交货',
      fields: [
        'state.deliveryTime.type',
        'state.deliveryTime.deliveryCycle.start',
        'state.deliveryTime.deliveryCycle.end',
      ],
    },
    {
      id: 'identity',
      label: '标识',
      fields: ['state.id', 'state.productCostId', 'version'],
    },
    { id: 'time', label: '时间', fields: ['firstEventTime', 'eventTime'] },
  ],
  fields: [
    {
      name: 'state.id',
      label: '定价 ID',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    {
      // What a product is called on a quote and in a catalogue, and what an
      // operator pastes to find it; letters and digits mean what they say.
      name: 'state.skuId.code',
      label: '商品编码',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
      stringComparison: 'CASE_SENSITIVE',
    },
    {
      name: 'state.skuId.brandName',
      label: '品牌',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.skuId.id',
      label: 'SKU ID',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    {
      name: 'state.skuId.bizId',
      label: '业务编码',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    {
      name: 'state.skuId.orderNo',
      label: '货号',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.skuId.isComposite',
      label: '组合商品',
      kind: 'boolean',
      sortable: true,
    },
    {
      name: 'state.price',
      label: '单价',
      kind: 'number',
      sortable: true,
      numberFormat: PRICE_FORMAT,
      // A sum of unit prices is no amount anyone pays.
      summary: ['MIN', 'AVG', 'MAX'],
    },
    {
      name: 'state.minOrderQuantity',
      label: '起订量',
      kind: 'number',
      sortable: true,
    },
    {
      name: 'state.status',
      label: '状态',
      kind: 'enum',
      sortable: true,
      cell: 'status',
      options: PRICING_STATUSES,
    },
    {
      name: 'state.deadline',
      label: '有效期至',
      kind: 'date',
      sortable: true,
      temporal: { type: 'epoch', timeUnit: 'MILLISECONDS' },
      summary: ['MIN', 'MAX'],
    },
    {
      name: 'state.deliveryTime.type',
      label: '货期类型',
      kind: 'enum',
      sortable: true,
      options: DELIVERY_TYPES,
    },
    {
      name: 'state.deliveryTime.deliveryCycle.start',
      label: '交货周期起（周）',
      kind: 'number',
      sortable: true,
    },
    {
      name: 'state.deliveryTime.deliveryCycle.end',
      label: '交货周期止（周）',
      kind: 'number',
      sortable: true,
    },
    {
      // Null for a price set by hand rather than from a product cost.
      name: 'state.productCostId',
      label: '来源成本 ID',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    { name: 'version', label: '版本', kind: 'number', sortable: true },
    {
      name: 'firstEventTime',
      label: '创建时间',
      kind: 'datetime',
      sortable: true,
    },
    { name: 'eventTime', label: '最近更新', kind: 'datetime', sortable: true },
  ],
  record: {
    rowKey: 'state.id',
    paging: 'paged',
    layouts: ['table', 'card'],
    // The service refuses a page reaching past its 10,000th row.
    maxWindow: 10_000,
  },
  // What the schema lets the service aggregate: AGGREGATE_TERMS groups by
  // value, AGGREGATE_NUMERIC bands and computes, AGGREGATE_TEMPORAL buckets
  // by date — which the two event times have and the deadline does not, so
  // the deadline has an earliest and a latest and no calendar.
  analysis: {
    count: true,
    having: true,
    expressions: true,
    // The service refuses an aggregation asking for more than 1,000 groups.
    limits: { maxLimit: 1000 },
    fields: [
      ...[
        'state.status',
        'state.deliveryTime.type',
        'state.skuId.brandName',
        'state.skuId.code',
        'state.skuId.isComposite',
      ].map(field => ({ field, groups: [TERMS], functions: [] })),
      {
        field: 'state.skuId.id',
        groups: [TERMS],
        functions: [],
        distinctCount: true,
      },
      {
        field: 'state.price',
        groups: [HISTOGRAM],
        functions: [AVG, MIN, MAX],
        percentile: true,
      },
      {
        field: 'state.minOrderQuantity',
        groups: [TERMS, HISTOGRAM],
        functions: [AVG, MIN, MAX],
      },
      ...[
        'state.deliveryTime.deliveryCycle.start',
        'state.deliveryTime.deliveryCycle.end',
      ].map(field => ({ field, groups: [TERMS], functions: [MIN, MAX] })),
      { field: 'state.deadline', groups: [], functions: [MIN, MAX] },
      ...['firstEventTime', 'eventTime'].map(field => ({
        field,
        groups: [DATE_HISTOGRAM],
        functions: [MIN, MAX],
        dateUnits: [DAY, WEEK, MONTH],
      })),
    ],
  },
  views: [
    {
      id: 'in-force',
      title: '生效中',
      config: recordView(IN_FORCE, { sort: BY_PRODUCT }),
    },
    {
      // Six months is a pricing cycle: what lapses within it needs a new
      // quote before it does.
      id: 'expiring',
      title: '半年内到期',
      config: recordView(
        [
          { field: 'state.status', operator: 'IN', value: ['ACTIVE'] },
          {
            field: 'state.deadline',
            operator: 'BETWEEN',
            value: {
              type: 'relative',
              amount: 6,
              unit: 'month',
              direction: 'future',
            },
          },
        ],
        { sort: [{ field: 'state.deadline', direction: 'ASC' }] },
      ),
    },
    {
      id: 'lapsed',
      title: '已停用或过期',
      config: recordView([
        {
          field: 'state.status',
          operator: 'IN',
          value: ['INACTIVE', 'EXPIRED'],
        },
      ]),
    },
    { id: 'all', title: '全部定价', config: recordView([]) },
    {
      id: 'by-status',
      title: '按状态分布',
      config: analysisView({
        groups: [{ type: 'TERMS', field: 'state.status', alias: 'status' }],
        layout: 'chart',
      }),
    },
    {
      // Where the prices sit, in bands of five hundred yuan, from the
      // cheapest band up, with how many products each band holds.
      id: 'by-price',
      title: '价格区间分布',
      config: analysisView({
        groups: [
          {
            type: 'HISTOGRAM',
            field: 'state.price',
            alias: 'band',
            interval: 500,
            label: '价格区间',
          },
        ],
        metrics: [
          { alias: 'count', type: 'COUNT', label: '定价数' },
          {
            alias: 'products',
            type: 'DISTINCT_COUNT',
            expression: { type: 'FIELD', field: 'state.skuId.id' },
            label: '商品数',
          },
        ],
        sort: [{ alias: 'band', direction: 'ASC' }],
        limit: 100,
        layout: 'chart',
      }),
    },
    {
      // Each brand's price range, and when its first price lapses.
      id: 'by-brand',
      title: '各品牌价格',
      config: analysisView({
        groups: [
          {
            type: 'TERMS',
            field: 'state.skuId.brandName',
            alias: 'brand',
            label: '品牌',
          },
        ],
        metrics: [
          { alias: 'count', type: 'COUNT', label: '定价数' },
          price('lowest', MIN, '最低单价'),
          price('highest', MAX, '最高单价'),
          {
            alias: 'earliest',
            type: 'NUMERIC',
            function: MIN,
            expression: { type: 'FIELD', field: 'state.deadline' },
            label: '最早到期',
          },
        ],
      }),
    },
    {
      id: 'by-delivery',
      title: '货期类型分布',
      config: analysisView({
        groups: [
          {
            type: 'TERMS',
            field: 'state.deliveryTime.type',
            alias: 'delivery',
            label: '货期类型',
          },
        ],
        metrics: [
          { alias: 'count', type: 'COUNT', label: '定价数' },
          price('average', AVG, '平均单价'),
        ],
      }),
    },
  ],
};

/** The fetcher every scene over the pricing service at `host` shares. */
export function pricingFetcher(host: string): Fetcher {
  return new Fetcher({ baseURL: host });
}

/**
 * The product pricings of the service `fetcher` points at, as a view
 * source: the snapshot query client as it is, since `ViewSource` is three
 * of its methods.
 */
export function productPricingSource(fetcher: Fetcher): ViewSource {
  return new SnapshotQueryClient({
    basePath: PRODUCT_PRICING_AGGREGATE,
    fetcher,
  });
}

/**
 * A fresh engine over the service `fetcher` points at. Saved views live in
 * memory, so they last as long as the story does.
 */
export function createProductPricingEngine(fetcher: Fetcher): ViewEngine {
  const source = productPricingSource(fetcher);
  return new ViewEngine({
    definitions: [productPricingDefinition],
    // The service pages at most 100 rows at a time.
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 100 },
    store: new MemoryViewStore({ instances: [] }),
    resolveSource: () => source,
  });
}
