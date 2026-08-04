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

import { aggregateId, pagedQuery } from "@ahoo-wang/fetcher-wow";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { queryExecutionFailedEventStreamPage } from "./executionFailedEventStreamClient.ts";

const mocks = vi.hoisted(() => ({
  createEventStreamQueryClient: vi.fn(),
  paged: vi.fn(),
}));

vi.mock("../generated", () => ({
  executionFailedQueryClientFactory: {
    createEventStreamQueryClient:
      mocks.createEventStreamQueryClient.mockReturnValue({
        paged: mocks.paged,
      }),
  },
}));

describe("executionFailedEventStreamClient", () => {
  beforeEach(() => {
    mocks.paged.mockClear();
  });

  it("creates the EventStream REST client behind the dashboard service boundary", () => {
    expect(mocks.createEventStreamQueryClient).toHaveBeenCalledWith({
      contextAlias: "",
    });
  });

  it("delegates paged history queries with request context and cancellation", async () => {
    const query = pagedQuery({ condition: aggregateId("failed-1") });
    const attributes = { source: "history" };
    const abortController = new AbortController();

    await queryExecutionFailedEventStreamPage(
      query,
      attributes,
      abortController,
    );

    expect(mocks.paged).toHaveBeenCalledWith(
      query,
      attributes,
      abortController,
    );
  });
});
