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

import { AggregationDateUnit } from "@ahoo-wang/wow-client";
import { defineView, systemInstanceId } from "@ahoo-wang/wow-view-engine";
import { ACTIVITY_ANALYSIS_VIEWS } from "./activityAnalyses.ts";
import { EXECUTION_HISTORY_DESCRIPTOR } from "./descriptors.ts";
import { textKeys, type Words } from "./textKeys.ts";

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

export const EXECUTION_HISTORY_WORDS = {
  en: {
    title: "Execution history",
    streams: "All event streams",
    groupStream: "Event stream",
    groupAppended: "Appended",
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
    streams: "全部事件流",
    groupStream: "事件流",
    groupAppended: "追加的内容",
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
} satisfies Words;

const t = textKeys("executionHistory", EXECUTION_HISTORY_WORDS.en);

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
 * streams' own workbench (`/events`).
 */
export const executionHistory = defineView(EXECUTION_HISTORY_DESCRIPTOR, {
  id: EXECUTION_HISTORY,
  source: EXECUTION_HISTORY_SOURCE,
  title: t.title,
  recordNoun: t.recordNoun,
  // When a stream was written: what the overview's window reads it by.
  timeField: "createTime",
  // How one stream reads in its detail: whose it is and when, then its
  // events.
  fieldGroups: [
    {
      id: "stream",
      label: t.groupStream,
      fields: ["id", "aggregateId", "version", "createTime", "commandId"],
    },
    // Not 「事件」 again: the one field in it is already called so.
    { id: "events", label: t.groupAppended, fields: ["body"] },
  ],
  fields: {
    // The row key: sortable, as a stable page order needs.
    id: { label: t.id, cell: "copyable", analysis: false },
    // How many executions a day's commands were about: counted, never a
    // category.
    aggregateId: {
      label: t.aggregateId,
      cell: "copyable",
      analysis: { groups: [] },
    },
    version: { label: t.version, analysis: false },
    createTime: {
      label: t.createTime,
      analysis: {
        dateUnits: [
          AggregationDateUnit.HOUR,
          AggregationDateUnit.DAY,
          AggregationDateUnit.WEEK,
          AggregationDateUnit.MONTH,
        ],
      },
    },
    commandId: {
      label: t.commandId,
      cell: "copyable",
      sortable: false,
      analysis: false,
    },
    body: {
      label: t.body,
      operators: ["ELEMENT_MATCH"],
      elementTitle: "bodyType",
      elements: {
        // The events' make-up, by type — the name an event is read by.
        bodyType: {
          label: t.bodyType,
          options: Object.fromEntries(
            EVENTS.map(([type, key]) => [`${API}.${type}`, t[key]]),
          ),
        },
        name: t.name,
        revision: { label: t.revision, analysis: false },
        id: { label: t.eventId, analysis: false },
      },
    },
  },
  record: { layouts: ["table"] },
  views: [
    // The streams' own workbench opens on this one: every execution's
    // streams, the newest first, each saying whose it is.
    {
      id: "streams",
      title: t.streams,
      config: {
        kind: "record",
        filter: { op: "and", children: [] },
        filterMode: "simple",
        refresh: { interval: null },
        sort: [{ field: "createTime", direction: "DESC" }],
        pageSize: 20,
        layout: "table",
        summaries: [],
        // The last column is the one a narrow table keeps pinned (D13):
        // what happened, rather than the command's ID.
        table: {
          columns: [
            "createTime",
            "aggregateId",
            "version",
            "commandId",
            "body",
          ].map((field) => ({ field })),
        },
        card: { title: "aggregateId", fields: ["body", "createTime"] },
      },
    },
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
    // What the compensation did, and what people did to it.
    ...ACTIVITY_ANALYSIS_VIEWS,
  ],
});
