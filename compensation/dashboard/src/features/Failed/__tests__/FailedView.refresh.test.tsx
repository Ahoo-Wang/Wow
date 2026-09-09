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

import { MemoryRouter } from "react-router";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { FunctionKind, RecoverableType } from "@ahoo-wang/fetcher-wow";
import {
  ExecutionFailedStatus,
  type ExecutionFailedState,
} from "../../../generated";
import { GlobalDrawerProvider } from "@/components/GlobalDrawer";
import { TooltipProvider } from "@/components/ui/tooltip";
import FailedView from "../FailedView.tsx";
import { FetchingFailedDetails } from "../details/FetchingFailedDetails.tsx";
import { FindCategory } from "../FindCategory.ts";
const mocks = vi.hoisted(() => ({ page: vi.fn(), single: vi.fn() }));
vi.mock("../../../services", () => ({
  queryExecutionFailedPage: mocks.page,
  queryExecutionFailedState: mocks.single,
}));
vi.mock("@/hooks/useMediaQuery", () => ({ useMediaQuery: () => true }));
afterEach(() => vi.useRealTimers());
const firstState: ExecutionFailedState = {
  id: "failed-1",
  status: ExecutionFailedStatus.FAILED,
  recoverable: RecoverableType.RECOVERABLE,
  error: {
    errorCode: "E1",
    errorMsg: "failed",
    stackTrace: "trace",
    bindingErrors: [],
    succeeded: false,
  },
  eventId: {
    id: "event-1",
    version: 1,
    aggregateId: {
      aggregateName: "payment",
      contextName: "billing",
      aggregateId: "payment-1",
      tenantId: "tenant",
    },
  },
  executeAt: 1,
  function: {
    contextName: "billing",
    processorName: "processor",
    name: "handle",
    functionKind: FunctionKind.EVENT,
  },
  retrySpec: { maxRetries: 3, minBackoff: 1, executionTimeout: 2 },
  retryState: { nextRetryAt: 2, retries: 1, retryAt: 1, timeoutAt: 2 },
  isBelowRetryThreshold: true,
  isRetryable: true,
};

it.each([FindCategory.ToRetry, FindCategory.NonRetryable])("updates an off-page execution while viewing %s", async (category) => {
  vi.useFakeTimers({ toFake: ["setInterval", "clearInterval", "Date"] });
  mocks.page.mockResolvedValue({ total: 0, list: [] });
  mocks.single.mockResolvedValue({
    ...firstState,
    status: ExecutionFailedStatus.PREPARED,
  });
  render(
    <MemoryRouter initialEntries={["/to-retry?id=failed-1"]}>
      <TooltipProvider>
        <GlobalDrawerProvider>
          <FailedView category={category} />
        </GlobalDrawerProvider>
      </TooltipProvider>
    </MemoryRouter>,
  );
  await screen.findByRole("heading", { name: "handle" });
  await waitFor(() =>
    expect(
      screen.getByRole("button", { name: "Search" }),
    ).toBeEnabled(),
  );
  const initialReads = mocks.single.mock.calls.length;
  mocks.single.mockResolvedValue({
    ...firstState,
    status: ExecutionFailedStatus.SUCCEEDED,
    isRetryable: false,
  });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(30_001);
  });
  await screen.findByRole("button", { name: "Already succeeded" });
  await waitFor(() =>
    expect(mocks.single.mock.calls.length).toBeGreaterThan(initialReads),
  );
  expect(
    screen.getByRole("button", { name: "Already succeeded" }),
  ).toBeDisabled();
});


it("does not query records for an invalid cluster link", () => {
  mocks.page.mockClear();
  mocks.single.mockClear();
  render(<MemoryRouter initialEntries={["/active?cluster=invalid"]}><FailedView category={FindCategory.Active} /></MemoryRouter>);
  expect(screen.getByRole("alert")).toHaveTextContent("Invalid cluster filter.");
  expect(mocks.page).not.toHaveBeenCalled();
  expect(mocks.single).not.toHaveBeenCalled();
});


it("retains off-page context read-only after a refresh failure and recovers on retry", async () => {
  mocks.single.mockResolvedValue(firstState);
  const detail = (refreshToken: number, id = firstState.id) => (
    <TooltipProvider><GlobalDrawerProvider>
      <FetchingFailedDetails id={id} refreshToken={refreshToken} />
    </GlobalDrawerProvider></TooltipProvider>
  );
  const view = render(detail(0));
  await screen.findByRole("heading", { name: "handle" });
  mocks.single.mockRejectedValue(new Error("detail unavailable"));
  view.rerender(detail(1));
  await screen.findByRole("alert");
  expect(screen.getByRole("heading", { name: "handle" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Refreshing state" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Edit retry specification" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Edit function" })).toBeDisabled();
  expect(screen.getByRole("alert")).toHaveTextContent("detail unavailable");

  mocks.single.mockResolvedValue(firstState);
  fireEvent.click(screen.getByRole("button", { name: "Retry" }));
  await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  await waitFor(() => expect(screen.getByRole("button", { name: "Prepare compensation" })).toBeEnabled());

  mocks.single.mockResolvedValue(null);
  view.rerender(detail(2));
  await screen.findByText("Execution not found");
  expect(screen.queryByRole("heading", { name: "handle" })).not.toBeInTheDocument();
});

it("does not reuse another execution's context after a failed deep-link change", async () => {
  mocks.single.mockResolvedValue(firstState);
  const view = render(<TooltipProvider><GlobalDrawerProvider><FetchingFailedDetails id={firstState.id} /></GlobalDrawerProvider></TooltipProvider>);
  await screen.findByRole("heading", { name: "handle" });
  mocks.single.mockRejectedValue(new Error("other execution unavailable"));
  view.rerender(<TooltipProvider><GlobalDrawerProvider><FetchingFailedDetails id="other-id" /></GlobalDrawerProvider></TooltipProvider>);
  await screen.findByRole("alert");
  expect(screen.queryByRole("heading", { name: "handle" })).not.toBeInTheDocument();
});
