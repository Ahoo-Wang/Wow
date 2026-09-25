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

import { MemoryViewStore, type ViewSource } from "@ahoo-wang/wow-view-engine";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import {
  createMemoryRouter,
  RouterProvider,
  useLocation,
} from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { I18nProvider } from "@/i18n.tsx";
import type { ExecutionCommands } from "../Executions/executionCommands.ts";
import OverviewPage from "./OverviewPage.tsx";

const DUE = {
  aggregateId: "EF-1",
  firstEventTime: 1_790_000_000_000,
  eventTime: 1_790_000_000_000,
  state: {
    id: "EF-1",
    status: "FAILED",
    recoverable: "RECOVERABLE",
    function: { processorName: "OrderSaga", name: "onOrderCreated" },
    error: { errorCode: "BAD_REQUEST" },
    isBelowRetryThreshold: true,
    retryState: {
      retries: 1,
      nextRetryAt: 1_790_000_180_000,
      timeoutAt: 1_790_000_120_000,
    },
  },
};

/** Every figure the board asks for, one count a metric alias. */
function answer(query: { groupBy?: unknown[]; metrics?: { alias: string }[] }) {
  if (query.groupBy?.length) return Promise.resolve([]);
  return Promise.resolve([
    Object.fromEntries(
      (query.metrics ?? []).map(({ alias }, index) => [alias, 40 + index]),
    ),
  ]);
}

function sources() {
  const source: ViewSource = {
    paged: vi.fn(() => Promise.resolve({ total: 1, list: [DUE] })),
    cursor: vi.fn(() => Promise.reject(new Error("not paged by cursor"))),
    aggregate: vi.fn((query) => answer(query as never)),
  };
  const historySource: ViewSource = {
    paged: vi.fn(() => Promise.resolve({ total: 0, list: [] })),
    cursor: vi.fn(() => Promise.reject(new Error("not paged by cursor"))),
    aggregate: vi.fn((query) => answer(query as never)),
  };
  return { source, historySource };
}

function commands(): ExecutionCommands & {
  [K in keyof ExecutionCommands]: ReturnType<typeof vi.fn>;
} {
  return {
    prepare: vi.fn(() => Promise.resolve()),
    forcePrepare: vi.fn(() => Promise.resolve()),
    markRecoverable: vi.fn(() => Promise.resolve()),
    applyRetrySpec: vi.fn(() => Promise.resolve()),
    changeFunction: vi.fn(() => Promise.resolve()),
  };
}

/**
 * The history entry's state as the page last rendered it. The router commits
 * a navigation in a transition, so `router.state` runs ahead of the render:
 * the page's link carries what the render saw, and a test that clicks it
 * waits on this, not on the router.
 */
function RenderedState() {
  return (
    <output data-testid="rendered-state">
      {JSON.stringify(useLocation().state ?? null)}
    </output>
  );
}

function renderAt(path: string, sent = commands()) {
  const store = new MemoryViewStore();
  const props = { store, ...sources(), commands: sent };
  const router = createMemoryRouter(
    [
      {
        path: "/",
        element: (
          <>
            <OverviewPage {...props} />
            <RenderedState />
          </>
        ),
      },
      { path: "/boards", element: <p>the dashboard workbench</p> },
      { path: "/executions", element: <p>the workbench</p> },
    ],
    { initialEntries: [path] },
  );
  render(
    <I18nProvider>
      <RouterProvider router={router} />
    </I18nProvider>,
  );
  return { router, sent, props };
}

/** A panel, by its title. */
async function panel(name: string) {
  const heading = await screen.findByRole("heading", { name });
  const region = heading.closest<HTMLElement>("[data-slot='dashboard-panel']");
  return region ?? heading.parentElement!.parentElement!;
}

describe("OverviewPage", () => {
  beforeEach(() => {
    localStorage.setItem("wow-dashboard-locale", "en");
  });

  it("embeds the overview board over the service's counts", async () => {
    const { props } = renderAt("/");
    expect(
      await screen.findByRole("heading", { name: "Actionable now" }),
    ).toBeInTheDocument();
    await waitFor(() => expect(props.source.aggregate).toHaveBeenCalled());
    await waitFor(() =>
      expect(props.historySource.aggregate).toHaveBeenCalled(),
    );
    // The backlog is counted over the failed executions, the outcomes over
    // their event streams: each figure is its own query.
    expect(
      await within(await panel("Active in range")).findByText("40"),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Open in the dashboard workbench" }),
    ).toHaveAttribute("href", "/boards?view=system%3Aoverview%3Ahome");
  });

  it("prepares a due execution from the board's own panel", async () => {
    const { sent } = renderAt("/");
    const attention = await panel("Needing attention — due for retry");
    fireEvent.click(
      await within(attention).findByRole("button", { name: "Prepare" }),
    );
    await waitFor(() => expect(sent.prepare).toHaveBeenCalledWith("EF-1"));
  });

  it("takes the reader into the dashboard workbench with the filters left", async () => {
    const { router } = renderAt("/");
    // The board reports the window it opened on, which the page keeps —
    // and renders again with, before its link can carry it.
    await waitFor(() =>
      expect(
        JSON.parse(screen.getByTestId("rendered-state").textContent!),
      ).toMatchObject({
        filters: { values: { window: { amount: 7 } } },
      }),
    );
    fireEvent.click(
      await screen.findByRole("link", {
        name: "Open in the dashboard workbench",
      }),
    );
    expect(
      await screen.findByText("the dashboard workbench"),
    ).toBeInTheDocument();
    expect(router.state.location.search).toBe("?view=system%3Aoverview%3Ahome");
    // The window the board holds goes along: its default, never touched.
    expect(router.state.location.state).toEqual({
      filters: {
        values: { window: { type: "relative", amount: 7, unit: "day" } },
      },
    });
  });

  it("builds the board again in the new language", async () => {
    localStorage.setItem("wow-dashboard-locale", "zh-CN");
    renderAt("/");
    expect(
      await screen.findByRole("heading", { name: "可立即处理" }),
    ).toBeInTheDocument();
  });
});
