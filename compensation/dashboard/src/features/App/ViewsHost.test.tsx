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

import type { ViewNavigation, ViewSource } from "@ahoo-wang/wow-view-engine";
import { resolveNavigation } from "@ahoo-wang/wow-view-engine/testing";
import type * as Ui from "@ahoo-wang/wow-view-engine/ui";
import { EmbeddedView } from "@ahoo-wang/wow-view-engine/ui";
import type {
  ViewBinding,
  ViewEngineProviderProps,
} from "@ahoo-wang/wow-view-engine/ui";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { I18nProvider } from "@/i18n.tsx";
import { EXECUTION_FAILED } from "@/views/executionFailed.ts";
import { EXECUTION_HISTORY } from "@/views/executionHistory.ts";
import { OVERVIEW, OVERVIEW_BOARD } from "@/views/overview.ts";
import ExecutionsPage from "../Executions/ExecutionsPage.tsx";
import type { ExecutionCommands } from "../Executions/executionCommands.ts";
import { withViews } from "./withViews.tsx";

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

function commands(): ExecutionCommands {
  return {
    // Never answers: the run is still going when the reader leaves.
    prepare: vi.fn(() => new Promise<void>(() => undefined)),
    forcePrepare: vi.fn(() => Promise.resolve()),
    markRecoverable: vi.fn(() => Promise.resolve()),
    applyRetrySpec: vi.fn(() => Promise.resolve()),
    changeFunction: vi.fn(() => Promise.resolve()),
  };
}

/**
 * What the host hands its providers: the outer one the engine, the words,
 * the route and where each resource lives; the page's own the failed
 * executions' reading and commands. Captured as they are rendered.
 */
const seen = vi.hoisted(() => ({
  outer: undefined as ViewEngineProviderProps | undefined,
  inner: undefined as ViewEngineProviderProps | undefined,
}));

vi.mock("@ahoo-wang/wow-view-engine/ui", async (actual) => {
  const ui = await actual<typeof Ui>();
  return {
    ...ui,
    ViewEngineProvider: (props: ViewEngineProviderProps) => {
      if (props.engine) seen.outer = props;
      else seen.inner = props;
      return <ui.ViewEngineProvider {...props} />;
    },
  };
});

/** What the page's provider, else the host's, binds to `definitionId`. */
function bindingOf(definitionId: string): ViewBinding | undefined {
  const find = (props: ViewEngineProviderProps | undefined) =>
    props?.bindings?.find((entry) => entry.definitionId === definitionId);
  return find(seen.inner) ?? find(seen.outer);
}

/**
 * A way off, as the engine hands it to the host's route: the engine's own
 * resolution (`/testing`'s `resolveNavigation`) over the console's own
 * bindings, handed to the console's own `navigate`.
 */
function go(to: ViewNavigation): void {
  seen.outer?.navigate?.(resolveNavigation(to, bindingOf));
}

function mount(path: string, sent: ExecutionCommands = commands()) {
  const router = createMemoryRouter(
    [
      {
        // One host for every page, as the console's route has it.
        element: withViews(<RoutedOutlet />, { source, commands: sent })
          .element,
        children: [
          { path: "/executions", element: <ExecutionsPage /> },
          { path: "/other", element: <p>another page</p> },
          {
            path: "/embedded",
            element: (
              <EmbeddedView
                instanceId="system:execution-failed:active"
                interaction="interactive"
              />
            ),
          },
          { path: "/", element: <p>home</p> },
          { path: "/events", element: <p>events</p> },
          { path: "/boards", element: <p>boards</p> },
        ],
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

/** The children of the host's route. */
function RoutedOutlet() {
  return <Outlet />;
}

describe("ViewsHost", () => {
  beforeEach(() => {
    localStorage.setItem("wow-dashboard-locale", "en");
    seen.outer = undefined;
    seen.inner = undefined;
    rows = [ROW];
  });
  afterEach(() => vi.restoreAllMocks());

  describe("routes each way off through the console's bindings (review of #3761, 2)", () => {
    const view = (definitionId: string): ViewNavigation => ({
      kind: "view",
      definitionId,
      instanceId: `system:${definitionId}:all`,
      scopeFilter: null,
      filter: null,
    });

    it("opens a failed executions' view on its workbench, handed over", () => {
      const router = mount("/other");
      const to = view(EXECUTION_FAILED);
      act(() => go(to));
      expect(router.state.location.pathname).toBe("/executions");
      expect(router.state.location.search).toBe(
        "?view=system%3Aexecution-failed%3Aall",
      );
      expect(router.state.location.state).toEqual({ handOver: to });
    });

    it("opens a history view nobody saved on the event stream's default", () => {
      const router = mount("/other");
      const to: ViewNavigation = {
        kind: "unsaved",
        definitionId: EXECUTION_HISTORY,
        title: "These events",
        config: {} as never,
        scopeFilter: null,
      };
      act(() => go(to));
      expect(router.state.location.pathname).toBe("/events");
      expect(router.state.location.search).toBe("");
      expect(router.state.location.state).toEqual({ handOver: to });
    });

    it("goes back to the overview at home, and to another board on the workbench, under its filters", () => {
      const router = mount("/other");
      const filters = { values: {} };
      act(() =>
        go({
          kind: "dashboard",
          definitionId: OVERVIEW,
          instanceId: OVERVIEW_BOARD,
          filters,
        }),
      );
      expect(router.state.location.pathname).toBe("/");
      expect(router.state.location.state).toEqual({ filters });
      act(() => void router.navigate("/other"));
      act(() =>
        go({
          kind: "dashboard",
          definitionId: OVERVIEW,
          instanceId: "mine",
          filters,
        }),
      );
      expect(router.state.location.pathname).toBe("/boards");
      expect(router.state.location.search).toBe("?view=mine");
      act(() => void router.navigate("/other"));
      act(() =>
        go({
          kind: "dashboard",
          definitionId: OVERVIEW,
          instanceId: "mine",
          filters,
          tab: "week",
        }),
      );
      // The tab a board opens on rides along, as the engine's route says.
      expect(router.state.location.state).toEqual({ filters, tab: "week" });
    });

    it("opens another site apart, and a page of the console in place", () => {
      const router = mount("/other");
      const open = vi.spyOn(window, "open").mockReturnValue(null);
      act(() => go({ kind: "url", url: "//example.com/a" }));
      expect(open).toHaveBeenCalledWith(
        "//example.com/a",
        "_blank",
        "noopener,noreferrer",
      );
      expect(router.state.location.pathname).toBe("/other");
      act(() => go({ kind: "url", url: "/events" }));
      expect(router.state.location.pathname).toBe("/events");
    });
  });

  it("follows a real surface's way off to the failed executions' workbench (third review of #3761, 5)", async () => {
    const router = mount("/embedded");
    fireEvent.click(
      await screen.findByRole("button", { name: "Open in the workbench" }),
    );
    await waitFor(() =>
      expect(router.state.location.pathname).toBe("/executions"),
    );
    expect(router.state.location.search).toBe(
      "?view=system%3Aexecution-failed%3Aactive",
    );
    expect(router.state.location.state).toMatchObject({
      handOver: {
        kind: "view",
        definitionId: EXECUTION_FAILED,
        instanceId: "system:execution-failed:active",
      },
    });
  });

  describe("keeps a page's commands to that page (review of #3761, 1)", () => {
    it("sends no more of a bulk run once its page is left, and lets those under way land", async () => {
      rows = Array.from({ length: 8 }, (_, at) => ({
        ...ROW,
        aggregateId: `EF-${at}`,
        state: { ...ROW.state, id: `EF-${at}` },
      }));
      const sent = commands();
      const landed: string[] = [];
      // Each command waits for the test to let it land: nothing lands on its
      // own, so exactly the first handful are under way when the page goes.
      const releases: (() => void)[] = [];
      vi.mocked(sent.prepare).mockImplementation(
        (id: string) =>
          new Promise<void>((resolve) => {
            releases.push(() => {
              landed.push(id);
              resolve();
            });
          }),
      );
      const router = mount("/executions", sent);
      fireEvent.click(
        await screen.findByRole("checkbox", { name: "Select all rows" }),
      );
      fireEvent.click(
        await screen.findByRole("button", { name: /^Prepare 8/ }),
      );
      const dialog = await screen.findByRole("alertdialog");
      const [confirm] = within(dialog)
        .getAllByRole("button")
        .filter((button) => button.textContent !== "Cancel")
        .reverse();
      fireEvent.click(confirm);
      // The bulk command runs four at a time (its concurrency).
      await waitFor(() => expect(sent.prepare).toHaveBeenCalledTimes(4));

      await act(() => router.navigate("/other"));
      expect(bindingOf(EXECUTION_FAILED)?.bulk?.running).toBeNull();
      await act(async () => {
        for (const release of releases.splice(0)) release();
      });
      // What was under way landed; nothing more was sent with nobody to see
      // the run or stop it.
      expect(landed).toEqual(["EF-0", "EF-1", "EF-2", "EF-3"]);
      expect(sent.prepare).toHaveBeenCalledTimes(4);
      expect(releases).toEqual([]);
    });

    it("closes a confirmation when its page is left", async () => {
      const router = mount("/executions");
      fireEvent.click(
        await screen.findByRole("checkbox", { name: "Select EF-1" }),
      );
      fireEvent.click(
        await screen.findByRole("button", { name: "Mark recoverability" }),
      );
      fireEvent.click(
        await screen.findByRole("menuitem", { name: "Unrecoverable" }),
      );
      await screen.findByRole("alertdialog");
      await act(() => router.navigate("/other"));
      expect(screen.queryByRole("alertdialog")).toBeNull();
    });
  });

  describe("keeps the bindings still while the address moves (review of #3761, 4)", () => {
    it("rebinds the failed executions only when the record open changes", async () => {
      const router = mount("/other");
      const first = bindingOf(EXECUTION_FAILED);
      expect(first?.reading).toBeDefined();
      await act(() => router.navigate("/other?view=mine"));
      expect(bindingOf(EXECUTION_FAILED)).toBe(first);
      await act(() => router.navigate("/other?view=mine&id=EF-1"));
      expect(bindingOf(EXECUTION_FAILED)).not.toBe(first);
      expect(bindingOf(EXECUTION_FAILED)?.reading?.open).toBe("EF-1");
    });
  });
});
