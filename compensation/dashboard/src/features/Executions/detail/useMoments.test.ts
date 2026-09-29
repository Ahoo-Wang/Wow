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

import type { FilterPagedQuery, PagedList } from "@ahoo-wang/wow-client";
import type { RecordData, ViewSource } from "@ahoo-wang/wow-view-engine";
import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useMoments } from "./useMoments.ts";

interface Pending {
  query: FilterPagedQuery;
  answer(page: PagedList<RecordData>): void;
  refuse(error: Error): void;
}

/** A source whose every page waits until the test answers it. */
function heldSource() {
  const pending: Pending[] = [];
  const source: ViewSource = {
    paged: vi.fn(
      (query: FilterPagedQuery) =>
        new Promise<PagedList<RecordData>>((answer, refuse) =>
          pending.push({ query, answer, refuse }),
        ),
    ),
    cursor: vi.fn(() => Promise.reject(new Error("not paged by cursor"))),
  } as unknown as ViewSource;
  return { source, pending };
}

/** One execution's story: recorded for `errorCode`. */
function storyOf(errorCode: string): PagedList<RecordData> {
  return {
    total: 1,
    list: [
      {
        version: 1,
        createTime: 1_790_000_000_000,
        body: [
          { name: "execution_failed_created", body: { error: { errorCode } } },
        ],
      },
    ],
  };
}

describe("useMoments", () => {
  it("shows the current execution when an earlier one's answer lands after it", async () => {
    const { source, pending } = heldSource();
    const { result, rerender } = renderHook(
      ({ id }) => useMoments(source, id, "1"),
      { initialProps: { id: "A" } },
    );
    await waitFor(() => expect(pending).toHaveLength(1));
    rerender({ id: "B" });
    await waitFor(() => expect(pending).toHaveLength(2));
    const [a, b] = pending;
    await act(async () => b!.answer(storyOf("B")));
    await act(async () => a!.answer(storyOf("A")));
    expect(result.current).toEqual({
      status: "read",
      total: 1,
      moments: [{ kind: "created", at: 1_790_000_000_000, errorCode: "B" }],
    });
  });

  it("keeps the current execution when an earlier one fails late", async () => {
    const { source, pending } = heldSource();
    const { result, rerender } = renderHook(
      ({ id }) => useMoments(source, id, "1"),
      { initialProps: { id: "A" } },
    );
    await waitFor(() => expect(pending).toHaveLength(1));
    rerender({ id: "B" });
    await waitFor(() => expect(pending).toHaveLength(2));
    const [a, b] = pending;
    await act(async () => b!.answer(storyOf("B")));
    await act(async () => a!.refuse(new Error("gone")));
    expect(result.current.status).toBe("read");
  });

  it("reads again when the execution changes, and says reading until then", async () => {
    const { source, pending } = heldSource();
    const { result, rerender } = renderHook(
      ({ revision }) => useMoments(source, "A", revision),
      { initialProps: { revision: "1" } },
    );
    await waitFor(() => expect(pending).toHaveLength(1));
    await act(async () => pending[0]!.answer(storyOf("first")));
    expect(result.current.status).toBe("read");
    rerender({ revision: "2" });
    expect(result.current.status).toBe("reading");
    await waitFor(() => expect(pending).toHaveLength(2));
    await act(async () => pending[1]!.answer(storyOf("second")));
    expect(result.current).toMatchObject({
      status: "read",
      moments: [{ errorCode: "second" }],
    });
    expect(pending[1]!.query).toEqual(pending[0]!.query);
  });

  it("says a refused read failed", async () => {
    const { source, pending } = heldSource();
    const { result } = renderHook(() => useMoments(source, "A", "1"));
    await waitFor(() => expect(pending).toHaveLength(1));
    await act(async () => pending[0]!.refuse(new Error("down")));
    expect(result.current).toEqual({ status: "failed" });
  });
});
