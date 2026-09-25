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
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { I18nProvider, useI18n } from "@/i18n.tsx";
import { EXECUTION_FAILED } from "@/views/executionFailed.ts";
import ExecutionsPreview, { VIEW_PARAM } from "./ExecutionsPreview.tsx";

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
    retryState: { retries: 1 },
  },
};

const source: ViewSource = {
  paged: vi.fn(() => Promise.resolve({ total: 1, list: [ROW] })),
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

function renderPreview(path = "/executions") {
  const store = new MemoryViewStore();
  const router = createMemoryRouter(
    [
      {
        path: "/executions",
        element: (
          <>
            <LanguageSwitch />
            <ExecutionsPreview store={store} source={source} />
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

describe("ExecutionsPreview", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    localStorage.setItem("wow-dashboard-locale", "en");
  });

  it("opens the Active system view over the service's rows", async () => {
    renderPreview();
    expect(
      await screen.findByRole("heading", { name: "Active" }),
    ).toBeInTheDocument();
    expect(await screen.findByText("OrderSaga")).toBeInTheDocument();
    expect(source.paged).toHaveBeenCalled();
  });

  it("puts the open view in the route, and opens the view the route names", async () => {
    const router = renderPreview(
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
    renderPreview();
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
});
