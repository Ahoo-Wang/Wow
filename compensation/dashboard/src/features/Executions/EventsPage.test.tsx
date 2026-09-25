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
import { render, screen, waitFor } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { I18nProvider } from "@/i18n.tsx";
import EventsPage from "./EventsPage.tsx";

const historySource: ViewSource = {
  paged: vi.fn(() =>
    Promise.resolve({
      total: 1,
      list: [
        {
          id: "stream-1",
          aggregateId: "EF-1",
          version: 1,
          createTime: 1_790_000_000_000,
          body: [
            {
              name: "execution_failed_created",
              bodyType: "me.ahoo.wow.compensation.api.ExecutionFailedCreated",
            },
          ],
        },
      ],
    }),
  ),
  cursor: vi.fn(() => Promise.reject(new Error("not paged by cursor"))),
  aggregate: vi.fn(() => Promise.resolve([{ count: 3 }])),
};

function renderAt(entry: { search?: string; state?: unknown }) {
  const router = createMemoryRouter(
    [
      {
        path: "/executions/events",
        element: (
          <EventsPage
            store={new MemoryViewStore()}
            historySource={historySource}
          />
        ),
      },
    ],
    { initialEntries: [{ pathname: "/executions/events", ...entry }] },
  );
  render(
    <I18nProvider>
      <RouterProvider router={router} />
    </I18nProvider>,
  );
  return router;
}

describe("EventsPage", () => {
  beforeEach(() => {
    localStorage.setItem("wow-dashboard-locale", "en");
  });

  it("lists the event streams on the view the route names", async () => {
    renderAt({ search: "?view=system%3Aexecution-history%3Ahistory" });
    expect(
      await screen.findByRole("heading", { name: "Execution history" }),
    ).toBeInTheDocument();
    expect(await screen.findByText("First failed")).toBeInTheDocument();
  });

  it("opens on every execution's streams, each saying whose it is", async () => {
    renderAt({});
    expect(
      await screen.findByRole("heading", { name: "All event streams" }),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole("columnheader", { name: /Execution ID/ }),
    ).toBeInTheDocument();
    expect(await screen.findByText("EF-1")).toBeInTheDocument();
  });

  it("opens the question a board's panel handed over", async () => {
    renderAt({
      state: {
        handOver: {
          kind: "unsaved",
          definitionId: "execution-history",
          title: "New failures",
          scopeFilter: null,
          config: {
            kind: "analysis",
            filter: { op: "and", children: [] },
            filterMode: "simple",
            refresh: { interval: null },
            groups: [],
            metrics: [{ alias: "count", type: "COUNT" }],
            sort: [],
            limit: 1,
            layout: "table",
            table: { columns: [] },
            chart: { type: "metric", metric: { metric: "count" } },
          },
        },
      },
    });
    expect(
      await screen.findByRole("heading", { name: "New failures" }),
    ).toBeInTheDocument();
    await waitFor(() => expect(historySource.aggregate).toHaveBeenCalled());
  });
});
