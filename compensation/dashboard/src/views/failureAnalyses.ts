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

import type {
  AnalysisGroup,
  AnalysisMetric,
  AnalysisViewConfig,
  ChartSpec,
  FilterNode,
  SystemView,
} from "@ahoo-wang/wow-view-engine";
import type { Locale } from "@/i18n.tsx";

/**
 * The failed executions' analyses (2026-09-27, from the test service's
 * data): each answers one question someone who keeps the compensation
 * running asks, and each is a system view of its own, so the workbench
 * opens it and a board lays it out.
 *
 * The data said what to ask. Of 685,000 active failures, three will be
 * retried by the scheduler; 154,000 have spent their retries and wait on a
 * decision; 529,000 were classified unrecoverable when they failed. One
 * processor holds 95% of them and one error code 77%. New ones arrive
 * 2,500–3,000 a day in bursts at fixed hours. The few that recover do so in
 * minutes. So: where do failures go, where do they come from, how old is
 * the pile, when do they arrive, how many does compensation save, and how
 * fast.
 */

/** The ids of the analyses, as the definition's system views. */
export const FAILURE_ANALYSES = {
  fate: "fate",
  concentration: "concentration",
  errorCodes: "error-codes",
  sources: "sources",
  backlogAge: "backlog-age",
  arrivals: "arrivals",
  calendar: "calendar",
  retries: "retries",
  repair: "repair",
  recovery: "recovery",
} as const;

const TEXT = {
  en: {
    fate: "Where active failures go",
    concentration: "Where active failures are",
    errorCodes: "Active failures by error",
    sources: "Active failures by source aggregate",
    backlogAge: "Active failures by month first failed",
    arrivals: "When failures arrive",
    calendar: "New failures by calendar day",
    retries: "Retries spent by active failures",
    repair: "What compensation repairs",
    recovery: "Time to recover",
    count: "Failures",
    failed: "Failed",
    retried: "Retried at least once",
    succeeded: "Recovered",
    recovered: "Recovered",
    median: "Median minutes",
    slowest: "90th percentile minutes",
  },
  "zh-CN": {
    fate: "活动失败的去向",
    concentration: "活动失败集中在哪",
    errorCodes: "活动失败 · 按错误码",
    sources: "活动失败 · 按来源聚合",
    backlogAge: "活动失败 · 按首次失败月份",
    arrivals: "失败发生的时段",
    calendar: "新增失败 · 日历",
    retries: "活动失败已用的重试次数",
    repair: "补偿修复了多少",
    recovery: "恢复耗时",
    count: "失败数",
    failed: "失败",
    retried: "至少重试过一次",
    succeeded: "已恢复",
    recovered: "已恢复",
    median: "中位耗时（分钟）",
    slowest: "90 分位耗时（分钟）",
  },
} satisfies Record<Locale, Record<string, string>>;

type Text = (typeof TEXT)[Locale];

/** Still waiting on someone: failed, or prepared for a retry. */
const ACTIVE: FilterNode = {
  field: "state.status",
  operator: "IN",
  value: ["FAILED", "PREPARED"],
};

const RECOVERED: FilterNode = {
  field: "state.status",
  operator: "IN",
  value: ["SUCCEEDED"],
};

const COUNT = (label: string): AnalysisMetric => ({
  type: "COUNT",
  alias: "count",
  label,
});

const terms = (field: string, alias: string): AnalysisGroup => ({
  type: "TERMS",
  field,
  alias,
});

/** One analysis, drawn as a chart unless it says otherwise. */
function analysis(
  config: Partial<AnalysisViewConfig> &
    Pick<AnalysisViewConfig, "groups" | "metrics" | "chart"> & {
      conditions?: FilterNode[];
    },
): AnalysisViewConfig {
  const { conditions = [], ...rest } = config;
  return {
    kind: "analysis",
    filterMode: "simple",
    refresh: { interval: null },
    sort: [],
    limit: 100,
    layout: "chart",
    table: { columns: [] },
    ...rest,
    filter: { op: "and", children: conditions },
  };
}

const bars = (x: string, horizontal = false): ChartSpec => ({
  type: "bar",
  cartesian: {
    x,
    series: [{ metric: "count" }],
    ...(horizontal ? { orientation: "horizontal" as const } : {}),
  },
});

/** Minutes from the first failure to the recovery, for a percentile. */
const MINUTES_TO_RECOVER = {
  type: "DATE_DIFF" as const,
  from: "firstEventTime",
  to: "eventTime",
  unit: "MINUTE" as const,
};

/**
 * Where active failures go: from the service, through the error, to the
 * recoverability that decides whether the scheduler takes them again.
 */
function fate(t: Text): AnalysisViewConfig {
  return analysis({
    conditions: [ACTIVE],
    groups: [
      terms("state.function.contextName", "context"),
      terms("state.error.errorCode", "errorCode"),
      terms("state.recoverable", "recoverable"),
    ],
    metrics: [COUNT(t.count)],
    sort: [{ alias: "count", direction: "DESC" }],
    limit: 300,
    chart: {
      type: "sankey",
      sankey: {
        levels: ["context", "errorCode", "recoverable"],
        value: "count",
      },
    },
  });
}

/** Where active failures are: each processor inside its service. */
function concentration(t: Text): AnalysisViewConfig {
  return analysis({
    conditions: [ACTIVE],
    groups: [
      terms("state.function.contextName", "context"),
      terms("state.function.processorName", "processor"),
    ],
    metrics: [COUNT(t.count)],
    sort: [{ alias: "count", direction: "DESC" }],
    limit: 200,
    chart: {
      type: "treemap",
      treemap: { category: "processor", parent: "context", value: "count" },
    },
  });
}

/** The errors active failures fail with, the most common first. */
function errorCodes(t: Text): AnalysisViewConfig {
  return analysis({
    conditions: [ACTIVE],
    groups: [terms("state.error.errorCode", "errorCode")],
    metrics: [COUNT(t.count)],
    sort: [{ alias: "count", direction: "DESC" }],
    limit: 10,
    chart: bars("errorCode", true),
  });
}

/** The aggregates whose events active failures failed on. */
function sources(t: Text): AnalysisViewConfig {
  return analysis({
    conditions: [ACTIVE],
    groups: [terms("state.eventId.aggregateId.aggregateName", "aggregate")],
    metrics: [COUNT(t.count)],
    sort: [{ alias: "count", direction: "DESC" }],
    limit: 10,
    chart: bars("aggregate", true),
  });
}

/** How old the pile is: its failures by the month they first failed. */
function backlogAge(t: Text): AnalysisViewConfig {
  return analysis({
    conditions: [ACTIVE],
    groups: [
      {
        type: "DATE_HISTOGRAM",
        field: "firstEventTime",
        alias: "month",
        unit: "MONTH",
      },
    ],
    metrics: [COUNT(t.count)],
    sort: [{ alias: "month", direction: "ASC" }],
    limit: 1000,
    chart: bars("month"),
  });
}

/** When failures arrive: the weekday against the hour. */
function arrivals(t: Text): AnalysisViewConfig {
  return analysis({
    groups: [
      {
        type: "DATE_PART",
        field: "firstEventTime",
        part: "DAY_OF_WEEK",
        alias: "weekday",
      },
      {
        type: "DATE_PART",
        field: "firstEventTime",
        part: "HOUR_OF_DAY",
        alias: "hour",
      },
    ],
    metrics: [COUNT(t.count)],
    limit: 1000,
    chart: {
      type: "heatmap",
      heatmap: { x: "hour", y: "weekday", value: "count" },
    },
  });
}

/** New failures a day, laid out as the calendar lays the days out. */
function calendar(t: Text): AnalysisViewConfig {
  return analysis({
    groups: [
      {
        type: "DATE_HISTOGRAM",
        field: "firstEventTime",
        alias: "day",
        unit: "DAY",
      },
    ],
    metrics: [COUNT(t.count)],
    sort: [{ alias: "day", direction: "ASC" }],
    limit: 1000,
    chart: { type: "calendar", calendar: { date: "day", value: "count" } },
  });
}

/**
 * The retries active failures have spent: the spikes stand at the retry
 * limits, where the scheduler stopped.
 */
function retries(t: Text): AnalysisViewConfig {
  return analysis({
    conditions: [ACTIVE],
    groups: [
      {
        type: "HISTOGRAM",
        field: "state.retryState.retries",
        alias: "retries",
        interval: 1,
      },
    ],
    metrics: [COUNT(t.count)],
    sort: [{ alias: "retries", direction: "ASC" }],
    limit: 100,
    chart: bars("retries"),
  });
}

/**
 * What compensation repairs, of the failures of a time: how many were
 * retried at all, and how many of those it brought back.
 */
function repair(t: Text): AnalysisViewConfig {
  const counted = (
    alias: string,
    label: string,
    condition?: FilterNode,
  ): AnalysisMetric => ({
    type: "COUNT",
    alias,
    label,
    ...(condition ? { filter: { op: "and", children: [condition] } } : {}),
  });
  return analysis({
    groups: [],
    metrics: [
      counted("failed", t.failed),
      counted("retried", t.retried, {
        field: "state.retryState.retries",
        operator: "GTE",
        value: 1,
      }),
      counted("recovered", t.succeeded, RECOVERED),
    ],
    limit: 1,
    chart: {
      type: "funnel",
      funnel: {
        stages: {
          from: "metrics",
          items: [
            { metric: "failed" },
            { metric: "retried" },
            { metric: "recovered" },
          ],
        },
      },
    },
  });
}

/**
 * How fast the recovered ones recovered, week by week: how many, and the
 * median and the slowest tenth of the minutes from the first failure.
 */
function recovery(t: Text): AnalysisViewConfig {
  return analysis({
    conditions: [RECOVERED],
    groups: [
      {
        type: "DATE_HISTOGRAM",
        field: "eventTime",
        alias: "week",
        unit: "WEEK",
      },
    ],
    metrics: [
      COUNT(t.recovered),
      {
        type: "PERCENTILE",
        alias: "median",
        label: t.median,
        percentile: 50,
        expression: MINUTES_TO_RECOVER,
      },
      {
        type: "PERCENTILE",
        alias: "slowest",
        label: t.slowest,
        percentile: 90,
        expression: MINUTES_TO_RECOVER,
      },
    ],
    sort: [{ alias: "week", direction: "ASC" }],
    limit: 1000,
    chart: {
      type: "combo",
      cartesian: {
        x: "week",
        series: [
          { metric: "count", type: "bar", axis: "left" },
          { metric: "median", type: "line", axis: "right" },
          { metric: "slowest", type: "line", axis: "right" },
        ],
        // A recovery takes minutes or, when it waited on a person, weeks:
        // a log scale keeps both readable.
        yAxis: { right: { scale: "log" } },
      },
    },
  });
}

/** The analyses as the definition's system views, in one language. */
export function failureAnalyses(locale: Locale): SystemView[] {
  const t = TEXT[locale];
  const A = FAILURE_ANALYSES;
  return [
    { id: A.fate, title: t.fate, config: fate(t) },
    { id: A.concentration, title: t.concentration, config: concentration(t) },
    { id: A.errorCodes, title: t.errorCodes, config: errorCodes(t) },
    { id: A.sources, title: t.sources, config: sources(t) },
    { id: A.backlogAge, title: t.backlogAge, config: backlogAge(t) },
    { id: A.arrivals, title: t.arrivals, config: arrivals(t) },
    { id: A.calendar, title: t.calendar, config: calendar(t) },
    { id: A.retries, title: t.retries, config: retries(t) },
    { id: A.repair, title: t.repair, config: repair(t) },
    { id: A.recovery, title: t.recovery, config: recovery(t) },
  ];
}
