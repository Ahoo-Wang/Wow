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
import { act, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { FunctionKind, RecoverableType } from "@ahoo-wang/fetcher-wow";
import {
  ExecutionFailedStatus,
  type ExecutionFailedState,
} from "../../../generated";
import { GlobalDrawerProvider } from "@/components/GlobalDrawer";
import { TooltipProvider } from "@/components/ui/tooltip";
import FailedView from "../FailedView.tsx";
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
