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
  type FilterNode,
  type RecordViewConfig,
} from '@ahoo-wang/wow-view-engine';

/**
 * The compensation service's event streams as View Engine's regression
 * fixtures read them.
 *
 * The product reads an execution's history through its own definition
 * (`compensation/dashboard/src/views/executionHistory.ts`), which ships with
 * the console; this one is not a copy of it and is not kept in step. It is
 * the engine's fixture for a stream whose events sit in an array — an
 * element match, an expansion that counts events rather than streams, a
 * column that reads the array by its elements' types — on real shapes. The
 * two drift on purpose: the product's views must not move an engine
 * regression.
 */
export const EXECUTION_FAILED_EVENTS = 'execution-failed-events';

/** Wow's `ExecutionFailed` aggregate, whose event stream this reads. */
const AGGREGATE = 'execution_failed';

// Wow's names for what the analysis side may group and compute by.
const { TERMS, HISTOGRAM, DATE_HISTOGRAM } = AggregationGroupType;
const { AVG, MIN, MAX } = AggregationFunction;
const { HOUR, DAY, WEEK, MONTH } = AggregationDateUnit;

/**
 * The event types of an `ExecutionFailed` stream — the schema's `enumValues`
 * for `body[].bodyType` — in the order an execution's history runs, each
 * named for what it says happened to the execution.
 */
const EVENT = {
  created: 'me.ahoo.wow.compensation.api.ExecutionFailedCreated',
  prepared: 'me.ahoo.wow.compensation.api.CompensationPrepared',
  failed: 'me.ahoo.wow.compensation.api.ExecutionFailedApplied',
  succeeded: 'me.ahoo.wow.compensation.api.ExecutionSuccessApplied',
  retrySpec: 'me.ahoo.wow.compensation.api.RetrySpecApplied',
  recoverable: 'me.ahoo.wow.compensation.api.RecoverableMarked',
  function: 'me.ahoo.wow.compensation.api.FunctionChanged',
} as const;

/** The events an operator raises by hand, rather than the retry loop. */
const MANUAL = [EVENT.retrySpec, EVENT.recoverable, EVENT.function];

// The row key leads, as the table pins it first whatever the order says.
const COLUMNS = [
  'id',
  'createTime',
  'body',
  'aggregateId',
  'version',
  'commandId',
  'requestId',
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

/** How many executions the events of a bucket belong to. */
const EXECUTIONS = {
  alias: 'executions',
  type: 'DISTINCT_COUNT',
  label: '涉及执行',
  expression: { type: 'FIELD', field: 'aggregateId' },
} as const;

/**
 * The last thirty days, newest first — a table, since a chart would read
 * them right to left, and cutting the oldest days off in time order needs a
 * date condition.
 */
function dailyView(filter: FilterNode[]): AnalysisViewConfig {
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
    metrics: [{ alias: 'count', type: 'COUNT', label: '事件数' }, EXECUTIONS],
    sort: [{ alias: 'day', direction: 'DESC' }],
    limit: 30,
  });
}

/**
 * The event stream of `execution_failed` as the service's own query schema
 * describes it (`GET /execution_failed/event/schema`, model `EVENT_STREAM`),
 * converted by hand: every field here is one the schema lists, with the
 * operators, sorting and aggregation its capabilities admit, and no more.
 * The schema carries no titles, so the labels, the grouping and the choice
 * of what an analyst reading an execution's history needs are this
 * definition's.
 *
 * One record is one event stream — what one command appended to one
 * execution, with its version and time — and its events sit in `body`, an
 * array the schema scopes (`ELEMENT_SCOPE`): a condition on an event is an
 * element match, and an analysis of events expands `body` and counts events
 * rather than streams.
 *
 * Left out on purpose:
 * - `contextName` and `aggregateName`: this resource is one aggregate of one
 *   service, so both are the same on every stream;
 * - `tenantId`, `ownerId` and `spaceId`: the compensation service is
 *   single-tenant and writes the same value on every stream;
 * - `header`: an open map with no capability at all;
 * - each event's payload (`body[].body`): a union of seven event shapes
 *   with no capability — it can be neither filtered nor aggregated;
 * - a search field: the model declares `FULL_TEXT_TERMS` but no field it
 *   lists declares a full-text capability, a whole-document search finds
 *   nothing (it answered 0 for words the events hold), and a phrase search
 *   is refused ("Model search is unsupported").
 *
 * An event's fields live inside an array. The `事件` column reads the array
 * as its events, each by its type (`elementTitle`), and fetches the types
 * alone; the event type is also a filter (an element match on `body`) and
 * an analysis dimension. A whole event — its payload, a failure's stack
 * trace — is one record's detail away.
 */
export const executionFailedEventsDefinition: DataViewDefinition = {
  id: EXECUTION_FAILED_EVENTS,
  title: '事件流分析台',
  recordNoun: '补偿事件',
  kind: 'data',
  source: AGGREGATE,
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
    // The root identifiers carry every capability a string field can:
    // presence, exact and literal match, range, sort and cursor sort, terms.
    {
      name: 'aggregateId',
      label: '执行 ID',
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
    {
      name: 'version',
      label: '版本',
      kind: 'number',
      sortable: true,
    },
    {
      // TEMPORAL_EPOCH in milliseconds; exact match, range and sort.
      name: 'createTime',
      label: '事件时间',
      kind: 'datetime',
      sortable: true,
    },
    {
      // `ELEMENT_SCOPE` and nothing else: no presence, so only the match.
      name: 'body',
      label: '事件',
      kind: 'elementMatch',
      operators: ['ELEMENT_MATCH'],
      // A stream is read by what happened in it: each event by its type,
      // in the type's own words. The page then asks for `body.bodyType`
      // alone — the payloads and stack traces stay on the server.
      elementTitle: 'bodyType',
      elements: [
        {
          name: 'bodyType',
          label: '事件类型',
          kind: 'enum',
          options: [
            { value: EVENT.created, label: '首次失败' },
            { value: EVENT.prepared, label: '准备重试' },
            { value: EVENT.failed, label: '重试失败' },
            { value: EVENT.succeeded, label: '重试成功' },
            { value: EVENT.retrySpec, label: '重试策略变更' },
            { value: EVENT.recoverable, label: '标记可恢复性' },
            { value: EVENT.function, label: '处理函数变更' },
          ],
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
      // A template: fill in the execution and read its history in order.
      // Left blank it reads every history, one after another.
      id: 'history',
      title: '执行历史',
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
      id: 'succeeded',
      title: '重试成功',
      config: recordView([carrying(EVENT.succeeded)]),
    },
    {
      id: 'manual',
      title: '人工干预',
      config: recordView([carrying(...MANUAL)]),
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
          EXECUTIONS,
        ],
        sort: [{ alias: 'month', direction: 'ASC' }],
        limit: 120,
        layout: 'chart',
        chart: {
          type: 'line',
          cartesian: {
            x: 'month',
            series: [{ metric: 'count' }, { metric: 'executions' }],
          },
        },
      }),
    },
    { id: 'daily', title: '每日事件量', config: dailyView([]) },
    {
      id: 'daily-succeeded',
      title: '每日重试成功',
      config: dailyView([carrying(EVENT.succeeded)]),
    },
    {
      id: 'most-retried',
      title: '重试最多的执行',
      config: analysisView({
        filter: { op: 'and', children: [carrying(EVENT.prepared)] },
        groups: [
          {
            type: 'TERMS',
            field: 'aggregateId',
            alias: 'execution',
            label: '执行 ID',
          },
        ],
        // How many retries, and when the last one was: the latest of the
        // retry events' times, which reads as a date.
        metrics: [
          { alias: 'count', type: 'COUNT', label: '重试次数' },
          {
            alias: 'latest',
            type: 'NUMERIC',
            function: MAX,
            expression: { type: 'FIELD', field: 'createTime' },
            label: '最近一次重试',
          },
        ],
      }),
    },
  ],
};

/**
 * A fresh engine over the service `fetcher` points at. The event stream query client
 * is the source as it is — `paged`, `cursor` and `aggregate` are
 * `ViewSource`'s three methods, as they are on the snapshot client. A page
 * holds at most a hundred streams: an event carries its payload, and the
 * failures carry whole stack traces.
 */
export function createCompensationEventsEngine(fetcher: Fetcher): ViewEngine {
  const source = new EventStreamQueryClient({ basePath: AGGREGATE, fetcher });
  return new ViewEngine({
    definitions: [executionFailedEventsDefinition],
    store: new MemoryViewStore({ instances: [] }),
    resolveSource: () => source,
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 100 },
  });
}
