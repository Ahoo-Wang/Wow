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

import { expect, test, type Page } from "@playwright/test";
import {
  stubExecutionFailedCommands,
  stubExecutionFailedService,
  type Snapshot,
} from "./support/executionFailedService.ts";

type AggregationQueryBody = {
  filter?: unknown;
  groupBy?: Array<{ alias: string }>;
  metrics?: Array<{ alias: string }>;
};

function metricAliases(query: AggregationQueryBody) {
  return query.metrics?.map(({ alias }) => alias) ?? [];
}

function queryWindow(query: AggregationQueryBody, field: string) {
  const matches: Array<{ field?: string; op?: string; value?: number }> = [];
  const visit = (value: unknown) => {
    if (!value || typeof value !== "object") {
      return;
    }
    const node = value as Record<string, unknown>;
    if (node.field === field) {
      matches.push(node);
    }
    Object.values(node).forEach(visit);
  };
  visit(query.filter);
  return {
    end: matches.find(({ op }) => op === "LT")?.value,
    start: matches.find(({ op }) => op === "GTE")?.value,
  };
}

const execution = {
  id: "E2E-TR-14",
  status: "FAILED",
  recoverable: "RECOVERABLE",
  error: {
    errorCode: "E2E_ERROR",
    errorMsg: "Connection prematurely closed BEFORE response",
    stackTrace: "at e2e.CompensationTest.run(CompensationTest.java:14)",
    bindingErrors: [],
    succeeded: false,
  },
  eventId: {
    id: "event-e2e-14",
    version: 656,
    aggregateId: {
      contextName: "openapi-service",
      aggregateName: "quotation",
      aggregateId: "quotation-e2e-14",
      tenantId: "(0)",
    },
  },
  executeAt: 1_735_000_000_000,
  function: {
    contextName: "openapi-service",
    processorName: "QuotationSaga",
    name: "onQuotationApplied",
    functionKind: "EVENT",
  },
  retrySpec: { maxRetries: 3, minBackoff: 180, executionTimeout: 120 },
  retryState: {
    nextRetryAt: 1_735_000_180_000,
    retries: 1,
    retryAt: 1_735_000_000_000,
    timeoutAt: 1_735_000_120_000,
  },
  isBelowRetryThreshold: true,
  isRetryable: true,
};

const executionHistory = {
  id: "history-stream-e2e-2",
  aggregateId: execution.id,
  aggregateName: "execution_failed",
  contextName: "compensation",
  tenantId: "(0)",
  ownerId: "",
  spaceId: "",
  commandId: "history-command-e2e-2",
  requestId: "history-request-e2e-2",
  version: 2,
  createTime: 1_735_000_180_000,
  header: {},
  body: [
    {
      id: "history-event-e2e-2",
      name: "execution_failed_applied",
      bodyType: "me.ahoo.wow.compensation.api.ExecutionFailedApplied",
      revision: "1.0.0",
      body: {
        executeAt: 1_735_000_180_000,
        recoverable: "RECOVERABLE",
        error: execution.error,
      },
    },
  ],
};

async function mockAnalyticsAggregations(
  page: Page,
  callbacks: {
    failSnapshotAlias?: string;
    snapshotErrorMessage?: string;
    onEvent: (query: AggregationQueryBody) => void;
    onSnapshot: (query: AggregationQueryBody) => void;
  },
) {
  const pressureClusters = [
    ["TEST_TIMEOUT", "billing", "OrderProcessor", "run", "EVENT", 120, 90, 30],
    [
      "BAD_REQUEST",
      "orders",
      "OrderItemReservedTrackEventProcessor",
      "onPickupOrderItemReservedConfirmed",
      "EVENT",
      80,
      70,
      10,
    ],
    [
      "NOT_FOUND",
      "catalog",
      "ProductProjectionProcessor",
      "onProductChanged",
      "EVENT",
      60,
      60,
      0,
    ],
    [
      "CONFLICT",
      "inventory",
      "ReservationProcessor",
      "reserve",
      "COMMAND",
      40,
      30,
      10,
    ],
    [
      "UNAVAILABLE",
      "payment",
      "PaymentProcessor",
      "charge",
      "COMMAND",
      20,
      15,
      5,
    ],
  ] as const;

  await page.route(
    "**/execution_failed/snapshot/aggregation",
    async (route) => {
      const query = route.request().postDataJSON() as AggregationQueryBody;
      callbacks.onSnapshot(query);
      const aliases = query.groupBy?.map(({ alias }) => alias) ?? [];
      if (
        callbacks.failSnapshotAlias &&
        aliases.includes(callbacks.failSnapshotAlias)
      ) {
        await route.fulfill({
          status: 500,
          contentType: "application/json",
          body: JSON.stringify({
            message:
              callbacks.snapshotErrorMessage ?? "analytics section unavailable",
          }),
        });
        return;
      }
      let rows: Array<Record<string, unknown>>;

      if (aliases.includes("errorCode") && aliases.includes("status")) {
        rows = pressureClusters.flatMap(
          ([
            errorCode,
            contextName,
            processorName,
            functionName,
            functionKind,
            ,
            failedCount,
            preparedCount,
          ]) => [
            {
              errorCode,
              contextName,
              processorName,
              functionName,
              functionKind,
              status: "FAILED",
              statusCount: failedCount,
            },
            {
              errorCode,
              contextName,
              processorName,
              functionName,
              functionKind,
              status: "PREPARED",
              statusCount: preparedCount,
            },
          ],
        );
      } else if (aliases.includes("errorCode")) {
        rows = pressureClusters.map(
          ([
            errorCode,
            contextName,
            processorName,
            functionName,
            functionKind,
            currentCount,
          ]) => ({
            errorCode,
            contextName,
            processorName,
            functionName,
            functionKind,
            currentCount,
            oldestExecuteAt: 1_787_846_400_000,
            nextRetryAt: 1_787_932_800_000,
          }),
        );
      } else if (aliases.includes("recoverable")) {
        rows = [
          { recoverable: "RECOVERABLE", count: 300 },
          { recoverable: "UNKNOWN", count: 10 },
          { recoverable: "UNRECOVERABLE", count: 10 },
        ];
      } else if (metricAliases(query).includes("actionableNow")) {
        rows = [
          {
            actionableNow: 128,
            activeTotal: 1_000,
            newerThanRange: 0,
            olderThanRange: 680,
            selectedInRange: 320,
            timedOut: 34,
            unrecoverable: 9,
          },
        ];
      } else if (metricAliases(query).includes("sixPlus")) {
        rows = [{ oneToTwo: 4, sixPlus: 1, threeToFive: 2, zero: 5 }];
      } else {
        rows = [{ count: 9 }];
      }
      await route.fulfill({ json: rows });
    },
  );

  await page.route("**/execution_failed/event/aggregation", async (route) => {
    const query = route.request().postDataJSON() as AggregationQueryBody;
    callbacks.onEvent(query);
    const { start } = queryWindow(query, "createTime");
    const name = JSON.stringify(query).match(
      /execution_(?:failed_created|failed_applied|success_applied)|compensation_prepared/,
    )?.[0];
    const counts: Record<string, number> = {
      execution_failed_created: 12,
      compensation_prepared: 6,
      execution_failed_applied: 4,
      execution_success_applied: 2,
    };
    await route.fulfill({
      json: [
        {
          bucket: start,
          streamCount: name ? counts[name] : 0,
        },
      ],
    });
  });
}

// The old queues' detail scenarios, said again of the page their addresses
// open since batch 5: the failed executions' workbench and its detail drawer.
// Each starts from an old address, so the redirect is under test too.

/** The fixture as the snapshot the workbench reads (`…/snapshot/paged`). */
function snapshotOf(state: typeof execution): Snapshot {
  return {
    aggregateId: state.id,
    firstEventTime: state.executeAt,
    eventTime: state.executeAt,
    state,
  };
}

function detailDrawer(page: Page) {
  return page.getByRole("dialog", { name: new RegExp(execution.id) });
}

/** Opens the fixture's row, as a reader does, and waits for the whole record. */
async function openDetails(page: Page) {
  await page.getByRole("row", { name: new RegExp(execution.id) }).press("Enter");
  const panel = detailDrawer(page);
  await expect(
    panel.getByRole("heading", { name: execution.id, level: 2 }),
  ).toBeVisible();
  await expect(
    panel.getByRole("form", { name: "Change function" }),
  ).toBeVisible();
  const close = await panel.getByRole("button", { name: "Close" }).boundingBox();
  expect(close?.width).toBeGreaterThanOrEqual(24);
  expect(close?.height).toBeGreaterThanOrEqual(24);
  return panel;
}

test("loads the deterministic queue and responsive execution details", async ({
  page,
}) => {
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );
  const queries = await stubExecutionFailedService(page, [
    snapshotOf(execution),
  ]);

  await page.goto("/to-retry");
  await expect(page).toHaveURL(
    /\/executions\?view=system%3Aexecution-failed%3Ato-retry$/,
  );
  const workbench = page.getByRole("region", { name: "To retry" });
  await expect(
    workbench.getByRole("row", { name: new RegExp(execution.id) }),
  ).toBeVisible();
  const panel = await openDetails(page);

  // The failed event's version, and the retry spec as the form holds it.
  await expect(
    panel.getByRole("region", { name: "Failed event", exact: true }),
  ).toContainText("656");
  const spec = panel.getByRole("form", { name: "Apply retry specification" });
  await expect(spec.getByLabel("Min backoff")).toHaveValue("180");
  for (const name of ["Execution history", "Stack trace"])
    await expect(
      panel.getByRole("region", { name, exact: true }),
    ).toBeVisible();
  // The view's own order: the most recently changed first, the ID breaking
  // a tie (a read of the one record the detail opens is by its key).
  const listed = queries.paged.find(({ pagination }) => pagination.size > 1);
  expect(listed).toMatchObject({
    sort: [
      { field: "eventTime", direction: "DESC" },
      { field: "state.id", direction: "ASC" },
    ],
  });
});

test("copies identifiers when the Clipboard API is unavailable", async ({
  page,
}) => {
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );
  await stubExecutionFailedService(page, [snapshotOf(execution)]);

  await page.goto("/to-retry");
  const panel = await openDetails(page);
  await page.evaluate(() => {
    const execCommand = document.execCommand.bind(document);
    Object.defineProperty(document, "execCommand", {
      configurable: true,
      value: (commandId: string, showUI?: boolean, valueArgument?: string) => {
        const area = document.activeElement;
        if (commandId === "copy" && area instanceof HTMLTextAreaElement)
          Object.defineProperty(window, "__copiedText", {
            configurable: true,
            value: area.value.slice(area.selectionStart, area.selectionEnd),
          });
        return execCommand(commandId, showUI, valueArgument);
      },
    });
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: undefined,
    });
  });
  expect(await page.evaluate(() => navigator.clipboard)).toBeUndefined();

  for (const value of [
    execution.id,
    execution.eventId.id,
    execution.eventId.aggregateId.aggregateId,
  ]) {
    const copyButton = panel.getByRole("button", {
      name: `Copy ${value}`,
      exact: true,
    });
    await copyButton.hover();
    await copyButton.click();
    await expect(
      panel.getByRole("button", { name: "Copied" }).first(),
    ).toBeVisible();
    await expect
      .poll(() =>
        page.evaluate(
          () => (window as Window & { __copiedText?: string }).__copiedText,
        ),
      )
      .toBe(value);
  }
  await expect(panel.getByText(/Could not copy|Unable to copy/)).toHaveCount(0);
});

test("loads lifecycle history through the paged EventStream REST API", async ({
  page,
}) => {
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );
  const historyQueries: Array<Record<string, unknown>> = [];
  await stubExecutionFailedService(page, [snapshotOf(execution)]);
  await page.route("**/execution_failed/event/paged", async (route) => {
    historyQueries.push(route.request().postDataJSON());
    await route.fulfill({ json: { total: 1, list: [executionHistory] } });
  });

  await page.goto("/to-retry");
  const panel = await openDetails(page);
  const history = panel.locator('[data-section="history"]');
  await history.scrollIntoViewIfNeeded();
  const stream = history.getByRole("row").nth(1);
  await expect(stream).toContainText("Retry failed");
  await expect(stream).toContainText("history-command-e2e-2");
  // The newest first; the stream id only breaks a tie.
  expect(historyQueries[0]).toMatchObject({
    sort: [
      { field: "version", direction: "DESC" },
      { field: "id", direction: "ASC" },
    ],
    pagination: { index: 1, size: 10 },
  });
  expect(JSON.stringify(historyQueries[0].filter)).toContain(
    `"value":"${execution.id}"`,
  );

  // The event's payload, the old detail's 「事件载荷」: the stream opens
  // read whole, in a drawer over the execution's.
  await stream.press("Enter");
  const payload = page.getByRole("dialog", { name: /history-stream-e2e-2/ });
  await expect(payload.getByText("E2E_ERROR").first()).toBeVisible();
  await expect(
    payload.getByText("Connection prematurely closed BEFORE response").first(),
  ).toBeVisible();
  expect(historyQueries.at(-1)).toMatchObject({
    pagination: { index: 1, size: 1 },
  });
  expect(JSON.stringify(historyQueries.at(-1)?.filter)).toContain(
    '"value":"history-stream-e2e-2"',
  );

  // Escape closes the inner drawer alone, and the reader is back on the row.
  await page.keyboard.press("Escape");
  await expect(payload).toBeHidden();
  await expect(panel).toBeVisible();
  await expect(stream).toBeFocused();
});

test("enables prepared actions only after the execution timeout", async ({
  page,
}) => {
  const now = Date.parse("2026-09-20T00:00:00Z");
  await page.clock.install({ time: now });
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );
  const prepared = snapshotOf({
    ...execution,
    status: "PREPARED",
    retryState: { ...execution.retryState, timeoutAt: now + 60_000 },
  });
  await stubExecutionFailedService(page, [prepared], { now });

  await page.goto("/executing");
  await expect(page).toHaveURL(
    /\/executions\?view=system%3Aexecution-failed%3Aexecuting$/,
  );
  const panel = await openDetails(page);

  const prepare = panel.getByRole("button", { name: "Prepare", exact: true });
  await expect(prepare).toBeDisabled();
  await expect(prepare).toHaveAccessibleDescription(
    "Execution is in progress; wait until it times out.",
  );
  await panel
    .getByRole("button", { name: `Actions for ${execution.id}` })
    .click();
  await expect(
    page.getByRole("menuitem", { name: "Force prepare" }),
  ).toBeDisabled();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("menu")).toBeHidden();

  await page.clock.fastForward(60_001);
  await expect(prepare).toBeEnabled();
  await panel
    .getByRole("button", { name: `Actions for ${execution.id}` })
    .click();
  await expect(
    page.getByRole("menuitem", { name: "Force prepare" }),
  ).toBeEnabled();
});

test("preserves and freezes last-known-good data after refresh fails", async ({
  page,
}) => {
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );
  const documents = [snapshotOf(execution)];
  await stubExecutionFailedService(page, documents);
  const sent = await stubExecutionFailedCommands(page, documents);
  let failing = false;
  await page.route("**/execution_failed/snapshot/paged", async (route) => {
    if (!failing) return route.fallback();
    await route.fulfill({ status: 503, body: "refresh unavailable" });
  });

  await page.goto("/to-retry");
  const panel = await openDetails(page);
  failing = true;
  await panel.getByRole("button", { name: "Prepare", exact: true }).click();
  await expect.poll(() => sent.length).toBe(1);
  expect(sent[0]).toMatchObject({
    id: execution.id,
    command: "prepare_compensation",
    waitStage: "SNAPSHOT",
  });

  // The workbench behind the drawer says it could not load, and keeps the
  // row it had; the open execution stays open with what it read.
  const workbench = page.getByRole("region", {
    name: "To retry",
    includeHidden: true,
  });
  await expect(workbench.getByText(/Could not load the data/)).toBeVisible();
  await expect(
    workbench.getByRole("row", {
      name: new RegExp(execution.id),
      includeHidden: true,
    }),
  ).toHaveCount(1);
  await expect(
    panel.getByRole("heading", { name: execution.id, level: 2 }),
  ).toBeVisible();
});

test("loads the root dashboard with natural Top 5 pressure height", async ({
  page,
}, testInfo) => {
  const consoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") {
      consoleErrors.push(message.text());
    }
  });
  if (testInfo.project.name === "desktop-chromium") {
    await page.setViewportSize({ width: 1280, height: 720 });
  }
  let snapshotRequests = 0;
  let eventRequests = 0;
  const snapshotQueries: AggregationQueryBody[] = [];
  const eventQueries: AggregationQueryBody[] = [];
  await mockAnalyticsAggregations(page, {
    onEvent: (query) => {
      eventRequests++;
      eventQueries.push(query);
    },
    onSnapshot: (query) => {
      snapshotRequests++;
      snapshotQueries.push(query);
    },
  });

  await page.goto("/");

  expect(await page.evaluate(() => location.pathname)).toBe("/");
  await expect(
    page.getByRole("heading", { name: "Overview", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText(/Failure concentration · Top cluster/),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "STOCK / Backlog exposure" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "FLOW / Compensation effectiveness" }),
  ).toBeVisible();
  await expect.poll(() => snapshotRequests).toBe(5);
  await expect.poll(() => eventRequests).toBe(4);
  const timeRange = page.getByRole("button", { name: /^Time range:/ });
  await expect(timeRange).toContainText("–");
  await timeRange.click();
  const pickerSizing = await page.getByRole("dialog").evaluate((dialog) => {
    const calendar = dialog.querySelector<HTMLElement>(
      "[data-slot='calendar']",
    );
    if (!calendar) {
      throw new Error("Date range calendar is missing");
    }
    const dialogBounds = dialog.getBoundingClientRect();
    const calendarBounds = calendar.getBoundingClientRect();
    return {
      calendarWidth: calendarBounds.width,
      dialogLeft: dialogBounds.left,
      dialogRight: dialogBounds.right,
      dialogWidth: dialogBounds.width,
      viewportWidth: window.innerWidth,
    };
  });
  expect(
    Math.abs(pickerSizing.dialogWidth - pickerSizing.calendarWidth),
  ).toBeLessThanOrEqual(2);
  expect(pickerSizing.dialogLeft).toBeGreaterThanOrEqual(0);
  expect(pickerSizing.dialogRight).toBeLessThanOrEqual(
    pickerSizing.viewportWidth,
  );
  for (const label of ["Today", "Last 7 days", "Last 30 days"]) {
    const shortcut = page.getByRole("button", { name: label, exact: true });
    await expect(shortcut).toBeVisible();
    expect(
      await shortcut.evaluate((element) =>
        Number.parseFloat(getComputedStyle(element).fontSize),
      ),
    ).toBeGreaterThanOrEqual(14);
  }
  await page.getByRole("button", { name: "Today", exact: true }).click();
  await expect.poll(() => snapshotRequests).toBe(10);
  await expect.poll(() => eventRequests).toBe(8);
  await page.getByRole("button", { name: "Refresh dashboard" }).click();
  await expect.poll(() => snapshotRequests).toBe(15);
  await expect.poll(() => eventRequests).toBe(12);

  const appliedWindows: Array<{ end: number; start: number }> = [];
  for (const batch of [0, 1, 2]) {
    const batchSnapshotQueries = snapshotQueries.slice(
      batch * 5,
      batch * 5 + 5,
    );
    const summaryQueries = batchSnapshotQueries.filter((query) =>
      metricAliases(query).includes("actionableNow"),
    );
    expect(summaryQueries).toHaveLength(1);
    expect(
      summaryQueries[0].metrics?.map(({ alias }) => alias).sort(),
    ).toEqual([
      "actionableNow",
      "activeTotal",
      "newerThanRange",
      "olderThanRange",
      "selectedInRange",
      "timedOut",
      "unrecoverable",
    ]);
    const snapshotWindows = batchSnapshotQueries.map((query) =>
      queryWindow(query, "state.executeAt"),
    );
    const fullyWindowedSnapshots = snapshotWindows.filter(
      ({ end, start }) => Number.isFinite(start) && Number.isFinite(end),
    );
    expect(fullyWindowedSnapshots).toHaveLength(4);
    const eventWindows = eventQueries
      .slice(batch * 4, batch * 4 + 4)
      .map((query) => queryWindow(query, "createTime"));
    const windows = [...fullyWindowedSnapshots, ...eventWindows];
    for (const { end, start } of windows) {
      expect(Number.isFinite(start)).toBe(true);
      expect(Number.isFinite(end)).toBe(true);
      expect(start).toBeLessThan(end as number);
    }
    expect(
      new Set(windows.map(({ end, start }) => `${start}:${end}`)).size,
    ).toBe(1);
    appliedWindows.push(windows[0] as { end: number; start: number });
  }
  expect(appliedWindows[0]).not.toEqual(appliedWindows[1]);
  expect(appliedWindows[1]).toEqual(appliedWindows[2]);

  const pressureTable = page.getByRole("table", {
    name: "Current failure pressure",
  });
  await expect(pressureTable.getByRole("row")).toHaveCount(6);
  await expect(page.getByText("Refreshing…")).toHaveCount(0);
  await page.evaluate(() => document.fonts.ready);

  const dashboard = page.locator(".dashboard-view");
  const overflow = await dashboard.evaluate((element) => ({
    clientHeight: element.clientHeight,
    clientWidth: element.clientWidth,
    scrollHeight: element.scrollHeight,
    scrollWidth: element.scrollWidth,
  }));
  if (testInfo.project.name === "desktop-chromium") {
    expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth);
    const pressureSizing = await page
      .locator(".dashboard-pressure-table")
      .evaluate((element) => {
        const container = element.getBoundingClientRect();
        const table = element.querySelector("table")?.getBoundingClientRect();
        const rows = Array.from(element.querySelectorAll("tbody tr"));
        return {
          contentDelta: table
            ? Math.abs(container.height - table.height - 2)
            : Number.POSITIVE_INFINITY,
          overflowY: getComputedStyle(element).overflowY,
          rowsVisible: rows.every((row) => {
            const bounds = row.getBoundingClientRect();
            return (
              bounds.top >= container.top && bounds.bottom <= container.bottom
            );
          }),
        };
      });
    expect(pressureSizing.contentDelta).toBeLessThanOrEqual(2);
    expect(pressureSizing.overflowY).not.toMatch(/auto|scroll/);
    expect(pressureSizing.rowsVisible).toBe(true);
    await expect(page.getByText("Swipe to view more")).toBeHidden();
    for (const name of [
      "STOCK / Backlog exposure",
      "FLOW / Compensation effectiveness",
      "Daily trend",
      "Outcome flow (total in selected range)",
      "Recoverability composition",
      "Retry distribution",
    ]) {
      await expect(page.getByRole("heading", { name })).toBeVisible();
    }
    for (const label of ["1–2 retries", "3–5 retries"]) {
      await expect(
        page
          .getByRole("img", { name: /Retry distribution:/ })
          .getByText(label, { exact: true }),
      ).toBeVisible();
    }
    const activity = page.getByRole("region", {
      name: "Compensation activity",
    });
    await expect(activity.getByText("12 new failures")).toBeVisible();
    await expect(
      activity.getByRole("img", { name: /Outcome flow:/ }),
    ).toBeVisible();
    const fontTargets = [
      page.getByText("Actionable now", { exact: true }),
      pressureTable.locator("tbody tr").first().locator("td").nth(1),
      pressureTable
        .locator("tbody tr")
        .first()
        .locator("td")
        .first()
        .locator(".text-muted-foreground"),
      pressureTable.locator("tbody tr").first().getByText("90 (75%)"),
      page
        .getByRole("region", { name: "Recoverability composition" })
        .getByText("Recoverable", { exact: true }),
      page
        .getByRole("region", { name: "Retry distribution" })
        .getByText("1–2 retries", { exact: true }),
      page
        .getByRole("region", { name: "Retry distribution" })
        .getByText("4 (33%)", { exact: true }),
      activity.locator(".dashboard-series-label"),
      activity.getByText("12 new failures", { exact: true }),
    ];
    for (const target of fontTargets) {
      await expect(target).toBeVisible();
      const fontSize = await target.evaluate((element) =>
        Number.parseFloat(getComputedStyle(element).fontSize),
      );
      expect(fontSize).toBeGreaterThanOrEqual(14);
    }

    await page.setViewportSize({ width: 1440, height: 1024 });
    const tallViewportLayout = await page
      .locator(".dashboard-view")
      .evaluate((dashboard) => {
        const overview = dashboard.querySelector<HTMLElement>(
          ".dashboard-overview",
        );
        const activity = dashboard.querySelector<HTMLElement>(
          ".dashboard-activity",
        );
        const health =
          dashboard.querySelector<HTMLElement>(".dashboard-health");
        const healthTitle = dashboard.querySelector<HTMLElement>(
          "#dashboard-health-title",
        );
        const recoverabilityTitle = dashboard.querySelector<HTMLElement>(
          "[aria-label='Recoverability composition'] h3",
        );
        const recoverabilityTotal = dashboard.querySelector<HTMLElement>(
          "[aria-label='Recoverability composition'] .dashboard-chart-total",
        );
        if (
          !overview ||
          !activity ||
          !health ||
          !healthTitle ||
          !recoverabilityTitle ||
          !recoverabilityTotal
        ) {
          throw new Error("Dashboard visual hierarchy is incomplete");
        }
        const activityBounds = activity.getBoundingClientRect();
        const healthTitleBounds = healthTitle.getBoundingClientRect();
        const recoverabilityTitleBounds =
          recoverabilityTitle.getBoundingClientRect();
        return {
          activityHeight: activityBounds.height,
          healthBottom: health.getBoundingClientRect().bottom,
          healthHeight: health.getBoundingClientRect().height,
          healthLeadGap:
            recoverabilityTitleBounds.top - healthTitleBounds.bottom,
          healthOverflow: getComputedStyle(health).overflowY,
          overviewHeight: overview.getBoundingClientRect().height,
          recoverabilityTotalBottom:
            recoverabilityTotal.getBoundingClientRect().bottom,
        };
      });
    expect(tallViewportLayout.overviewHeight).toBeGreaterThanOrEqual(260);
    expect(tallViewportLayout.activityHeight).toBeGreaterThanOrEqual(224);
    expect(tallViewportLayout.healthHeight).toBeGreaterThanOrEqual(140);
    expect(tallViewportLayout.healthLeadGap).toBeLessThanOrEqual(32);
    expect(tallViewportLayout.healthOverflow).not.toBe("hidden");
    expect(tallViewportLayout.recoverabilityTotalBottom).toBeLessThanOrEqual(
      tallViewportLayout.healthBottom,
    );
  }
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);

  if (testInfo.project.name === "mobile-chromium") {
    const sidebarTrigger = page.locator("[data-slot='sidebar-trigger']");
    await expect(sidebarTrigger).toBeVisible();
    await sidebarTrigger.click();
    await expect(sidebarTrigger).toHaveAttribute("aria-expanded", "true");

    const mobileSidebar = page.locator(
      "[data-slot='sidebar'][data-mobile='true']",
    );
    await expect(mobileSidebar).toBeVisible();
    for (const name of ["Overview", "Failed executions"]) {
      await expect(
        mobileSidebar.getByRole("link", { name, exact: true }),
      ).toBeVisible();
    }
    await mobileSidebar
      .getByRole("link", { name: "Wow compensation dashboard" })
      .click();
    await expect(mobileSidebar).toBeHidden();
    await expect(sidebarTrigger).toHaveAttribute("aria-expanded", "false");
    await sidebarTrigger.click();
    await mobileSidebar
      .getByRole("link", { name: "Overview", exact: true })
      .click();
    await expect(mobileSidebar).toBeHidden();
    await expect(page.getByText("Swipe to view more")).toHaveCount(0);
    const pressureContainer = page
      .getByRole("table", { name: "Current failure pressure" })
      .locator("..");
    const pressureOverflow = await pressureContainer.evaluate((element) => ({
      clientWidth: element.clientWidth,
      overflowX: getComputedStyle(element).overflowX,
      scrollWidth: element.scrollWidth,
    }));
    expect(pressureOverflow.scrollWidth).toBeLessThanOrEqual(
      pressureOverflow.clientWidth,
    );
    expect(pressureOverflow.overflowX).not.toMatch(/auto|scroll/);
    const firstPressureCard = await pressureContainer
      .locator("tbody tr")
      .first()
      .evaluate((element) => {
        const bounds = element.getBoundingClientRect();
        const container =
          element.parentElement?.parentElement?.parentElement?.getBoundingClientRect();
        return {
          display: getComputedStyle(element).display,
          right: bounds.right,
          containerRight: container?.right ?? 0,
        };
      });
    expect(firstPressureCard.display).toBe("grid");
    expect(firstPressureCard.right).toBeLessThanOrEqual(
      firstPressureCard.containerRight,
    );
    await page
      .getByRole("heading", { name: "Daily trend" })
      .scrollIntoViewIfNeeded();
    await expect(
      page.getByRole("heading", { name: "Daily trend" }),
    ).toBeVisible();
  }
  expect(consoleErrors).toEqual([]);
});

test("separates multi-day failure inflow from outcome flow", async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== "desktop-chromium");
  await page.setViewportSize({ width: 1440, height: 1024 });
  await mockAnalyticsAggregations(page, {
    onEvent: () => undefined,
    onSnapshot: () => undefined,
  });

  await page.goto("/");

  const activity = page.getByRole("region", { name: "Compensation activity" });
  await expect(
    activity.getByRole("heading", { name: "Daily trend" }),
  ).toBeVisible();
  await expect(
    activity.getByRole("heading", {
      name: "Outcome flow (total in selected range)",
    }),
  ).toBeVisible();
  await expect(
    activity.getByRole("img", { name: /Outcome flow:/ }),
  ).toBeVisible();
  expect((await activity.boundingBox())?.height).toBeGreaterThanOrEqual(200);

  await page.setViewportSize({ width: 1440, height: 800 });
  const activityContainment = await page
    .locator(".dashboard-activity")
    .evaluate((card) => {
      const chart = card.querySelector<HTMLElement>("[data-slot='chart']");
      const outcomeBars = card.querySelector<HTMLElement>(
        ".dashboard-outcome-flow-bars",
      );
      if (!chart || !outcomeBars) {
        throw new Error("Dashboard activity content is missing");
      }
      return {
        cardBottom: card.getBoundingClientRect().bottom,
        cardHeight: card.getBoundingClientRect().height,
        contentBottom: Math.max(
          chart.getBoundingClientRect().bottom,
          outcomeBars.getBoundingClientRect().bottom,
        ),
      };
    });
  expect(activityContainment.contentBottom).toBeLessThanOrEqual(
    activityContainment.cardBottom,
  );
  expect(activityContainment.cardHeight).toBeGreaterThanOrEqual(224);
});

test("keeps zero-valued dashboard bars visually empty", async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== "desktop-chromium");
  await page.setViewportSize({ width: 1440, height: 1024 });
  await mockAnalyticsAggregations(page, {
    onEvent: () => undefined,
    onSnapshot: () => undefined,
  });
  await page.route(
    "**/execution_failed/snapshot/aggregation",
    async (route) => {
      const query = route.request().postDataJSON() as AggregationQueryBody;
      const aliases = query.groupBy?.map(({ alias }) => alias) ?? [];
      if (aliases.includes("recoverable")) {
        await route.fulfill({ json: [] });
        return;
      }
      if (metricAliases(query).includes("actionableNow")) {
        await route.fulfill({
          json: [
            {
              actionableNow: 0,
              activeTotal: 680,
              newerThanRange: 0,
              olderThanRange: 680,
              selectedInRange: 0,
              timedOut: 0,
              unrecoverable: 0,
            },
          ],
        });
        return;
      }
      if (metricAliases(query).includes("sixPlus")) {
        await route.fulfill({
          json: [{ oneToTwo: 0, sixPlus: 0, threeToFive: 0, zero: 0 }],
        });
        return;
      }
      await route.fallback();
    },
  );
  await page.route("**/execution_failed/event/aggregation", async (route) => {
    const query = route.request().postDataJSON() as AggregationQueryBody;
    const { start } = queryWindow(query, "createTime");
    await route.fulfill({ json: [{ bucket: start, streamCount: 0 }] });
  });

  await page.goto("/");

  const coverageWidth = await page
    .locator(".dashboard-stock-progress [data-slot='progress-indicator']")
    .evaluate((indicator) => indicator.getBoundingClientRect().width);
  expect(coverageWidth).toBe(0);
  const outcomeWidths = await page
    .locator(".dashboard-outcome-flow-track > span")
    .evaluateAll((indicators) =>
      indicators.map((indicator) => indicator.getBoundingClientRect().width),
    );
  expect(outcomeWidths).toEqual([0, 0, 0]);
});

test("hides desktop navigation labels when collapsed", async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== "desktop-chromium");
  await mockAnalyticsAggregations(page, {
    onEvent: () => undefined,
    onSnapshot: () => undefined,
  });

  await page.goto("/");

  const labels = ["Overview", "Failed executions"].map((name) =>
    page.getByRole("link", { name, exact: true }).locator("span"),
  );
  for (const label of labels) {
    await expect(label).toBeVisible();
  }
  await page.getByRole("button", { name: "Collapse navigation" }).click();
  for (const label of labels) {
    await expect(label).toBeHidden();
  }
  await expect
    .poll(() =>
      page
        .locator("[data-slot='sidebar-container']")
        .evaluate((element) =>
          Math.round(element.getBoundingClientRect().width),
        ),
    )
    .toBe(56);
  const alignment = await page.evaluate(() => {
    const sidebar = document.querySelector<HTMLElement>(
      "[data-slot='sidebar-container']",
    );
    const dashboard = document.querySelector<HTMLElement>(
      "a[aria-label='Overview']",
    );
    const dashboardIcon = dashboard?.querySelector<SVGElement>("svg");
    const activeExecutions = document.querySelector<HTMLElement>(
      "a[aria-label='Failed executions']",
    );
    const footer = document.querySelector<HTMLElement>(
      "button[aria-label='Expand navigation']",
    );
    const footerIcon = footer?.querySelector<SVGElement>("svg");
    if (
      !sidebar ||
      !dashboard ||
      !dashboardIcon ||
      !activeExecutions ||
      !footer ||
      !footerIcon
    ) {
      throw new Error("Collapsed navigation alignment targets are missing");
    }
    const center = (element: Element) => {
      const bounds = element.getBoundingClientRect();
      return bounds.left + bounds.width / 2;
    };
    return {
      dashboard: center(dashboard),
      dashboardIcon: center(dashboardIcon),
      dashboardIconSize: dashboardIcon.getBoundingClientRect().width,
      menuGap:
        activeExecutions.getBoundingClientRect().top -
        dashboard.getBoundingClientRect().bottom,
      footer: center(footer),
      footerIcon: center(footerIcon),
      footerIconSize: footerIcon.getBoundingClientRect().width,
      sidebar: center(sidebar),
    };
  });
  for (const center of [
    alignment.dashboard,
    alignment.dashboardIcon,
    alignment.footer,
    alignment.footerIcon,
  ]) {
    expect(Math.abs(center - alignment.sidebar)).toBeLessThanOrEqual(0.5);
  }
  expect(alignment.dashboardIconSize).toBe(20);
  expect(alignment.footerIconSize).toBe(20);
  expect(alignment.menuGap).toBe(4);
});

test("stacks mobile dashboard regions without overlap", async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== "mobile-chromium");
  await mockAnalyticsAggregations(page, {
    onEvent: () => undefined,
    onSnapshot: () => undefined,
  });

  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "STOCK / Backlog exposure" }),
  ).toBeVisible();

  const mobileLayout = await page
    .locator(".dashboard-view")
    .evaluate((dashboard) => {
      const overview = dashboard.querySelector<HTMLElement>(
        ".dashboard-overview",
      );
      const stock = dashboard.querySelector<HTMLElement>(".dashboard-stock");
      const flow = dashboard.querySelector<HTMLElement>(".dashboard-flow");
      const activity = dashboard.querySelector<HTMLElement>(
        ".dashboard-activity",
      );
      const failureInflow = dashboard.querySelector<HTMLElement>(
        ".dashboard-failure-inflow",
      );
      const outcomeFlow = dashboard.querySelector<HTMLElement>(
        ".dashboard-outcome-flow",
      );
      const health = dashboard.querySelector<HTMLElement>(".dashboard-health");
      const pressure = dashboard.querySelector<HTMLElement>(
        ".dashboard-pressure",
      );
      const recoverability = dashboard.querySelector<HTMLElement>(
        "[aria-label='Recoverability composition']",
      );
      const retries = dashboard.querySelector<HTMLElement>(
        "[aria-label='Retry distribution']",
      );
      if (
        !overview ||
        !stock ||
        !flow ||
        !activity ||
        !failureInflow ||
        !outcomeFlow ||
        !health ||
        !pressure ||
        !recoverability ||
        !retries
      ) {
        throw new Error("Mobile dashboard hierarchy is incomplete");
      }
      return {
        activityBottom: activity.getBoundingClientRect().bottom,
        flowTop: flow.getBoundingClientRect().top,
        healthBottom: health.getBoundingClientRect().bottom,
        outcomeFlowTop: outcomeFlow.getBoundingClientRect().top,
        overviewBottom: overview.getBoundingClientRect().bottom,
        pressureTop: pressure.getBoundingClientRect().top,
        recoverabilityBottom: recoverability.getBoundingClientRect().bottom,
        retriesTop: retries.getBoundingClientRect().top,
        stockBottom: stock.getBoundingClientRect().bottom,
        failureInflowBottom: failureInflow.getBoundingClientRect().bottom,
      };
    });
  expect(mobileLayout.flowTop).toBeGreaterThanOrEqual(mobileLayout.stockBottom);
  expect(mobileLayout.outcomeFlowTop).toBeGreaterThanOrEqual(
    mobileLayout.failureInflowBottom,
  );
  expect(mobileLayout.retriesTop).toBeGreaterThanOrEqual(
    mobileLayout.recoverabilityBottom,
  );
  expect(mobileLayout.activityBottom).toBeGreaterThanOrEqual(
    mobileLayout.overviewBottom,
  );
  expect(mobileLayout.pressureTop).toBeGreaterThanOrEqual(
    mobileLayout.healthBottom,
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("reflows pressure rows from the available card width", async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== "desktop-chromium");
  await page.setViewportSize({ width: 900, height: 900 });
  await mockAnalyticsAggregations(page, {
    onEvent: () => undefined,
    onSnapshot: () => undefined,
  });

  await page.goto("/");

  const pressure = page.getByRole("region", {
    name: /Failure concentration/,
  });
  const firstRow = pressure.locator("tbody tr").first();
  await expect(firstRow).toBeVisible();
  expect(
    await firstRow.evaluate((element) => getComputedStyle(element).display),
  ).toBe("grid");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("shows a distinct dashboard skeleton while initial data is pending", async ({
  page,
}) => {
  let release: () => void = () => undefined;
  const pending = new Promise<void>((resolve) => {
    release = resolve;
  });
  await mockAnalyticsAggregations(page, {
    onEvent: () => undefined,
    onSnapshot: () => undefined,
  });
  for (const pattern of [
    "**/execution_failed/snapshot/aggregation",
    "**/execution_failed/event/aggregation",
  ]) {
    await page.route(pattern, async (route) => {
      await pending;
      await route.fallback();
    });
  }

  try {
    await page.goto("/");
    await expect(
      page.getByRole("button", { name: /^Time range:/ }),
    ).toBeVisible();
    const loading = page.getByRole("status", { name: "Loading dashboard" });
    await expect(loading).toBeVisible();
    await expect(
      page.locator(".dashboard-view [data-slot='skeleton']").first(),
    ).toHaveClass(/bg-muted/);
  } finally {
    release();
  }

  await expect(
    page.getByRole("heading", { name: "STOCK / Backlog exposure" }),
  ).toBeVisible();
});

test("redirects Dashboard aliases and fallback to the root", async ({
  page,
}) => {
  for (const path of ["/dashboard", "/analytics", "/missing-dashboard-route"]) {
    await page.goto(path);
    expect(await page.evaluate(() => location.pathname)).toBe("/");
  }
});

test("isolates one failed analytics region", async ({ page }) => {
  await mockAnalyticsAggregations(page, {
    failSnapshotAlias: "recoverable",
    onEvent: () => undefined,
    onSnapshot: () => undefined,
  });

  await page.goto("/analytics");

  await expect(page.getByText("TEST_TIMEOUT")).toBeVisible();
  await expect(page.getByRole("alert")).toContainText(
    "analytics section unavailable",
  );
  await expect(
    page.getByRole("heading", {
      name: "Failure inflow (new failures) — daily trend",
    }),
  ).toBeHidden();
  await expect(
    page.getByRole("heading", { name: "Daily trend" }),
  ).toBeVisible();
});

test("keeps a long analytics error wrapped and reachable", async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== "desktop-chromium");
  await page.setViewportSize({ width: 1280, height: 720 });
  const longMessage = `analytics-${"unavailable".repeat(160)}`;
  await mockAnalyticsAggregations(page, {
    failSnapshotAlias: "recoverable",
    snapshotErrorMessage: longMessage,
    onEvent: () => undefined,
    onSnapshot: () => undefined,
  });

  await page.goto("/");

  const alert = page.getByRole("alert");
  await expect(alert).toHaveText(longMessage);
  const layout = await alert.evaluate((element) => {
    const dashboard = document.querySelector<HTMLElement>(".dashboard-view");
    const ownerCard = element.closest<HTMLElement>("[data-slot='card']");
    if (!dashboard || !ownerCard) {
      throw new Error("Dashboard layout is missing");
    }
    return {
      alertClientWidth: element.clientWidth,
      alertBottom: element.getBoundingClientRect().bottom,
      alertScrollWidth: element.scrollWidth,
      dashboardClientHeight: dashboard.clientHeight,
      dashboardScrollHeight: dashboard.scrollHeight,
      overflowWrap: getComputedStyle(element).overflowWrap,
      ownerCardBottom: ownerCard.getBoundingClientRect().bottom,
    };
  });
  expect(layout.overflowWrap).toBe("anywhere");
  expect(layout.alertScrollWidth).toBeLessThanOrEqual(layout.alertClientWidth);
  expect(layout.alertBottom).toBeLessThanOrEqual(layout.ownerCardBottom);
  expect(layout.dashboardScrollHeight).toBeGreaterThanOrEqual(
    layout.dashboardClientHeight,
  );
  await alert.scrollIntoViewIfNeeded();
  await expect(alert).toBeVisible();
});

test("a pressure cluster opens its active executions on the workbench", async ({
  page,
}) => {
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );
  // The workbench's pages; the dashboard's aggregations answer over them.
  const queries = await stubExecutionFailedService(page, [
    snapshotOf(execution),
  ]);
  await mockAnalyticsAggregations(page, {
    onEvent: () => undefined,
    onSnapshot: () => undefined,
  });

  await page.goto("/");
  await page.getByRole("link", { name: "View cluster TEST_TIMEOUT" }).click();

  await expect(page).toHaveURL(
    /\/executions\?view=system%3Aexecution-failed%3Aactive&cluster=/,
  );
  await expect(page.getByRole("region", { name: "Active" })).toBeVisible();
  await expect(
    page.getByText("This view is narrowed by the link it was opened from."),
  ).toBeVisible();
  await expect
    .poll(() => JSON.stringify(queries.paged.at(-1)?.filter))
    .toContain('"value":"TEST_TIMEOUT"');
  expect(JSON.stringify(queries.paged.at(-1)?.filter)).toContain(
    '"value":"OrderProcessor"',
  );
});
