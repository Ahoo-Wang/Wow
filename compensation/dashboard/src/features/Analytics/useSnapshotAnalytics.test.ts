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

import { act, renderHook, waitFor } from "@testing-library/react";
import { AggregationDateUnit } from "@ahoo-wang/fetcher-wow";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PressureClusterRow, TrendWindow } from "./analyticsQueries.ts";
import { useSnapshotAnalytics } from "./useSnapshotAnalytics.ts";

const mocks = vi.hoisted(() => ({
  aggregate: vi.fn(),
}));

vi.mock("../../services", () => ({
  aggregateExecutionFailedSnapshots: mocks.aggregate,
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((nextResolve, nextReject) => {
    resolve = nextResolve;
    reject = nextReject;
  });
  return { promise, reject, resolve };
}

function isQuery(
  query: { groupBy?: Array<{ alias?: string }> } | undefined,
  alias: string,
) {
  return query?.groupBy?.some((groupBy) => groupBy.alias === alias);
}

function hasMetric(query: unknown, alias: string) {
  const metrics = (query as { metrics?: Array<{ alias?: string }> } | undefined)
    ?.metrics;
  return metrics?.some((metric) => metric.alias === alias) ?? false;
}

const isSummaryQuery = (query: unknown) => hasMetric(query, "actionableNow");
const isRetryQuery = (query: unknown) => hasMetric(query, "sixPlus");

function summaryRow(count: number) {
  return {
    actionableNow: count,
    activeTotal: count,
    newerThanRange: 0,
    olderThanRange: 0,
    selectedInRange: count,
    timedOut: count,
    unrecoverable: count,
  };
}

const retryRow = (zero: number) => [
  { oneToTwo: 0, sixPlus: 0, threeToFive: 0, zero },
];

const initialWindow: TrendWindow = {
  buckets: Array.from({ length: 7 }, (_, index) =>
    new Date(2026, 7, 22 + index).getTime(),
  ),
  end: new Date(2026, 7, 29).getTime(),
  start: new Date(2026, 7, 22).getTime(),
  timeZone: "Asia/Shanghai",
  unit: AggregationDateUnit.DAY,
};
const nextWindow: TrendWindow = {
  buckets: Array.from({ length: 3 }, (_, index) =>
    new Date(2026, 7, 26 + index).getTime(),
  ),
  end: initialWindow.end,
  start: new Date(2026, 7, 26).getTime(),
  timeZone: "Asia/Shanghai",
  unit: AggregationDateUnit.DAY,
};

describe("useSnapshotAnalytics", () => {
  beforeEach(() => {
    vi.spyOn(Date, "now").mockReturnValue(
      new Date(2026, 7, 28, 10, 37).getTime(),
    );
    mocks.aggregate.mockReset();
  });

  afterEach(() => vi.restoreAllMocks());

  it("starts four snapshot requests and waits for top clusters before status mix", async () => {
    const pressure = deferred<PressureClusterRow[]>();
    mocks.aggregate.mockImplementation((query) => {
      if (isQuery(query, "errorCode")) {
        return pressure.promise;
      }
      if (isRetryQuery(query)) {
        return Promise.resolve(retryRow(0));
      }
      return Promise.resolve([summaryRow(1)]);
    });

    renderHook(() => useSnapshotAnalytics(initialWindow, 0));

    expect(mocks.aggregate).toHaveBeenCalledTimes(4);
    pressure.resolve([
      {
        errorCode: "TEST",
        contextName: "billing",
        processorName: "Processor",
        functionName: "run",
        functionKind: "EVENT",
        currentCount: 3,
        oldestExecuteAt: 1_000,
        nextRetryAt: 2_000,
      },
    ]);
    await waitFor(() => expect(mocks.aggregate).toHaveBeenCalledTimes(5));
  });

  it("settles independent sections while recoverability remains deferred", async () => {
    const recoverability = deferred<unknown[]>();
    mocks.aggregate.mockImplementation((query) => {
      if (isQuery(query, "errorCode")) {
        return Promise.resolve([]);
      }
      if (isQuery(query, "recoverable")) {
        return recoverability.promise;
      }
      if (isRetryQuery(query)) {
        return Promise.resolve(retryRow(2));
      }
      return Promise.resolve([summaryRow(3)]);
    });

    const { result } = renderHook(() => useSnapshotAnalytics(initialWindow, 0));

    await waitFor(() => {
      expect(result.current.pressure).toMatchObject({
        data: [],
        loading: false,
      });
      expect(result.current.retries).toMatchObject({ loading: false });
    });
    expect(result.current.summary).toEqual({ loading: true, error: undefined });
    expect(result.current.recoverability).toEqual({ loading: true });

    recoverability.resolve([]);
    await waitFor(() => {
      expect(result.current.summary).toMatchObject({
        data: {
          actionableNow: 3,
          activeTotal: 3,
          newerThanRange: 0,
          olderThanRange: 0,
          selectedInRange: 3,
          stockTruncated: false,
          timedOut: 3,
          unrecoverable: 3,
        },
        loading: false,
      });
      expect(result.current.recoverability).toMatchObject({
        data: [],
        loading: false,
      });
      expect(result.current.retries.data).toEqual({
        buckets: [
          { key: "0", count: 2 },
          { key: "1–2", count: 0 },
          { key: "3–5", count: 0 },
          { key: "6+", count: 0 },
        ],
      });
    });
  });

  it("derives stock partitions from one summary response", async () => {
    mocks.aggregate.mockImplementation((query) => {
      if (isQuery(query, "errorCode") || isQuery(query, "recoverable")) {
        return Promise.resolve([]);
      }
      if (isRetryQuery(query)) {
        return Promise.resolve(retryRow(0));
      }
      if (isSummaryQuery(query)) {
        return Promise.resolve([
          {
            actionableNow: 1,
            activeTotal: 15,
            newerThanRange: 7,
            olderThanRange: 3,
            selectedInRange: 5,
            timedOut: 1,
            unrecoverable: 1,
          },
        ]);
      }
      return Promise.resolve([summaryRow(1)]);
    });

    const { result } = renderHook(() => useSnapshotAnalytics(initialWindow, 0));

    await waitFor(() =>
      expect(result.current.summary.data).toMatchObject({
        activeTotal: 15,
        newerThanRange: 7,
        olderThanRange: 3,
        selectedInRange: 5,
        stockTruncated: false,
      }),
    );
  });

  it("marks stock unavailable when partitions are inconsistent", async () => {
    mocks.aggregate.mockImplementation((query) => {
      if (isQuery(query, "errorCode") || isQuery(query, "recoverable")) {
        return Promise.resolve([]);
      }
      if (isRetryQuery(query)) {
        return Promise.resolve(retryRow(0));
      }
      if (isSummaryQuery(query)) {
        return Promise.resolve([
          {
            actionableNow: 1,
            activeTotal: 10,
            newerThanRange: 4,
            olderThanRange: 0,
            selectedInRange: 8,
            timedOut: 1,
            unrecoverable: 1,
          },
        ]);
      }
      return Promise.resolve([summaryRow(1)]);
    });

    const { result } = renderHook(() => useSnapshotAnalytics(initialWindow, 0));

    await waitFor(() =>
      expect(result.current.summary.data?.stockTruncated).toBe(true),
    );
  });

  it("marks stock unavailable when total exceeds the partition counts", async () => {
    mocks.aggregate.mockImplementation((query) => {
      if (isQuery(query, "errorCode") || isQuery(query, "recoverable")) {
        return Promise.resolve([]);
      }
      if (isRetryQuery(query)) {
        return Promise.resolve(retryRow(0));
      }
      if (isSummaryQuery(query)) {
        return Promise.resolve([
          {
            actionableNow: 0,
            activeTotal: 1,
            newerThanRange: 0,
            olderThanRange: 0,
            selectedInRange: 0,
            timedOut: 0,
            unrecoverable: 0,
          },
        ]);
      }
      return Promise.resolve([summaryRow(0)]);
    });

    const { result } = renderHook(() => useSnapshotAnalytics(initialWindow, 0));

    await waitFor(() =>
      expect(result.current.summary.data).toMatchObject({
        activeTotal: 1,
        newerThanRange: 0,
        olderThanRange: 0,
        selectedInRange: 0,
        stockTruncated: true,
      }),
    );
  });

  it("hides data from the previous window while the new window loads", async () => {
    mocks.aggregate.mockImplementation((query) => {
      if (isQuery(query, "errorCode") || isQuery(query, "recoverable")) {
        return Promise.resolve([]);
      }
      if (isRetryQuery(query)) {
        return Promise.resolve(retryRow(0));
      }
      return Promise.resolve([summaryRow(1)]);
    });
    const { result, rerender } = renderHook(
      ({ window }: { window: TrendWindow }) => useSnapshotAnalytics(window, 0),
      { initialProps: { window: initialWindow } },
    );
    await waitFor(() => expect(result.current.summary.data).toBeDefined());

    mocks.aggregate.mockImplementation(() => new Promise(() => undefined));
    await act(async () => {
      rerender({ window: nextWindow });
      await Promise.resolve();
    });

    for (const section of Object.values(result.current)) {
      expect(section).toEqual({ loading: true });
    }
  });

  it("keeps the last coherent stock pair until both refreshed sections settle", async () => {
    let refresh = 0;
    const refreshedSummary = deferred<unknown[]>();
    const refreshedRecoverability = deferred<unknown[]>();
    mocks.aggregate.mockImplementation((query) => {
      if (isQuery(query, "errorCode") || isRetryQuery(query)) {
        return Promise.resolve([]);
      }
      if (isQuery(query, "recoverable")) {
        return refresh === 0
          ? Promise.resolve([{ count: 1, recoverable: "true" }])
          : refreshedRecoverability.promise;
      }
      return refresh === 0
        ? Promise.resolve([summaryRow(1)])
        : refreshedSummary.promise;
    });

    const { result, rerender } = renderHook(
      ({ token }) => useSnapshotAnalytics(initialWindow, token),
      { initialProps: { token: 0 } },
    );
    await waitFor(() =>
      expect(result.current.summary.data?.actionableNow).toBe(1),
    );

    refresh = 1;
    rerender({ token: 1 });
    await act(async () => {
      refreshedSummary.resolve([summaryRow(2)]);
      await refreshedSummary.promise;
      await Promise.resolve();
    });

    expect(result.current.summary).toMatchObject({
      data: { actionableNow: 1 },
      loading: true,
    });
    expect(result.current.recoverability).toMatchObject({
      data: [{ count: 1, recoverable: "true" }],
      loading: true,
    });

    refreshedRecoverability.resolve([{ count: 2, recoverable: "true" }]);
    await waitFor(() => {
      expect(result.current.summary).toMatchObject({
        data: { actionableNow: 2 },
        loading: false,
      });
      expect(result.current.recoverability).toMatchObject({
        data: [{ count: 2, recoverable: "true" }],
        loading: false,
      });
    });
  });

  it("aborts the old window and starts four requests with the applied window", async () => {
    const controllers: AbortController[] = [];
    const queries: Array<{
      filter: unknown;
      metrics?: Array<{ alias?: string }>;
    }> = [];
    mocks.aggregate.mockImplementation((query, _attributes, controller) => {
      if (!controller) {
        return Promise.resolve([]);
      }
      queries.push(query);
      controllers.push(controller);
      return new Promise((_, reject) => {
        controller.signal.addEventListener("abort", () =>
          reject(new DOMException("cancelled", "AbortError")),
        );
      });
    });

    const { rerender } = renderHook(
      ({ window }: { window: TrendWindow }) => useSnapshotAnalytics(window, 0),
      { initialProps: { window: initialWindow } },
    );
    await act(async () => {
      rerender({ window: nextWindow });
      await Promise.resolve();
    });

    expect(mocks.aggregate).toHaveBeenCalledTimes(8);
    expect(controllers.slice(0, 4).every(({ signal }) => signal.aborted)).toBe(
      true,
    );
    const fullyWindowedQueries = queries.filter((query) => {
      const serializedFilter = JSON.stringify(query.filter ?? null);
      return (
        serializedFilter.includes('"op":"GTE"') &&
        serializedFilter.includes('"op":"LT"')
      );
    });
    expect(fullyWindowedQueries).toHaveLength(6);
    const summaryQueries = queries.filter(isSummaryQuery);
    expect(summaryQueries).toHaveLength(2);
    summaryQueries.forEach((query) => {
      expect(JSON.stringify(query.metrics)).toContain('"state.executeAt"');
      expect(JSON.stringify(query.filter ?? null)).not.toContain(
        '"state.executeAt"',
      );
    });
    expect(JSON.stringify(summaryQueries[0].metrics)).toContain(
      String(initialWindow.start),
    );
    expect(JSON.stringify(summaryQueries[1].metrics)).toContain(
      String(nextWindow.start),
    );
  });

  it("does not let settled stale requests overwrite refreshed sections", async () => {
    const firstLoads = Array.from({ length: 4 }, () => deferred<unknown[]>());
    let firstCall = 0;
    let refresh = 0;
    mocks.aggregate.mockImplementation((query) => {
      if (refresh === 0) {
        return firstLoads[firstCall++].promise;
      }
      if (isQuery(query, "errorCode")) {
        return Promise.resolve([]);
      }
      if (isQuery(query, "recoverable")) {
        return Promise.resolve([{ count: 2, recoverable: "true" }]);
      }
      if (isRetryQuery(query)) {
        return Promise.resolve(retryRow(2));
      }
      return Promise.resolve([summaryRow(2)]);
    });

    const { result, rerender } = renderHook(
      ({ token }) => useSnapshotAnalytics(initialWindow, token),
      { initialProps: { token: 0 } },
    );
    expect(mocks.aggregate).toHaveBeenCalledTimes(4);

    refresh = 1;
    await act(async () => {
      rerender({ token: 1 });
      await Promise.resolve();
    });
    await waitFor(() =>
      expect(result.current.summary.data?.actionableNow).toBe(2),
    );

    await act(async () => {
      firstLoads[0].resolve([summaryRow(1)]);
      firstLoads[1].resolve([]);
      firstLoads[2].resolve([{ count: 1, recoverable: "true" }]);
      firstLoads[3].resolve(retryRow(1));
      await Promise.all(firstLoads.map(({ promise }) => promise));
    });

    await waitFor(() => {
      expect(result.current.summary).toMatchObject({
        data: {
          actionableNow: 2,
          activeTotal: 2,
          newerThanRange: 0,
          olderThanRange: 0,
          selectedInRange: 2,
          stockTruncated: false,
          timedOut: 2,
          unrecoverable: 2,
        },
        loading: false,
      });
      expect(result.current.summary.error).toBeUndefined();
      expect(result.current.retries.data?.buckets[0]).toEqual({
        count: 2,
        key: "0",
      });
      expect(result.current.retries.loading).toBe(false);
      expect(result.current.retries.error).toBeUndefined();
    });
  });

  it("keeps failed section data while other sections update", async () => {
    let refresh = 0;
    mocks.aggregate.mockImplementation((query) => {
      if (isQuery(query, "errorCode")) {
        return Promise.resolve([]);
      }
      if (isQuery(query, "recoverable")) {
        return Promise.resolve([{ count: refresh + 1, recoverable: "true" }]);
      }
      if (isRetryQuery(query)) {
        return refresh === 0
          ? Promise.resolve(retryRow(1))
          : Promise.reject(new Error("retry data unavailable"));
      }
      return Promise.resolve([summaryRow(refresh + 1)]);
    });

    const { result, rerender } = renderHook(
      ({ token }) => useSnapshotAnalytics(initialWindow, token),
      { initialProps: { token: 0 } },
    );
    await waitFor(() =>
      expect(result.current.summary.data?.actionableNow).toBe(1),
    );
    const retries = result.current.retries.data;

    refresh = 1;
    rerender({ token: 1 });

    await waitFor(() =>
      expect(result.current.retries.error?.message).toBe(
        "retry data unavailable",
      ),
    );
    expect(result.current.retries.data).toBe(retries);
    expect(result.current.summary.data).toEqual({
      actionableNow: 2,
      activeTotal: 2,
      newerThanRange: 0,
      olderThanRange: 0,
      selectedInRange: 2,
      stockTruncated: false,
      timedOut: 2,
      unrecoverable: 2,
    });
    expect(result.current.recoverability.data).toEqual([
      { count: 2, recoverable: "true" },
    ]);
  });

  it("does not expose AbortError as a section error", async () => {
    let refresh = 0;
    mocks.aggregate.mockImplementation((query) => {
      if (isQuery(query, "errorCode")) {
        return Promise.resolve([]);
      }
      if (isRetryQuery(query) && refresh === 1) {
        return Promise.reject(new DOMException("cancelled", "AbortError"));
      }
      if (isRetryQuery(query)) {
        return Promise.resolve(retryRow(1));
      }
      if (isQuery(query, "recoverable")) {
        return Promise.resolve([{ count: 1, recoverable: "true" }]);
      }
      return Promise.resolve([summaryRow(1)]);
    });

    const { result, rerender } = renderHook(
      ({ token }) => useSnapshotAnalytics(initialWindow, token),
      { initialProps: { token: 0 } },
    );
    await waitFor(() => expect(result.current.retries.data).toBeDefined());

    refresh = 1;
    rerender({ token: 1 });

    await waitFor(() => expect(result.current.retries.loading).toBe(false));
    expect(result.current.retries.error).toBeUndefined();
  });
});
