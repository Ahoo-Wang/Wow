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
import type { RecordData, ViewSource } from "@ahoo-wang/wow-view-engine";
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
import { withViews } from "../../App/withViews.tsx";

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

/** One stream of EF-1's, holding one event named `name`. */
function stream(version: number, name: string, body: object = {}) {
  return {
    id: `EF-1-v${version}`,
    aggregateId: "EF-1",
    version,
    createTime: 1_790_000_000_000 + version * 60_000,
    commandId: `EF-1-v${version}-command`,
    body: [
      {
        id: `EF-1-v${version}-event`,
        name,
        revision: "0.0.1",
        // The class the service names it by: execution_failed_applied is
        // ExecutionFailedApplied.
        bodyType: `me.ahoo.wow.compensation.api.${name.replace(
          /(^|_)(\w)/g,
          (_, _gap: string, first: string) => first.toUpperCase(),
        )}`,
        body,
      },
    ],
  };
}

const FAILED = { error: { errorCode: "BAD_REQUEST" } };

/** EF-1's story: recorded, then retried twice, failing the same way. */
const STREAMS = [
  stream(1, "execution_failed_created", FAILED),
  stream(2, "compensation_prepared"),
  stream(3, "execution_failed_applied", FAILED),
  stream(4, "compensation_prepared"),
  stream(5, "execution_failed_applied", FAILED),
];

const STREAM = STREAMS[4]!;

/** One stream read whole, as a payload's read asks for it. */
const WHOLE = STREAM;

let readStream: () => Promise<PagedList<RecordData>>;

const historySource: ViewSource = {
  paged: vi.fn((query: FilterPagedQuery) =>
    query.pagination?.size === 1
      ? readStream()
      : Promise.resolve({ total: STREAMS.length, list: STREAMS }),
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
        element: withViews(<ExecutionsPage />, {
          source,
          historySource,
          commands: sent,
        }).element,
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
  // The whole record has come once the reading offers its changes.
  await within(panel).findByRole("button", { name: "Change function" });
  return panel;
}

/** The event streams themselves, one press under the attempts. */
async function openHistory(panel: HTMLElement) {
  fireEvent.click(
    await within(panel).findByRole("button", { name: /^All events/ }),
  );
  return within(panel).findByRole("region", { name: "Execution history" });
}

/**
 * A change folded under what it changes (2026-09-27 review): the form is
 * one press away, not in the middle of the reading.
 */
async function openForm(panel: HTMLElement, name: string) {
  expect(within(panel).queryByRole("form", { name })).not.toBeInTheDocument();
  fireEvent.click(within(panel).getByRole("button", { name }));
  return within(panel).findByRole("form", { name });
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

  it("opens the execution the address names, read the way it is asked about", async () => {
    renderAt(`/executions?id=EF-1`);
    const panel = await openDetail();

    // Named by its handler, its key above the name (D60).
    expect(
      within(panel).getByRole("heading", {
        level: 2,
        name: "OrderSaga.onOrderCreated",
      }),
    ).toBeInTheDocument();
    expect(
      panel.querySelector('[data-slot="record-detail-key"]'),
    ).toHaveTextContent("EF-1");
    // Where it stands, then why it failed, what happened, what it ran and
    // on which event, and how it is retried (2026-09-27 redesign).
    expect(within(panel).getByText("Failed")).toBeInTheDocument();
    expect(
      within(panel).getByText("Recoverability: Recoverable"),
    ).toBeInTheDocument();
    expect(within(panel).getByText("1 / 3")).toBeInTheDocument();
    expect(
      within(panel)
        .getAllByRole("heading", { level: 3 })
        .map((heading) => heading.textContent),
    ).toEqual([
      "Why it failed",
      "Attempts",
      "Handler",
      "Triggering event",
      "Retry specification",
    ]);

    // The error's message and code, and its stack read once, lines counted.
    const failure = within(panel).getByRole("region", {
      name: "Why it failed",
    });
    expect(within(failure).getByText("Inventory refused")).toBeInTheDocument();
    const trace = within(failure).getByRole("region", { name: "Stack trace" });
    expect(within(trace).getByText("2 lines")).toBeInTheDocument();
    // No form is open until it is asked for.
    expect(within(panel).queryByRole("form")).not.toBeInTheDocument();
  });

  it("tells the attempts from the event streams, and when retrying will not help", async () => {
    renderAt(`/executions?id=EF-1`);
    const panel = await openDetail();
    const attempts = within(panel).getByRole("region", { name: "Attempts" });
    const items = await within(attempts).findAllByRole("listitem");
    expect(items.map((item) => item.firstChild?.textContent)).toEqual([
      "Attempt 2",
      "Attempt 1",
      "First failed",
    ]);
    expect(items[0]).toHaveTextContent("BAD_REQUEST");
    // Read once, from its end; the streams stay unread below.
    const query = vi.mocked(historySource.paged).mock.calls[0]![0];
    expect(JSON.stringify(query.filter)).toContain('"value":"EF-1"');
    expect(query.sort?.[0]).toEqual({ field: "version", direction: "DESC" });
    expect(historySource.paged).toHaveBeenCalledTimes(1);
    expect(
      within(panel).getByRole("button", { name: "All events (5)" }),
    ).toBeInTheDocument();

    // The same error twice in a row: said where the error is.
    const failure = within(panel).getByRole("region", {
      name: "Why it failed",
    });
    expect(within(failure).getByRole("alert")).toHaveTextContent(
      "The last 2 attempts failed with this same error",
    );
  });

  it("says nothing about retrying when the attempts failed differently", async () => {
    records["EF-1"] = execution("EF-1", {
      error: { errorCode: "TIMEOUT", errorMsg: "Timed out", stackTrace: "" },
    });
    renderAt(`/executions?id=EF-1`);
    const panel = await openDetail();
    const attempts = within(panel).getByRole("region", { name: "Attempts" });
    await within(attempts).findAllByRole("listitem");
    expect(within(panel).queryByRole("alert")).not.toBeInTheDocument();
  });

  it("reads the execution's history, scoped to it and the newest first", async () => {
    renderAt(`/executions?id=EF-1`);
    const panel = await openDetail();
    const history = await openHistory(panel);
    expect(
      (await within(history).findAllByText("Retry failed")).length,
    ).toBeGreaterThan(0);
    const query = vi.mocked(historySource.paged).mock.calls.at(-1)![0];
    expect(JSON.stringify(query.filter)).toContain('"value":"EF-1"');
    expect(query.sort?.[0]).toEqual({ field: "version", direction: "DESC" });
  });

  it("opens a stream of the history over the execution, its payload read whole", async () => {
    renderAt(`/executions?id=EF-1`);
    const panel = await openDetail();
    const history = await openHistory(panel);
    const row = (
      await within(history).findAllByText("Retry failed")
    )[0]!.closest("tr")!;
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
    // The stream of the row pressed, by its key.
    expect(JSON.stringify(asked.filter)).toContain(
      `"value":"${row.dataset.rowKey}"`,
    );
  });

  it("follows a press on a row into the address, and a close out of it", async () => {
    const router = renderAt("/executions");
    const row = (await screen.findByText("OrderSaga")).closest("tr")!;
    fireEvent.keyDown(row, { key: "Enter" });
    await openDetail();
    await waitFor(() =>
      expect(router.state.location.search).toBe(`?id=EF-1`),
    );

    fireEvent.keyDown(await screen.findByRole("dialog"), { key: "Escape" });
    await waitFor(() => expect(router.state.location.search).toBe(""));
  });

  it("opens one the page does not hold, and says when it is not there", async () => {
    renderAt(`/executions?id=EF-9`);
    const panel = await openDetail();
    expect(
      within(panel).getByRole("heading", {
        name: "OrderSaga.onOrderCreated",
        level: 2,
      }),
    ).toBeInTheDocument();
    expect(
      panel.querySelector('[data-slot="record-detail-key"]'),
    ).toHaveTextContent("EF-9");
    expect(source.paged).toHaveBeenCalledWith(
      expect.objectContaining({
        pagination: expect.objectContaining({ size: 1 }),
      }),
      undefined,
      expect.anything(),
    );
  });

  it("says a linked execution is gone rather than drawing forms for it", async () => {
    renderAt(`/executions?id=EF-404`);
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
    renderAt(`/executions?id=EF-1`, sent);
    const panel = await openDetail();
    const form = await openForm(panel, "Apply retry specification");
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
    // Taken: the form folds away and the result is said beside its button.
    await waitFor(() =>
      expect(
        within(panel).queryByRole("form", {
          name: "Apply retry specification",
        }),
      ).not.toBeInTheDocument(),
    );
    expect(panel).toHaveTextContent("Retry specification updated");
  });

  it("changes the function, and keeps the service's refusal beside the form", async () => {
    const sent = commands();
    sent.changeFunction.mockRejectedValueOnce(new Error("Function not found."));
    renderAt(`/executions?id=EF-1`, sent);
    const panel = await openDetail();
    const form = await openForm(panel, "Change function");
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
    renderAt(`/executions?id=EF-1`);
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
    renderAt(`/executions?id=EF-1`);
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
    renderAt(`/executions?id=EF-1`);
    const panel = await openDetail();
    const trace = within(panel).getByRole("region", { name: "Stack trace" });
    expect(within(trace).getByText("No stack trace")).toBeInTheDocument();
  });
});
