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

import {
  MemoryViewStore,
  systemInstanceId,
  ViewEngine,
  type ViewSource,
} from "@ahoo-wang/wow-view-engine";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { I18nProvider, useI18n } from "@/i18n.tsx";
import { EXECUTION_FAILED } from "@/views/executionFailed.ts";
import type { ExecutionCommands } from "./executionCommands.ts";
import ExecutionsPage, { VIEW_PARAM } from "./ExecutionsPage.tsx";

const ROW = {
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
    retryState: { retries: 1, timeoutAt: 1_790_000_120_000 },
  },
};

let rows: Record<string, unknown>[] = [ROW];

const source: ViewSource = {
  paged: vi.fn(() => Promise.resolve({ total: rows.length, list: rows })),
  cursor: vi.fn(() => Promise.reject(new Error("not paged by cursor"))),
  aggregate: vi.fn(() => Promise.resolve([{ total: 1 }])),
};

/** A button that switches the console's language, as the shell's menu does. */
function LanguageSwitch() {
  const { setLocale } = useI18n();
  return (
    <button type="button" onClick={() => setLocale("zh-CN")}>
      switch to Chinese
    </button>
  );
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

function renderPage(
  path = "/executions",
  sent: ExecutionCommands = commands(),
) {
  const store = new MemoryViewStore();
  const router = createMemoryRouter(
    [
      {
        path: "/executions",
        element: (
          <>
            <LanguageSwitch />
            <ExecutionsPage store={store} source={source} commands={sent} />
          </>
        ),
      },
    ],
    { initialEntries: [path] },
  );
  render(
    <I18nProvider>
      <RouterProvider router={router} />
    </I18nProvider>,
  );
  return router;
}

describe("ExecutionsPage", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    rows = [ROW];
    localStorage.setItem("wow-dashboard-locale", "en");
  });

  it("opens the Active system view over the service's rows", async () => {
    renderPage();
    expect(
      await screen.findByRole("heading", { name: "Active" }),
    ).toBeInTheDocument();
    expect(await screen.findByText("OrderSaga")).toBeInTheDocument();
    expect(source.paged).toHaveBeenCalled();
  });

  it("puts the open view in the route, and opens the view the route names", async () => {
    const router = renderPage(
      `/executions?${VIEW_PARAM}=${systemInstanceId(EXECUTION_FAILED, "succeeded")}`,
    );
    expect(
      await screen.findByRole("heading", { name: "Succeeded" }),
    ).toBeInTheDocument();

    const expand = screen.queryByRole("button", {
      name: "Show the view list",
    });
    if (expand) fireEvent.click(expand);
    fireEvent.click(
      await screen.findByRole("button", { name: /^Unrecoverable\s*system$/ }),
    );
    expect(
      await screen.findByRole("heading", { name: "Unrecoverable" }),
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(router.state.location.search).toBe(
        `?${VIEW_PARAM}=${encodeURIComponent(systemInstanceId(EXECUTION_FAILED, "unrecoverable"))}`,
      ),
    );
  });

  it("rebuilds the engine in the new language and disposes the old one", async () => {
    renderPage();
    expect(
      await screen.findByRole("heading", { name: "Active" }),
    ).toBeInTheDocument();
    const dispose = vi.spyOn(ViewEngine.prototype, "dispose");

    fireEvent.click(screen.getByRole("button", { name: "switch to Chinese" }));
    expect(
      await screen.findByRole("heading", { name: "活动中" }),
    ).toBeInTheDocument();
    expect(dispose).toHaveBeenCalled();
  });

  it("prepares an execution from its row and says what the service refused", async () => {
    const sent = commands();
    sent.prepare.mockRejectedValueOnce(
      new Error("ExecutionFailed can not retry."),
    );
    renderPage("/executions", sent);
    fireEvent.click(await screen.findByRole("button", { name: "Prepare" }));
    await waitFor(() => expect(sent.prepare).toHaveBeenCalledWith("EF-1"));
    expect(
      await screen.findByText(
        "Prepare · 1 failed · ExecutionFailed can not retry. (1) · the rest stay selected",
      ),
    ).toBeInTheDocument();
  });

  it("lets a preparation be prepared again the moment it times out", async () => {
    // The clock is the test's: the row stays in progress however slowly it
    // renders, and moves past its deadline only when the test says so.
    // Timers still run with real time, so the engine's own work proceeds.
    vi.useFakeTimers({
      shouldAdvanceTime: true,
      toFake: ["Date", "setTimeout", "clearTimeout"],
    });
    try {
      const timeoutAt = Date.now() + 60_000;
      rows = [
        {
          ...ROW,
          state: {
            ...ROW.state,
            status: "PREPARED",
            retryState: { retries: 1, timeoutAt },
          },
        },
      ];
      renderPage();
      const prepare = await screen.findByRole("button", { name: "Prepare" });
      expect(prepare).toBeDisabled();
      expect(prepare).toHaveAccessibleDescription(
        "Execution is in progress; wait until it times out.",
      );

      // Past the deadline (`now > timeoutAt`), the row redraws on its own.
      await act(() => vi.advanceTimersByTimeAsync(timeoutAt - Date.now() + 1));
      expect(
        screen.getByRole("button", { name: "Prepare" }),
      ).not.toBeDisabled();
    } finally {
      vi.useRealTimers();
    }
  });

  it("asks before marking a selection's recoverability, and sends it on yes", async () => {
    const sent = commands();
    renderPage("/executions", sent);
    fireEvent.click(
      await screen.findByRole("checkbox", { name: "Select EF-1" }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Mark recoverability" }),
    );
    fireEvent.click(
      await screen.findByRole("menuitem", { name: "Unrecoverable" }),
    );
    const dialog = await screen.findByRole("alertdialog", {
      name: "Mark 1 execution as Unrecoverable?",
    });
    expect(
      within(dialog).getByText(
        /The scheduler stops retrying unrecoverable executions\./,
      ),
    ).toBeInTheDocument();
    fireEvent.click(
      within(dialog).getByRole("button", { name: "Mark as Unrecoverable" }),
    );
    await waitFor(() =>
      expect(sent.markRecoverable).toHaveBeenCalledWith(
        "EF-1",
        "UNRECOVERABLE",
      ),
    );
    expect(
      await screen.findByText("Mark as Unrecoverable · 1 done"),
    ).toBeInTheDocument();
  });

  it("names how many a bulk prepare is for, and which it will not send", async () => {
    const sent = commands();
    rows = [
      ROW,
      {
        ...ROW,
        aggregateId: "EF-2",
        state: { ...ROW.state, id: "EF-2", status: "SUCCEEDED" },
      },
    ];
    renderPage("/executions", sent);
    fireEvent.click(
      await screen.findByRole("checkbox", { name: "Select all rows" }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Force prepare" }),
    );
    const dialog = await screen.findByRole("alertdialog", {
      name: "Force prepare 2 executions?",
    });
    expect(
      within(dialog).getByText("This execution has already succeeded. (1)"),
    ).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() =>
      expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument(),
    );
    expect(sent.forcePrepare).not.toHaveBeenCalled();
  });

  it("narrows the view a link opens to its window, and lets the narrowing go", async () => {
    const start = Date.parse("2026-09-18T00:00:00Z");
    const end = Date.parse("2026-09-19T00:00:00Z");
    const view = systemInstanceId(EXECUTION_FAILED, "to-retry");
    const router = renderPage(
      `/executions?${VIEW_PARAM}=${encodeURIComponent(view)}&start=${start}&end=${end}`,
    );
    expect(
      await screen.findByRole("heading", { name: "To retry" }),
    ).toBeInTheDocument();
    const sentFilters = () =>
      vi
        .mocked(source.paged)
        .mock.calls.map(([query]) => JSON.stringify(query.filter));
    await waitFor(() =>
      expect(sentFilters().at(-1)).toContain("state.executeAt"),
    );
    expect(sentFilters().at(-1)).toContain(String(start));
    expect(sentFilters().at(-1)).toContain(String(end - 1));
    expect(
      screen.getByText("This view is narrowed by the link it was opened from."),
    ).toBeInTheDocument();

    fireEvent.click(
      screen.getByRole("button", { name: "Remove the narrowing" }),
    );
    await waitFor(() =>
      expect(router.state.location.search).toBe(
        `?${VIEW_PARAM}=${encodeURIComponent(view)}`,
      ),
    );
    await waitFor(() =>
      expect(sentFilters().at(-1)).not.toContain("state.executeAt"),
    );
    expect(
      screen.queryByText(
        "This view is narrowed by the link it was opened from.",
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "To retry" }),
    ).toBeInTheDocument();
  });

  it("lets a link's narrowing go with the view it opened", async () => {
    const cluster = {
      errorCode: "BAD_REQUEST",
      contextName: "order-service",
      processorName: "OrderSaga",
      functionName: "onOrderCreated",
      functionKind: "EVENT",
      start: 1,
      end: 2_000_000_000_000,
    };
    const router = renderPage(
      `/executions?cluster=${encodeURIComponent(JSON.stringify(cluster))}`,
    );
    expect(
      await screen.findByRole("heading", { name: "Active" }),
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(
        JSON.stringify(vi.mocked(source.paged).mock.calls.at(-1)?.[0].filter),
      ).toContain("state.function.processorName"),
    );

    const expand = screen.queryByRole("button", {
      name: "Show the view list",
    });
    if (expand) fireEvent.click(expand);
    fireEvent.click(
      await screen.findByRole("button", { name: /^Succeeded\s*system$/ }),
    );
    expect(
      await screen.findByRole("heading", { name: "Succeeded" }),
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(router.state.location.search).toBe(
        `?${VIEW_PARAM}=${encodeURIComponent(systemInstanceId(EXECUTION_FAILED, "succeeded"))}`,
      ),
    );
    await waitFor(() =>
      expect(
        JSON.stringify(vi.mocked(source.paged).mock.calls.at(-1)?.[0].filter),
      ).not.toContain("state.function.processorName"),
    );
  });

  it.each([
    [
      "cluster",
      "cluster=%7B",
      "Invalid cluster filter.",
      "Clear cluster filter",
      "",
      "Active",
    ],
    [
      "window",
      "view=system%3Aexecution-failed%3Asucceeded&start=2&end=1",
      "Invalid time range filter.",
      "Clear time range filter",
      "?view=system%3Aexecution-failed%3Asucceeded",
      "Succeeded",
    ],
  ])(
    "says a link's %s cannot be read, and queries nothing until it is cleared",
    async (_what, query, title, clear, left, heading) => {
      vi.mocked(source.paged).mockClear();
      const router = renderPage(`/executions?${query}`);
      expect(await screen.findByText(title)).toBeInTheDocument();
      expect(source.paged).not.toHaveBeenCalled();

      fireEvent.click(screen.getByRole("button", { name: clear }));
      await waitFor(() => expect(router.state.location.search).toBe(left));
      expect(
        await screen.findByRole("heading", { name: heading }),
      ).toBeInTheDocument();
    },
  );
});
