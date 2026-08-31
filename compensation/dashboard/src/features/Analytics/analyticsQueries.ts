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

import dayjs from "dayjs";
import {
  aggregation,
  AggregationDateUnit,
  desc,
  DomainEventStreamMetadataFields,
  filter,
} from "@ahoo-wang/fetcher-wow";
import type {
  AggregationQuery,
  FilterExpression,
  RecoverableType,
} from "@ahoo-wang/fetcher-wow";
import {
  ExecutionFailedAggregatedFields,
  ExecutionFailedStatus,
} from "../../generated";
import { RetryConditions } from "../Failed/RetryConditions.ts";

export type SnapshotAggregationQuery =
  AggregationQuery<ExecutionFailedAggregatedFields>;

export type TrendSeriesKey =
  "newFailures" | "prepared" | "retriedFailed" | "succeeded";

export interface TrendWindow {
  buckets: number[];
  end: number;
  start: number;
  timeZone: string;
  unit: AggregationDateUnit;
}

export function trendWindowKey(window: TrendWindow): string {
  return `${window.start}:${window.end}:${window.timeZone}:${window.unit}`;
}

export interface TrendRow {
  bucket: number;
  streamCount: number;
}

export interface TrendPoint {
  bucket: number;
  newFailures: number;
  prepared: number;
  retriedFailed: number;
  succeeded: number;
}

export interface TrendSummary {
  netBacklog: number;
  newFailures: number;
  prepared: number;
  retriedFailed: number;
  retrySuccess: number | null;
  succeeded: number;
}

export type TrendRowsBySeries = Record<TrendSeriesKey, TrendRow[]>;

export function summarizeTrend(points: TrendPoint[]): TrendSummary {
  const totals = points.reduce(
    (sum, point) => ({
      newFailures: sum.newFailures + point.newFailures,
      prepared: sum.prepared + point.prepared,
      retriedFailed: sum.retriedFailed + point.retriedFailed,
      succeeded: sum.succeeded + point.succeeded,
    }),
    { newFailures: 0, prepared: 0, retriedFailed: 0, succeeded: 0 },
  );
  const retryOutcomes = totals.retriedFailed + totals.succeeded;
  return {
    ...totals,
    netBacklog: totals.newFailures - totals.succeeded,
    retrySuccess: retryOutcomes === 0 ? null : totals.succeeded / retryOutcomes,
  };
}

export const TREND_EVENTS = {
  newFailures: "execution_failed_created",
  prepared: "compensation_prepared",
  retriedFailed: "execution_failed_applied",
  succeeded: "execution_success_applied",
} as const;
export const MAX_TREND_DAYS = 1_000;

export function createTrendWindow(
  startDate: Date,
  endDate: Date,
  timeZone: string,
): TrendWindow {
  const start = dayjs(startDate).startOf("day");
  const end = dayjs(endDate).startOf("day").add(1, "day");
  if (end.diff(start, "day") > MAX_TREND_DAYS) {
    throw new Error(`Date range cannot exceed ${MAX_TREND_DAYS} days.`);
  }
  const buckets: number[] = [];
  for (
    let bucket = start;
    bucket.isBefore(end);
    bucket = bucket.add(1, "day")
  ) {
    buckets.push(bucket.valueOf());
  }

  return {
    buckets,
    end: end.valueOf(),
    start: start.valueOf(),
    timeZone,
    unit: AggregationDateUnit.DAY,
  };
}

function createEventTrendQuery(
  window: TrendWindow,
  eventName: string,
): AggregationQuery<string> {
  return {
    filter: filter.and([
      filter.gte(DomainEventStreamMetadataFields.CREATE_TIME, window.start),
      filter.lt(DomainEventStreamMetadataFields.CREATE_TIME, window.end),
      filter.elementMatch(
        DomainEventStreamMetadataFields.BODY,
        filter.eq("name", eventName),
      ),
    ]),
    groupBy: [
      aggregation.dateHistogram(DomainEventStreamMetadataFields.CREATE_TIME, {
        unit: window.unit,
        alias: "bucket",
        timeZone: window.timeZone,
      }),
    ],
    metrics: [aggregation.count("streamCount")],
    limit: window.buckets.length,
  };
}

export function createEventTrendQueries(
  window: TrendWindow,
): Record<TrendSeriesKey, AggregationQuery<string>> {
  return {
    newFailures: createEventTrendQuery(window, TREND_EVENTS.newFailures),
    prepared: createEventTrendQuery(window, TREND_EVENTS.prepared),
    retriedFailed: createEventTrendQuery(window, TREND_EVENTS.retriedFailed),
    succeeded: createEventTrendQuery(window, TREND_EVENTS.succeeded),
  };
}

export function mergeTrendRows(
  window: TrendWindow,
  rows: TrendRowsBySeries,
): TrendPoint[] {
  const counts = {
    newFailures: new Map(
      rows.newFailures.map((row) => [row.bucket, row.streamCount]),
    ),
    prepared: new Map(
      rows.prepared.map((row) => [row.bucket, row.streamCount]),
    ),
    retriedFailed: new Map(
      rows.retriedFailed.map((row) => [row.bucket, row.streamCount]),
    ),
    succeeded: new Map(
      rows.succeeded.map((row) => [row.bucket, row.streamCount]),
    ),
  };

  return window.buckets.map((bucket) => ({
    bucket,
    newFailures: counts.newFailures.get(bucket) ?? 0,
    prepared: counts.prepared.get(bucket) ?? 0,
    retriedFailed: counts.retriedFailed.get(bucket) ?? 0,
    succeeded: counts.succeeded.get(bucket) ?? 0,
  }));
}

export interface PressureClusterKey {
  errorCode: string;
  contextName: string;
  processorName: string;
  functionName: string;
  functionKind: string;
}

export interface PressureClusterRow extends PressureClusterKey {
  currentCount: number;
  oldestExecuteAt: number | null;
  nextRetryAt: number | null;
}

export interface PressureStatusRow extends PressureClusterKey {
  status: ExecutionFailedStatus;
  statusCount: number;
}

export interface PressureCluster extends PressureClusterRow {
  failedCount: number;
  preparedCount: number;
}

export interface SnapshotSummary {
  actionableNow: number;
  activeTotal: number;
  newerThanRange: number;
  olderThanRange: number;
  selectedInRange: number;
  stockTruncated: boolean;
  timedOut: number;
  unrecoverable: number;
}

export interface RecoverabilityRow {
  recoverable: RecoverableType;
  count: number;
}

export interface RetryHistogramRow {
  retries: number;
  count: number;
}

export interface RetryDistribution {
  buckets: Array<{ key: "0" | "1–2" | "3–5" | "6+"; count: number }>;
  truncated: boolean;
}

const activeFilter = filter.isIn(ExecutionFailedAggregatedFields.STATE_STATUS, [
  ExecutionFailedStatus.FAILED,
  ExecutionFailedStatus.PREPARED,
]);
const RETRY_HISTOGRAM_LIMIT = 1_000;

const clusterId = (key: PressureClusterKey) =>
  JSON.stringify([
    key.errorCode,
    key.contextName,
    key.processorName,
    key.functionName,
    key.functionKind,
  ]);

const countMetric = () => aggregation.count("count");
const withSnapshotWindow = (
  window: TrendWindow,
  expression: FilterExpression<ExecutionFailedAggregatedFields>,
) =>
  filter.and([
    filter.gte(ExecutionFailedAggregatedFields.STATE_EXECUTE_AT, window.start),
    filter.lt(ExecutionFailedAggregatedFields.STATE_EXECUTE_AT, window.end),
    expression,
  ]);

export function createSnapshotSummaryQueries(
  now: number,
  window: TrendWindow,
): Record<
  | "actionableNow"
  | "activeTotal"
  | "newerThanRange"
  | "selectedInRange"
  | "timedOut"
  | "unrecoverable",
  SnapshotAggregationQuery
> {
  return {
    actionableNow: {
      filter: withSnapshotWindow(
        window,
        RetryConditions.nextRetryCondition(
          now,
        ) as FilterExpression<ExecutionFailedAggregatedFields>,
      ),
      metrics: [countMetric()],
    },
    activeTotal: {
      filter: activeFilter,
      metrics: [countMetric()],
    },
    selectedInRange: {
      filter: withSnapshotWindow(window, activeFilter),
      metrics: [countMetric()],
    },
    newerThanRange: {
      filter: filter.and([
        filter.gte(
          ExecutionFailedAggregatedFields.STATE_EXECUTE_AT,
          window.end,
        ),
        activeFilter,
      ]),
      metrics: [countMetric()],
    },
    timedOut: {
      filter: withSnapshotWindow(
        window,
        filter.and([
          filter.eq(
            ExecutionFailedAggregatedFields.STATE_STATUS,
            ExecutionFailedStatus.PREPARED,
          ),
          filter.lte(
            ExecutionFailedAggregatedFields.STATE_RETRY_STATE_TIMEOUT_AT,
            now,
          ),
        ]),
      ),
      metrics: [countMetric()],
    },
    unrecoverable: {
      filter: withSnapshotWindow(
        window,
        RetryConditions.unrecoverableCondition as FilterExpression<ExecutionFailedAggregatedFields>,
      ),
      metrics: [countMetric()],
    },
  } satisfies Record<string, SnapshotAggregationQuery>;
}

export function createPressureQuery(
  window: TrendWindow,
): SnapshotAggregationQuery {
  return {
    filter: withSnapshotWindow(window, activeFilter),
    groupBy: [
      aggregation.terms(
        ExecutionFailedAggregatedFields.STATE_ERROR_ERROR_CODE,
        "errorCode",
      ),
      aggregation.terms(
        ExecutionFailedAggregatedFields.STATE_FUNCTION_CONTEXT_NAME,
        "contextName",
      ),
      aggregation.terms(
        ExecutionFailedAggregatedFields.STATE_FUNCTION_PROCESSOR_NAME,
        "processorName",
      ),
      aggregation.terms(
        ExecutionFailedAggregatedFields.STATE_FUNCTION_NAME,
        "functionName",
      ),
      aggregation.terms(
        ExecutionFailedAggregatedFields.STATE_FUNCTION_FUNCTION_KIND,
        "functionKind",
      ),
    ],
    metrics: [
      aggregation.count("currentCount"),
      aggregation.min(
        aggregation.field(ExecutionFailedAggregatedFields.STATE_EXECUTE_AT),
        "oldestExecuteAt",
      ),
      aggregation.min(
        aggregation.field(
          ExecutionFailedAggregatedFields.STATE_RETRY_STATE_NEXT_RETRY_AT,
        ),
        "nextRetryAt",
      ),
    ],
    sort: [desc("currentCount")],
    limit: 5,
  };
}

export function createPressureStatusQuery(
  keys: PressureClusterKey[],
  window: TrendWindow,
): SnapshotAggregationQuery {
  if (keys.length === 0) {
    throw new Error("Pressure status query requires at least one cluster.");
  }
  const clusterFilter = filter.or(
    keys.map((key) =>
      filter.and([
        filter.eq(
          ExecutionFailedAggregatedFields.STATE_ERROR_ERROR_CODE,
          key.errorCode,
        ),
        filter.eq(
          ExecutionFailedAggregatedFields.STATE_FUNCTION_CONTEXT_NAME,
          key.contextName,
        ),
        filter.eq(
          ExecutionFailedAggregatedFields.STATE_FUNCTION_PROCESSOR_NAME,
          key.processorName,
        ),
        filter.eq(
          ExecutionFailedAggregatedFields.STATE_FUNCTION_NAME,
          key.functionName,
        ),
        filter.eq(
          ExecutionFailedAggregatedFields.STATE_FUNCTION_FUNCTION_KIND,
          key.functionKind,
        ),
      ]),
    ),
  );
  return {
    filter: withSnapshotWindow(
      window,
      filter.and([activeFilter, clusterFilter]),
    ),
    groupBy: [
      aggregation.terms(
        ExecutionFailedAggregatedFields.STATE_ERROR_ERROR_CODE,
        "errorCode",
      ),
      aggregation.terms(
        ExecutionFailedAggregatedFields.STATE_FUNCTION_CONTEXT_NAME,
        "contextName",
      ),
      aggregation.terms(
        ExecutionFailedAggregatedFields.STATE_FUNCTION_PROCESSOR_NAME,
        "processorName",
      ),
      aggregation.terms(
        ExecutionFailedAggregatedFields.STATE_FUNCTION_NAME,
        "functionName",
      ),
      aggregation.terms(
        ExecutionFailedAggregatedFields.STATE_FUNCTION_FUNCTION_KIND,
        "functionKind",
      ),
      aggregation.terms(ExecutionFailedAggregatedFields.STATE_STATUS, "status"),
    ],
    metrics: [aggregation.count("statusCount")],
  };
}

export function createRecoverabilityQuery(
  window: TrendWindow,
): SnapshotAggregationQuery {
  return {
    filter: withSnapshotWindow(window, activeFilter),
    groupBy: [
      aggregation.terms(
        ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
        "recoverable",
      ),
    ],
    metrics: [countMetric()],
  };
}

export function createRetryHistogramQuery(
  window: TrendWindow,
): SnapshotAggregationQuery {
  return {
    filter: withSnapshotWindow(window, activeFilter),
    groupBy: [
      aggregation.histogram(
        ExecutionFailedAggregatedFields.STATE_RETRY_STATE_RETRIES,
        { interval: 1, alias: "retries" },
      ),
    ],
    metrics: [countMetric()],
    limit: RETRY_HISTOGRAM_LIMIT,
  };
}

export function mergePressureRows(
  rows: PressureClusterRow[],
  statuses: PressureStatusRow[],
): PressureCluster[] {
  const counts = new Map<
    string,
    Partial<Record<ExecutionFailedStatus, number>>
  >();
  for (const status of statuses) {
    const clusterCounts = counts.get(clusterId(status)) ?? {};
    clusterCounts[status.status] = status.statusCount;
    counts.set(clusterId(status), clusterCounts);
  }
  return rows.map((row) => {
    const clusterCounts = counts.get(clusterId(row));
    return {
      ...row,
      failedCount: clusterCounts?.[ExecutionFailedStatus.FAILED] ?? 0,
      preparedCount: clusterCounts?.[ExecutionFailedStatus.PREPARED] ?? 0,
    };
  });
}

export function bucketRetryRows(rows: RetryHistogramRow[]): RetryDistribution {
  if (rows.length >= RETRY_HISTOGRAM_LIMIT) {
    return { buckets: [], truncated: true };
  }
  const buckets: RetryDistribution["buckets"] = [
    { key: "0", count: 0 },
    { key: "1–2", count: 0 },
    { key: "3–5", count: 0 },
    { key: "6+", count: 0 },
  ];
  for (const row of rows) {
    const bucket =
      row.retries === 0 ? 0 : row.retries <= 2 ? 1 : row.retries <= 5 ? 2 : 3;
    buckets[bucket].count += row.count;
  }
  return { buckets, truncated: false };
}
