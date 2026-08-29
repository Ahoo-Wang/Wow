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
    ["BAD_REQUEST", "orders", "OrderItemReservedTrackEventProcessor", "onPickupOrderItemReservedConfirmed", "EVENT", 80, 70, 10],
    ["NOT_FOUND", "catalog", "ProductProjectionProcessor", "onProductChanged", "EVENT", 60, 60, 0],
    ["CONFLICT", "inventory", "ReservationProcessor", "reserve", "COMMAND", 40, 30, 10],
    ["UNAVAILABLE", "payment", "PaymentProcessor", "charge", "COMMAND", 20, 15, 5],
  ] as const;

  await page.route("**/execution_failed/snapshot/aggregation", async (route) => {
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
        ([errorCode, contextName, processorName, functionName, functionKind, , failedCount, preparedCount]) => [
          { errorCode, contextName, processorName, functionName, functionKind, status: "FAILED", statusCount: failedCount },
          { errorCode, contextName, processorName, functionName, functionKind, status: "PREPARED", statusCount: preparedCount },
        ],
      );
    } else if (aliases.includes("errorCode")) {
      rows = pressureClusters.map(
        ([errorCode, contextName, processorName, functionName, functionKind, currentCount]) => ({
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
        { recoverable: "RECOVERABLE", count: 7 },
        { recoverable: "UNKNOWN", count: 3 },
        { recoverable: "UNRECOVERABLE", count: 2 },
      ];
    } else if (aliases.includes("retries")) {
      rows = [
        { retries: 0, count: 5 },
        { retries: 1, count: 4 },
        { retries: 3, count: 2 },
        { retries: 6, count: 1 },
      ];
    } else if (serializedFilter.includes("nextRetryAt")) {
      rows = [{ count: 128 }];
    } else if (serializedFilter.includes("timeoutAt")) {
      rows = [{ count: 34 }];
    } else {
      rows = [{ count: 9 }];
    }
    await route.fulfill({ json: rows });
  });

  await page.route("**/execution_failed/event/aggregation", async (route) => {
    const query = route.request().postDataJSON() as AggregationQueryBody;
    callbacks.onEvent(query);
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
          bucket: new Date(2026, 7, 28).getTime(),
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
  await page.route(
    "**/execution_failed/snapshot/paged/state",
    (route) =>
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
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
  await expect(
    page.getByText("Current failure pressure — Top 5 clusters"),
  ).toBeVisible();
  await expect(page.getByText("Compensation outcomes")).toBeVisible();
  await expect.poll(() => snapshotRequests).toBe(7);
  await expect.poll(() => eventRequests).toBe(4);
  const timeRange = page.getByRole("button", { name: /^Time range:/ });
  await expect(timeRange).toContainText("–");
  await timeRange.click();
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
  await expect.poll(() => snapshotRequests).toBe(14);
  await expect.poll(() => eventRequests).toBe(8);
  await page.getByRole("button", { name: "Refresh dashboard" }).click();
  await expect.poll(() => snapshotRequests).toBe(21);
  await expect.poll(() => eventRequests).toBe(12);

  const appliedWindows: Array<{ end: number; start: number }> = [];
  for (const batch of [0, 1, 2]) {
    const snapshotWindows = snapshotQueries
      .slice(batch * 7, batch * 7 + 7)
      .map((query) => queryWindow(query, "state.executeAt"));
    const eventWindows = eventQueries
      .slice(batch * 4, batch * 4 + 4)
      .map((query) => queryWindow(query, "createTime"));
    const windows = [...snapshotWindows, ...eventWindows];
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
    expect(overflow.scrollHeight).toBeLessThanOrEqual(overflow.clientHeight);
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
            return bounds.top >= container.top && bounds.bottom <= container.bottom;
          }),
        };
      });
    expect(pressureSizing.contentDelta).toBeLessThanOrEqual(2);
    expect(pressureSizing.overflowY).not.toMatch(/auto|scroll/);
    expect(pressureSizing.rowsVisible).toBe(true);
    expect((await page.locator(".dashboard-signals").boundingBox())?.height).toBeGreaterThanOrEqual(200);
    for (const name of [
      "Recoverability",
      "Retry distribution",
      "Compensation outcomes",
    ]) {
      await expect(page.getByRole("heading", { name })).toBeVisible();
    }
    for (const label of ["1–2 retries", "3–5 retries"]) {
      const tick = page
        .locator("tspan")
        .filter({ hasText: label });
      await expect(tick).toHaveCount(1);
    }
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
        .getByRole("region", { name: "Recoverability" })
        .getByText("Recoverable", { exact: true }),
      page
        .getByRole("region", { name: "Retry distribution" })
        .locator("tspan")
        .filter({ hasText: "1–2 retries" }),
      page
        .getByRole("region", { name: "Retry distribution" })
        .locator("svg text")
        .filter({ hasText: "4 (33%)" })
        .first(),
      page
        .getByRole("region", { name: "Compensation outcomes trend" })
        .locator("svg text")
        .filter({ hasText: "08-29 00:00" })
        .first(),
      page
        .getByRole("region", { name: "Compensation outcomes trend" })
        .locator(".recharts-legend-wrapper")
        .getByText("New failures", { exact: true }),
    ];
    for (const target of fontTargets) {
      await expect(target).toBeVisible();
      const fontSize = await target.evaluate((element) =>
        Number.parseFloat(getComputedStyle(element).fontSize),
      );
      expect(fontSize).toBeGreaterThanOrEqual(14);
    }
  }
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);

  if (testInfo.project.name === "mobile-chromium") {
    const pressureContainer = page
      .getByRole("table", { name: "Current failure pressure" })
      .locator("..");
    const pressureOverflow = await pressureContainer.evaluate((element) => ({
      clientWidth: element.clientWidth,
      overflowX: getComputedStyle(element).overflowX,
      scrollWidth: element.scrollWidth,
    }));
    expect(pressureOverflow.scrollWidth).toBeGreaterThan(
      pressureOverflow.clientWidth,
    );
    expect(["auto", "scroll"]).toContain(pressureOverflow.overflowX);
    await page
      .getByRole("heading", { name: "Compensation outcomes" })
      .scrollIntoViewIfNeeded();
    await expect(
      page.getByRole("heading", { name: "Compensation outcomes" }),
    ).toBeVisible();
  } else {
    expect(
      await page.evaluate(
        () => document.documentElement.scrollHeight <= window.innerHeight,
      ),
    ).toBe(true);
  }
  expect(consoleErrors).toEqual([]);
});

test("redirects Dashboard aliases and fallback to the root", async ({ page }) => {
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
  await expect(page.getByText("Compensation outcomes")).toBeVisible();
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
    const signals = document.querySelector<HTMLElement>(".dashboard-signals");
    if (!dashboard || !signals) {
      throw new Error("Dashboard layout is missing");
    }
    return {
      alertClientWidth: element.clientWidth,
      alertScrollWidth: element.scrollWidth,
      dashboardClientHeight: dashboard.clientHeight,
      dashboardScrollHeight: dashboard.scrollHeight,
      overflowWrap: getComputedStyle(element).overflowWrap,
      signalsHeight: signals.getBoundingClientRect().height,
    };
  });
  expect(layout.overflowWrap).toBe("anywhere");
  expect(layout.alertScrollWidth).toBeLessThanOrEqual(layout.alertClientWidth);
  expect(layout.signalsHeight).toBeGreaterThan(208);
  expect(layout.dashboardScrollHeight).toBeGreaterThan(
    layout.dashboardClientHeight,
  );
  await alert.scrollIntoViewIfNeeded();
  await expect(alert).toBeVisible();
});
