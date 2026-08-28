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
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PressureClusterRow } from "./analyticsQueries.ts";
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

describe("useSnapshotAnalytics", () => {
  beforeEach(() => mocks.aggregate.mockReset());

  afterEach(() => vi.restoreAllMocks());

  it("starts six snapshot requests and waits for top clusters before status mix", async () => {
    const pressure = deferred<PressureClusterRow[]>();
    mocks.aggregate.mockImplementation((query) => {
      if (isQuery(query, "errorCode")) {
        return pressure.promise;
      }
      return Promise.resolve([{ count: 1 }]);
    });

    renderHook(() => useSnapshotAnalytics(0));

    expect(mocks.aggregate).toHaveBeenCalledTimes(6);
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
    await waitFor(() => expect(mocks.aggregate).toHaveBeenCalledTimes(7));
  });

  it("aborts stale snapshot requests when refreshToken changes", async () => {
    const controllers: AbortController[] = [];
    mocks.aggregate.mockImplementation((_query, _attributes, controller) => {
      if (!controller) {
        return Promise.resolve([]);
      }
      controllers.push(controller);
      return new Promise((_, reject) => {
        controller.signal.addEventListener("abort", () =>
          reject(new DOMException("cancelled", "AbortError")),
        );
      });
    });

    const { rerender } = renderHook(
      ({ token }) => useSnapshotAnalytics(token),
      { initialProps: { token: 0 } },
    );
    await act(async () => {
      rerender({ token: 1 });
      await Promise.resolve();
    });

    expect(controllers.slice(0, 6).every(({ signal }) => signal.aborted)).toBe(
      true,
    );
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
      if (isQuery(query, "retries")) {
        return refresh === 0
          ? Promise.resolve([{ count: 1, retries: 0 }])
          : Promise.reject(new Error("retry data unavailable"));
      }
      return Promise.resolve([{ count: refresh + 1 }]);
    });

    const { result, rerender } = renderHook(
      ({ token }) => useSnapshotAnalytics(token),
      { initialProps: { token: 0 } },
    );
    await waitFor(() => expect(result.current.summary.data?.actionableNow).toBe(1));
    const retries = result.current.retries.data;

    refresh = 1;
    rerender({ token: 1 });

    await waitFor(() =>
      expect(result.current.retries.error?.message).toBe("retry data unavailable"),
    );
    expect(result.current.retries.data).toBe(retries);
    expect(result.current.summary.data).toEqual({
      actionableNow: 2,
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
      if (isQuery(query, "retries") && refresh === 1) {
        return Promise.reject(new DOMException("cancelled", "AbortError"));
      }
      if (isQuery(query, "retries")) {
        return Promise.resolve([{ count: 1, retries: 0 }]);
      }
      if (isQuery(query, "recoverable")) {
        return Promise.resolve([{ count: 1, recoverable: "true" }]);
      }
      return Promise.resolve([{ count: 1 }]);
    });

    const { result, rerender } = renderHook(
      ({ token }) => useSnapshotAnalytics(token),
      { initialProps: { token: 0 } },
    );
    await waitFor(() => expect(result.current.retries.data).toBeDefined());

    refresh = 1;
    rerender({ token: 1 });

    await waitFor(() => expect(result.current.retries.loading).toBe(false));
    expect(result.current.retries.error).toBeUndefined();
  });
});
