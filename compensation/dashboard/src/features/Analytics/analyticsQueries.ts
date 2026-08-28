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

import { aggregation, desc, filter } from "@ahoo-wang/fetcher-wow";
import type {
  AggregationQuery,
  FilterExpression,
} from "@ahoo-wang/fetcher-wow";
import {
  ExecutionFailedAggregatedFields,
  ExecutionFailedStatus,
} from "../../generated";
import { RetryConditions } from "../Failed/RetryConditions.ts";

export type SnapshotAggregationQuery =
  AggregationQuery<ExecutionFailedAggregatedFields>;

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
  timedOut: number;
  unrecoverable: number;
}

export interface RecoverabilityRow {
  recoverable: string;
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

const clusterId = (key: PressureClusterKey) =>
  JSON.stringify([
    key.errorCode,
    key.contextName,
    key.processorName,
    key.functionName,
    key.functionKind,
  ]);

const countMetric = () => aggregation.count("count");

export function createSnapshotSummaryQueries(
  now: number,
): Record<
  "actionableNow" | "timedOut" | "unrecoverable",
  SnapshotAggregationQuery
> {
  return {
    actionableNow: {
      filter: RetryConditions.nextRetryCondition(
        now,
      ) as FilterExpression<ExecutionFailedAggregatedFields>,
      metrics: [countMetric()],
    },
    timedOut: {
      filter: filter.and([
        filter.eq(
          ExecutionFailedAggregatedFields.STATE_STATUS,
          ExecutionFailedStatus.PREPARED,
        ),
        filter.lte(
          ExecutionFailedAggregatedFields.STATE_RETRY_STATE_TIMEOUT_AT,
          now,
        ),
      ]),
      metrics: [countMetric()],
    },
    unrecoverable: {
      filter:
        RetryConditions.unrecoverableCondition as FilterExpression<ExecutionFailedAggregatedFields>,
      metrics: [countMetric()],
    },
  } satisfies Record<string, SnapshotAggregationQuery>;
}

export function createPressureQuery(): SnapshotAggregationQuery {
  return {
    filter: activeFilter,
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
    limit: 10,
  };
}

export function createPressureStatusQuery(
  keys: PressureClusterKey[],
): SnapshotAggregationQuery {
  if (keys.length === 0) {
    throw new Error("Pressure status query requires at least one cluster.");
  }
  return {
    filter: filter.or(
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

export function createRecoverabilityQuery(): SnapshotAggregationQuery {
  return {
    filter: activeFilter,
    groupBy: [
      aggregation.terms(
        ExecutionFailedAggregatedFields.STATE_RECOVERABLE,
        "recoverable",
      ),
    ],
    metrics: [countMetric()],
  };
}

export function createRetryHistogramQuery(): SnapshotAggregationQuery {
  return {
    filter: activeFilter,
    groupBy: [
      aggregation.histogram(
        ExecutionFailedAggregatedFields.STATE_RETRY_STATE_RETRIES,
        { interval: 1, alias: "retries" },
      ),
    ],
    metrics: [countMetric()],
    limit: 10_000,
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
  if (rows.length >= 10_000) {
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
