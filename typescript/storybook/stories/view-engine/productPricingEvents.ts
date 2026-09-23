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
  type FilterNode,
  type RecordViewConfig,
} from '@ahoo-wang/fetcher-view-engine';
import {
  PRICE_FORMAT,
  PRICING_STATUSES,
  PRODUCT_PRICING_AGGREGATE,
} from './productPricing.js';

export const PRODUCT_PRICING_EVENTS = 'product-pricing-events';

// Wow's names for what the analysis side may group and compute by.
const { TERMS, HISTOGRAM, DATE_HISTOGRAM } = AggregationGroupType;
const { AVG, MIN, MAX } = AggregationFunction;
const { HOUR, DAY, WEEK, MONTH } = AggregationDateUnit;

/**
 * The event types of a `ProductPricing` stream. The first two are the
 * schema's `enumValues` for `body[].bodyType`; the third is Wow's own, which
 * the service's ABAC applies when a pricing is created and the schema does
 * not list — the streams hold it all the same, so it is named too.
 */
export const PRICING_EVENT = {
  saved: 'com.linyikj.pricing.api.product.ProductPricingSaved',
  statusChanged: 'com.linyikj.pricing.api.product.ProductPricingStatusChanged',
  tagsApplied: 'me.ahoo.wow.api.abac.DefaultResourceTagsApplied',
} as const;

/**
 * When, which pricing and which version, then what happened. The row key
 * leads, as the table pins it first whatever the order says. The request
 * id — a batch import's name runs to forty characters — waits in the
 * picker, on the card and in a stream's detail: as a column it would push
 * the table past the page, and the table holds its last column at the
 * right edge over what scrolls.
 */
const COLUMNS = ['id', 'createTime', 'aggregateId', 'version', 'body'];

/**
 * The streams that carry an event of one of `types`: an element match, the
 * one way the schema lets a query reach inside `body`.
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
      fields: ['body', 'createTime', 'version', 'requestId'],
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

/** How many pricings the events of a bucket belong to. */
const PRICINGS = {
  alias: 'pricings',
  type: 'DISTINCT_COUNT',
  label: '涉及定价',
  expression: { type: 'FIELD', field: 'aggregateId' },
} as const;

/**
 * The event stream of `product_pricing` as the service's own query schema
 * describes it (`GET /product_pricing/event/schema`, model `EVENT_STREAM`),
 * converted by hand: every field here is one the schema lists, with the
 * operators, sorting and aggregation its capabilities admit, and no more.
 *
 * One record is one event stream — what one command appended to one
 * pricing, with its version and time — and its events sit in `body`, an
 * array the schema scopes (`ELEMENT_SCOPE`): a condition on an event is an
 * element match, and an analysis of events expands `body` and counts events
 * rather than streams. Each event's payload is one of two shapes the schema
 * does describe with capabilities — a saved pricing (product, delivery,
 * quantity, price, deadline) or a new status — so the payload members a
 * pricing's history is read by are element fields: which product, at what
 * price, to which status.
 *
 * Left out on purpose:
 * - `contextName` and `aggregateName`: one aggregate of one service, the
 *   same on every stream; `tenantId` is the one tenant, `ownerId` and
 *   `spaceId` are empty;
 * - `commandId`: one per stream and opaque; `requestId` stays, since the
 *   service's batch imports name their requests (`pricing-tier-20260917-…`);
 * - `header`: an open map with no capability — and it carries the caller's
 *   address and user agent, which a console has no business showing;
 * - the rest of a saved pricing's payload (delivery, quantity, deadline, the
 *   product's other ids): the snapshot console holds them as they are now,
 *   and one stream's detail shows them as they were;
 * - a search field: the model declares no full-text capability.
 */
export const productPricingEventsDefinition: DataViewDefinition = {
  id: PRODUCT_PRICING_EVENTS,
  title: '事件流分析台',
  kind: 'data',
  source: PRODUCT_PRICING_AGGREGATE,
  fieldGroups: [
    {
      id: 'identity',
      label: '标识',
      fields: ['aggregateId', 'id', 'requestId'],
    },
    {
      id: 'event',
      label: '事件',
      fields: ['body', 'version', 'createTime'],
    },
  ],
  fields: [
    {
      name: 'aggregateId',
      label: '定价 ID',
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
      // `ELEMENT_SCOPE` and presence: the match is what a condition takes.
      name: 'body',
      label: '事件',
      kind: 'elementMatch',
      operators: ['ELEMENT_MATCH'],
      // A stream is read by what happened in it: each event by its type,
      // in the type's own words. The page then asks for `body.bodyType`
      // alone — the payloads stay on the server until a stream is opened.
      elementTitle: 'bodyType',
      elements: [
        {
          name: 'bodyType',
          label: '事件类型',
          kind: 'enum',
          options: [
            { value: PRICING_EVENT.saved, label: '保存定价' },
            { value: PRICING_EVENT.statusChanged, label: '变更状态' },
            { value: PRICING_EVENT.tagsApplied, label: '应用默认标签' },
          ],
        },
        { name: 'body.skuId.code', label: '商品编码', kind: 'string' },
        {
          name: 'body.price',
          label: '单价',
          kind: 'number',
          numberFormat: PRICE_FORMAT,
        },
        {
          name: 'body.status',
          label: '变更后状态',
          kind: 'enum',
          options: PRICING_STATUSES,
        },
        // The schema declares no values for the name, so it stays text.
        { name: 'name', label: '事件名', kind: 'string' },
        { name: 'revision', label: '事件修订', kind: 'string' },
        { name: 'id', label: '事件 ID', kind: 'string' },
      ],
    },
  ],
  record: { rowKey: 'id', paging: 'paged', layouts: ['table', 'card'] },
  // AGGREGATE_TERMS groups by value, AGGREGATE_NUMERIC bands and computes,
  // AGGREGATE_TEMPORAL buckets by date. The ids that are one per stream have
  // terms too, and are left out: a group of one answers nothing.
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
        aggregations: [
          ...['bodyType', 'body.status', 'body.skuId.code', 'name'].map(
            field => ({ field, groups: [TERMS], functions: [] }),
          ),
          { field: 'body.price', groups: [], functions: [AVG, MIN, MAX] },
        ],
      },
    ],
    // The service refuses an aggregation asking for more than 1,000 groups.
    limits: { maxLimit: 1000 },
  },
  views: [
    { id: 'recent', title: '最近的事件', config: recordView([]) },
    {
      // A template: fill in the pricing and read its history in order.
      // Left blank it reads every history, one after another.
      id: 'history',
      title: '定价历史',
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
      id: 'status-changed',
      title: '状态变更',
      config: recordView([carrying(PRICING_EVENT.statusChanged)]),
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
      id: 'monthly',
      title: '每月事件量',
      config: analysisView({
        groups: [
          {
            type: 'DATE_HISTOGRAM',
            field: 'createTime',
            alias: 'month',
            unit: 'MONTH',
            label: '月份',
          },
        ],
        metrics: [{ alias: 'count', type: 'COUNT', label: '事件数' }, PRICINGS],
        sort: [{ alias: 'month', direction: 'ASC' }],
        limit: 120,
        layout: 'chart',
        chart: {
          type: 'line',
          cartesian: {
            x: 'month',
            series: [{ metric: 'count' }, { metric: 'pricings' }],
          },
        },
      }),
    },
    {
      // The last thirty days with events, newest first — a table, since a
      // chart would read them right to left.
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
        metrics: [{ alias: 'count', type: 'COUNT', label: '事件数' }, PRICINGS],
        sort: [{ alias: 'day', direction: 'DESC' }],
        limit: 30,
      }),
    },
    {
      id: 'most-changed',
      title: '改动最多的定价',
      config: analysisView({
        groups: [
          {
            type: 'TERMS',
            field: 'aggregateId',
            alias: 'pricing',
            label: '定价 ID',
          },
        ],
        // Changed after it was created: a pricing whose only stream is its
        // creation is most of them, and would push the rest off the list.
        having: {
          type: 'CONDITION',
          metric: 'count',
          operator: 'GT',
          value: 1,
        },
        // How many times, and when the last one was: the latest of its
        // events' times, which reads as a date.
        metrics: [
          { alias: 'count', type: 'COUNT', label: '事件数' },
          {
            alias: 'latest',
            type: 'NUMERIC',
            function: MAX,
            expression: { type: 'FIELD', field: 'createTime' },
            label: '最近一次改动',
          },
        ],
      }),
    },
  ],
};

/**
 * A fresh engine over the service `fetcher` points at. The event stream
 * query client is the source as it is, as the snapshot client is.
 */
export function createProductPricingEventsEngine(fetcher: Fetcher): ViewEngine {
  const source = new EventStreamQueryClient({
    basePath: PRODUCT_PRICING_AGGREGATE,
    fetcher,
  });
  return new ViewEngine({
    definitions: [productPricingEventsDefinition],
    store: new MemoryViewStore({ instances: [] }),
    resolveSource: () => source,
    // The service pages at most 100 streams at a time.
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 100 },
  });
}
