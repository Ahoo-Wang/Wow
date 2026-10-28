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
import { CommandGateway, wait } from '@ahoo-wang/wow-client';
import { queryRoute, routeSource } from './recordedWowService.js';
import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  SortDirection,
  AggregationMetricType,
  SearchMode,
  FilterOperator,
  QueryValueKind,
  AggregationExpressionType,
} from '@ahoo-wang/wow-client/dsl';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type FilterNode,
  type RecordViewConfig,
  type ViewSource,
} from '@ahoo-wang/wow-view-engine';

/**
 * The compensation domain as View Engine's regression fixtures read it.
 *
 * It is the engine's fixture on a domain whose shapes are real — nested
 * snapshot paths, row commands that read fields no column shows, a phrase
 * search over error text — cut down to what the regression stories over it
 * assert (`CompensationWorkbench`, `CompensationOverview`). It was never a
 * copy of the 9.x compensation console's definitions, which V10 removed.
 */

export const EXECUTION_FAILED = 'execution-failed';

/** Wow's `ExecutionFailed` aggregate, as the compensation service exposes it. */
const CONTEXT = 'compensation';
const AGGREGATE = 'execution_failed';

/** The statuses of an execution still waiting on someone. */
export const ACTIVE = ['FAILED', 'PREPARED'];

// Wow's names for what the analysis side may group and compute by.
const { TERMS, HISTOGRAM, DATE_HISTOGRAM } = AggregationGroupType;
const { SUM, AVG, MIN, MAX } = AggregationFunction;
const { HOUR, DAY, WEEK, MONTH } = AggregationDateUnit;

const COLUMNS = [
  'state.id',
  'state.status',
  'state.recoverable',
  'state.function.processorName',
  'state.function.name',
  'state.error.errorCode',
  'state.retryState.retries',
  'eventTime',
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
    sort: [{ field: 'eventTime', direction: SortDirection.DESC }],
    pageSize: 20,
    layout: 'table',
    summaries: [],
    table: { columns: COLUMNS.map(field => ({ field })) },
    // A card is scanned for which business flow failed: the processor
    // titles it, and the function inside it leads the body.
    card: {
      title: 'state.function.processorName',
      fields: [
        'state.function.name',
        'state.status',
        'state.error.errorCode',
        'state.error.errorMsg',
        'state.retryState.retries',
        'eventTime',
      ],
    },
    ...overrides,
  };
}

/**
 * A count by `groups`. Its chart plots the first group whichever layout the
 * view opens in: switching to the chart must not need a repair first, so the
 * engine holds a table view's chart to its groups as well.
 */
function analysisView(
  overrides: Partial<AnalysisViewConfig> & Pick<AnalysisViewConfig, 'groups'>,
): AnalysisViewConfig {
  return {
    kind: 'analysis',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    metrics: [{ alias: 'count', type: AggregationMetricType.COUNT }],
    sort: [{ alias: 'count', direction: SortDirection.DESC }],
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

/**
 * Failed executions, in the shape the compensation service's query schema
 * describes (`GET /execution_failed/snapshot/schema`), cut down to the
 * fields the fixtures read: the four a row's commands decide by
 * (`rowFields`), what the default columns and cards show, the processor a
 * condition picks from the data, and the error text the title bar searches
 * as a phrase. Record and analysis views live together in the one
 * definition, which is what a definition is for: a workbench lists the
 * kinds it can open.
 */
export const executionFailedDefinition: DataViewDefinition = {
  id: EXECUTION_FAILED,
  title: '快照控制台',
  recordNoun: '失败执行',
  kind: 'data',
  source: AGGREGATE,
  // The pickers list the fields under these, in this order.
  fieldGroups: [
    { id: 'search', label: '搜索', fields: ['keyword'] },
    { id: 'identity', label: '标识', fields: ['state.id'] },
    {
      id: 'status',
      label: '状态',
      fields: [
        'state.status',
        'state.recoverable',
        'state.isRetryable',
        'state.isBelowRetryThreshold',
      ],
    },
    {
      id: 'function',
      label: '处理函数',
      fields: ['state.function.processorName', 'state.function.name'],
    },
    {
      id: 'error',
      label: '错误',
      fields: ['state.error.errorCode', 'state.error.errorMsg'],
    },
    { id: 'retry', label: '重试', fields: ['state.retryState.retries'] },
    { id: 'time', label: '时间', fields: ['firstEventTime', 'eventTime'] },
  ],
  fields: [
    // A search over the error text, matched as a phrase: an operator pastes
    // a piece of an error and means those words together.
    {
      name: 'keyword',
      label: '搜索错误',
      kind: 'search',
      searchFields: ['state.error.errorMsg'],
      searchMode: SearchMode.PHRASE,
    },
    {
      name: 'state.id',
      label: FilterOperator.ID,
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    {
      name: 'state.status',
      label: '状态',
      kind: 'enum',
      sortable: true,
      cell: 'status',
      options: [
        { value: 'FAILED', label: '失败', tone: 'danger' },
        { value: 'PREPARED', label: '已准备重试', tone: 'warning' },
        { value: 'SUCCEEDED', label: '已成功', tone: 'success' },
      ],
    },
    {
      name: 'state.recoverable',
      label: '可恢复性',
      kind: 'enum',
      sortable: true,
      options: [
        { value: 'RECOVERABLE', label: '可恢复' },
        { value: 'UNRECOVERABLE', label: '不可恢复' },
        { value: QueryValueKind.UNKNOWN, label: '未知' },
      ],
    },
    {
      name: 'state.isRetryable',
      label: '可重试',
      kind: 'boolean',
      sortable: true,
    },
    {
      name: 'state.isBelowRetryThreshold',
      label: '未达重试上限',
      kind: 'boolean',
      sortable: true,
    },
    {
      name: 'state.function.processorName',
      label: '处理器',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.function.name',
      label: '处理函数',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.error.errorCode',
      label: '错误码',
      kind: 'string',
      sortable: true,
    },
    // Full-text only in the service's schema: shown and searched
    // (`keyword`), and filtered by presence alone.
    {
      name: 'state.error.errorMsg',
      label: '错误信息',
      kind: 'string',
      cell: 'text',
      operators: [FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL],
    },
    {
      name: 'state.retryState.retries',
      label: '已重试次数',
      kind: 'number',
      sortable: true,
      summary: [
        AggregationFunction.SUM,
        AggregationFunction.AVG,
        AggregationFunction.MAX,
      ],
    },
    {
      name: 'firstEventTime',
      label: '首次失败',
      kind: 'datetime',
      sortable: true,
    },
    { name: 'eventTime', label: '最近更新', kind: 'datetime', sortable: true },
  ],
  record: {
    rowKey: 'state.id',
    paging: 'paged',
    layouts: ['table', 'card'],
    // What the row commands read to decide what an execution takes — they
    // are not columns, and a page asks only for the fields it shows.
    rowFields: [
      'state.status',
      'state.isRetryable',
      'state.isBelowRetryThreshold',
      'state.recoverable',
    ],
  },
  // What the views and the condition's value candidates group and compute
  // by: TERMS by value, the retries banded and summed, the times by date.
  analysis: {
    count: true,
    fields: [
      ...[
        'state.status',
        'state.recoverable',
        'state.isRetryable',
        'state.isBelowRetryThreshold',
        'state.function.processorName',
        'state.function.name',
        'state.error.errorCode',
      ].map(field => ({ field, groups: [TERMS], functions: [] })),
      {
        field: 'state.retryState.retries',
        groups: [TERMS, HISTOGRAM],
        functions: [SUM, AVG, MIN, MAX],
      },
      ...['firstEventTime', 'eventTime'].map(field => ({
        field,
        groups: [DATE_HISTOGRAM],
        functions: [MIN, MAX],
        dateUnits: [HOUR, DAY, WEEK, MONTH],
      })),
    ],
  },
  views: [
    {
      id: 'active',
      title: '活动中',
      config: recordView(
        [{ field: 'state.status', operator: FilterOperator.IN, value: ACTIVE }],
        {
          summaries: [
            { field: 'state.retryState.retries', fn: AggregationFunction.SUM },
          ],
        },
      ),
    },
    {
      id: 'non-retryable',
      title: '不可重试',
      config: recordView([
        {
          field: 'state.recoverable',
          operator: FilterOperator.IN,
          value: ['RECOVERABLE', QueryValueKind.UNKNOWN],
        },
        { field: 'state.status', operator: FilterOperator.IN, value: ACTIVE },
        {
          field: 'state.isBelowRetryThreshold',
          operator: FilterOperator.EQ,
          value: false,
        },
      ]),
    },
    {
      id: 'unrecoverable',
      title: '不可恢复',
      config: recordView([
        {
          field: 'state.recoverable',
          operator: FilterOperator.IN,
          value: ['UNRECOVERABLE'],
        },
        { field: 'state.status', operator: FilterOperator.IN, value: ACTIVE },
      ]),
    },
    {
      id: 'succeeded',
      title: '已成功',
      config: recordView([
        {
          field: 'state.status',
          operator: FilterOperator.IN,
          value: ['SUCCEEDED'],
        },
      ]),
    },
    { id: 'all', title: '全部', config: recordView([]) },
    {
      id: 'by-status',
      title: '按状态分布',
      config: analysisView({
        groups: [
          {
            type: AggregationGroupType.TERMS,
            field: 'state.status',
            alias: 'status',
          },
        ],
        layout: 'chart',
      }),
    },
    {
      id: 'by-processor',
      title: '活动失败 · 按处理器',
      config: analysisView({
        filter: {
          op: 'and',
          children: [
            {
              field: 'state.status',
              operator: FilterOperator.IN,
              value: ACTIVE,
            },
          ],
        },
        groups: [
          {
            type: AggregationGroupType.TERMS,
            field: 'state.function.processorName',
            alias: 'processor',
          },
        ],
        metrics: [
          { alias: 'count', type: AggregationMetricType.COUNT },
          {
            alias: 'retries',
            type: AggregationMetricType.NUMERIC,
            function: AggregationFunction.AVG,
            expression: {
              type: AggregationExpressionType.FIELD,
              field: 'state.retryState.retries',
            },
          },
        ],
      }),
    },
    {
      id: 'daily',
      title: '每日新增失败',
      config: analysisView({
        groups: [
          {
            type: AggregationGroupType.DATE_HISTOGRAM,
            field: 'firstEventTime',
            alias: 'day',
            unit: AggregationDateUnit.DAY,
          },
        ],
        sort: [{ alias: 'day', direction: SortDirection.DESC }],
        limit: 30,
      }),
    },
  ],
};

/**
 * A fresh engine over the service at `host`. The snapshot query client is the
 * source as it is: `ViewSource` is three of its methods. Saved views live in
 * memory, so they last as long as the story does.
 */
export function createCompensationEngine(fetcher: Fetcher): ViewEngine {
  const source = compensationSource(fetcher);
  return new ViewEngine({
    resources: [{ definition: executionFailedDefinition, source }],
    // The service pages at most 100 rows at a time; an export pages at the
    // runtime's largest size, so that is the largest this source takes.
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 100 },
    store: new MemoryViewStore({ instances: [] }),
  });
}

/**
 * The failed executions of the service `fetcher` points at, as a view source:
 * the snapshot query client as it is, since `ViewSource` is three of its
 * methods. Every scene over this service reads through it.
 */
export function compensationSource(fetcher: Fetcher): ViewSource {
  return routeSource(fetcher, queryRoute(CONTEXT, AGGREGATE, 'snapshot'));
}

export function compensationFetcher(host: string): Fetcher {
  return new Fetcher({ baseURL: host });
}

/** Whether a failed execution can be recovered, as the compensation service marks it. */
export const RecoverableType = {
  RECOVERABLE: 'RECOVERABLE',
  UNRECOVERABLE: 'UNRECOVERABLE',
  [QueryValueKind.UNKNOWN]: QueryValueKind.UNKNOWN,
} as const;

/** A {@link RecoverableType} value. */
export type RecoverableType =
  (typeof RecoverableType)[keyof typeof RecoverableType];

/**
 * The compensation commands a failed execution takes. Each waits until the
 * snapshot reflects it, so a refresh right after shows the new state, and
 * each rejects when the service refused it: a refusal is a problem, which the
 * engine reads for the service's own words (`sourceReason`).
 */
export function compensationCommands(fetcher: Fetcher) {
  const gateway = new CommandGateway({ fetcher });
  // Each command by its route, as the service's Manifest lists it.
  const send = async (
    id: string,
    command: string,
    segment: string,
    body: object = {},
  ): Promise<void> => {
    await gateway.wait({
      command: `${CONTEXT}.${AGGREGATE}.${command}`,
      routes: [
        { method: 'POST', path: `/${CONTEXT}/${AGGREGATE}/{id}/${segment}` },
      ],
      body,
      id,
      wait: [wait.snapshot(CONTEXT)],
    });
  };
  return {
    /** Wow's `PrepareCompensation`: retry now, within the retry spec. */
    retry: (id: string) =>
      send(id, 'PrepareCompensation', 'prepare-compensation'),
    /** Wow's `ForcePrepareCompensation`: retry even past the limit. */
    forceRetry: (id: string) =>
      send(id, 'ForcePrepareCompensation', 'force-prepare-compensation'),
    markRecoverable: (id: string, recoverable: RecoverableType) =>
      send(id, 'MarkRecoverable', 'mark-recoverable', { recoverable }),
  };
}

export type CompensationCommands = ReturnType<typeof compensationCommands>;
