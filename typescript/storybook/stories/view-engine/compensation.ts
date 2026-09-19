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

import { ExchangeError, Fetcher, HttpMethod } from '@ahoo-wang/fetcher';
import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  CommandClient,
  CommandHeaders,
  CommandStage,
  ErrorCodes,
  RecoverableType,
  SnapshotQueryClient,
  type CommandResult,
} from '@ahoo-wang/fetcher-wow';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type FilterNode,
  type RecordViewConfig,
} from '@ahoo-wang/fetcher-view-engine';

/**
 * The Wow compensation service the stories start on. Each story takes it as
 * its `host` arg, so the Controls panel can point it anywhere without a
 * restart; set `STORYBOOK_WOW_COMPENSATION_HOST` to change where it starts.
 */
export const DEFAULT_COMPENSATION_HOST: string =
  import.meta.env.STORYBOOK_WOW_COMPENSATION_HOST ?? 'http://localhost:8080';

export const EXECUTION_FAILED = 'execution-failed';

export const EXECUTION_FAILED_ANALYSIS = 'execution-failed-analysis';

/** Wow's `ExecutionFailed` aggregate, as the compensation service exposes it. */
const AGGREGATE = 'execution_failed';

const ACTIVE = ['FAILED', 'PREPARED'];

// Wow's names for what the analysis side may group and compute by.
const { TERMS, HISTOGRAM, DATE_HISTOGRAM } = AggregationGroupType;
const { SUM, AVG, MAX } = AggregationFunction;
const { DAY, WEEK, MONTH } = AggregationDateUnit;

const COLUMNS = [
  'state.id',
  'state.status',
  'state.recoverable',
  'state.function.processorName',
  'state.function.name',
  'state.error.errorCode',
  'state.retryState.retries',
  'state.retryState.nextRetryAt',
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
    sort: [{ field: 'eventTime', direction: 'DESC' }],
    pageSize: 20,
    layout: 'table',
    summaries: [],
    table: { columns: COLUMNS.map(field => ({ field })) },
    card: {
      title: 'state.function.name',
      fields: [
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
    metrics: [{ alias: 'count', type: 'COUNT' }],
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

/**
 * Failed executions, written by hand from the service's own schema
 * (`GET /execution_failed/snapshot/schema`). The system views follow the
 * categories of Wow's compensation dashboard that do not depend on the clock;
 * the ones that compare against "now" belong to a query, not to saved data.
 */
export const executionFailedDefinition: DataViewDefinition = {
  id: EXECUTION_FAILED,
  title: '执行失败',
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
      fields: [
        'state.function.contextName',
        'state.function.processorName',
        'state.function.name',
        'state.function.functionKind',
      ],
    },
    {
      id: 'event',
      label: '失败事件',
      fields: [
        'state.eventId.aggregateId.contextName',
        'state.eventId.aggregateId.aggregateName',
        'state.eventId.aggregateId.aggregateId',
      ],
    },
    {
      id: 'error',
      label: '错误',
      fields: ['state.error.errorCode', 'state.error.errorMsg'],
    },
    {
      id: 'retry',
      label: '重试',
      fields: [
        'state.retryState.retries',
        'state.retrySpec.maxRetries',
        'state.retryState.retryAt',
        'state.retryState.nextRetryAt',
        'state.retryState.timeoutAt',
      ],
    },
    { id: 'time', label: '时间', fields: ['firstEventTime', 'eventTime'] },
  ],
  fields: [
    { name: 'keyword', label: '全文搜索', kind: 'search' },
    { name: 'state.id', label: 'ID', kind: 'string' },
    {
      name: 'state.status',
      label: '状态',
      kind: 'enum',
      options: [
        { value: 'FAILED', label: '失败' },
        { value: 'PREPARED', label: '已准备重试' },
        { value: 'SUCCEEDED', label: '已成功' },
      ],
    },
    {
      name: 'state.recoverable',
      label: '可恢复性',
      kind: 'enum',
      options: [
        { value: 'RECOVERABLE', label: '可恢复' },
        { value: 'UNRECOVERABLE', label: '不可恢复' },
        { value: 'UNKNOWN', label: '未知' },
      ],
    },
    {
      name: 'state.isRetryable',
      label: '可重试',
      kind: 'boolean',
    },
    {
      name: 'state.isBelowRetryThreshold',
      label: '未达重试上限',
      kind: 'boolean',
    },
    {
      name: 'state.function.contextName',
      label: '处理上下文',
      kind: 'string',
    },
    {
      name: 'state.function.processorName',
      label: '处理器',
      kind: 'string',
    },
    {
      name: 'state.function.name',
      label: '处理函数',
      kind: 'string',
    },
    {
      name: 'state.function.functionKind',
      label: '函数类型',
      kind: 'enum',
      options: [
        { value: 'COMMAND', label: '命令' },
        { value: 'SOURCING', label: '溯源' },
        { value: 'EVENT', label: '事件' },
        { value: 'STATE_EVENT', label: '状态事件' },
        { value: 'ERROR', label: '错误' },
      ],
    },
    {
      name: 'state.eventId.aggregateId.contextName',
      label: '事件上下文',
      kind: 'string',
    },
    {
      name: 'state.eventId.aggregateId.aggregateName',
      label: '事件聚合',
      kind: 'string',
    },
    {
      name: 'state.eventId.aggregateId.aggregateId',
      label: '事件聚合 ID',
      kind: 'string',
    },
    {
      name: 'state.error.errorCode',
      label: '错误码',
      kind: 'string',
    },
    {
      name: 'state.error.errorMsg',
      label: '错误信息',
      kind: 'string',
    },
    {
      name: 'state.retryState.retries',
      label: '已重试次数',
      kind: 'number',
      sortable: true,
      summary: ['SUM', 'AVG', 'MAX'],
    },
    {
      name: 'state.retrySpec.maxRetries',
      label: '最大重试次数',
      kind: 'number',
    },
    {
      name: 'state.retryState.retryAt',
      label: '上次重试',
      kind: 'datetime',
      sortable: true,
    },
    {
      name: 'state.retryState.nextRetryAt',
      label: '下次重试',
      kind: 'datetime',
      sortable: true,
    },
    {
      name: 'state.retryState.timeoutAt',
      label: '重试超时',
      kind: 'datetime',
      sortable: true,
    },
    {
      name: 'firstEventTime',
      label: '首次失败',
      kind: 'datetime',
      sortable: true,
    },
    {
      name: 'eventTime',
      label: '最近更新',
      kind: 'datetime',
      sortable: true,
    },
  ],
  record: { rowKey: 'state.id', paging: 'paged', layouts: ['table', 'card'] },
  analysis: {
    count: true,
    fields: [
      { field: 'state.status', groups: [TERMS], functions: [] },
      { field: 'state.recoverable', groups: [TERMS], functions: [] },
      {
        field: 'state.function.functionKind',
        groups: [TERMS],
        functions: [],
      },
      { field: 'state.function.contextName', groups: [TERMS], functions: [] },
      {
        field: 'state.function.processorName',
        groups: [TERMS],
        functions: [],
      },
      { field: 'state.function.name', groups: [TERMS], functions: [] },
      {
        field: 'state.eventId.aggregateId.aggregateName',
        groups: [TERMS],
        functions: [],
      },
      { field: 'state.error.errorCode', groups: [TERMS], functions: [] },
      {
        field: 'state.retryState.retries',
        groups: [HISTOGRAM],
        functions: [SUM, AVG, MAX],
      },
      {
        field: 'firstEventTime',
        groups: [DATE_HISTOGRAM],
        functions: [],
        dateUnits: [DAY, WEEK, MONTH],
      },
      {
        field: 'eventTime',
        groups: [DATE_HISTOGRAM],
        functions: [],
        dateUnits: [DAY, WEEK, MONTH],
      },
    ],
  },
  views: [
    {
      id: 'active',
      title: '活动中',
      config: recordView(
        [{ field: 'state.status', operator: 'IN', value: ACTIVE }],
        { summaries: [{ field: 'state.retryState.retries', fn: 'SUM' }] },
      ),
    },
    {
      id: 'non-retryable',
      title: '不可重试',
      config: recordView([
        {
          field: 'state.recoverable',
          operator: 'IN',
          value: ['RECOVERABLE', 'UNKNOWN'],
        },
        { field: 'state.status', operator: 'IN', value: ACTIVE },
        { field: 'state.isBelowRetryThreshold', operator: 'EQ', value: false },
      ]),
    },
    {
      id: 'unrecoverable',
      title: '不可恢复',
      config: recordView([
        {
          field: 'state.recoverable',
          operator: 'IN',
          value: ['UNRECOVERABLE'],
        },
        { field: 'state.status', operator: 'IN', value: ACTIVE },
      ]),
    },
    {
      id: 'succeeded',
      title: '已成功',
      config: recordView([
        { field: 'state.status', operator: 'IN', value: ['SUCCEEDED'] },
      ]),
    },
    { id: 'all', title: '全部', config: recordView([]) },
  ],
};

/**
 * The same failed executions, for analysis. Its views live in a definition of
 * their own because a workbench lists every view of the definition it opens,
 * and a list entry carries no kind: with both kinds in one definition, the
 * record workbench offers views it can only open as an empty table, and the
 * analysis workbench the reverse.
 */
export const executionFailedAnalysisDefinition: DataViewDefinition = {
  ...executionFailedDefinition,
  id: EXECUTION_FAILED_ANALYSIS,
  title: '执行失败分析',
  views: [
    {
      id: 'by-status',
      title: '按状态分布',
      config: analysisView({
        groups: [{ type: 'TERMS', field: 'state.status', alias: 'status' }],
        layout: 'chart',
      }),
    },
    {
      id: 'by-processor',
      title: '活动失败 · 按处理器',
      config: analysisView({
        filter: {
          op: 'and',
          children: [{ field: 'state.status', operator: 'IN', value: ACTIVE }],
        },
        groups: [
          {
            type: 'TERMS',
            field: 'state.function.processorName',
            alias: 'processor',
          },
        ],
        metrics: [
          { alias: 'count', type: 'COUNT' },
          {
            alias: 'retries',
            type: 'NUMERIC',
            function: 'AVG',
            expression: { type: 'FIELD', field: 'state.retryState.retries' },
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
            type: 'DATE_HISTOGRAM',
            field: 'firstEventTime',
            alias: 'day',
            unit: 'DAY',
          },
        ],
        sort: [{ alias: 'day', direction: 'DESC' }],
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
  const source = new SnapshotQueryClient({ basePath: AGGREGATE, fetcher });
  return new ViewEngine({
    definitions: [executionFailedDefinition, executionFailedAnalysisDefinition],
    store: new MemoryViewStore({ instances: [] }),
    resolveSource: () => source,
  });
}

export function compensationFetcher(host: string): Fetcher {
  return new Fetcher({ baseURL: host });
}

/** What one command did to one record. */
export interface CommandOutcome {
  id: string;
  error: string | null;
}

/**
 * Why Wow refused a command. It answers a refusal with a 400 whose body is the
 * command result, so the reason is in that body rather than in the HTTP error
 * fetcher throws; Wow's own compensation dashboard reads it the same way.
 */
async function refusal(error: unknown): Promise<string> {
  if (error instanceof ExchangeError) {
    try {
      const result = await error.exchange.extractResult<CommandResult>();
      if (result.errorMsg) return `${result.errorCode}: ${result.errorMsg}`;
    } catch {
      // No command result to read: the HTTP error is all there is.
    }
  }
  return error instanceof Error ? error.message : String(error);
}

/**
 * The compensation commands a failed execution takes. Each waits until the
 * snapshot reflects it, so a refresh right after shows the new state.
 */
export function compensationCommands(fetcher: Fetcher) {
  const client = new CommandClient({ basePath: AGGREGATE, fetcher });
  const send = async (
    id: string,
    command: string,
    body: object = {},
  ): Promise<CommandOutcome> => {
    try {
      const result = await client.send({
        path: `{id}/${command}`,
        method: HttpMethod.PUT,
        urlParams: { path: { id } },
        headers: { [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT },
        body,
      });
      return {
        id,
        error:
          result.errorCode === ErrorCodes.SUCCEEDED
            ? null
            : `${result.errorCode}: ${result.errorMsg}`,
      };
    } catch (error) {
      return { id, error: await refusal(error) };
    }
  };
  return {
    /** Wow's `prepare_compensation`: retry now, within the retry spec. */
    retry: (id: string) => send(id, 'prepare_compensation'),
    /** Wow's `force_prepare_compensation`: retry even past the limit. */
    forceRetry: (id: string) => send(id, 'force_prepare_compensation'),
    markRecoverable: (id: string, recoverable: RecoverableType) =>
      send(id, 'mark_recoverable', { recoverable }),
  };
}

export type CompensationCommands = ReturnType<typeof compensationCommands>;
