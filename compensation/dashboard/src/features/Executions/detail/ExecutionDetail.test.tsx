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

import type { FilterPagedQuery, PagedList } from "@ahoo-wang/wow-client";
import {
  MemoryViewStore,
  type RecordData,
  type ViewSource,
} from "@ahoo-wang/wow-view-engine";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { I18nProvider } from "@/i18n.tsx";
import type { ExecutionCommands } from "../executionCommands.ts";
import ExecutionsPage from "../ExecutionsPage.tsx";
import { ID_PARAM } from "./useExecutionDetail.tsx";

const TRACE = [
  "java.lang.IllegalStateException: Inventory refused",
  "\tat order.OrderSaga.onOrderCreated(OrderSaga.kt:42)",
].join("\n");

function execution(id: string, overrides: Record<string, unknown> = {}) {
  return {
    aggregateId: id,
    firstEventTime: 1_790_000_000_000,
    eventTime: 1_790_000_000_000,
    state: {
      id,
      status: "FAILED",
      recoverable: "RECOVERABLE",
      isRetryable: true,
      isBelowRetryThreshold: true,
      function: {
        contextName: "order-service",
        processorName: "OrderSaga",
        name: "onOrderCreated",
        functionKind: "EVENT",
      },
      eventId: {
        id: `${id}-event`,
        version: 1,
        aggregateId: {
          contextName: "order-service",
          aggregateName: "order",
          aggregateId: `order-${id}`,
        },
      },
      error: {
        errorCode: "BAD_REQUEST",
        errorMsg: "Inventory refused",
        stackTrace: TRACE,
      },
      retrySpec: { maxRetries: 3, minBackoff: 180, executionTimeout: 120 },
      retryState: {
        retries: 1,
        retryAt: 1_790_000_000_000,
        nextRetryAt: 1_790_000_180_000,
        timeoutAt: 1_790_000_120_000,
      },
      ...overrides,
    },
  };
}

/** The one record a read by key asks for, out of a filter on `state.id`. */
function keyOf(query: FilterPagedQuery): string | undefined {
  return JSON.stringify(query.filter).match(/"value":"([^"]+)"/)?.[1];
}

let records: Record<string, ReturnType<typeof execution>>;
let read: (id: string | undefined) => Promise<PagedList<RecordData>>;

const source: ViewSource = {
  paged: vi.fn(async (query: FilterPagedQuery) => {
    // A read of one record, by its key: the detail's.
    if (query.pagination?.size === 1) return read(keyOf(query));
    return { total: 1, list: [records["EF-1"]] };
  }),
  cursor: vi.fn(() => Promise.reject(new Error("not paged by cursor"))),
  aggregate: vi.fn(() => Promise.resolve([{ total: 1 }])),
};

const STREAM = {
  id: "EF-1-v2",
  aggregateId: "EF-1",
  version: 2,
  createTime: 1_790_000_180_000,
  commandId: "EF-1-v2-command",
  body: [
    {
      id: "EF-1-v2-event",
      name: "execution_failed_applied",
      revision: "0.0.1",
      bodyType: "me.ahoo.wow.compensation.api.ExecutionFailedApplied",
    },
  ],
};

/** One stream read whole, as a payload's read asks for it. */
const WHOLE = {
  ...STREAM,
  body: [{ ...STREAM.body[0], body: { error: { errorCode: "BAD_REQUEST" } } }],
};

let readStream: () => Promise<PagedList<RecordData>>;

const historySource: ViewSource = {
  paged: vi.fn((query: FilterPagedQuery) =>
    query.pagination?.size === 1
      ? readStream()
      : Promise.resolve({ total: 1, list: [STREAM] }),
  ),
  cursor: vi.fn(() => Promise.reject(new Error("not paged by cursor"))),
  aggregate: vi.fn(() => Promise.resolve([])),
};

function commands() {
  return {
    prepare: vi.fn(() => Promise.resolve()),
    forcePrepare: vi.fn(() => Promise.resolve()),
    markRecoverable: vi.fn(() => Promise.resolve()),
    applyRetrySpec: vi.fn(() => Promise.resolve()),
    changeFunction: vi.fn(() => Promise.resolve()),
  } satisfies ExecutionCommands;
}

function renderAt(path: string, sent: ExecutionCommands = commands()) {
  const router = createMemoryRouter(
    [
      {
        path: "/executions",
        element: (
          <ExecutionsPage
            store={new MemoryViewStore()}
            source={source}
            historySource={historySource}
            commands={sent}
          />
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

async function openDetail() {
  const panel = await screen.findByRole("dialog");
  // The whole record has come once its host sections have their forms.
  await within(panel).findByRole("form", { name: "Change function" });
  return panel;
}

describe("the execution detail", () => {
  beforeEach(() => {
    localStorage.setItem("wow-dashboard-locale", "en");
    records = { "EF-1": execution("EF-1"), "EF-9": execution("EF-9") };
    readStream = () => Promise.resolve({ total: 1, list: [WHOLE] });
    read = (id) =>
      Promise.resolve(
        id && records[id]
          ? { total: 1, list: [records[id]] }
          : { total: 0, list: [] },
      );
  });

  afterEach(() => {
    vi.clearAllMocks();
    Reflect.deleteProperty(navigator, "clipboard");
  });

  it("opens the execution the address names, with the console's sections among the engine's", async () => {
    renderAt(`/executions?${ID_PARAM}=EF-1`);
    const panel = await openDetail();

    const headings = within(panel)
      .getAllByRole("heading", { level: 3 })
      .map((heading) => heading.textContent);
    // Each of the console's sections stands after what it is about.
    expect(headings.indexOf("Change function")).toBe(
      headings.indexOf("Function") + 1,
    );
    expect(headings.indexOf("Stack trace")).toBe(headings.indexOf("Error") + 1);
    expect(headings.indexOf("Apply retry specification")).toBe(
      headings.indexOf("Retry") + 1,
    );
    expect(headings.at(-1)).toBe("Execution history");

    // The stack trace is read once, the console's way, with its lines counted.
    const trace = within(panel).getByRole("region", { name: "Stack trace" });
    expect(within(trace).getByText("2 lines")).toBeInTheDocument();
    const error = within(panel).getByRole("region", { name: "Error" });
    expect(within(error).getByText("Inventory refused")).toBeInTheDocument();
    expect(error).not.toHaveTextContent("OrderSaga.kt:42");
  });

  it("reads the execution's history, scoped to it and the newest first", async () => {
    renderAt(`/executions?${ID_PARAM}=EF-1`);
    const panel = await openDetail();
    const history = within(panel).getByRole("region", {
      name: "Execution history",
    });
    expect(
      await within(history).findByText("Retry failed"),
    ).toBeInTheDocument();
    const query = vi.mocked(historySource.paged).mock.calls[0]![0];
    expect(JSON.stringify(query.filter)).toContain('"value":"EF-1"');
    expect(query.sort?.[0]).toEqual({ field: "version", direction: "DESC" });
  });

  it("opens a stream of the history over the execution, its payload read whole", async () => {
    renderAt(`/executions?${ID_PARAM}=EF-1`);
    const panel = await openDetail();
    const history = within(panel).getByRole("region", {
      name: "Execution history",
    });
    const row = (await within(history).findByText("Retry failed")).closest(
      "tr",
    )!;
    fireEvent.keyDown(row, { key: "Enter" });

    // A second drawer over the first, the stream read by its key.
    const panels = () => [
      ...document.querySelectorAll<HTMLElement>('[data-slot="record-detail"]'),
    ];
    await waitFor(() => expect(panels()).toHaveLength(2));
    const stream = panels().find((one) => !one.contains(history))!;
    expect(await within(stream).findByText("BAD_REQUEST")).toBeInTheDocument();
    const asked = vi
      .mocked(historySource.paged)
      .mock.calls.map(([query]) => query)
      .find((query) => query.pagination?.size === 1)!;
    expect(JSON.stringify(asked.filter)).toContain('"value":"EF-1-v2"');
  });

  it("follows a press on a row into the address, and a close out of it", async () => {
    const router = renderAt("/executions");
    const row = (await screen.findByText("OrderSaga")).closest("tr")!;
    fireEvent.keyDown(row, { key: "Enter" });
    await openDetail();
    await waitFor(() =>
      expect(router.state.location.search).toBe(`?${ID_PARAM}=EF-1`),
    );

    fireEvent.keyDown(await screen.findByRole("dialog"), { key: "Escape" });
    await waitFor(() => expect(router.state.location.search).toBe(""));
  });

  it("opens one the page does not hold, and says when it is not there", async () => {
    renderAt(`/executions?${ID_PARAM}=EF-9`);
    const panel = await openDetail();
    expect(
      within(panel).getByRole("heading", { name: "EF-9", level: 2 }),
    ).toBeInTheDocument();
    expect(source.paged).toHaveBeenCalledWith(
      expect.objectContaining({
        pagination: expect.objectContaining({ size: 1 }),
      }),
      undefined,
      expect.anything(),
    );
  });

  it("says a linked execution is gone rather than drawing forms for it", async () => {
    renderAt(`/executions?${ID_PARAM}=EF-404`);
    const panel = await screen.findByRole("dialog");
    expect(
      await within(panel).findByText(/no longer there/i),
    ).toBeInTheDocument();
    expect(
      within(panel).queryByRole("form", { name: "Change function" }),
    ).not.toBeInTheDocument();
  });

  it("applies a retry spec and reads the execution again", async () => {
    const sent = commands();
    renderAt(`/executions?${ID_PARAM}=EF-1`, sent);
    const panel = await openDetail();
    const form = within(panel).getByRole("form", {
      name: "Apply retry specification",
    });
    const apply = within(form).getByRole("button", {
      name: "Apply retry spec",
    });
    // Nothing changed yet, so nothing to send.
    expect(apply).toBeDisabled();
    fireEvent.change(within(form).getByLabelText("Max retries"), {
      target: { value: "5" },
    });
    fireEvent.change(within(form).getByLabelText("Min backoff (s)"), {
      target: { value: "" },
    });
    expect(within(form).getByText("Enter a duration")).toBeInTheDocument();
    expect(apply).toBeDisabled();
    fireEvent.change(within(form).getByLabelText("Min backoff (s)"), {
      target: { value: "60" },
    });
    const reads = vi.mocked(source.paged).mock.calls.length;
    fireEvent.click(apply);

    await waitFor(() =>
      expect(sent.applyRetrySpec).toHaveBeenCalledWith("EF-1", {
        maxRetries: 5,
        minBackoff: 60,
        executionTimeout: 120,
      }),
    );
    await waitFor(() =>
      expect(vi.mocked(source.paged).mock.calls.length).toBeGreaterThan(reads),
    );
  });

  it("changes the function, and keeps the service's refusal beside the form", async () => {
    const sent = commands();
    sent.changeFunction.mockRejectedValueOnce(new Error("Function not found."));
    renderAt(`/executions?${ID_PARAM}=EF-1`, sent);
    const panel = await openDetail();
    const form = within(panel).getByRole("form", { name: "Change function" });
    fireEvent.change(within(form).getByLabelText("Processor name"), {
      target: { value: " OrderSagaV2 " },
    });
    fireEvent.click(within(form).getByRole("button", { name: "State event" }));
    fireEvent.click(
      within(form).getByRole("button", { name: "Save function" }),
    );

    expect(await within(form).findByRole("alert")).toHaveTextContent(
      "Function not found.",
    );
    expect(sent.changeFunction).toHaveBeenCalledWith("EF-1", {
      contextName: "order-service",
      processorName: "OrderSagaV2",
      name: "onOrderCreated",
      functionKind: "STATE_EVENT",
    });
    // Clearing a name leaves nothing to send.
    fireEvent.change(within(form).getByLabelText("Function name"), {
      target: { value: "  " },
    });
    expect(
      within(form).getByRole("button", { name: "Save function" }),
    ).toBeDisabled();
  });

  it("copies the stack trace, and wraps or scrolls its long lines", async () => {
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText },
    });
    renderAt(`/executions?${ID_PARAM}=EF-1`);
    const panel = await openDetail();
    const trace = within(panel).getByRole("region", { name: "Stack trace" });
    fireEvent.click(
      within(trace).getByRole("button", { name: "Copy stack trace" }),
    );
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(TRACE));
    expect(await within(trace).findByRole("status")).toHaveTextContent(
      "Stack trace copied",
    );

    const wrap = within(trace).getByRole("button", { name: "Wrap lines" });
    const code = () =>
      within(trace)
        .getByRole("region", { name: "Stack trace content" })
        .querySelector("code");
    expect(wrap).toHaveAttribute("aria-pressed", "true");
    // A frame has nowhere to break, so wrapping breaks inside it.
    expect(code()?.style.overflowWrap).toBe("anywhere");
    fireEvent.click(wrap);
    expect(wrap).toHaveAttribute("aria-pressed", "false");
    expect(code()?.style.overflowWrap).toBe("");
  });

  it("says when it could not copy the stack trace", async () => {
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: undefined,
    });
    renderAt(`/executions?${ID_PARAM}=EF-1`);
    const panel = await openDetail();
    const trace = within(panel).getByRole("region", { name: "Stack trace" });
    fireEvent.click(
      within(trace).getByRole("button", { name: "Copy stack trace" }),
    );
    expect(await within(trace).findByRole("status")).toHaveTextContent(
      "Unable to copy stack trace",
    );
  });

  it("says when there is no stack trace", async () => {
    records["EF-1"] = execution("EF-1", {
      error: { errorCode: "BAD_REQUEST", errorMsg: "x", stackTrace: "" },
    });
    renderAt(`/executions?${ID_PARAM}=EF-1`);
    const panel = await openDetail();
    const trace = within(panel).getByRole("region", { name: "Stack trace" });
    expect(within(trace).getByText("No stack trace")).toBeInTheDocument();
  });
});
