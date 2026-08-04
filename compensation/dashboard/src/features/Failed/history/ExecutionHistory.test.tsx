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

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import {
  RecoverableType,
  type DomainEventStream,
} from "@ahoo-wang/fetcher-wow";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ExecutionFailedDomainEventType } from "../../../generated";
import { ExecutionHistory } from "./ExecutionHistory.tsx";

const mocks = vi.hoisted(() => ({
  query: vi.fn(),
}));

vi.mock("../../../services", () => ({
  queryExecutionFailedEventStreamPage: mocks.query,
}));

const stream: DomainEventStream<ExecutionFailedDomainEventType> = {
  id: "stream-1",
  aggregateId: "failed-1",
  aggregateName: "execution_failed",
  contextName: "compensation",
  tenantId: "(0)",
  ownerId: "",
  spaceId: "",
  commandId: "command-1",
  requestId: "request-1",
  createTime: new Date(2026, 7, 4, 10, 14, 30).getTime(),
  version: 2,
  header: { trace_id: "trace-1" },
  body: [
    {
      id: "event-1",
      name: "execution_failed_applied",
      bodyType: "compensation.execution_failed.ExecutionFailedApplied",
      revision: "1.0.0",
      body: {
        executeAt: new Date(2026, 7, 4, 10, 14, 30).getTime(),
        error: {
          errorCode: "TEST_ERROR",
          errorMsg: "Test error",
          stackTrace: "stack trace",
          bindingErrors: [],
          succeeded: false,
        },
        recoverable: RecoverableType.RECOVERABLE,
      },
    },
  ],
};

const nextPageStream: DomainEventStream<ExecutionFailedDomainEventType> = {
  ...stream,
  id: "stream-2",
  version: 3,
  body: stream.body.map((event) => ({
    ...event,
    id: "event-2",
  })),
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((nextResolve, nextReject) => {
    resolve = nextResolve;
    reject = nextReject;
  });
  return { promise, reject, resolve };
}

describe("ExecutionHistory", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.query.mockReset();
    mocks.query.mockResolvedValue({ total: 1, list: [stream] });
  });

  it("queries the selected execution through the paged EventStream REST API", async () => {
    render(<ExecutionHistory executionId="failed-1" />);

    expect(mocks.query).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Expand history" }));

    expect(
      screen.getByRole("status", { name: "Loading execution history" }),
    ).toBeInTheDocument();

    await waitFor(() => expect(mocks.query).toHaveBeenCalled());

    expect(mocks.query.mock.calls[0][0]).toEqual({
      condition: { operator: "AGGREGATE_ID", value: "failed-1" },
      projection: undefined,
      sort: [{ field: "version", direction: "DESC" }],
      pagination: { index: 1, size: 10 },
    });
  });

  it("renders event metadata and reveals the exact payload on demand", async () => {
    render(<ExecutionHistory executionId="failed-1" />);
    fireEvent.click(screen.getByRole("button", { name: "Expand history" }));

    expect(
      await screen.findByText("execution_failed_applied"),
    ).toBeInTheDocument();
    expect(screen.getByText("Version 2")).toBeInTheDocument();
    expect(screen.getByText("2026-08-04 10:14:30")).toBeInTheDocument();
    expect(screen.getByText("1–1 of 1")).toBeInTheDocument();

    fireEvent.click(screen.getByText("Event payload"));

    expect(screen.getByText(/"errorCode": "TEST_ERROR"/)).toBeVisible();
  });

  it("loads the next page while keeping pagination explicit", async () => {
    mocks.query.mockResolvedValue({ total: 11, list: [stream] });
    render(<ExecutionHistory executionId="failed-1" />);
    fireEvent.click(screen.getByRole("button", { name: "Expand history" }));

    const next = await screen.findByRole("button", { name: "Next history page" });
    fireEvent.click(next);

    await waitFor(() => expect(mocks.query).toHaveBeenCalledTimes(2));
    expect(mocks.query.mock.calls[1][0].pagination).toEqual({
      index: 2,
      size: 10,
    });
  });

  it("keeps the settled page visible and navigable when the next page fails", async () => {
    const nextPageRequest = deferred<{
      total: number;
      list: DomainEventStream<ExecutionFailedDomainEventType>[];
    }>();
    mocks.query
      .mockResolvedValueOnce({ total: 11, list: [stream] })
      .mockImplementationOnce(() => nextPageRequest.promise)
      .mockResolvedValueOnce({ total: 11, list: [nextPageStream] });

    render(<ExecutionHistory executionId="failed-1" />);
    fireEvent.click(screen.getByRole("button", { name: "Expand history" }));

    expect(await screen.findByText("Version 2")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Next history page" }));

    expect(screen.getByText("Version 2")).toBeInTheDocument();
    expect(screen.getByText("1–1 of 11")).toBeInTheDocument();
    expect(screen.getByText("1 / 2")).toBeInTheDocument();

    nextPageRequest.reject(new Error("next page unavailable"));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "next page unavailable",
    );
    expect(screen.getByText("Version 2")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Next history page" }),
    ).toBeEnabled();

    fireEvent.click(screen.getByRole("button", { name: "Next history page" }));

    expect(await screen.findByText("Version 3")).toBeInTheDocument();
    expect(screen.queryByText("Version 2")).not.toBeInTheDocument();
    expect(screen.getByText("11–11 of 11")).toBeInTheDocument();
    expect(screen.getByText("2 / 2")).toBeInTheDocument();
  });

  it("shows a recoverable error state", async () => {
    mocks.query.mockRejectedValueOnce(new Error("history unavailable"));
    render(<ExecutionHistory executionId="failed-1" />);
    fireEvent.click(screen.getByRole("button", { name: "Expand history" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "history unavailable",
    );

    fireEvent.click(screen.getByRole("button", { name: "Retry history" }));

    await waitFor(() => expect(mocks.query).toHaveBeenCalledTimes(2));
    expect(
      await screen.findByText("execution_failed_applied"),
    ).toBeInTheDocument();
  });
});
