/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)]
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

import { useEffect, useState } from "react";
import { aggregateExecutionFailedSnapshots } from "../../services";
import {
  bucketRetryRows,
  createPressureQuery,
  createPressureStatusQuery,
  createRecoverabilityQuery,
  createRetryHistogramQuery,
  createSnapshotSummaryQueries,
  mergePressureRows,
} from "./analyticsQueries.ts";
import type {
  PressureCluster,
  PressureClusterRow,
  PressureStatusRow,
  RecoverabilityRow,
  RetryDistribution,
  RetryHistogramRow,
  SnapshotSummary,
  TrendWindow,
} from "./analyticsQueries.ts";

export interface AnalyticsSection<T> {
  data?: T;
  error?: Error;
  loading: boolean;
  updatedAt?: number;
}

export interface SnapshotAnalyticsResult {
  pressure: AnalyticsSection<PressureCluster[]>;
  recoverability: AnalyticsSection<RecoverabilityRow[]>;
  retries: AnalyticsSection<RetryDistribution>;
  summary: AnalyticsSection<SnapshotSummary>;
}

interface CountRow {
  count: number;
}

export function useSnapshotAnalytics(
  window: TrendWindow,
  refreshToken: number,
): SnapshotAnalyticsResult {
  const [state, setState] = useState<SnapshotAnalyticsResult>({
    pressure: { loading: true },
    recoverability: { loading: true },
    retries: { loading: true },
    summary: { loading: true },
  });

  useEffect(() => {
    const abortController = new AbortController();
    const now = Date.now();

    const loads = {
      summary: loadSummary(now, window, abortController),
      pressure: loadPressure(window, abortController),
      recoverability: loadRecoverability(window, abortController),
      retries: loadRetries(window, abortController),
    };

    queueMicrotask(() => {
      if (!abortController.signal.aborted) {
        setState(markSnapshotLoading);
      }
    });

    const settle = <T>(
      load: Promise<T>,
      update: (
        current: SnapshotAnalyticsResult,
        result: PromiseSettledResult<T>,
      ) => SnapshotAnalyticsResult,
    ) => {
      void load.then(
        (value) => {
          if (!abortController.signal.aborted) {
            setState((current) =>
              update(current, { status: "fulfilled", value }),
            );
          }
        },
        (reason: unknown) => {
          if (!abortController.signal.aborted) {
            setState((current) =>
              update(current, { status: "rejected", reason }),
            );
          }
        },
      );
    };

    settle(loads.summary, (current, result) => ({
      ...current,
      summary: settleSnapshotSection(current.summary, result, now),
    }));
    settle(loads.pressure, (current, result) => ({
      ...current,
      pressure: settleSnapshotSection(current.pressure, result, now),
    }));
    settle(loads.recoverability, (current, result) => ({
      ...current,
      recoverability: settleSnapshotSection(
        current.recoverability,
        result,
        now,
      ),
    }));
    settle(loads.retries, (current, result) => ({
      ...current,
      retries: settleSnapshotSection(current.retries, result, now),
    }));

    return () => abortController.abort();
  }, [window, refreshToken]);

  return state;
}

async function loadSummary(
  now: number,
  window: TrendWindow,
  abortController: AbortController,
): Promise<SnapshotSummary> {
  const queries = createSnapshotSummaryQueries(now, window);
  const [actionableNow, timedOut, unrecoverable, activeTotal, olderThanRange] =
    await Promise.all([
      aggregateExecutionFailedSnapshots<CountRow>(
        queries.actionableNow,
        undefined,
        abortController,
      ),
      aggregateExecutionFailedSnapshots<CountRow>(
        queries.timedOut,
        undefined,
        abortController,
      ),
      aggregateExecutionFailedSnapshots<CountRow>(
        queries.unrecoverable,
        undefined,
        abortController,
      ),
      aggregateExecutionFailedSnapshots<CountRow>(
        queries.activeTotal,
        undefined,
        abortController,
      ),
      aggregateExecutionFailedSnapshots<CountRow>(
        queries.olderThanRange,
        undefined,
        abortController,
      ),
    ]);
  return {
    actionableNow: actionableNow[0]?.count ?? 0,
    activeTotal: activeTotal[0]?.count ?? 0,
    olderThanRange: olderThanRange[0]?.count ?? 0,
    timedOut: timedOut[0]?.count ?? 0,
    unrecoverable: unrecoverable[0]?.count ?? 0,
  };
}

async function loadPressure(
  window: TrendWindow,
  abortController: AbortController,
): Promise<PressureCluster[]> {
  const rows = await aggregateExecutionFailedSnapshots<PressureClusterRow>(
    createPressureQuery(window),
    undefined,
    abortController,
  );
  if (rows.length === 0) {
    return [];
  }
  const statuses = await aggregateExecutionFailedSnapshots<PressureStatusRow>(
    createPressureStatusQuery(rows, window),
    undefined,
    abortController,
  );
  return mergePressureRows(rows, statuses);
}

async function loadRecoverability(
  window: TrendWindow,
  abortController: AbortController,
): Promise<RecoverabilityRow[]> {
  return aggregateExecutionFailedSnapshots<RecoverabilityRow>(
    createRecoverabilityQuery(window),
    undefined,
    abortController,
  );
}

async function loadRetries(
  window: TrendWindow,
  abortController: AbortController,
): Promise<RetryDistribution> {
  const rows = await aggregateExecutionFailedSnapshots<RetryHistogramRow>(
    createRetryHistogramQuery(window),
    undefined,
    abortController,
  );
  return bucketRetryRows(rows);
}

function markSnapshotLoading(
  current: SnapshotAnalyticsResult,
): SnapshotAnalyticsResult {
  const loading = <T>(section: AnalyticsSection<T>): AnalyticsSection<T> => ({
    ...section,
    error: undefined,
    loading: true,
  });
  return {
    pressure: loading(current.pressure),
    recoverability: loading(current.recoverability),
    retries: loading(current.retries),
    summary: loading(current.summary),
  };
}

function settleSnapshotSection<T>(
  section: AnalyticsSection<T>,
  result: PromiseSettledResult<T>,
  updatedAt: number,
): AnalyticsSection<T> {
  if (result.status === "fulfilled") {
    return { data: result.value, loading: false, updatedAt };
  }
  if (
    typeof result.reason === "object" &&
    result.reason !== null &&
    "name" in result.reason &&
    result.reason.name === "AbortError"
  ) {
    return { ...section, loading: false };
  }
  return {
    ...section,
    error:
      result.reason instanceof Error
        ? result.reason
        : new Error(String(result.reason)),
    loading: false,
  };
}
