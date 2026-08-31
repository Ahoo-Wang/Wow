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

type AggregationQueryBody = {
  filter?: unknown;
  groupBy?: Array<{ alias: string }>;
};

const activeStockFilter = {
  op: "IN",
  field: "state.status",
  values: ["FAILED", "PREPARED"],
};

function stockCountKind(query: AggregationQueryBody) {
  if (query.groupBy?.length) {
    return undefined;
  }
  if (JSON.stringify(query.filter) === JSON.stringify(activeStockFilter)) {
    return "activeTotal";
  }
  const root = query.filter as
    | { op?: string; operands?: Array<{ field?: string; op?: string }> }
    | undefined;
  if (root?.op !== "AND" || !root.operands) {
    return undefined;
  }
  const hasActiveFilter = root.operands.some(
    (operand) => JSON.stringify(operand) === JSON.stringify(activeStockFilter),
  );
  if (!hasActiveFilter) {
    return undefined;
  }
  const hasLowerBound = root.operands.some(
    ({ field, op }) => field === "state.executeAt" && op === "GTE",
  );
  const hasUpperBound = root.operands.some(
    ({ field, op }) => field === "state.executeAt" && op === "LT",
  );
  if (hasLowerBound && hasUpperBound) {
    return "selectedInRange";
  }
  if (hasLowerBound) {
    return "newerThanRange";
  }
  return hasUpperBound ? "olderThanRange" : undefined;
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
      bodyType: "compensation.execution_failed.ExecutionFailedApplied",
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
      const serializedFilter = JSON.stringify(query.filter ?? {});
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
      } else if (aliases.includes("retries")) {
        rows = [
          { retries: 0, count: 5 },
          { retries: 1, count: 4 },
          { retries: 3, count: 2 },
          { retries: 6, count: 1 },
        ];
      } else if (stockCountKind(query) === "activeTotal") {
        rows = [{ count: 1_000 }];
      } else if (stockCountKind(query) === "selectedInRange") {
        rows = [{ count: 320 }];
      } else if (stockCountKind(query) === "newerThanRange") {
        rows = [{ count: 0 }];
      } else if (stockCountKind(query) === "olderThanRange") {
        rows = [{ count: 680 }];
      } else if (serializedFilter.includes("nextRetryAt")) {
        rows = [{ count: 128 }];
      } else if (serializedFilter.includes("timeoutAt")) {
        rows = [{ count: 34 }];
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

async function openDetails(page: Page, projectName: string) {
  if (projectName === "mobile-chromium") {
    await page
      .getByRole("button", { name: `View execution ${execution.id}` })
      .click();
    await expect(
      page.getByRole("dialog", { name: "Execution failed details" }),
    ).toBeVisible();
    const closeButtonBox = await page
      .getByRole("button", { name: "Close" })
      .boundingBox();
    expect(closeButtonBox?.width).toBeGreaterThanOrEqual(44);
    expect(closeButtonBox?.height).toBeGreaterThanOrEqual(44);
  }
  await expect(
    page.getByRole("heading", { name: execution.function.name }),
  ).toBeVisible();
}

test("loads the deterministic queue and responsive execution details", async ({
  page,
}, testInfo) => {
  let queryBody: Record<string, unknown> | undefined;
  await page.route(
    "**/execution_failed/snapshot/paged/state",
    async (route) => {
      queryBody = route.request().postDataJSON() as Record<string, unknown>;
      await route.fulfill({ json: { total: 1, list: [execution] } });
    },
  );

  await page.goto("/to-retry");
  await expect(
    page.getByText(execution.id, { exact: true }).first(),
  ).toBeVisible();
  await openDetails(page, testInfo.project.name);

  await expect(page.getByText("656", { exact: true })).toBeVisible();
  await expect(page.getByText("v656", { exact: true })).toHaveCount(0);
  await expect(page.getByText("3 minutes (180 s)")).toBeVisible();
  for (const [name, minHeight] of [
    ["History", 56],
    ["Execution context", 160],
    ["Stack trace", 52],
  ] as const) {
    const section = page.getByRole("region", { name });
    await expect(section).toBeVisible();
    expect((await section.boundingBox())?.height).toBeGreaterThanOrEqual(
      minHeight,
    );
  }
  await expect
    .poll(() => queryBody?.sort)
    .toEqual([{ field: "aggregateId", direction: "DESC" }]);
});

test("copies identifiers when the Clipboard API is unavailable", async ({
  page,
}, testInfo) => {
  await page.route("**/execution_failed/snapshot/paged/state", (route) =>
    route.fulfill({ json: { total: 1, list: [execution] } }),
  );

  await page.goto("/to-retry");
  await openDetails(page, testInfo.project.name);
  await page.evaluate(() => {
    const execCommand = document.execCommand.bind(document);
    Object.defineProperty(document, "execCommand", {
      configurable: true,
      value: (commandId: string, showUI?: boolean, valueArgument?: string) => {
        const textarea = [...document.querySelectorAll("textarea")].find(
          ({ selectionStart, selectionEnd }) => selectionEnd > selectionStart,
        );
        if (commandId === "copy" && textarea) {
          const { selectionStart, selectionEnd, value } = textarea;
          Object.defineProperty(window, "__copiedText", {
            configurable: true,
            value: value.slice(selectionStart, selectionEnd),
          });
        }
        return execCommand(commandId, showUI, valueArgument);
      },
    });
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: undefined,
    });
  });
  expect(await page.evaluate(() => navigator.clipboard)).toBeUndefined();
  expect(
    await page.evaluate(() => Object.hasOwn(document, "execCommand")),
  ).toBe(true);

  for (const [label, value] of [
    ["Copy execution ID", execution.id],
    ["Copy event ID", execution.eventId.id],
    ["Copy aggregate ID", execution.eventId.aggregateId.aggregateId],
  ]) {
    const copyButton = page.getByRole("button", { name: label });
    await copyButton.click();
    await expect(copyButton.locator("svg")).toHaveClass(/lucide-check/);
    await expect
      .poll(() =>
        page.evaluate(
          () => (window as Window & { __copiedText?: string }).__copiedText,
        ),
      )
      .toBe(value);
  }
  await expect(page.getByText(/^Unable to copy/)).toHaveCount(0);
});

test("loads lifecycle history through the paged EventStream REST API", async ({
  page,
}, testInfo) => {
  let historyQuery: Record<string, unknown> | undefined;
  await page.route("**/execution_failed/snapshot/paged/state", (route) =>
    route.fulfill({ json: { total: 1, list: [execution] } }),
  );
  await page.route("**/execution_failed/event/paged", async (route) => {
    historyQuery = route.request().postDataJSON() as Record<string, unknown>;
    await route.fulfill({ json: { total: 1, list: [executionHistory] } });
  });

  await page.goto("/to-retry");
  await openDetails(page, testInfo.project.name);
  await page.getByRole("button", { name: "Expand history" }).click();

  await expect(page.getByText("execution_failed_applied")).toBeVisible();
  await expect(page.getByText("Version 2")).toBeVisible();
  await page.getByText("Event payload").click();
  await expect(page.getByText(/"errorCode": "E2E_ERROR"/)).toBeVisible();
  await expect
    .poll(() => historyQuery)
    .toMatchObject({
      filter: { op: "AGGREGATE_ID", value: execution.id },
      sort: [{ field: "version", direction: "DESC" }],
      pagination: { index: 1, size: 10 },
    });
});

test("keeps prepared actions independent of the browser clock", async ({
  page,
}, testInfo) => {
  await page.route("**/execution_failed/snapshot/paged/state", (route) =>
    route.fulfill({
      json: {
        total: 1,
        list: [
          {
            ...execution,
            status: "PREPARED",
            retryState: {
              ...execution.retryState,
              timeoutAt: Date.now() + 86_400_000,
            },
          },
        ],
      },
    }),
  );

  await page.goto("/to-retry");
  await openDetails(page, testInfo.project.name);

  await expect(
    page.getByRole("button", { name: "Prepare compensation" }),
  ).toBeEnabled();
});

test("preserves and freezes last-known-good data after refresh fails", async ({
  page,
}, testInfo) => {
  let queryCount = 0;
  await page.route(
    "**/execution_failed/snapshot/paged/state",
    async (route) => {
      queryCount += 1;
      if (queryCount === 1) {
        await route.fulfill({ json: { total: 1, list: [execution] } });
        return;
      }
      await route.fulfill({ status: 503, body: "refresh unavailable" });
    },
  );
  await page.route(
    `**/execution_failed/${execution.id}/prepare_compensation`,
    (route) => route.fulfill({ json: {} }),
  );

  await page.goto("/to-retry");
  await openDetails(page, testInfo.project.name);
  await page.getByRole("button", { name: "Prepare compensation" }).click();

  await expect(page.getByRole("alert")).toContainText(
    "Showing the last loaded page",
  );
  await expect(
    page.getByText(execution.id, { exact: true }).first(),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Refreshing state" }),
  ).toBeDisabled();
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
    page.getByRole("heading", { name: "Dashboard", exact: true }),
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
  await expect.poll(() => snapshotRequests).toBe(11);
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
  await expect.poll(() => snapshotRequests).toBe(22);
  await expect.poll(() => eventRequests).toBe(8);
  await page.getByRole("button", { name: "Refresh dashboard" }).click();
  await expect.poll(() => snapshotRequests).toBe(33);
  await expect.poll(() => eventRequests).toBe(12);

  const appliedWindows: Array<{ end: number; start: number }> = [];
  for (const batch of [0, 1, 2]) {
    const batchSnapshotQueries = snapshotQueries.slice(
      batch * 11,
      batch * 11 + 11,
    );
    const stockCountSnapshots = batchSnapshotQueries.filter((query) =>
      stockCountKind(query),
    );
    const snapshotWindows = batchSnapshotQueries.map((query) =>
      queryWindow(query, "state.executeAt"),
    );
    const fullyWindowedSnapshots = snapshotWindows.filter(
      ({ end, start }) => Number.isFinite(start) && Number.isFinite(end),
    );
    expect(fullyWindowedSnapshots).toHaveLength(8);
    expect(stockCountSnapshots.map(stockCountKind).sort()).toEqual([
      "activeTotal",
      "newerThanRange",
      "olderThanRange",
      "selectedInRange",
    ]);
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
      "Failure inflow (new failures)",
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
    for (const name of [
      "Dashboard",
      "To Retry",
      "Executing",
      "Next Retry",
      "Non Retryable",
      "Succeeded",
      "Unrecoverable",
    ]) {
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
      .getByRole("link", { name: "Dashboard", exact: true })
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
      .getByRole("heading", { name: "Failure inflow (new failures)" })
      .scrollIntoViewIfNeeded();
    await expect(
      page.getByRole("heading", { name: "Failure inflow (new failures)" }),
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
    activity.getByRole("heading", {
      name: "Failure inflow (new failures) — daily trend",
    }),
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
      const stockCount = stockCountKind(query);
      if (stockCount === "activeTotal" || stockCount === "olderThanRange") {
        await route.fulfill({ json: [{ count: 680 }] });
        return;
      }
      if (stockCount === "selectedInRange" || stockCount === "newerThanRange") {
        await route.fulfill({ json: [{ count: 0 }] });
        return;
      }
      const serializedFilter = JSON.stringify(query.filter);
      if (
        aliases.length === 0 &&
        serializedFilter.includes('"op":"GTE"') &&
        serializedFilter.includes('"op":"LT"')
      ) {
        await route.fulfill({ json: [{ count: 0 }] });
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

  const labels = [
    "Dashboard",
    "To Retry",
    "Executing",
    "Next Retry",
    "Non Retryable",
    "Succeeded",
    "Unrecoverable",
  ].map((name) =>
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
      "a[aria-label='Dashboard']",
    );
    const dashboardIcon = dashboard?.querySelector<SVGElement>("svg");
    const toRetry = document.querySelector<HTMLElement>(
      "a[aria-label='To Retry']",
    );
    const footer = document.querySelector<HTMLElement>(
      "button[aria-label='Expand navigation']",
    );
    const footerIcon = footer?.querySelector<SVGElement>("svg");
    if (
      !sidebar ||
      !dashboard ||
      !dashboardIcon ||
      !toRetry ||
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
        toRetry.getBoundingClientRect().top -
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
  let release = () => undefined;
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
