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

import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AnalyticsRange, TrendRow } from "./analyticsQueries.ts";
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

describe("useEventTrend", () => {
  it("loads exactly four event aggregations for one shared window", async () => {
    mocks.aggregate.mockResolvedValue([]);

    const { result } = renderHook(() => useEventTrend("7d", 0));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(mocks.aggregate).toHaveBeenCalledTimes(4);
    const windows = mocks.aggregate.mock.calls.map(([query]) => ({
      filter: query.filter,
      groupBy: query.groupBy,
    }));
    expect(new Set(windows.map(({ groupBy }) => groupBy[0].timeZone)).size).toBe(
      1,
    );
  });

  it("keeps the last complete trend when one refreshed series fails", async () => {
    mocks.aggregate.mockResolvedValueOnce(successRows(1));
    mocks.aggregate.mockResolvedValueOnce(successRows(2));
    mocks.aggregate.mockResolvedValueOnce(successRows(3));
    mocks.aggregate.mockResolvedValueOnce(successRows(4));
    const { result, rerender } = renderHook(
      ({ token }) => useEventTrend("7d", token),
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

  it("aborts the old four requests when range changes before starting four new ones", async () => {
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
      ({ range }) => useEventTrend(range, 0),
      { initialProps: { range: "7d" as AnalyticsRange } },
    );
    await waitFor(() => expect(mocks.aggregate).toHaveBeenCalledTimes(4));

    rerender({ range: "30d" });

    await waitFor(() => expect(mocks.aggregate).toHaveBeenCalledTimes(8));
    expect(controllers.slice(0, 4).every(({ signal }) => signal.aborted)).toBe(
      true,
    );
  });

  it("does not replace the last complete trend with an AbortError", async () => {
    mocks.aggregate.mockResolvedValue(successRows(1));
    const { result, rerender } = renderHook(
      ({ token }) => useEventTrend("7d", token),
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
