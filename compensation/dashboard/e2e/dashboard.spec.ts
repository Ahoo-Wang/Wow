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

test("hides desktop navigation labels when collapsed", async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== "desktop-chromium");
  await stubExecutionFailedService(page);
  await page.route("**/execution_failed/event/aggregation", (route) =>
    route.fulfill({ json: [] }),
  );

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
