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

import type { ViewSource } from "@ahoo-wang/wow-view-engine";
import type * as Ui from "@ahoo-wang/wow-view-engine/ui";
import { EmbeddedView } from "@ahoo-wang/wow-view-engine/ui";
import type { ViewBinding, ViewHostProps } from "@ahoo-wang/wow-view-engine/ui";
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
 * What the console hands its hosts: the outer one the engine, the router,
 * the words, the theme and the route table; the page's own the failed
 * executions' reading and commands. Captured as they are rendered.
 */
const seen = vi.hoisted(() => ({
  outer: undefined as ViewHostProps | undefined,
  inner: undefined as ViewHostProps | undefined,
}));

vi.mock("@ahoo-wang/wow-view-engine/ui", async (actual) => {
  const ui = await actual<typeof Ui>();
  return {
    ...ui,
    ViewHost: (props: ViewHostProps) => {
      if (props.engine) seen.outer = props;
      else seen.inner = props;
      return <ui.ViewHost {...props} />;
    },
  };
});

/** What the page's host, else the console's, binds to `definitionId`. */
function bindingOf(definitionId: string): ViewBinding | undefined {
  const find = (props: ViewHostProps | undefined) =>
    props?.bindings?.find((entry) => entry.definitionId === definitionId);
  return find(seen.inner) ?? find(seen.outer);
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

describe("ConsoleHost", () => {
  beforeEach(() => {
    localStorage.setItem("wow-dashboard-locale", "en");
    seen.outer = undefined;
    seen.inner = undefined;
    rows = [ROW];
  });
  afterEach(() => vi.restoreAllMocks());

  it("hosts the engine on the router, in the porcelain preset and the reader's mode", () => {
    mount("/other");
    expect(seen.outer?.router?.location.pathname).toBe("/other");
    expect(seen.outer).toMatchObject({
      preset: "porcelain",
      rememberColorMode: "compensation-console.color-mode",
    });
    expect(seen.outer?.colorMode).toBeUndefined();
    expect(document.documentElement).toHaveAttribute(
      "data-fve-preset",
      "porcelain",
    );
    // Where each resource lives is the route table, and nothing else.
    expect(
      seen.outer?.bindings?.map(({ definitionId }) => definitionId),
    ).toEqual(["execution-failed", "execution-history", "overview"]);
    expect(seen.outer?.navigate).toBeUndefined();
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
      // The run's line went with the page's workbench.
      expect(document.querySelector('[data-slot="bulk-status"]')).toBeNull();
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
    it("binds the failed executions once per page: the record open is the engine's to keep", async () => {
      const router = mount("/other");
      const first = bindingOf(EXECUTION_FAILED);
      expect(first?.reading).toBeDefined();
      expect(first?.reading?.open).toBeUndefined();
      await act(() => router.navigate("/other?view=mine"));
      expect(bindingOf(EXECUTION_FAILED)).toBe(first);
      await act(() => router.navigate("/other?view=mine&id=EF-1"));
      expect(bindingOf(EXECUTION_FAILED)).toBe(first);
    });
  });
});
