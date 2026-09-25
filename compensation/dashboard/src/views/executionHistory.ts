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
import {
  systemInstanceId,
  type DataViewDefinition,
} from "@ahoo-wang/wow-view-engine";
import type { Locale } from "@/i18n.tsx";

export const EXECUTION_HISTORY = "execution-history";

/**
 * The source key of the `execution_failed` event stream, apart from the
 * snapshot's: the engine resolves a source by this key.
 */
export const EXECUTION_HISTORY_SOURCE = "execution_failed/event";

/** The one system view: an execution's streams, the newest first. */
export const EXECUTION_HISTORY_VIEW = systemInstanceId(
  EXECUTION_HISTORY,
  "history",
);

/**
 * The four events an execution's outcomes are counted by, as the service
 * names them in `body[].name`: a failure coming in, a retry prepared, and a
 * retry's two endings. A stream is counted once for the event it holds.
 */
export const OUTCOME_EVENTS = {
  newFailures: "execution_failed_created",
  prepared: "compensation_prepared",
  retryFailed: "execution_failed_applied",
  retrySucceeded: "execution_success_applied",
} as const;

export type OutcomeMetric = keyof typeof OUTCOME_EVENTS;

/**
 * The event types of an `ExecutionFailed` stream, as the service names them
 * in `body[].bodyType`, in the order an execution's history runs.
 */
const API = "me.ahoo.wow.compensation.api";
const EVENTS = [
  ["ExecutionFailedCreated", "created"],
  ["CompensationPrepared", "prepared"],
  ["ExecutionFailedApplied", "failed"],
  ["ExecutionSuccessApplied", "succeeded"],
  ["RetrySpecApplied", "retrySpec"],
  ["RecoverableMarked", "recoverable"],
  ["FunctionChanged", "function"],
] as const;

const TEXT = {
  en: {
    title: "Execution history",
    recordNoun: "event stream",
    id: "Stream ID",
    aggregateId: "Execution ID",
    version: "Version",
    createTime: "Time",
    commandId: "Command ID",
    body: "Events",
    bodyType: "Event type",
    name: "Event name",
    revision: "Revision",
    eventId: "Event ID",
    created: "First failed",
    prepared: "Prepared for retry",
    failed: "Retry failed",
    succeeded: "Retry succeeded",
    retrySpec: "Retry spec changed",
    recoverable: "Recoverability marked",
    function: "Function changed",
  },
  "zh-CN": {
    title: "执行历史",
    recordNoun: "事件流",
    id: "事件流 ID",
    aggregateId: "执行 ID",
    version: "版本",
    createTime: "时间",
    commandId: "命令 ID",
    body: "事件",
    bodyType: "事件类型",
    name: "事件名",
    revision: "事件修订",
    eventId: "事件 ID",
    created: "首次失败",
    prepared: "准备重试",
    failed: "重试失败",
    succeeded: "重试成功",
    retrySpec: "重试规格变更",
    recoverable: "标记可恢复性",
    function: "处理函数变更",
  },
} satisfies Record<Locale, Record<string, string>>;

/**
 * The events of each stream, one row apiece: an outcome is counted by the
 * event's name, which a count can only ask of one event at a time — a
 * metric's condition is one value per row, never a match over a stream's
 * events (`analysis.metricFilter.not-scalar`). A compensation command
 * appends one event, so an event counted is a stream counted, as the old
 * overview counted them.
 */
export const OUTCOME_ELEMENTS = { path: "body" } as const;

/**
 * The `execution_failed` event stream: one execution's history in the record
 * detail — its one system view, embedded with the execution as its scope
 * (`aggregateId`) — and the overview's outcomes, counted by event name. One
 * record is one stream — what one command appended — and its events sit in
 * `body`, each read by its type (`elementTitle`), so the page fetches the
 * types and not the payloads. A board's outcome panel opens on the event
 * streams' own workbench (`/executions/events`).
 */
export function executionHistoryDefinition(locale: Locale): DataViewDefinition {
  const t = TEXT[locale];
  return {
    id: EXECUTION_HISTORY,
    title: t.title,
    recordNoun: t.recordNoun,
    kind: "data",
    source: EXECUTION_HISTORY_SOURCE,
    fields: [
      // The row key: sortable, as a stable page order needs.
      {
        name: "id",
        label: t.id,
        kind: "string",
        sortable: true,
        cell: "copyable",
      },
      { name: "aggregateId", label: t.aggregateId, kind: "string" },
      { name: "version", label: t.version, kind: "number", sortable: true },
      {
        name: "createTime",
        label: t.createTime,
        kind: "datetime",
        sortable: true,
      },
      {
        name: "commandId",
        label: t.commandId,
        kind: "string",
        cell: "copyable",
      },
      {
        name: "body",
        label: t.body,
        kind: "elementMatch",
        operators: ["ELEMENT_MATCH"],
        elementTitle: "bodyType",
        elements: [
          {
            name: "bodyType",
            label: t.bodyType,
            kind: "enum",
            options: EVENTS.map(([type, key]) => ({
              value: `${API}.${type}`,
              label: t[key],
            })),
          },
          { name: "name", label: t.name, kind: "string" },
          { name: "revision", label: t.revision, kind: "string" },
          { name: "id", label: t.eventId, kind: "string" },
        ],
      },
    ],
    record: { rowKey: "id", paging: "paged", layouts: ["table"] },
    // What the overview asks of the streams: how many hold an event, by day.
    analysis: {
      count: true,
      // The net backlog and the retry success rate, out of the counts.
      expressions: true,
      fields: [
        {
          field: "createTime",
          groups: [AggregationGroupType.DATE_HISTOGRAM],
          functions: [AggregationFunction.MIN, AggregationFunction.MAX],
          dateUnits: [
            AggregationDateUnit.HOUR,
            AggregationDateUnit.DAY,
            AggregationDateUnit.WEEK,
            AggregationDateUnit.MONTH,
          ],
        },
      ],
      elements: [
        {
          path: "body",
          aggregations: [
            {
              field: "name",
              groups: [AggregationGroupType.TERMS],
              functions: [],
            },
          ],
        },
      ],
    },
    views: [
      {
        id: "history",
        title: t.title,
        config: {
          kind: "record",
          filter: { op: "and", children: [] },
          filterMode: "simple",
          refresh: { interval: null },
          sort: [{ field: "version", direction: "DESC" }],
          pageSize: 10,
          layout: "table",
          summaries: [],
          table: {
            columns: ["version", "body", "createTime", "commandId"].map(
              (field) => ({ field }),
            ),
          },
          // Only a table is offered here; a card would read the same.
          card: { title: "version", fields: ["body", "createTime"] },
        },
      },
    ],
  };
}
