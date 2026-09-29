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

import type * as Ui from "@ahoo-wang/wow-view-engine/ui";
import type {
  DashboardWorkbenchProps,
  ViewBinding,
  ViewHostProps,
} from "@ahoo-wang/wow-view-engine/ui";
import { render, screen } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { I18nProvider } from "@/i18n.tsx";
import BoardsPage from "./BoardsPage.tsx";
import { withViews } from "../App/withViews.tsx";

const seen = vi.hoisted(() => ({
  props: undefined as DashboardWorkbenchProps | undefined,
  binding: undefined as ViewBinding | undefined,
}));

// The workbench itself is the engine's, tested there — the board the
// address names, its filters, the way back out; this page is what is
// around it.
vi.mock("@ahoo-wang/wow-view-engine/ui", async (actual) => {
  const ui = await actual<typeof Ui>();
  return {
    ...ui,
    // What a record panel over the failed executions takes (`bind`): the
    // page's own binding of it.
    ViewHost: (props: ViewHostProps) => {
      const bound = props.bindings?.find(
        (entry) => entry.definitionId === "execution-failed",
      );
      if (!props.engine && bound) seen.binding = bound;
      return <ui.ViewHost {...props} />;
    },
    DashboardWorkbench: (props: DashboardWorkbenchProps) => {
      seen.props = props;
      return <p>workbench of {props.definitionId}</p>;
    },
  };
});

function renderAt(search: string, state: unknown = null) {
  const router = createMemoryRouter(
    [
      {
        path: "/boards",
        element: withViews(<BoardsPage />).element,
      },
    ],
    { initialEntries: [{ pathname: "/boards", search, state }] },
  );
  render(
    <I18nProvider>
      <RouterProvider router={router} />
    </I18nProvider>,
  );
  return router;
}

describe("BoardsPage", () => {
  beforeEach(() => {
    seen.props = undefined;
    localStorage.setItem("wow-dashboard-locale", "en");
  });

  it("opens the dashboard workbench, the board and its filters the address's", () => {
    renderAt("?view=system%3Aoverview%3Ahome", { filters: { values: {} } });
    expect(screen.getByText("workbench of overview")).toBeInTheDocument();
    expect(seen.props).toMatchObject({ landmark: "region" });
    // Which board, under which filters, is the address's — the engine keeps
    // it through the console's router (`ConsoleHost`) — and the engine, the
    // words and the commands on the failed executions' records are the
    // hosts', bound once for every page.
    for (const prop of [
      "instanceId",
      "onInstanceChange",
      "initialFilters",
      "onFiltersChange",
      "engine",
      "locale",
    ])
      expect(seen.props).not.toHaveProperty(prop);
    // The board's record panels over the failed executions take their
    // declared commands from the binding; the engine places and runs them.
    expect(seen.binding?.actions?.map(({ id }) => id)).toEqual([
      "prepare",
      "forcePrepare",
      "markRecoverable",
    ]);
  });
});
