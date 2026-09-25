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

import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
} from "@ahoo-wang/wow-client";
import type {
  AnalysisViewConfig,
  DataViewDefinition,
  FilterNode,
  RecordViewConfig,
} from "@ahoo-wang/wow-view-engine";
import type { Locale } from "@/i18n.tsx";

/** The definition's id: what saved views and routes name it by. */
export const EXECUTION_FAILED = "execution-failed";

/** The snapshot query resource the definition reads (`ViewSource` key). */
export const EXECUTION_FAILED_SOURCE = "execution_failed";

/** The statuses of an execution still waiting on someone. */
const ACTIVE = ["FAILED", "PREPARED"];
/** What a retry may still recover (`RetryConditions`). */
const RETRYABLE_RECOVERABILITY = ["RECOVERABLE", "UNKNOWN"];

const { TERMS, HISTOGRAM, DATE_HISTOGRAM } = AggregationGroupType;
const { SUM, AVG, MIN, MAX } = AggregationFunction;
const { HOUR, DAY, WEEK, MONTH } = AggregationDateUnit;

/**
 * Every word the definition shows, per language. A definition carries one
 * language (`label: string`), so the console builds one per language and
 * rebuilds the engine when the language changes (rebuild proposal, G12).
 */
const TEXT = {
  en: {
    title: "Failed executions",
    recordNoun: "failed execution",
    groupSearch: "Search",
    groupIdentity: "Identity",
    groupStatus: "Status",
    groupFunction: "Function",
    groupEvent: "Failed event",
    groupError: "Error",
    groupRetry: "Retry",
    groupTime: "Time",
    keyword: "Search errors",
    id: "ID",
    eventId: "Event ID",
    status: "Status",
    statusFailed: "Failed",
    statusPrepared: "Prepared",
    statusSucceeded: "Succeeded",
    recoverable: "Recoverability",
    recoverableYes: "Recoverable",
    recoverableNo: "Unrecoverable",
    recoverableUnknown: "Unknown",
    isRetryable: "Retryable",
    isBelowRetryThreshold: "Below retry limit",
    functionContext: "Processor context",
    processor: "Processor",
    functionName: "Function",
    functionKind: "Function kind",
    kindCommand: "Command",
    kindSourcing: "Sourcing",
    kindEvent: "Event",
    kindStateEvent: "State event",
    kindError: "Error",
    eventContext: "Event context",
    eventAggregate: "Event aggregate",
    eventAggregateId: "Event aggregate ID",
    eventVersion: "Event version",
    errorCode: "Error code",
    errorMsg: "Error message",
    stackTrace: "Stack trace",
    retries: "Retries",
    maxRetries: "Max retries",
    minBackoff: "Min backoff (s)",
    executionTimeout: "Execution timeout (s)",
    retryAt: "Last retry",
    nextRetryAt: "Next retry",
    timeoutAt: "Retry timeout",
    firstEventTime: "First failed",
    eventTime: "Last updated",
    executeAt: "Executed at",
    viewActive: "Active",
    viewNonRetryable: "Non-retryable",
    viewUnrecoverable: "Unrecoverable",
    viewSucceeded: "Succeeded",
    viewAll: "All",
    analysisByStatus: "By status",
    analysisByProcessor: "Active failures by processor",
    analysisDaily: "New failures per day",
  },
  "zh-CN": {
    title: "失败执行",
    recordNoun: "失败执行",
    groupSearch: "搜索",
    groupIdentity: "标识",
    groupStatus: "状态",
    groupFunction: "处理函数",
    groupEvent: "失败事件",
    groupError: "错误",
    groupRetry: "重试",
    groupTime: "时间",
    keyword: "搜索错误",
    id: "ID",
    eventId: "事件 ID",
    status: "状态",
    statusFailed: "失败",
    statusPrepared: "已准备",
    statusSucceeded: "已成功",
    recoverable: "可恢复性",
    recoverableYes: "可恢复",
    recoverableNo: "不可恢复",
    recoverableUnknown: "未知",
    isRetryable: "可重试",
    isBelowRetryThreshold: "未达重试上限",
    functionContext: "处理上下文",
    processor: "处理器",
    functionName: "处理函数",
    functionKind: "函数类型",
    kindCommand: "命令",
    kindSourcing: "溯源",
    kindEvent: "事件",
    kindStateEvent: "状态事件",
    kindError: "错误",
    eventContext: "事件上下文",
    eventAggregate: "事件聚合",
    eventAggregateId: "事件聚合 ID",
    eventVersion: "事件版本",
    errorCode: "错误码",
    errorMsg: "错误信息",
    stackTrace: "堆栈",
    retries: "已重试次数",
    maxRetries: "最大重试次数",
    minBackoff: "最小退避（秒）",
    executionTimeout: "执行超时（秒）",
    retryAt: "上次重试",
    nextRetryAt: "下次重试",
    timeoutAt: "重试超时",
    firstEventTime: "首次失败",
    eventTime: "最近更新",
    executeAt: "执行时间",
    viewActive: "活动中",
    viewNonRetryable: "不可重试",
    viewUnrecoverable: "不可恢复",
    viewSucceeded: "已成功",
    viewAll: "全部",
    analysisByStatus: "按状态分布",
    analysisByProcessor: "活动失败 · 按处理器",
    analysisDaily: "每日新增失败",
  },
} satisfies Record<Locale, Record<string, string>>;

const COLUMNS = [
  "state.id",
  "state.status",
  "state.recoverable",
  "state.function.processorName",
  "state.function.name",
  "state.error.errorCode",
  "state.retryState.retries",
  "state.retryState.nextRetryAt",
  "eventTime",
];

function recordView(
  filter: FilterNode[],
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    kind: "record",
    filter: { op: "and", children: filter },
    filterMode: "simple",
    refresh: { interval: null },
    sort: [{ field: "eventTime", direction: "DESC" }],
    pageSize: 20,
    layout: "table",
    summaries: [],
    table: { columns: COLUMNS.map((field) => ({ field })) },
    // A card is scanned for which business flow failed: the processor titles
    // it, and the function inside it leads the body.
    card: {
      title: "state.function.processorName",
      fields: [
        "state.function.name",
        "state.status",
        "state.error.errorCode",
        "state.error.errorMsg",
        "state.retryState.retries",
        "eventTime",
      ],
    },
    ...overrides,
  };
}

/** A count by `groups`, whose chart plots the first group. */
function analysisView(
  overrides: Partial<AnalysisViewConfig> & Pick<AnalysisViewConfig, "groups">,
): AnalysisViewConfig {
  return {
    kind: "analysis",
    filter: { op: "and", children: [] },
    filterMode: "simple",
    refresh: { interval: null },
    metrics: [{ alias: "count", type: "COUNT" }],
    sort: [{ alias: "count", direction: "DESC" }],
    limit: 20,
    layout: "table",
    table: { columns: [] },
    chart: {
      type: "bar",
      cartesian: {
        x: overrides.groups[0].alias,
        series: [{ metric: "count" }],
      },
    },
    ...overrides,
  };
}

/**
 * The system views of batch 1: the queues that do not compare against the
 * current moment, each the same condition as the old console's
 * `RetryConditions` (checked in `executionFailed.test.ts`). The three that do
 * — to retry, executing, due for retry — come in batch 2 with
 * `BEFORE_NOW`/`AFTER_NOW`.
 */
function systemViews(t: (typeof TEXT)[Locale]): DataViewDefinition["views"] {
  return [
    {
      id: "active",
      title: t.viewActive,
      config: recordView(
        [{ field: "state.status", operator: "IN", value: ACTIVE }],
        { summaries: [{ field: "state.retryState.retries", fn: "SUM" }] },
      ),
    },
    {
      id: "non-retryable",
      title: t.viewNonRetryable,
      config: recordView([
        {
          field: "state.recoverable",
          operator: "IN",
          value: RETRYABLE_RECOVERABILITY,
        },
        { field: "state.status", operator: "IN", value: ACTIVE },
        { field: "state.isBelowRetryThreshold", operator: "EQ", value: false },
      ]),
    },
    {
      id: "unrecoverable",
      title: t.viewUnrecoverable,
      config: recordView([
        {
          field: "state.recoverable",
          operator: "IN",
          value: ["UNRECOVERABLE"],
        },
        { field: "state.status", operator: "IN", value: ACTIVE },
      ]),
    },
    {
      id: "succeeded",
      title: t.viewSucceeded,
      config: recordView([
        { field: "state.status", operator: "IN", value: ["SUCCEEDED"] },
      ]),
    },
    { id: "all", title: t.viewAll, config: recordView([]) },
    {
      id: "by-status",
      title: t.analysisByStatus,
      config: analysisView({
        groups: [{ type: "TERMS", field: "state.status", alias: "status" }],
        layout: "chart",
      }),
    },
    {
      id: "by-processor",
      title: t.analysisByProcessor,
      config: analysisView({
        filter: {
          op: "and",
          children: [{ field: "state.status", operator: "IN", value: ACTIVE }],
        },
        groups: [
          {
            type: "TERMS",
            field: "state.function.processorName",
            alias: "processor",
          },
        ],
        metrics: [
          { alias: "count", type: "COUNT" },
          {
            alias: "retries",
            type: "NUMERIC",
            function: "AVG",
            expression: { type: "FIELD", field: "state.retryState.retries" },
          },
        ],
      }),
    },
    {
      id: "daily",
      title: t.analysisDaily,
      config: analysisView({
        groups: [
          {
            type: "DATE_HISTOGRAM",
            field: "firstEventTime",
            alias: "day",
            unit: "DAY",
          },
        ],
        sort: [{ alias: "day", direction: "DESC" }],
        limit: 30,
      }),
    },
  ];
}

/**
 * The compensation service's `execution_failed` snapshot as the view engine
 * reads it, written from the service's own query schema
 * (`GET /execution_failed/snapshot/schema`): every field is one the schema
 * lists, with the operators, sorting and aggregation its capabilities admit.
 * Left out on purpose: the derived duplicates the schema lists with no
 * capability, the snapshot's bookkeeping and the binding errors.
 */
export function executionFailedDefinition(locale: Locale): DataViewDefinition {
  const t = TEXT[locale];
  return {
    id: EXECUTION_FAILED,
    title: t.title,
    recordNoun: t.recordNoun,
    kind: "data",
    source: EXECUTION_FAILED_SOURCE,
    fieldGroups: [
      { id: "search", label: t.groupSearch, fields: ["keyword"] },
      {
        id: "identity",
        label: t.groupIdentity,
        fields: ["state.id", "state.eventId.id"],
      },
      {
        id: "status",
        label: t.groupStatus,
        fields: [
          "state.status",
          "state.recoverable",
          "state.isRetryable",
          "state.isBelowRetryThreshold",
        ],
      },
      {
        id: "function",
        label: t.groupFunction,
        fields: [
          "state.function.contextName",
          "state.function.processorName",
          "state.function.name",
          "state.function.functionKind",
        ],
      },
      {
        id: "event",
        label: t.groupEvent,
        fields: [
          "state.eventId.aggregateId.contextName",
          "state.eventId.aggregateId.aggregateName",
          "state.eventId.aggregateId.aggregateId",
          "state.eventId.version",
        ],
      },
      {
        id: "error",
        label: t.groupError,
        fields: [
          "state.error.errorCode",
          "state.error.errorMsg",
          "state.error.stackTrace",
        ],
      },
      {
        id: "retry",
        label: t.groupRetry,
        fields: [
          "state.retryState.retries",
          "state.retrySpec.maxRetries",
          "state.retrySpec.minBackoff",
          "state.retrySpec.executionTimeout",
          "state.retryState.retryAt",
          "state.retryState.nextRetryAt",
          "state.retryState.timeoutAt",
        ],
      },
      {
        id: "time",
        label: t.groupTime,
        fields: ["firstEventTime", "eventTime", "state.executeAt"],
      },
    ],
    fields: [
      // The full text an operator searches: what went wrong and where. Both
      // fields are full-text only in the schema, so this is the one way to
      // filter by them. A pasted piece of an error means those words
      // together, hence a phrase.
      {
        name: "keyword",
        label: t.keyword,
        kind: "search",
        searchFields: ["state.error.errorMsg", "state.error.stackTrace"],
        searchMode: "PHRASE",
      },
      {
        name: "state.id",
        label: t.id,
        kind: "string",
        sortable: true,
        cell: "copyable",
      },
      {
        name: "state.eventId.id",
        label: t.eventId,
        kind: "string",
        sortable: true,
        cell: "copyable",
      },
      {
        name: "state.status",
        label: t.status,
        kind: "enum",
        sortable: true,
        cell: "status",
        options: [
          { value: "FAILED", label: t.statusFailed, tone: "danger" },
          { value: "PREPARED", label: t.statusPrepared, tone: "warning" },
          { value: "SUCCEEDED", label: t.statusSucceeded, tone: "success" },
        ],
      },
      {
        name: "state.recoverable",
        label: t.recoverable,
        kind: "enum",
        sortable: true,
        options: [
          { value: "RECOVERABLE", label: t.recoverableYes },
          { value: "UNRECOVERABLE", label: t.recoverableNo },
          { value: "UNKNOWN", label: t.recoverableUnknown },
        ],
      },
      {
        name: "state.isRetryable",
        label: t.isRetryable,
        kind: "boolean",
        sortable: true,
      },
      {
        name: "state.isBelowRetryThreshold",
        label: t.isBelowRetryThreshold,
        kind: "boolean",
        sortable: true,
      },
      {
        name: "state.function.contextName",
        label: t.functionContext,
        kind: "string",
        sortable: true,
      },
      {
        name: "state.function.processorName",
        label: t.processor,
        kind: "string",
        sortable: true,
      },
      {
        name: "state.function.name",
        label: t.functionName,
        kind: "string",
        sortable: true,
      },
      {
        name: "state.function.functionKind",
        label: t.functionKind,
        kind: "enum",
        sortable: true,
        options: [
          { value: "COMMAND", label: t.kindCommand },
          { value: "SOURCING", label: t.kindSourcing },
          { value: "EVENT", label: t.kindEvent },
          { value: "STATE_EVENT", label: t.kindStateEvent },
          { value: "ERROR", label: t.kindError },
        ],
      },
      {
        name: "state.eventId.aggregateId.contextName",
        label: t.eventContext,
        kind: "string",
        sortable: true,
      },
      {
        name: "state.eventId.aggregateId.aggregateName",
        label: t.eventAggregate,
        kind: "string",
        sortable: true,
      },
      {
        name: "state.eventId.aggregateId.aggregateId",
        label: t.eventAggregateId,
        kind: "string",
        sortable: true,
        cell: "copyable",
      },
      {
        name: "state.eventId.version",
        label: t.eventVersion,
        kind: "number",
        sortable: true,
      },
      {
        name: "state.error.errorCode",
        label: t.errorCode,
        kind: "string",
        sortable: true,
      },
      // Full-text only in the schema: shown and searched (`keyword`), and
      // filtered by presence alone.
      {
        name: "state.error.errorMsg",
        label: t.errorMsg,
        kind: "string",
        cell: "text",
        operators: ["IS_NULL", "IS_NOT_NULL"],
      },
      {
        name: "state.error.stackTrace",
        label: t.stackTrace,
        kind: "string",
        cell: "text",
        operators: ["IS_NULL", "IS_NOT_NULL"],
      },
      {
        name: "state.retryState.retries",
        label: t.retries,
        kind: "number",
        sortable: true,
        summary: ["SUM", "AVG", "MAX"],
      },
      {
        name: "state.retrySpec.maxRetries",
        label: t.maxRetries,
        kind: "number",
        sortable: true,
      },
      {
        name: "state.retrySpec.minBackoff",
        label: t.minBackoff,
        kind: "number",
        sortable: true,
      },
      {
        name: "state.retrySpec.executionTimeout",
        label: t.executionTimeout,
        kind: "number",
        sortable: true,
      },
      {
        name: "state.retryState.retryAt",
        label: t.retryAt,
        kind: "datetime",
        sortable: true,
      },
      {
        name: "state.retryState.nextRetryAt",
        label: t.nextRetryAt,
        kind: "datetime",
        sortable: true,
      },
      {
        name: "state.retryState.timeoutAt",
        label: t.timeoutAt,
        kind: "datetime",
        sortable: true,
      },
      {
        name: "firstEventTime",
        label: t.firstEventTime,
        kind: "datetime",
        sortable: true,
      },
      {
        name: "eventTime",
        label: t.eventTime,
        kind: "datetime",
        sortable: true,
      },
      {
        name: "state.executeAt",
        label: t.executeAt,
        kind: "datetime",
        sortable: true,
      },
    ],
    record: {
      rowKey: "state.id",
      paging: "paged",
      layouts: ["table", "card"],
      // What batch 3's row commands read to decide what an execution takes.
      rowFields: [
        "state.status",
        "state.isRetryable",
        "state.isBelowRetryThreshold",
        "state.recoverable",
      ],
      // The service refuses a page reaching past its 10,000th row.
      maxWindow: 10_000,
    },
    // What the schema lets the service aggregate: terms by value, numeric
    // bands and sums, date buckets — and a date's earliest and latest.
    analysis: {
      count: true,
      having: true,
      expressions: true,
      // The service refuses an aggregation asking for more than 1,000 groups.
      limits: { maxLimit: 1000 },
      fields: [
        ...[
          "state.status",
          "state.recoverable",
          "state.isRetryable",
          "state.isBelowRetryThreshold",
          "state.function.functionKind",
          "state.function.contextName",
          "state.function.processorName",
          "state.function.name",
          "state.eventId.aggregateId.contextName",
          "state.eventId.aggregateId.aggregateName",
          "state.error.errorCode",
        ].map((field) => ({ field, groups: [TERMS], functions: [] })),
        {
          field: "state.eventId.aggregateId.aggregateId",
          groups: [],
          functions: [],
          distinctCount: true,
        },
        {
          field: "state.retryState.retries",
          groups: [TERMS, HISTOGRAM],
          functions: [SUM, AVG, MIN, MAX],
          percentile: true,
        },
        {
          field: "state.retrySpec.maxRetries",
          groups: [TERMS],
          functions: [AVG, MIN, MAX],
        },
        ...[
          "firstEventTime",
          "eventTime",
          "state.executeAt",
          "state.retryState.nextRetryAt",
        ].map((field) => ({
          field,
          groups: [DATE_HISTOGRAM],
          functions: [MIN, MAX],
          dateUnits: [HOUR, DAY, WEEK, MONTH],
        })),
      ],
    },
    views: systemViews(t),
  };
}
