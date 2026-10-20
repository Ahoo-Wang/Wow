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
} from "@ahoo-wang/wow-client/dsl";
import type {
  AnalysisMetric,
  AnalysisViewConfig,
  DataViewDefinition,
  FieldDefinition,
  FilterNode,
  RecordViewConfig,
  SystemView,
  Text,
} from "@ahoo-wang/wow-view-engine";
import { textKeys, type Words } from "./words.ts";

/** The definition's id: what saved views and routes name it by. */
export const PARKED = "ops-parked";

/** The source key the definition reads: the `parked` snapshots. */
export const PARKED_SOURCE = "ops.parked";

/** Where an entry is (`ParkedStatus`): its ledger's status while parked, then resolved. */
export const PARKED_STATUSES = [
  "SCHEDULED",
  "EXHAUSTED",
  "UNRECOVERABLE",
  "RESOLVED",
] as const;
export type ParkedStatus = (typeof PARKED_STATUSES)[number];

/** Whether a retry may help the entry (`MarkRecoverable`). */
export const RECOVERABLES = [
  "RECOVERABLE",
  "UNRECOVERABLE",
  "UNKNOWN",
] as const;

/** How often the open view reads the entries again, in seconds. */
export const REFRESH_SECONDS = 15;

/** Every word the definition shows, per language. */
export const PARKED_WORDS = {
  en: {
    title: "Parked entries",
    recordNoun: "parked entry",
    groupWhere: "Where",
    groupStatus: "Status",
    groupRetry: "Retry",
    groupTime: "Time",
    id: "Entry ID",
    service: "Service",
    context: "Context",
    subscription: "Subscription",
    aggregateName: "Aggregate",
    aggregateId: "Aggregate ID",
    aggregateTenant: "Aggregate tenant",
    fromVersion: "From version",
    reason: "Cause",
    errorType: "Error type",
    status: "Status",
    statusScheduled: "Scheduled",
    statusExhausted: "Exhausted",
    statusUnrecoverable: "Unrecoverable",
    statusResolved: "Resolved",
    recoverable: "Recoverability",
    recoverableYes: "Recoverable",
    recoverableNo: "Unrecoverable",
    recoverableUnknown: "Unknown",
    requested: "Requested",
    requestedResume: "Resume",
    requestedSkip: "Skip",
    requestedMark: "Mark recoverable",
    requestedSpec: "Apply retry spec",
    refusedAction: "Refused action",
    refusalCode: "Refusal code",
    refusalReason: "Refusal reason",
    outcome: "Outcome",
    outcomeResumed: "Resumed",
    outcomeSkipped: "Skipped",
    outcomeResolved: "Resolved",
    outcomeNotParked: "Not parked",
    retries: "Attempts",
    maxRetries: "Max retries",
    minBackoff: "Min backoff",
    executionTimeout: "Execution timeout",
    nextRetryAt: "Next retry",
    retryAt: "Last retry",
    failedAt: "Failed at",
    recordedAt: "Recorded at",
    resolvedAt: "Resolved at",
    viewAttention: "Needs attention",
    viewScheduled: "Retrying",
    viewUnrecoverable: "Unrecoverable",
    viewResolved: "Resolved",
    viewAll: "All",
    viewByContext: "Parked by context",
    viewActive: "Parked",
    viewClusters: "Failure clusters",
    viewBySubscription: "Top failing subscriptions",
    viewOverTime: "Parked over time",
    clusterCount: "Parked",
    clusterOldest: "First failed",
    clusterLatest: "Last failed",
  },
  "zh-CN": {
    title: "搁置条目",
    recordNoun: "搁置条目",
    groupWhere: "位置",
    groupStatus: "状态",
    groupRetry: "重试",
    groupTime: "时间",
    id: "条目 ID",
    service: "服务",
    context: "上下文",
    subscription: "订阅",
    aggregateName: "聚合",
    aggregateId: "聚合 ID",
    aggregateTenant: "聚合租户",
    fromVersion: "起始版本",
    reason: "原因",
    errorType: "错误类型",
    status: "状态",
    statusScheduled: "计划重试",
    statusExhausted: "重试耗尽",
    statusUnrecoverable: "不可恢复",
    statusResolved: "已解决",
    recoverable: "可恢复性",
    recoverableYes: "可恢复",
    recoverableNo: "不可恢复",
    recoverableUnknown: "未知",
    requested: "已请求",
    requestedResume: "恢复",
    requestedSkip: "跳过",
    requestedMark: "标记可恢复性",
    requestedSpec: "应用重试规格",
    refusedAction: "被拒绝的操作",
    refusalCode: "拒绝代码",
    refusalReason: "拒绝原因",
    outcome: "结果",
    outcomeResumed: "已恢复",
    outcomeSkipped: "已跳过",
    outcomeResolved: "已解决",
    outcomeNotParked: "未搁置",
    retries: "已尝试次数",
    maxRetries: "最大重试次数",
    minBackoff: "最小退避",
    executionTimeout: "执行超时",
    nextRetryAt: "下次重试",
    retryAt: "上次重试",
    failedAt: "失败时间",
    recordedAt: "记录时间",
    resolvedAt: "解决时间",
    viewAttention: "需要处理",
    viewScheduled: "重试中",
    viewUnrecoverable: "不可恢复",
    viewResolved: "已解决",
    viewAll: "全部",
    viewByContext: "按上下文统计",
    viewActive: "搁置中",
    viewClusters: "失败集群",
    viewBySubscription: "失败最多的订阅",
    viewOverTime: "搁置趋势",
    clusterCount: "搁置中",
    clusterOldest: "最早失败",
    clusterLatest: "最近失败",
  },
} satisfies Words;

const t = textKeys("parked", PARKED_WORDS.en);

/** The statuses that wait on an operator: no automatic retry is coming. */
export const ATTENTION: FilterNode = {
  field: "state.status",
  operator: "IN",
  value: ["EXHAUSTED", "UNRECOVERABLE"],
};

/** Still parked: every status but resolved, the 9.x dashboard's "active". */
export const ACTIVE: FilterNode = {
  field: "state.status",
  operator: "IN",
  value: ["SCHEDULED", "EXHAUSTED", "UNRECOVERABLE"],
};

export function status(...values: ParkedStatus[]): FilterNode {
  return { field: "state.status", operator: "IN", value: values };
}

const COLUMNS = [
  "state.status",
  "state.context",
  "state.subscription",
  "state.aggregate.id",
  "state.reason",
  "state.retries",
  "state.requested",
  "state.failedAt",
];

/** A record view of the entries `filter` keeps, the latest failure first. */
export function recordView(
  filter: FilterNode[],
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    kind: "record",
    filter: { op: "and", children: filter },
    filterMode: "simple",
    // The entries change as the services publish them: the open view reads
    // them again on its own, as the 9.x dashboard's queues did.
    refresh: { interval: REFRESH_SECONDS },
    sort: [{ field: "state.failedAt", direction: "DESC" }],
    pageSize: 20,
    layout: "table",
    summaries: [],
    table: { columns: COLUMNS.map((field) => ({ field })) },
    card: {
      title: "state.subscription",
      fields: [
        "state.status",
        "state.context",
        "state.aggregate.id",
        "state.reason",
        "state.failedAt",
      ],
    },
    ...overrides,
  };
}

/** An analysis of the entries `filter` keeps, counted, in a table. */
export function analysisView(
  overrides: Partial<AnalysisViewConfig> &
    Pick<AnalysisViewConfig, "groups" | "chart">,
  filter: FilterNode[] = [ACTIVE],
): AnalysisViewConfig {
  return {
    kind: "analysis",
    filter: { op: "and", children: filter },
    filterMode: "simple",
    refresh: { interval: null },
    metrics: [{ alias: "count", type: "COUNT" }],
    sort: [{ alias: "count", direction: "DESC" }],
    limit: 20,
    layout: "table",
    table: { columns: [] },
    ...overrides,
  };
}

/**
 * A cluster's identity, as the 9.x dashboard's: what failed (the error
 * type the entry's reason names), where (the context) and in which
 * processor (the subscription, `Processor.function`).
 */
export const CLUSTER_GROUPS: AnalysisViewConfig["groups"] = [
  {
    type: AggregationGroupType.TERMS,
    field: "state.errorType",
    alias: "errorType",
  },
  {
    type: AggregationGroupType.TERMS,
    field: "state.context",
    alias: "context",
  },
  {
    type: AggregationGroupType.TERMS,
    field: "state.subscription",
    alias: "subscription",
  },
];

/** A cluster's parked entries, and when the first and the last of them failed. */
export function clusterMetrics(): AnalysisViewConfig["metrics"] {
  const failedAt = (alias: string, label: Text, fn: "MIN" | "MAX") => ({
    type: "NUMERIC" as const,
    alias,
    label,
    function: fn,
    expression: { type: "FIELD" as const, field: "state.failedAt" },
  });
  return [
    { type: "COUNT", alias: "count", label: t.clusterCount },
    failedAt("oldest", t.clusterOldest, "MIN"),
    failedAt("latest", t.clusterLatest, "MAX"),
  ];
}

/**
 * The failure clusters: the parked entries grouped by what failed and
 * where, the largest first. A row drills into its entries (the workbench's
 * drill-down), as the 9.x dashboard's cluster link opened them.
 */
export function clusters(limit = 50): AnalysisViewConfig {
  return analysisView({
    groups: CLUSTER_GROUPS,
    metrics: clusterMetrics(),
    limit,
    chart: {
      type: "bar",
      cartesian: { x: "errorType", series: [{ metric: "count" }] },
    },
  });
}

/** The subscriptions (processors) with the most parked entries. */
export function bySubscription(limit = 10): AnalysisViewConfig {
  return analysisView({
    groups: [
      {
        type: AggregationGroupType.TERMS,
        field: "state.subscription",
        alias: "subscription",
      },
    ],
    limit,
    layout: "chart",
    chart: {
      type: "bar",
      cartesian: {
        x: "subscription",
        series: [{ metric: "count" }],
        orientation: "horizontal",
      },
      legend: "none",
    },
  });
}

/** The statuses of the trend, the ones needing attention first. */
const TREND: readonly [ParkedStatus, Text][] = [
  ["EXHAUSTED", t.statusExhausted],
  ["UNRECOVERABLE", t.statusUnrecoverable],
  ["SCHEDULED", t.statusScheduled],
  ["RESOLVED", t.statusResolved],
];

/**
 * The entries by the day they failed, a stacked bar per status: how the
 * pile grew, and how much of it was resolved since.
 */
export function overTime(): AnalysisViewConfig {
  const [first, ...rest] = TREND.map(([value, label]): AnalysisMetric => ({
    type: "COUNT",
    alias: value.toLowerCase(),
    label,
    filter: { op: "and", children: [status(value)] },
  }));
  return analysisView(
    {
      groups: [
        {
          type: AggregationGroupType.DATE_HISTOGRAM,
          field: "state.failedAt",
          alias: "day",
          unit: "DAY",
        },
      ],
      metrics: [first, ...rest],
      sort: [{ alias: "day", direction: "ASC" }],
      limit: 366,
      layout: "chart",
      chart: {
        type: "bar",
        cartesian: {
          x: "day",
          series: TREND.map(([value]) => ({
            metric: value.toLowerCase(),
            stack: "status",
          })),
        },
      },
    },
    [],
  );
}

function byContext(): AnalysisViewConfig {
  return {
    kind: "analysis",
    filter: { op: "and", children: [ATTENTION] },
    filterMode: "simple",
    refresh: { interval: null },
    groups: [
      {
        type: AggregationGroupType.TERMS,
        field: "state.context",
        alias: "context",
      },
    ],
    metrics: [{ alias: "count", type: "COUNT" }],
    sort: [{ alias: "count", direction: "DESC" }],
    limit: 20,
    layout: "table",
    table: { columns: [] },
    chart: {
      type: "bar",
      cartesian: { x: "context", series: [{ metric: "count" }] },
    },
  };
}

/** The system views: the queues an operator works through. */
export function parkedViews(): SystemView[] {
  return [
    {
      id: "attention",
      title: t.viewAttention,
      config: recordView([ATTENTION]),
    },
    {
      id: "scheduled",
      title: t.viewScheduled,
      config: recordView([status("SCHEDULED")], {
        sort: [{ field: "state.nextRetryAt", direction: "ASC" }],
      }),
    },
    {
      id: "unrecoverable",
      title: t.viewUnrecoverable,
      config: recordView([status("UNRECOVERABLE")]),
    },
    {
      id: "resolved",
      title: t.viewResolved,
      config: recordView([status("RESOLVED")], {
        sort: [{ field: "state.resolvedAt", direction: "DESC" }],
      }),
    },
    { id: "active", title: t.viewActive, config: recordView([ACTIVE]) },
    { id: "all", title: t.viewAll, config: recordView([]) },
    { id: "by-context", title: t.viewByContext, config: byContext() },
    { id: "clusters", title: t.viewClusters, config: clusters() },
    {
      id: "by-subscription",
      title: t.viewBySubscription,
      config: bySubscription(),
    },
    { id: "over-time", title: t.viewOverTime, config: overTime() },
  ];
}

const string = (name: string, label: string): FieldDefinition => ({
  name,
  label,
  kind: "string",
  sortable: true,
});

const datetime = (name: string, label: string): FieldDefinition => ({
  name,
  label,
  kind: "datetime",
  sortable: true,
});

const number = (name: string, label: string): FieldDefinition => ({
  name,
  label,
  kind: "number",
  sortable: true,
});

/**
 * The `parked` snapshots as the engine reads them: one service's entry of
 * one parked aggregate, as its failure ledger publishes it (D141). Written
 * in full by hand: the paths are the generated `ParkedStateField`s.
 */
export const parked: DataViewDefinition = {
  id: PARKED,
  kind: "data",
  source: PARKED_SOURCE,
  title: t.title,
  recordNoun: t.recordNoun,
  // A board's or a view's time filter reads the entries by when they failed.
  timeField: "state.failedAt",
  fields: [
    { ...string("aggregateId", t.id), cell: "copyable" },
    string("state.service", t.service),
    string("state.context", t.context),
    string("state.subscription", t.subscription),
    string("state.aggregate.name", t.aggregateName),
    { ...string("state.aggregate.id", t.aggregateId), cell: "copyable" },
    string("state.aggregate.tenantId", t.aggregateTenant),
    number("state.fromVersion", t.fromVersion),
    { ...string("state.reason", t.reason), cell: "text", sortable: false },
    string("state.errorType", t.errorType),
    {
      name: "state.status",
      label: t.status,
      kind: "enum",
      sortable: true,
      cell: "status",
      options: [
        { value: "SCHEDULED", label: t.statusScheduled, tone: "neutral" },
        { value: "EXHAUSTED", label: t.statusExhausted, tone: "danger" },
        {
          value: "UNRECOVERABLE",
          label: t.statusUnrecoverable,
          tone: "warning",
        },
        { value: "RESOLVED", label: t.statusResolved, tone: "success" },
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
      name: "state.requested",
      label: t.requested,
      kind: "enum",
      sortable: true,
      options: [
        { value: "RESUME", label: t.requestedResume },
        { value: "SKIP", label: t.requestedSkip },
        { value: "MARK_RECOVERABLE", label: t.requestedMark },
        { value: "APPLY_RETRY_SPEC", label: t.requestedSpec },
      ],
    },
    {
      name: "state.outcome",
      label: t.outcome,
      kind: "enum",
      sortable: true,
      options: [
        { value: "RESUMED", label: t.outcomeResumed },
        { value: "SKIPPED", label: t.outcomeSkipped },
        { value: "RESOLVED", label: t.outcomeResolved },
        { value: "NOT_PARKED", label: t.outcomeNotParked },
      ],
    },
    number("state.retries", t.retries),
    number("state.retrySpec.maxRetries", t.maxRetries),
    { ...string("state.retrySpec.minBackoff", t.minBackoff), sortable: false },
    {
      ...string("state.retrySpec.executionTimeout", t.executionTimeout),
      sortable: false,
    },
    datetime("state.nextRetryAt", t.nextRetryAt),
    datetime("state.retryAt", t.retryAt),
    datetime("state.failedAt", t.failedAt),
    datetime("state.recordedAt", t.recordedAt),
    datetime("state.resolvedAt", t.resolvedAt),
  ],
  fieldGroups: [
    {
      id: "where",
      label: t.groupWhere,
      fields: [
        "aggregateId",
        "state.service",
        "state.context",
        "state.subscription",
        "state.aggregate.name",
        "state.aggregate.id",
        "state.aggregate.tenantId",
        "state.fromVersion",
        "state.reason",
        "state.errorType",
      ],
    },
    {
      id: "status",
      label: t.groupStatus,
      fields: [
        "state.status",
        "state.recoverable",
        "state.requested",
        "state.outcome",
      ],
    },
    {
      id: "retry",
      label: t.groupRetry,
      fields: [
        "state.retries",
        "state.retrySpec.maxRetries",
        "state.retrySpec.minBackoff",
        "state.retrySpec.executionTimeout",
        "state.nextRetryAt",
        "state.retryAt",
      ],
    },
    {
      id: "time",
      label: t.groupTime,
      fields: ["state.failedAt", "state.recordedAt", "state.resolvedAt"],
    },
  ],
  record: {
    rowKey: "aggregateId",
    paging: "paged",
    layouts: ["table", "card"],
    // What the actions read to decide what an entry takes, whichever
    // columns the open view shows.
    rowFields: ["state.status", "state.recoverable", "state.requested"],
  },
  analysis: {
    count: true,
    // Categories to count by; a formula takes none of them.
    fields: [
      ...[
        "state.context",
        "state.service",
        "state.subscription",
        "state.status",
        "state.errorType",
        "state.recoverable",
        "state.requested",
        "state.outcome",
      ].map((field) => ({
        field,
        groups: [AggregationGroupType.TERMS],
        functions: [],
        expressionInput: false,
      })),
      // The times bucket by day or hour, and a cluster reads its first and
      // last failure (`@QueryTemporal` on the state's times).
      ...["state.failedAt", "state.resolvedAt"].map((field) => ({
        field,
        groups: [AggregationGroupType.DATE_HISTOGRAM],
        functions: [AggregationFunction.MIN, AggregationFunction.MAX],
        dateUnits: [
          AggregationDateUnit.HOUR,
          AggregationDateUnit.DAY,
          AggregationDateUnit.WEEK,
          AggregationDateUnit.MONTH,
        ],
      })),
    ],
  },
  views: parkedViews(),
};
