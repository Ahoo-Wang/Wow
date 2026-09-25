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

import { render, waitFor } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { describe, expect, it } from "vitest";
import { QueueRoutes } from "../constants.tsx";
import { QueueRedirect } from "../QueueRedirect.tsx";

function follow(entry: string) {
  const router = createMemoryRouter(
    [
      ...QueueRoutes.map(({ path, view }) => ({
        path,
        element: <QueueRedirect view={view} />,
      })),
      { path: "/executions", element: <p>executions</p> },
    ],
    { initialEntries: ["/", entry] },
  );
  render(<RouterProvider router={router} />);
  return router;
}

describe("QueueRedirect", () => {
  it.each(QueueRoutes.map(({ path, view }) => [path, view]))(
    "sends %s to its system view",
    async (path, view) => {
      const router = follow(path);
      await waitFor(() =>
        expect(router.state.location.pathname).toBe("/executions"),
      );
      expect(router.state.location.search).toBe(
        `?view=${encodeURIComponent(view)}`,
      );
    },
  );

  it("keeps the execution, the cluster and the window, and replaces the entry", async () => {
    const router = follow("/to-retry?id=EF-1&start=1&end=2&cluster=x#top");
    await waitFor(() =>
      expect(router.state.location.pathname).toBe("/executions"),
    );
    expect(
      Object.fromEntries(new URLSearchParams(router.state.location.search)),
    ).toEqual({
      view: "system:execution-failed:to-retry",
      id: "EF-1",
      start: "1",
      end: "2",
      cluster: "x",
    });
    expect(router.state.location.hash).toBe("#top");
    // Back goes to where the link was followed from, not to the old address.
    expect(router.state.historyAction).toBe("REPLACE");
  });
});
