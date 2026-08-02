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
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ServerClockProvider } from "../ServerClockProvider.tsx";
import { resetServerClock } from "@/services/serverClock.ts";

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
}));

vi.mock("@ahoo-wang/fetcher", () => ({
  fetcher: { get: mocks.get },
}));

function runtimeResponse(serverTime: number): Response {
  return new Response(JSON.stringify({ serverTime }), {
    headers: { "Content-Type": "application/json" },
  });
}

describe("ServerClockProvider", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetServerClock();
  });

  it("waits for the authoritative server time before rendering the app", async () => {
    mocks.get.mockResolvedValue(runtimeResponse(1_785_501_209_222));

    render(
      <ServerClockProvider>
        <div>Dashboard ready</div>
      </ServerClockProvider>,
    );

    expect(screen.getByRole("status")).toHaveTextContent("Connecting");
    expect(await screen.findByText("Dashboard ready")).toBeInTheDocument();
    expect(mocks.get).toHaveBeenCalledWith("/dashboard/runtime", {
      abortController: expect.any(AbortController),
      cache: "no-store",
    });
  });

  it("shows a retryable blocking error when initial synchronization fails", async () => {
    mocks.get.mockRejectedValueOnce(new Error("runtime unavailable"));
    render(
      <ServerClockProvider>
        <div>Dashboard ready</div>
      </ServerClockProvider>,
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "runtime unavailable",
    );
    mocks.get.mockResolvedValueOnce(runtimeResponse(1_785_501_209_222));
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    await waitFor(() => {
      expect(screen.getByText("Dashboard ready")).toBeInTheDocument();
    });
  });
});
