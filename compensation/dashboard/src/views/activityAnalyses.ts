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
  AnalysisViewConfig,
  FilterNode,
  SystemView,
} from "@ahoo-wang/wow-view-engine";
import type { Locale } from "@/i18n.tsx";

/**
 * The compensation's activity, out of the `execution_failed` event streams
 * (2026-09-27, from the test service's data): what the service did, day by
 * day, and what people did to it.
 *
 * What the streams can be asked is set by the service's aggregation (checked
 * against it): a stream is one command's, so a day counts streams and the
 * executions they touched; an event's name is counted only with the events
 * expanded (`elements`), and an expanded event no longer reaches its
 * stream's `createTime` — so a day's make-up by event is one chart per
 * event (the overview's outcome figures), and the make-up here is of a
 * window's whole. Metric conditions cannot match an event of the stream
 * (`METRIC_FILTER_ELEMENT_MATCH`), and the events' payloads are not
 * declared for aggregation in this deployment's schema.
 */

/** The ids of the analyses, as the definition's system views. */
export const ACTIVITY_ANALYSES = {
  activity: "activity",
  eventMix: "event-mix",
  interventions: "interventions",
} as const;

/** Where the service's event types live (`body.bodyType`). */
const API = "me.ahoo.wow.compensation.api";

/** The events a person's decision appends, never the scheduler's. */
export const INTERVENTION_EVENTS = [
  "FunctionChanged",
  "RetrySpecApplied",
  "RecoverableMarked",
].map((type) => `${API}.${type}`);

const TEXT = {
  en: {
    activity: "Compensation activity per day",
    eventMix: "Events by type",
    interventions: "Operator interventions",
    streams: "Event streams",
    executions: "Executions touched",
    events: "Events",
  },
  "zh-CN": {
    activity: "每日补偿活动",
    eventMix: "事件构成",
    interventions: "人工干预",
    streams: "事件流",
    executions: "涉及的执行",
    events: "事件数",
  },
} satisfies Record<Locale, Record<string, string>>;

type Text = (typeof TEXT)[Locale];

function analysis(
  config: Partial<AnalysisViewConfig> &
    Pick<AnalysisViewConfig, "groups" | "metrics" | "chart">,
): AnalysisViewConfig {
  return {
    kind: "analysis",
    filter: { op: "and", children: [] },
    filterMode: "simple",
    refresh: { interval: null },
    sort: [],
    limit: 100,
    layout: "chart",
    table: { columns: [] },
    ...config,
  };
}

/**
 * What the compensation did a day: the commands it took (one stream each)
 * and how many executions they were about.
 */
function activity(t: Text): AnalysisViewConfig {
  return analysis({
    groups: [
      {
        type: "DATE_HISTOGRAM",
        field: "createTime",
        alias: "day",
        unit: "DAY",
      },
    ],
    metrics: [
      { type: "COUNT", alias: "streams", label: t.streams },
      {
        type: "DISTINCT_COUNT",
        alias: "executions",
        label: t.executions,
        expression: { type: "FIELD", field: "aggregateId" },
      },
    ],
    sort: [{ alias: "day", direction: "ASC" }],
    limit: 1000,
    chart: {
      type: "combo",
      cartesian: {
        x: "day",
        series: [
          { metric: "streams", type: "bar", axis: "left" },
          { metric: "executions", type: "line", axis: "right" },
        ],
      },
    },
  });
}

/** The events of a window, by type: its whole make-up. */
function eventMix(t: Text, only?: FilterNode): AnalysisViewConfig {
  return analysis({
    elements: [
      {
        path: "body",
        ...(only ? { filter: { op: "and", children: [only] } } : {}),
      },
    ],
    // By type, which the definition names in words (「重试失败」).
    groups: [{ type: "TERMS", field: "body.bodyType", alias: "event" }],
    metrics: [{ type: "COUNT", alias: "count", label: t.events }],
    sort: [{ alias: "count", direction: "DESC" }],
    limit: 20,
    chart: {
      type: "bar",
      cartesian: {
        x: "event",
        series: [{ metric: "count" }],
        orientation: "horizontal",
      },
    },
  });
}

/** The analyses as the definition's system views, in one language. */
export function activityAnalyses(locale: Locale): SystemView[] {
  const t = TEXT[locale];
  const A = ACTIVITY_ANALYSES;
  return [
    { id: A.activity, title: t.activity, config: activity(t) },
    { id: A.eventMix, title: t.eventMix, config: eventMix(t) },
    {
      id: A.interventions,
      title: t.interventions,
      config: eventMix(t, {
        field: "body.bodyType",
        operator: "IN",
        value: INTERVENTION_EVENTS,
      }),
    },
  ];
}
