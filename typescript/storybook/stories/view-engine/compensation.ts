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
  DEFAULT_RUNTIME_LIMITS,
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

/** Wow's `ExecutionFailed` aggregate, as the compensation service exposes it. */
const AGGREGATE = 'execution_failed';

const ACTIVE = ['FAILED', 'PREPARED'];

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
 *
 * Record and analysis views live here together, which is what one definition
 * is for: a summary carries the `kind` of the config it names, and a
 * workbench lists only the kind it can open (`useViewList({ kind })`). This
 * used to be two definitions, the second a copy of the first with a
 * different `views` — a workaround from before the list could tell them
 * apart, and one that gave the same failed executions two names.
 */
/**
 * `execution_failed` as the service's own query schema describes it
 * (`GET /execution_failed/snapshot/schema`), converted by hand: every field
 * here is one the schema lists, with the operators, sorting and aggregation
 * its capabilities admit, and no more. The schema carries no titles, so the
 * labels, the grouping and the choice of what an operator needs are this
 * definition's. Left out on purpose: the four derived duplicates the schema
 * lists with no capability at all (`state.belowRetryThreshold`,
 * `state.retryable`, `state.function.empty`, `state.eventId.initialVersion`),
 * the snapshot's bookkeeping (`version`, `snapshotTime`, `deleted`,
 * operators, tenant/owner/space) and the binding errors, whose members are
 * searchable text only.
 */
export const executionFailedDefinition: DataViewDefinition = {
  id: EXECUTION_FAILED,
  title: '快照控制台',
  kind: 'data',
  source: AGGREGATE,
  // The pickers list the fields under these, in this order.
  fieldGroups: [
    { id: 'search', label: '搜索', fields: ['keyword'] },
    { id: 'identity', label: '标识', fields: ['state.id', 'state.eventId.id'] },
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
        'state.eventId.version',
      ],
    },
    {
      id: 'error',
      label: '错误',
      fields: [
        'state.error.errorCode',
        'state.error.errorMsg',
        'state.error.stackTrace',
      ],
    },
    {
      id: 'retry',
      label: '重试',
      fields: [
        'state.retryState.retries',
        'state.retrySpec.maxRetries',
        'state.retrySpec.minBackoff',
        'state.retrySpec.executionTimeout',
        'state.retryState.retryAt',
        'state.retryState.nextRetryAt',
        'state.retryState.timeoutAt',
      ],
    },
    {
      id: 'time',
      label: '时间',
      fields: ['firstEventTime', 'eventTime', 'state.executeAt'],
    },
  ],
  fields: [
    // The model's full-text capability, narrowed to the text an operator
    // searches: what went wrong and where. Both fields are full-text only in
    // the schema — no exact match, no substring, no sort — so this is the one
    // way to filter by them.
    {
      name: 'keyword',
      label: '搜索错误',
      kind: 'search',
      searchFields: ['state.error.errorMsg', 'state.error.stackTrace'],
      // An operator pastes a piece of an error — 「Connection prematurely
      // closed」 — and means those words together; the service's term mode
      // matches any one of them (twice as many rows, half of them wrong).
      searchMode: 'PHRASE',
    },
    {
      name: 'state.id',
      label: 'ID',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    {
      name: 'state.eventId.id',
      label: '事件 ID',
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
        { value: 'UNKNOWN', label: '未知' },
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
      name: 'state.function.contextName',
      label: '处理上下文',
      kind: 'string',
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
      name: 'state.function.functionKind',
      label: '函数类型',
      kind: 'enum',
      sortable: true,
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
      sortable: true,
    },
    {
      name: 'state.eventId.aggregateId.aggregateName',
      label: '事件聚合',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.eventId.aggregateId.aggregateId',
      label: '事件聚合 ID',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    {
      name: 'state.eventId.version',
      label: '事件版本',
      kind: 'number',
      sortable: true,
    },
    {
      name: 'state.error.errorCode',
      label: '错误码',
      kind: 'string',
      sortable: true,
    },
    // Full-text only in the schema: shown and searched (`keyword`), and
    // filtered by presence alone.
    {
      name: 'state.error.errorMsg',
      label: '错误信息',
      kind: 'string',
      cell: 'text',
      operators: ['IS_NULL', 'IS_NOT_NULL'],
    },
    {
      name: 'state.error.stackTrace',
      label: '堆栈',
      kind: 'string',
      cell: 'text',
      operators: ['IS_NULL', 'IS_NOT_NULL'],
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
      sortable: true,
    },
    {
      name: 'state.retrySpec.minBackoff',
      label: '最小退避（秒）',
      kind: 'number',
      sortable: true,
    },
    {
      name: 'state.retrySpec.executionTimeout',
      label: '执行超时（秒）',
      kind: 'number',
      sortable: true,
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
    { name: 'eventTime', label: '最近更新', kind: 'datetime', sortable: true },
    {
      name: 'state.executeAt',
      label: '执行时间',
      kind: 'datetime',
      sortable: true,
    },
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
    // The service refuses a page reaching past its 10,000th row.
    maxWindow: 10_000,
  },
  // What the schema lets the service aggregate: AGGREGATE_TERMS groups by
  // value, AGGREGATE_NUMERIC bands and sums, AGGREGATE_TEMPORAL buckets by
  // date — and a date is a number too, so its earliest and latest are metrics.
  analysis: {
    count: true,
    having: true,
    expressions: true,
    // The service refuses an aggregation asking for more than 1,000 groups.
    limits: { maxLimit: 1000 },
    fields: [
      ...[
        'state.status',
        'state.recoverable',
        'state.isRetryable',
        'state.isBelowRetryThreshold',
        'state.function.functionKind',
        'state.function.contextName',
        'state.function.processorName',
        'state.function.name',
        'state.eventId.aggregateId.contextName',
        'state.eventId.aggregateId.aggregateName',
        'state.error.errorCode',
      ].map(field => ({ field, groups: [TERMS], functions: [] })),
      {
        field: 'state.eventId.aggregateId.aggregateId',
        groups: [],
        functions: [],
        distinctCount: true,
      },
      {
        field: 'state.retryState.retries',
        groups: [TERMS, HISTOGRAM],
        functions: [SUM, AVG, MIN, MAX],
        percentile: true,
      },
      {
        field: 'state.retrySpec.maxRetries',
        groups: [TERMS],
        functions: [AVG, MIN, MAX],
      },
      ...[
        'firstEventTime',
        'eventTime',
        'state.executeAt',
        'state.retryState.nextRetryAt',
      ].map(field => ({
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
    definitions: [executionFailedDefinition],
    // The service pages at most 100 rows at a time; an export pages at the
    // runtime's largest size, so that is the largest this source takes.
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 100 },
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
