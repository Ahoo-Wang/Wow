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

import { describe, expect, it, vi } from "vitest";
import {
  aggregateExecutionFailedEvents,
  executionFailedEventStreamQueryClient,
} from "./executionFailedEventStreamClient.ts";

const mocks = vi.hoisted(() => ({
  client: {
    count: vi.fn(),
    list: vi.fn(),
    listStream: vi.fn(),
    paged: vi.fn(),
    aggregate: vi.fn(),
  },
  createEventStreamQueryClient: vi.fn(),
}));

vi.mock("../generated", () => ({
  executionFailedQueryClientFactory: {
    createEventStreamQueryClient:
      mocks.createEventStreamQueryClient.mockReturnValue(mocks.client),
  },
}));

describe("executionFailedEventStreamClient", () => {
  it("exposes the configured EventStreamQueryClient without narrowing its API", () => {
    expect(mocks.createEventStreamQueryClient).toHaveBeenCalledWith({
      contextAlias: "",
    });
    expect(executionFailedEventStreamQueryClient).toBe(mocks.client);
  });

  it("delegates event aggregation through the configured client", async () => {
    const query = { metrics: [{ type: "COUNT", alias: "count" }] } as never;
    const abortController = new AbortController();

    await aggregateExecutionFailedEvents(query, undefined, abortController);

    expect(mocks.client.aggregate).toHaveBeenCalledWith(
      query,
      undefined,
      abortController,
    );
  });
});
