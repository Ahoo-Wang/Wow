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
  emptyDashboardConfig,
  systemInstanceId,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DashboardDefinition,
  type DashboardPanel,
  type DashboardViewConfig,
  type FilterNode,
  type PanelBinding,
  type PanelLayout,
} from "@ahoo-wang/wow-view-engine";
import type { Locale } from "@/i18n.tsx";
import { ACTIVITY_ANALYSES } from "./activityAnalyses.ts";
import {
  ACTIVE_CONDITION,
  CLUSTER_GROUPS,
  clusterTimes,
  DUE_FOR_RETRY,
  EXECUTION_FAILED,
  EXHAUSTED,
  TIMED_OUT,
  UNRECOVERABLE,
} from "./executionFailed.ts";
import { FAILURE_ANALYSES } from "./failureAnalyses.ts";
import {
  EXECUTION_HISTORY,
  OUTCOME_ELEMENTS,
  OUTCOME_EVENTS,
  type OutcomeMetric,
} from "./executionHistory.ts";

/** The overview's definition: a board owns no data, only its system board. */
export const OVERVIEW = "overview";

/** The console's home page: the one system board of the overview. */
export const OVERVIEW_BOARD = systemInstanceId(OVERVIEW, "home");

/** The board's one filter: the window its panels count in. */
export const OVERVIEW_WINDOW = "window";

/** The record panel the console puts its commands on. */
export const ATTENTION_PANEL = "attention";

const TEXT = {
  en: {
    title: "Overview",
    board: "Compensation overview",
    window: "Time range",
    inWindow: "Active in range",
    allActive: "All active",
    actionable: "Actionable now",
    timedOut: "Timed out",
    unrecoverable: "Unrecoverable",
    newFailures: "New failures",
    prepared: "Prepared",
    retryFailed: "Retry failed",
    retrySucceeded: "Retry succeeded",
    netBacklog: "Net backlog",
    retrySuccess: "Retry success",
    recoverability: "Recoverability of active failures",
    retries: "Retries of active failures",
    retriesZero: "0",
    retriesOneToTwo: "1–2",
    retriesThreeToFive: "3–5",
    retriesSixPlus: "6+",
    clusters: "Failure clusters — top 5",
    count: "Active",
    oldest: "Oldest",
    nextRetry: "Next retry",
    attention: "Needing attention — due for retry",
    exhausted: "Retries spent — awaiting a decision",
    failuresBoard: "Failure analysis",
    activityBoard: "Compensation activity",
  },
  "zh-CN": {
    title: "概览",
    board: "补偿概览",
    window: "时间范围",
    inWindow: "范围内活动",
    allActive: "全部活动",
    actionable: "可立即处理",
    timedOut: "已超时",
    unrecoverable: "不可恢复",
    newFailures: "新增失败",
    prepared: "准备重试",
    retryFailed: "重试失败",
    retrySucceeded: "重试成功",
    netBacklog: "净积压",
    retrySuccess: "重试成功率",
    recoverability: "活动失败的可恢复性",
    retries: "活动失败的重试次数",
    retriesZero: "0 次",
    retriesOneToTwo: "1–2 次",
    retriesThreeToFive: "3–5 次",
    retriesSixPlus: "6 次及以上",
    clusters: "失败集中度 · 前 5 个集群",
    count: "活动失败",
    oldest: "最早执行",
    nextRetry: "最早下次重试",
    attention: "最需要处理 · 已到重试时间",
    exhausted: "重试已耗尽 · 待决定",
    failuresBoard: "失败分析",
    activityBoard: "补偿活动",
  },
} satisfies Record<Locale, Record<string, string>>;

type Text = (typeof TEXT)[Locale];

/** An analysis the board owns, under `conditions`; counted unless it says. */
function analysis(
  overrides: Partial<AnalysisViewConfig> &
    Pick<AnalysisViewConfig, "chart"> & { conditions?: FilterNode[] },
): AnalysisViewConfig {
  const { conditions: filter = [], ...rest } = overrides;
  return {
    kind: "analysis",
    // An `or` inside the conditions needs the advanced editor to be read.
    filterMode: filter.some((node) => "op" in node) ? "advanced" : "simple",
    refresh: { interval: null },
    groups: [],
    metrics: [{ alias: "count", type: "COUNT" }],
    sort: [],
    limit: 1,
    layout: "chart",
    table: { columns: [] },
    ...rest,
    filter: { op: "and", children: filter },
  };
}

/** The failed executions that `filter` keeps, read as one number. */
function countCard(filter: FilterNode[]): AnalysisViewConfig {
  return analysis({
    conditions: filter,
    chart: { type: "metric", metric: { metric: "count" } },
  });
}

/** The streams holding `metric`'s event: one per command that appended it. */
function holding(metric: OutcomeMetric): FilterNode {
  return {
    field: "body",
    operator: "ELEMENT_MATCH",
    value: {
      op: "and",
      children: [
        { field: "body.name", operator: "EQ", value: OUTCOME_EVENTS[metric] },
      ],
    },
  };
}

/**
 * One outcome over the window: the streams holding its event, the number
 * the whole window's and the line its days' (`headline: 'whole'`, which no
 * date on the board anchors — the number stays the window's total).
 */
function outcomeTrendCard(metric: OutcomeMetric): AnalysisViewConfig {
  return analysis({
    conditions: [holding(metric)],
    groups: [
      {
        type: "DATE_HISTOGRAM",
        field: "createTime",
        alias: "day",
        unit: "DAY",
      },
    ],
    sort: [{ alias: "day", direction: "ASC" }],
    // A day a point: the old overview took windows of up to 1,000 days.
    limit: 1000,
    chart: {
      type: "metric",
      metric: { metric: "count", trend: { x: "day", headline: "whole" } },
    },
  });
}

/** The events of `metric`'s name, counted one event a row. */
function eventCount(metric: OutcomeMetric): AnalysisMetric {
  return {
    type: "COUNT",
    alias: metric,
    filter: {
      op: "and",
      children: [
        { field: "body.name", operator: "EQ", value: OUTCOME_EVENTS[metric] },
      ],
    },
  };
}

/**
 * A figure worked out of two outcomes, by the service: a metric's condition
 * reads one value a row, so the counts are of events (`OUTCOME_ELEMENTS`) —
 * one a stream, as a compensation command appends one.
 */
function derivedCard(
  operands: [OutcomeMetric, OutcomeMetric],
  derived: AnalysisMetric,
): AnalysisViewConfig {
  return analysis({
    elements: [OUTCOME_ELEMENTS],
    metrics: [eventCount(operands[0]), eventCount(operands[1]), derived],
    chart: { type: "metric", metric: { metric: derived.alias } },
  });
}

/** How many active failures took each number of retries. */
function retriesTable(t: Text): AnalysisViewConfig {
  const retries = "state.retryState.retries";
  const band = (
    alias: string,
    label: string,
    condition: FilterNode,
  ): AnalysisMetric => ({
    type: "COUNT",
    alias,
    label,
    filter: { op: "and", children: [condition] },
  });
  return analysis({
    conditions: [ACTIVE_CONDITION],
    metrics: [
      band("zero", t.retriesZero, { field: retries, operator: "EQ", value: 0 }),
      band("oneToTwo", t.retriesOneToTwo, {
        field: retries,
        operator: "BETWEEN",
        value: [1, 2],
      }),
      band("threeToFive", t.retriesThreeToFive, {
        field: retries,
        operator: "BETWEEN",
        value: [3, 5],
      }),
      band("sixPlus", t.retriesSixPlus, {
        field: retries,
        operator: "GTE",
        value: 6,
      }),
    ],
    layout: "table",
    chart: { type: "bar" },
  });
}

/**
 * The five clusters with the most active failures, in the few columns a
 * panel has room for: where it failed — the context, the processor and its
 * function — and with which error, how many are active, and the earliest
 * execution and next retry. The old five-part identity less the function's
 * kind alone, which the context, processor and function already fix: every
 * other part can differ between two clusters that share the rest (two
 * services with a processor of the same name), so none of them is dropped.
 * The whole view (`clusters` on the failed executions), with the kind and
 * the split by status, is what 「在工作台中打开」 opens in the panel's stead.
 * A press on a cluster opens its active failures in the workbench, under
 * the board's window.
 */
function clustersTable(t: Text): AnalysisViewConfig {
  const pick = (alias: string) => {
    const group = CLUSTER_GROUPS.find((each) => each.alias === alias);
    if (!group) throw new Error(`no cluster group ${alias}`);
    return group;
  };
  return analysis({
    conditions: [ACTIVE_CONDITION],
    groups: ["contextName", "processorName", "functionName", "errorCode"].map(
      pick,
    ),
    metrics: [
      { type: "COUNT", alias: "count", label: t.count },
      ...clusterTimes(t.oldest, t.nextRetry),
    ],
    sort: [{ alias: "count", direction: "DESC" }],
    limit: 5,
    layout: "table",
    chart: { type: "bar" },
  });
}

/** Active failures by recoverability. */
function recoverabilityPie(): AnalysisViewConfig {
  return analysis({
    conditions: [ACTIVE_CONDITION],
    groups: [
      { type: "TERMS", field: "state.recoverable", alias: "recoverable" },
    ],
    sort: [{ alias: "count", direction: "DESC" }],
    limit: 3,
    chart: {
      type: "pie",
      pie: { category: "recoverable", value: "count", donut: true },
    },
  });
}

/** A panel's frame on the board's 24-column grid. */
const at = (x: number, y: number, w: number, h: number): PanelLayout => ({
  x,
  y,
  w,
  h,
});

/**
 * A panel showing one of the analyses the definitions offer as system views
 * (`failureAnalyses`, `activityAnalyses`): the same view the workbench
 * opens, titled as it is there, narrowed by the board's window only where
 * `bindings` say — the pile's make-up is read whole.
 */
function analysisPanel(
  view: string,
  layout: PanelLayout,
  bindings: PanelBinding[] = [],
  definitionId: string = EXECUTION_FAILED,
): DashboardPanel {
  return {
    id: view,
    kind: "view",
    bindings,
    layout,
    instanceId: systemInstanceId(definitionId, view),
  };
}

/** The panel's view, owned by the board, over one of the two definitions. */
function owned(
  definitionId: string,
  config: AnalysisViewConfig,
): { owned: { definitionId: string; config: AnalysisViewConfig } } {
  return { owned: { definitionId, config } };
}

/**
 * The console's home page as a board (rebuild proposal, 2.3, batch 6): what
 * the old overview showed, each figure a panel counted by the engine.
 *
 * - Its one filter is the window, 「近 7 天」 until the reader picks another —
 *   today and the six whole days before it, as the old overview's default.
 *   It narrows the failed executions by when they ran (`state.executeAt`)
 *   and the event streams by when they were written (`createTime`), as the
 *   old overview did. 「全部活动」 is the one panel it leaves alone: the old
 *   overview's older and newer backlog are said as the two numbers, in range
 *   and all (G9).
 * - The backlog row counts what the queues hold: 「可立即处理」 is exactly
 *   the due-for-retry queue, whose rows are the last panel — so its number
 *   is that panel's total, and the panel opens the queue in the workbench.
 * - The outcomes are the event streams: each figure counts the streams that
 *   hold its event; the net backlog and the retry success rate are worked
 *   out by the service from those counts.
 */
function homeBoard(locale: Locale): DashboardViewConfig {
  const t = TEXT[locale];
  const executeAt = [
    { globalField: OVERVIEW_WINDOW, panelField: "state.executeAt" },
  ];
  const createTime = [
    { globalField: OVERVIEW_WINDOW, panelField: "createTime" },
  ];
  const firstFailed = [
    { globalField: OVERVIEW_WINDOW, panelField: "firstEventTime" },
  ];
  const card = (
    id: string,
    title: string,
    filter: FilterNode[],
    layout: PanelLayout,
    windowed = true,
    /** The queue 「在工作台中打开」 opens: the records the number counts. */
    opens?: string,
  ): DashboardPanel => ({
    id,
    kind: "view",
    title,
    bindings: windowed ? executeAt : [],
    layout,
    ...owned(EXECUTION_FAILED, countCard(filter)),
    ...(opens ? { opens } : {}),
  });
  const ref = (metric: string) => ({ type: "METRIC_REF" as const, metric });
  const netBacklog: AnalysisMetric = {
    type: "DERIVED",
    alias: "netBacklog",
    label: t.netBacklog,
    expression: {
      type: "BINARY",
      operator: "SUBTRACT",
      left: ref("newFailures"),
      right: ref("retrySucceeded"),
    },
    format: { style: "number", decimals: 0 },
  };
  const retrySuccess: AnalysisMetric = {
    type: "DERIVED",
    alias: "retrySuccess",
    label: t.retrySuccess,
    expression: {
      type: "BINARY",
      operator: "DIVIDE",
      left: ref("retrySucceeded"),
      right: {
        type: "BINARY",
        operator: "ADD",
        left: ref("retrySucceeded"),
        right: ref("retryFailed"),
      },
    },
    format: { style: "percent", decimals: 1 },
  };
  const outcome = (
    id: string,
    title: string,
    config: AnalysisViewConfig,
    layout: PanelLayout,
  ): DashboardPanel => ({
    id,
    kind: "view",
    title,
    bindings: createTime,
    layout,
    ...owned(EXECUTION_HISTORY, config),
  });
  return {
    ...emptyDashboardConfig(),
    // The page is the board: it spreads across whatever the shell leaves it.
    width: "full",
    fields: [
      {
        name: OVERVIEW_WINDOW,
        label: t.window,
        kind: "datetime",
        default: { type: "relative", amount: 7, unit: "day" },
        required: true,
      },
    ],
    panels: [
      card("in-window", t.inWindow, [ACTIVE_CONDITION], at(0, 0, 4, 2)),
      card(
        "all-active",
        t.allActive,
        [ACTIVE_CONDITION],
        at(4, 0, 4, 2),
        false,
      ),
      card("actionable", t.actionable, DUE_FOR_RETRY, at(8, 0, 4, 2)),
      card(
        "timed-out",
        t.timedOut,
        [
          { field: "state.status", operator: "IN", value: ["PREPARED"] },
          TIMED_OUT,
        ],
        at(12, 0, 4, 2),
      ),
      // What the scheduler will not take again and no rule has closed: a
      // person's to decide — force it, or mark it unrecoverable. In the test
      // service's data, 154,000 of the 685,000 active failures.
      card(
        "exhausted",
        t.exhausted,
        EXHAUSTED,
        at(16, 0, 4, 2),
        false,
        systemInstanceId(EXECUTION_FAILED, "non-retryable"),
      ),
      card("unrecoverable", t.unrecoverable, UNRECOVERABLE, at(20, 0, 4, 2)),
      outcome(
        "new-failures",
        t.newFailures,
        outcomeTrendCard("newFailures"),
        at(0, 2, 4, 2),
      ),
      outcome(
        "prepared",
        t.prepared,
        outcomeTrendCard("prepared"),
        at(4, 2, 4, 2),
      ),
      outcome(
        "retry-failed",
        t.retryFailed,
        outcomeTrendCard("retryFailed"),
        at(8, 2, 4, 2),
      ),
      outcome(
        "retry-succeeded",
        t.retrySucceeded,
        outcomeTrendCard("retrySucceeded"),
        at(12, 2, 4, 2),
      ),
      outcome(
        "net-backlog",
        t.netBacklog,
        derivedCard(["newFailures", "retrySucceeded"], netBacklog),
        at(16, 2, 4, 2),
      ),
      outcome(
        "retry-success",
        t.retrySuccess,
        derivedCard(["retrySucceeded", "retryFailed"], retrySuccess),
        at(20, 2, 4, 2),
      ),
      {
        id: "clusters",
        kind: "view",
        title: t.clusters,
        bindings: executeAt,
        layout: at(0, 4, 24, 4),
        // The old overview's cluster link: the cluster's active failures.
        click: {
          kind: "view",
          instanceId: systemInstanceId(EXECUTION_FAILED, "active"),
        },
        ...owned(EXECUTION_FAILED, clustersTable(t)),
        // Every column of it, in the workbench (W13).
        opens: systemInstanceId(EXECUTION_FAILED, "clusters"),
      },
      // Where the pile goes and where it is, whole: the scheduler's reach
      // is decided by the recoverability at its end.
      analysisPanel(FAILURE_ANALYSES.fate, at(0, 8, 24, 6)),
      analysisPanel(FAILURE_ANALYSES.concentration, at(0, 14, 12, 5)),
      // Of the failures of the window, how many compensation brought back.
      analysisPanel(FAILURE_ANALYSES.repair, at(12, 14, 12, 5), firstFailed),
      {
        id: "recoverability",
        kind: "view",
        title: t.recoverability,
        bindings: executeAt,
        layout: at(0, 19, 12, 4),
        ...owned(EXECUTION_FAILED, recoverabilityPie()),
      },
      {
        id: "retries",
        kind: "view",
        title: t.retries,
        bindings: executeAt,
        layout: at(12, 19, 12, 4),
        ...owned(EXECUTION_FAILED, retriesTable(t)),
      },
      {
        id: ATTENTION_PANEL,
        kind: "view",
        title: t.attention,
        bindings: executeAt,
        layout: at(0, 23, 24, 7),
        instanceId: systemInstanceId(EXECUTION_FAILED, "next-retry"),
      },
    ],
  };
}

/** The board's one filter, as the home board has it: the window. */
function windowField(t: Text): DashboardViewConfig["fields"][number] {
  return {
    name: OVERVIEW_WINDOW,
    label: t.window,
    kind: "datetime",
    default: { type: "relative", amount: 30, unit: "day" },
    required: true,
  };
}

const bound = (panelField: string): PanelBinding[] => [
  { globalField: OVERVIEW_WINDOW, panelField },
];

/**
 * 「失败分析」: for whoever owns a processor or the compensation's health —
 * where failures go and are, what they fail with and on, how old the pile
 * is, when failures arrive, and how many compensation brings back and how
 * fast. The pile's make-up is read whole; when failures arrive and what
 * came of them are read in the window, the last 30 days until another.
 */
function failuresBoard(t: Text): DashboardViewConfig {
  const A = FAILURE_ANALYSES;
  return {
    ...emptyDashboardConfig(),
    width: "full",
    fields: [windowField(t)],
    panels: [
      analysisPanel(A.fate, at(0, 0, 24, 6)),
      analysisPanel(A.concentration, at(0, 6, 12, 5)),
      analysisPanel(A.errorCodes, at(12, 6, 12, 5)),
      analysisPanel(A.sources, at(0, 11, 12, 5)),
      analysisPanel(A.backlogAge, at(12, 11, 12, 5)),
      analysisPanel(A.arrivals, at(0, 16, 12, 5), bound("firstEventTime")),
      analysisPanel(A.retries, at(12, 16, 12, 5)),
      analysisPanel(A.repair, at(0, 21, 12, 5), bound("firstEventTime")),
      analysisPanel(A.recovery, at(12, 21, 12, 5), bound("eventTime")),
      analysisPanel(A.calendar, at(0, 26, 24, 5)),
    ],
  };
}

/**
 * 「补偿活动」: what the compensation did, out of its event streams — the
 * outcomes of the window, each a day at a time, the commands and the
 * executions a day, the events' make-up, and what people did.
 */
function activityBoard(t: Text): DashboardViewConfig {
  const A = ACTIVITY_ANALYSES;
  const createTime = bound("createTime");
  const outcome = (
    id: OutcomeMetric,
    title: string,
    layout: PanelLayout,
  ): DashboardPanel => ({
    id,
    kind: "view",
    title,
    bindings: createTime,
    layout,
    ...owned(EXECUTION_HISTORY, outcomeTrendCard(id)),
  });
  return {
    ...emptyDashboardConfig(),
    width: "full",
    fields: [windowField(t)],
    panels: [
      outcome("newFailures", t.newFailures, at(0, 0, 6, 2)),
      outcome("prepared", t.prepared, at(6, 0, 6, 2)),
      outcome("retryFailed", t.retryFailed, at(12, 0, 6, 2)),
      outcome("retrySucceeded", t.retrySucceeded, at(18, 0, 6, 2)),
      analysisPanel(A.activity, at(0, 2, 24, 6), createTime, EXECUTION_HISTORY),
      analysisPanel(A.eventMix, at(0, 8, 12, 5), createTime, EXECUTION_HISTORY),
      analysisPanel(
        A.interventions,
        at(12, 8, 12, 5),
        createTime,
        EXECUTION_HISTORY,
      ),
    ],
  };
}

/** The overview's definition in one language, with its one system board. */
export function overviewDefinition(locale: Locale): DashboardDefinition {
  const t = TEXT[locale];
  return {
    id: OVERVIEW,
    title: t.title,
    kind: "dashboard",
    views: [
      { id: "home", title: t.board, config: homeBoard(locale) },
      { id: "failures", title: t.failuresBoard, config: failuresBoard(t) },
      { id: "activity", title: t.activityBoard, config: activityBoard(t) },
    ],
  };
}
