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
import type { TrendRow, TrendWindow } from "./analyticsQueries.ts";
import { useEventTrend } from "./useEventTrend.ts";

const mocks = vi.hoisted(() => ({
  aggregate: vi.fn(),
}));

vi.mock("../../services", () => ({
  aggregateExecutionFailedEvents: mocks.aggregate,
}));

beforeEach(() => {
  vi.spyOn(Date, "now").mockReturnValue(
    new Date(2026, 7, 28, 10, 37).getTime(),
  );
  mocks.aggregate.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

const successRows = (streamCount: number): TrendRow[] => [
  {
    bucket: new Date(2026, 7, 28).getTime(),
    streamCount,
  },
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

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((nextResolve) => {
    resolve = nextResolve;
  });
  return { promise, resolve };
}

describe("useEventTrend", () => {
  it("loads exactly four event aggregations for one shared window", async () => {
    mocks.aggregate.mockResolvedValue([]);

    const { result } = renderHook(() => useEventTrend(initialWindow, 0));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(mocks.aggregate).toHaveBeenCalledTimes(4);
    const windows = mocks.aggregate.mock.calls.map(
      ([query, , controller]) => {
        const filters: Array<{ op: string; value: unknown }> =
          query.filter.operands;
        return {
          end: filters.find((operand) => operand.op === "LT")?.value,
          groupBy: query.groupBy,
          start: filters.find((operand) => operand.op === "GTE")?.value,
          controller,
        };
      },
    );
    expect(new Set(windows.map(({ groupBy }) => groupBy[0].timeZone)).size).toBe(
      1,
    );
    expect(new Set(windows.map(({ start, end }) => `${start}:${end}`)).size).toBe(
      1,
    );
    expect(new Set(windows.map(({ controller }) => controller)).size).toBe(1);
  });

  it("keeps the last complete trend when one refreshed series fails", async () => {
    mocks.aggregate.mockResolvedValueOnce(successRows(1));
    mocks.aggregate.mockResolvedValueOnce(successRows(2));
    mocks.aggregate.mockResolvedValueOnce(successRows(3));
    mocks.aggregate.mockResolvedValueOnce(successRows(4));
    const { result, rerender } = renderHook(
      ({ token }) => useEventTrend(initialWindow, token),
      { initialProps: { token: 0 } },
    );
    await waitFor(() => expect(result.current.data).toBeDefined());
    const lastGood = result.current.data;

    mocks.aggregate.mockRejectedValueOnce(new Error("route unavailable"));
    mocks.aggregate.mockResolvedValue([]);
    rerender({ token: 1 });

    await waitFor(() =>
      expect(result.current.error?.message).toBe("route unavailable"),
    );
    expect(result.current.data).toBe(lastGood);
  });

  it("hides data from the previous window while the new window loads", async () => {
    mocks.aggregate.mockResolvedValue(successRows(1));
    const { result, rerender } = renderHook(
      ({ window }: { window: TrendWindow }) => useEventTrend(window, 0),
      { initialProps: { window: initialWindow } },
    );
    await waitFor(() => expect(result.current.data).toBeDefined());

    mocks.aggregate.mockImplementation(() => new Promise(() => undefined));
    await act(async () => {
      rerender({ window: nextWindow });
      await Promise.resolve();
    });

    expect(result.current).toEqual({ loading: true });
  });

  it("replaces the trend only after all four refreshed series complete", async () => {
    const initialLoads = Array.from({ length: 4 }, () => deferred<TrendRow[]>());
    const refreshedLoads = Array.from({ length: 4 }, () =>
      deferred<TrendRow[]>(),
    );
    let calls = 0;
    let loads = initialLoads;
    mocks.aggregate.mockImplementation(() => loads[calls++].promise);

    const { result, rerender } = renderHook(
      ({ token }) => useEventTrend(initialWindow, token),
      { initialProps: { token: 0 } },
    );
    await waitFor(() => expect(mocks.aggregate).toHaveBeenCalledTimes(4));
    await act(async () => {
      initialLoads.forEach((load, index) => load.resolve(successRows(index + 1)));
      await Promise.all(initialLoads.map(({ promise }) => promise));
    });
    await waitFor(() => expect(result.current.data?.at(-1)?.newFailures).toBe(1));
    const lastGood = result.current.data;

    calls = 0;
    loads = refreshedLoads;
    rerender({ token: 1 });
    await waitFor(() => expect(mocks.aggregate).toHaveBeenCalledTimes(8));
    await act(async () => {
      refreshedLoads
        .slice(0, 3)
        .forEach((load, index) => load.resolve(successRows(index + 5)));
      await Promise.all(refreshedLoads.slice(0, 3).map(({ promise }) => promise));
    });

    expect(result.current.loading).toBe(true);
    expect(result.current.data).toBe(lastGood);

    await act(async () => {
      refreshedLoads[3].resolve(successRows(8));
      await refreshedLoads[3].promise;
    });
    await waitFor(() => expect(result.current.data?.at(-1)?.newFailures).toBe(5));
  });

  it("aborts the old four requests when the applied window changes", async () => {
    const controllers: AbortController[] = [];
    mocks.aggregate.mockImplementation((_query, _attributes, controller) => {
      controllers.push(controller);
      return new Promise((_, reject) => {
        controller.signal.addEventListener("abort", () =>
          reject(new DOMException("cancelled", "AbortError")),
        );
      });
    });

    const { rerender } = renderHook(
      ({ window }: { window: TrendWindow }) => useEventTrend(window, 0),
      { initialProps: { window: initialWindow } },
    );
    await waitFor(() => expect(mocks.aggregate).toHaveBeenCalledTimes(4));

    rerender({ window: nextWindow });

    await waitFor(() => expect(mocks.aggregate).toHaveBeenCalledTimes(8));
    expect(controllers.slice(0, 4).every(({ signal }) => signal.aborted)).toBe(
      true,
    );
  });

  it.each(["window", "refreshToken"] as const)(
    "does not let a late stale %s batch overwrite refreshed data",
    async (trigger) => {
      const staleLoads = Array.from({ length: 4 }, () => deferred<TrendRow[]>());
      const refreshedLoads = Array.from({ length: 4 }, () =>
        deferred<TrendRow[]>(),
      );
      let calls = 0;
      let loads = staleLoads;
      mocks.aggregate.mockImplementation(() => loads[calls++].promise);

      const { result, rerender } = renderHook(
        ({ window, token }: { window: TrendWindow; token: number }) =>
          useEventTrend(window, token),
        { initialProps: { window: initialWindow, token: 0 } },
      );
      await waitFor(() => expect(mocks.aggregate).toHaveBeenCalledTimes(4));

      calls = 0;
      loads = refreshedLoads;
      rerender(
        trigger === "window"
          ? { window: nextWindow, token: 0 }
          : { window: initialWindow, token: 1 },
      );
      await waitFor(() => expect(mocks.aggregate).toHaveBeenCalledTimes(8));
      await act(async () => {
        refreshedLoads.forEach((load, index) =>
          load.resolve(successRows(index + 5)),
        );
        await Promise.all(refreshedLoads.map(({ promise }) => promise));
      });
      await waitFor(() =>
        expect(result.current.data?.at(-1)?.newFailures).toBe(5),
      );
      const refreshedData = result.current.data;

      await act(async () => {
        staleLoads.forEach((load, index) => load.resolve(successRows(index + 1)));
        await Promise.all(staleLoads.map(({ promise }) => promise));
      });
      expect(result.current.data).toBe(refreshedData);
    },
  );

  it("does not replace the last complete trend with an AbortError", async () => {
    mocks.aggregate.mockResolvedValue(successRows(1));
    const { result, rerender } = renderHook(
      ({ token }) => useEventTrend(initialWindow, token),
      { initialProps: { token: 0 } },
    );
    await waitFor(() => expect(result.current.data).toBeDefined());
    const lastGood = result.current.data;

    mocks.aggregate.mockRejectedValue(
      new DOMException("cancelled", "AbortError"),
    );
    rerender({ token: 1 });

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.data).toBe(lastGood);
    expect(result.current.error).toBeUndefined();
  });
});
