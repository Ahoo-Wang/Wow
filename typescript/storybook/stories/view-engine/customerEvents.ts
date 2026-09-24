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
} from '@ahoo-wang/wow-client';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type FieldOption,
  type FilterNode,
  type RecordViewConfig,
} from '@ahoo-wang/wow-view-engine';
import { CUSTOMER_AGGREGATE } from './customer.js';

export const CUSTOMER_EVENTS = 'customer-events';

// Wow's names for what the analysis side may group and compute by.
const { TERMS, HISTOGRAM, DATE_HISTOGRAM } = AggregationGroupType;
const { AVG, MIN, MAX } = AggregationFunction;
const { HOUR, DAY, WEEK, MONTH } = AggregationDateUnit;

/** The package the CRM's customer events are declared in. */
const API = 'com.linyikj.crm.api.customer';

/**
 * The event types of a `Customer` stream — the schema's `enumValues` for
 * `body[].bodyType`, all 31 of them — each named for what it says happened to
 * the customer and grouped by what it touches, so the picker lists them the
 * way an analyst looks for one. What ends a customer's standing reads as a
 * danger, what restores or confirms it as a success, and what waits on
 * someone as a warning; the rest are plain changes.
 */
const EVENT_TYPES: [string, string, string, FieldOption['tone']?][] = [
  ['CustomerCreated', '创建客户', '客户'],
  ['CustomerBasicInfoUpdated', '更新基本信息', '客户'],
  ['CustomerSalesInfoUpdated', '更新销售信息', '客户'],
  ['CustomerTagsUpdated', '更新标签', '客户'],
  ['CustomerTimelineUpdated', '更新时间信息', '客户'],
  ['CustomerLastActivityTimeUpdated', '记录最近活动', '客户'],
  ['CustomerExtendUpdated', '更新扩展字段', '客户'],
  ['CustomerEnabled', '启用客户', '客户', 'success'],
  ['CustomerDisabled', '禁用客户', '客户', 'danger'],
  ['CustomerClaimed', '认领客户', '归属'],
  ['CustomerReleased', '释放到公海', '归属'],
  ['CustomerTransferred', '转移负责人', '归属'],
  ['ContactAdded', '新增联系人', '联系人'],
  ['ContactUpdated', '更新联系人', '联系人'],
  ['ContactRemoved', '移除联系人', '联系人'],
  ['CustomerContactAccountApplied', '申请联系人账号', '联系人'],
  ['CustomerContactAccountBound', '联系人账号已开通', '联系人', 'success'],
  ['CustomerContactAccountFailed', '联系人账号开通失败', '联系人', 'danger'],
  ['DeliveryAddressAdded', '新增收货地址', '收货地址'],
  ['DeliveryAddressUpdated', '更新收货地址', '收货地址'],
  ['DeliveryAddressRemoved', '移除收货地址', '收货地址'],
  ['DefaultDeliveryAddressSet', '设置默认收货地址', '收货地址'],
  ['InvoiceAdded', '新增开票信息', '开票信息'],
  ['InvoiceUpdated', '更新开票信息', '开票信息'],
  ['InvoiceRemoved', '移除开票信息', '开票信息'],
  ['DefaultInvoiceSet', '设置默认开票信息', '开票信息'],
  [
    'CustomerEnterpriseVerificationManualReviewRequired',
    '企业认证待人工审核',
    '企业认证',
    'warning',
  ],
  [
    'CustomerEnterpriseVerificationPassed',
    '企业认证通过',
    '企业认证',
    'success',
  ],
  [
    'CustomerEnterpriseVerificationRejected',
    '企业认证驳回',
    '企业认证',
    'danger',
  ],
  ['CustomerFinancialInfoUpdated', '更新财务信息', '财务'],
  ['CustomerFinancialRelationshipEstablished', '建立财务关系', '财务'],
];

const EVENT_OPTIONS: FieldOption[] = EVENT_TYPES.map(
  ([type, label, group, tone]) => ({
    value: `${API}.${type}`,
    label,
    group,
    ...(tone ? { tone } : {}),
  }),
);

/** An event type by its simple name. */
const event = (type: string) => `${API}.${type}`;

/** The events that move a customer between owners and the public pool. */
const OWNERSHIP = [
  'CustomerClaimed',
  'CustomerReleased',
  'CustomerTransferred',
];

/** The events on a customer's contacts and the accounts they open. */
const CONTACTS = [
  'ContactAdded',
  'ContactUpdated',
  'ContactRemoved',
  'CustomerContactAccountApplied',
  'CustomerContactAccountBound',
  'CustomerContactAccountFailed',
];

// The row key leads, as the table pins it first whatever the order says.
// The tenant is in the picker rather than here: with it the columns run past
// the right edge of a 1440-wide screen.
const COLUMNS = [
  'id',
  'createTime',
  'body',
  'aggregateId',
  'version',
  'header.upstream_name',
  'header.command_operator',
];

/**
 * The streams that carry an event of one of `types`. An event stream holds
 * its events in an array, so a condition on an event is an element match —
 * the one way the schema lets a query reach inside `body`.
 */
function carrying(...types: string[]): FilterNode {
  return {
    field: 'body',
    operator: 'ELEMENT_MATCH',
    value: {
      op: 'and',
      children: [
        { field: 'body.bodyType', operator: 'IN', value: types.map(event) },
      ],
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
      fields: [
        'body',
        'createTime',
        'version',
        'header.upstream_name',
        'header.command_operator',
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

/** How many customers the events of a bucket belong to. */
const CUSTOMERS = {
  alias: 'customers',
  type: 'DISTINCT_COUNT',
  label: '涉及客户',
  expression: { type: 'FIELD', field: 'aggregateId' },
} as const;

/**
 * The last thirty days, newest first — a table, since a chart would read
 * them right to left, and cutting the oldest days off in time order needs a
 * date condition.
 */
function dailyView(filter: FilterNode[], count = '事件数'): AnalysisViewConfig {
  return analysisView({
    filter: { op: 'and', children: filter },
    groups: [
      {
        type: 'DATE_HISTOGRAM',
        field: 'createTime',
        alias: 'day',
        unit: 'DAY',
        label: '日期',
      },
    ],
    metrics: [{ alias: 'count', type: 'COUNT', label: count }, CUSTOMERS],
    sort: [{ alias: 'day', direction: 'DESC' }],
    limit: 30,
  });
}

/**
 * The event stream of `customer` as the CRM service's own query schema
 * describes it (`GET /customer/event/schema`, model `EVENT_STREAM`),
 * converted by hand: every field here is one the schema lists, with the
 * operators, sorting and aggregation its capabilities admit, and no more.
 * The schema titles none of the stream's own fields, so the labels, the
 * grouping and the choice of what an analyst reading a customer's history
 * needs are this definition's.
 *
 * One record is one event stream — what one command appended to one
 * customer, with its version and time — and its events sit in `body`, an
 * array the schema scopes (`ELEMENT_SCOPE`): a condition on an event is an
 * element match, and an analysis of events expands `body` and counts events
 * rather than streams.
 *
 * `header` is an open map the schema declares with presence alone. Two of its
 * members say what an analyst asks first of a change — which command made it
 * (`upstream_name`) and who sent it (`command_operator`) — so they are
 * columns; the service returns them when a page asks for them by path, and
 * refuses any condition on them but presence.
 *
 * Left out on purpose:
 * - `contextName` and `aggregateName`: this resource is one aggregate of one
 *   service, so both are the same on every stream;
 * - `ownerId` and `spaceId`: empty on every stream. `tenantId` stays — the
 *   service is multi-tenant and its streams span tenants;
 * - the rest of `header` (trace ids, the wait endpoint, the user agent and
 *   the caller's address): request plumbing, one record's detail away;
 * - each event's payload (`body[].body`): a union of 31 event shapes with
 *   presence alone — it can be neither filtered nor aggregated. The schema
 *   also titles every one of the 31 「客户企业认证已通过」, a defect of the
 *   service's schema, so no label is taken from it;
 * - a search field: the model declares no full-text capability.
 */
export const customerEventsDefinition: DataViewDefinition = {
  id: CUSTOMER_EVENTS,
  title: '事件流分析台',
  recordNoun: '客户事件',
  kind: 'data',
  source: CUSTOMER_AGGREGATE,
  fieldGroups: [
    {
      id: 'identity',
      label: '标识',
      fields: ['aggregateId', 'id', 'commandId', 'requestId', 'tenantId'],
    },
    {
      id: 'event',
      label: '事件',
      fields: ['body', 'version', 'createTime'],
    },
    {
      id: 'command',
      label: '命令',
      fields: ['header.upstream_name', 'header.command_operator'],
    },
  ],
  fields: [
    // The root identifiers carry every capability a string field can:
    // presence, exact and literal match, range, sort and cursor sort, terms.
    {
      name: 'aggregateId',
      label: '客户 ID',
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
    { name: 'tenantId', label: '租户', kind: 'string', sortable: true },
    { name: 'version', label: '版本', kind: 'number', sortable: true },
    {
      // TEMPORAL_EPOCH in milliseconds; exact match, range and sort.
      name: 'createTime',
      label: '事件时间',
      kind: 'datetime',
      sortable: true,
    },
    // Presence alone, as the schema declares the header's members.
    {
      name: 'header.upstream_name',
      label: '命令',
      kind: 'string',
      operators: ['IS_NULL', 'IS_NOT_NULL'],
    },
    {
      name: 'header.command_operator',
      label: '操作人',
      kind: 'string',
      operators: ['IS_NULL', 'IS_NOT_NULL'],
    },
    {
      // `ELEMENT_SCOPE` and presence.
      name: 'body',
      label: '事件',
      kind: 'elementMatch',
      // A stream is read by what happened in it: each event by its type, in
      // the type's own words. The page then asks for `body.bodyType` alone —
      // the payloads stay on the server.
      elementTitle: 'bodyType',
      elements: [
        {
          name: 'bodyType',
          label: '事件类型',
          kind: 'enum',
          options: EVENT_OPTIONS,
        },
        // The schema declares no values for the name, so it stays text.
        { name: 'name', label: '事件名', kind: 'string' },
        { name: 'revision', label: '事件修订', kind: 'string' },
        { name: 'id', label: '事件 ID', kind: 'string' },
      ],
    },
  ],
  record: {
    rowKey: 'id',
    paging: 'paged',
    layouts: ['table', 'card'],
    // The service refuses a page reaching past its 10,000th row.
    maxWindow: 10_000,
  },
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
      { field: 'tenantId', groups: [TERMS], functions: [] },
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
    // The service refuses an aggregation asking for more than 1,000 groups.
    limits: { maxLimit: 1000 },
  },
  views: [
    { id: 'recent', title: '最近的事件', config: recordView([]) },
    {
      // A template: fill in the customer and read its history in order.
      // Left blank it reads every history, one after another.
      id: 'history',
      title: '客户历史',
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
      id: 'ownership',
      title: '归属变更',
      config: recordView([carrying(...OWNERSHIP)]),
    },
    {
      id: 'contacts',
      title: '联系人变更',
      config: recordView([carrying(...CONTACTS)]),
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
        metrics: [
          { alias: 'count', type: 'COUNT', label: '事件数' },
          CUSTOMERS,
        ],
        sort: [{ alias: 'month', direction: 'ASC' }],
        limit: 120,
        layout: 'chart',
        chart: {
          type: 'line',
          cartesian: {
            x: 'month',
            series: [{ metric: 'count' }, { metric: 'customers' }],
          },
        },
      }),
    },
    { id: 'daily', title: '每日事件量', config: dailyView([]) },
    {
      id: 'daily-created',
      title: '每日新建客户',
      config: dailyView([carrying('CustomerCreated')], '新建客户'),
    },
    {
      id: 'most-changed',
      title: '变更最多的客户',
      config: analysisView({
        groups: [
          {
            type: 'TERMS',
            field: 'aggregateId',
            alias: 'customer',
            label: '客户 ID',
          },
        ],
        // How many changes, and when the last one was: the latest of the
        // streams' times, which reads as a date.
        metrics: [
          { alias: 'count', type: 'COUNT', label: '变更次数' },
          {
            alias: 'latest',
            type: 'NUMERIC',
            function: MAX,
            expression: { type: 'FIELD', field: 'createTime' },
            label: '最近一次变更',
          },
        ],
      }),
    },
  ],
};

/**
 * A fresh engine over the CRM service `fetcher` points at. The event stream
 * query client is the source as it is — `paged`, `cursor` and `aggregate` are
 * `ViewSource`'s three methods, as they are on the snapshot client. A page
 * holds at most a hundred streams, as the service allows.
 */
export function createCustomerEventsEngine(fetcher: Fetcher): ViewEngine {
  const source = new EventStreamQueryClient({
    basePath: CUSTOMER_AGGREGATE,
    fetcher,
  });
  return new ViewEngine({
    definitions: [customerEventsDefinition],
    store: new MemoryViewStore({ instances: [] }),
    resolveSource: () => source,
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 100 },
  });
}
