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
  executions,
  stubExecutionFailedCommands,
  stubExecutionFailedService,
  type Snapshot,
} from "./support/executionFailedService.ts";

// The record detail of 「失败执行（预览）」 (rebuild proposal, batch 4): the
// old queues' detail, copy-ID, execution-history, prepare-after-timeout and
// refresh-failure scenarios (`dashboard.spec.ts`), said again of the engine's
// drawer with the console's sections in it. The old ones stay until batch 5
// hands the queue routes over.

const IN_PROGRESS = "Execution is in progress; wait until it times out.";

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );
});

/** A long trace, as a real failure has: it scrolls in its own box. */
const TRACE = [
  "java.lang.IllegalStateException: Inventory refused the reservation.",
  ...Array.from(
    { length: 80 },
    (_, index) =>
      `\tat me.ahoo.wow.example.OrderSaga.step${index}(OrderSaga.kt:${index + 10})`,
  ),
].join("\n");

/** Forty-five executions; `EF-01` fails with a long trace. */
function withTrace(): Snapshot[] {
  return executions().map((document) =>
    document.aggregateId === "EF-01"
      ? {
          ...document,
          state: {
            ...document.state,
            error: {
              ...(document.state.error as Record<string, unknown>),
              stackTrace: TRACE,
            },
          },
        }
      : document,
  );
}

function drawer(page: Page) {
  return page.getByRole("dialog", { name: /EF-/ });
}

/** Opens `/executions?id=<id>` and waits for the whole record. */
async function openDetail(page: Page, id: string) {
  await page.goto(`/executions?id=${id}`);
  const panel = drawer(page);
  await expect(
    panel.getByRole("heading", { name: id, level: 2 }),
  ).toBeVisible();
  await expect(
    panel.getByRole("form", { name: "Change function" }),
  ).toBeVisible();
  return panel;
}

test("opens an execution from its link, on the page or not, and says when it cannot", async ({
  page,
}) => {
  const queries = await stubExecutionFailedService(page, withTrace());
  const panel = await openDetail(page, "EF-01");

  // The engine's field groups, and the console's sections beside what they
  // are about.
  for (const name of [
    "Identity",
    "Function",
    "Change function",
    "Error",
    "Stack trace",
    "Retry",
    "Apply retry specification",
    "Execution history",
  ])
    await expect(
      panel.getByRole("region", { name, exact: true }).first(),
    ).toBeVisible();
  const error = panel.getByRole("region", { name: "Error", exact: true });
  await expect(error).toContainText("Inventory refused the reservation.");
  // The stack trace is read once, in its own scrolling region, not also as
  // a field of the Error group.
  await expect(error).not.toContainText("OrderSaga.kt:10");
  const trace = panel.getByRole("region", { name: "Stack trace content" });
  await expect(trace).toContainText("OrderSaga.kt:89");
  const box = await trace.evaluate((element) => ({
    height: element.clientHeight,
    scrolls: element.scrollHeight > element.clientHeight,
  }));
  expect(box.scrolls).toBe(true);
  expect(box.height).toBeLessThanOrEqual(384);
  await expect(panel.getByText("81 lines")).toBeVisible();
  // The drawer fits the viewport: nothing of the page scrolls sideways.
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth - window.innerWidth,
    ),
  ).toBeLessThanOrEqual(0);
  const close = panel.getByRole("button", { name: "Close" });
  const target = await close.boundingBox();
  expect(target?.width).toBeGreaterThanOrEqual(24);
  expect(target?.height).toBeGreaterThanOrEqual(24);

  // Closed, the address forgets it; pressed on a row, it comes back.
  await close.click();
  await expect(panel).toBeHidden();
  await expect(page).toHaveURL(/\/executions$/);
  await page.getByRole("row", { name: /EF-45/ }).press("Enter");
  await expect(page).toHaveURL(/\/executions\?id=EF-45$/);
  await expect(drawer(page)).toBeVisible();

  // Not on the Active view's page (it succeeded): read on its own.
  await openDetail(page, "EF-04");
  expect(
    queries.paged.some(
      ({ pagination, filter }) =>
        pagination.size === 1 && JSON.stringify(filter).includes('"EF-04"'),
    ),
  ).toBe(true);

  // Gone: said, and none of the console's forms offered for it.
  await page.goto("/executions?id=EF-99");
  await expect(drawer(page)).toContainText("This record is no longer there");
  await expect(drawer(page).getByRole("form")).toHaveCount(0);

  // Refused: the service's 403 is the reader's standing, with no retry.
  await page.route("**/execution_failed/snapshot/paged", async (route) => {
    const query = route.request().postDataJSON();
    if (query.pagination?.size !== 1) return route.fallback();
    await route.fulfill({ status: 403, json: { errorCode: "Forbidden" } });
  });
  // (Off the page, so there is no row of the list's to show either.)
  await page.goto("/executions?id=EF-08");
  await expect(drawer(page)).toContainText(
    "You do not have permission to read this record.",
  );
  await expect(
    drawer(page).getByRole("button", { name: "Try again" }),
  ).toHaveCount(0);
});

test("copies identifiers when the Clipboard API is unavailable", async ({
  page,
}) => {
  await stubExecutionFailedService(page, withTrace());
  const panel = await openDetail(page, "EF-01");
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

  const copied = () =>
    page.evaluate(
      () => (window as Window & { __copiedText?: string }).__copiedText,
    );
  for (const value of ["EF-01", "EF-01-event", "order-EF-01"]) {
    const button = panel.getByRole("button", {
      name: `Copy ${value}`,
      exact: true,
    });
    await button.hover();
    await button.click();
    await expect(
      panel.getByRole("button", { name: "Copied" }).first(),
    ).toBeVisible();
    await expect.poll(copied).toBe(value);
  }
  // And the stack trace, whole, from its own section.
  await panel.getByRole("button", { name: "Copy stack trace" }).click();
  await expect(
    panel.getByRole("button", { name: "Stack trace copied" }),
  ).toBeVisible();
  await expect.poll(copied).toBe(TRACE);
  await expect(panel.getByText(/Could not copy|Unable to copy/)).toHaveCount(0);
});

test("reads an execution's history from its event stream, the newest first", async ({
  page,
}) => {
  await stubExecutionFailedService(page, withTrace());
  const asked: Array<Record<string, unknown>> = [];
  await page.route("**/execution_failed/event/paged", async (route) => {
    asked.push(route.request().postDataJSON());
    const stream = (version: number, type: string, minutes: number) => ({
      id: `EF-01-v${version}`,
      aggregateId: "EF-01",
      aggregateName: "execution_failed",
      contextName: "compensation-service",
      tenantId: "(0)",
      commandId: `EF-01-v${version}-command`,
      requestId: `EF-01-v${version}-command`,
      version,
      createTime: Date.parse("2026-09-18T08:00:00Z") + minutes * 60_000,
      header: {},
      body: [
        {
          id: `EF-01-v${version}-event`,
          name: type,
          revision: "0.0.1",
          bodyType: `me.ahoo.wow.compensation.api.${type}`,
          body: {},
        },
      ],
    });
    await route.fulfill({
      json: {
        total: 2,
        list: [
          stream(2, "ExecutionFailedApplied", 4),
          stream(1, "ExecutionFailedCreated", 0),
        ],
      },
    });
  });
  const panel = await openDetail(page, "EF-01");
  // The console's section, which holds the embedded view.
  const history = panel.locator('[data-section="history"]');
  await history.scrollIntoViewIfNeeded();
  const rows = history.getByRole("row");
  // A header, then the two streams, the newer first, each by its events.
  await expect(rows.nth(1)).toContainText("Retry failed");
  await expect(rows.nth(2)).toContainText("First failed");
  await expect(rows.nth(1)).toContainText("EF-01-v2-command");
  // The newest first; the stream id only breaks a tie, as a stable page needs.
  expect(asked[0]).toMatchObject({
    pagination: { index: 1, size: 10 },
    sort: [
      { field: "version", direction: "DESC" },
      { field: "id", direction: "ASC" },
    ],
  });
  expect(JSON.stringify(asked[0].filter)).toContain('"value":"EF-01"');
  expect(JSON.stringify(asked[0].filter)).toContain('"aggregateId"');
});

test("an execution in progress cannot be prepared from its detail until it times out", async ({
  page,
}) => {
  const now = Date.parse("2026-09-20T00:00:00Z");
  // Time flows from `now`, and jumps when the test says so.
  await page.clock.install({ time: now });
  const documents = executions(3).map((document) => ({
    ...document,
    state: {
      ...document.state,
      status: "PREPARED",
      recoverable: "RECOVERABLE",
      isRetryable: true,
      isBelowRetryThreshold: true,
      retryState: {
        ...(document.state.retryState as Record<string, number>),
        timeoutAt: now + 60_000,
      },
    },
  }));
  await stubExecutionFailedService(page, documents, { now });
  await stubExecutionFailedCommands(page, documents);
  const panel = await openDetail(page, "EF-01");

  const prepare = panel.getByRole("button", { name: "Prepare", exact: true });
  await expect(prepare).toBeDisabled();
  await expect(prepare).toHaveAccessibleDescription(IN_PROGRESS);
  await panel.getByRole("button", { name: "Actions for EF-01" }).click();
  await expect(
    page.getByRole("menuitem", { name: "Force prepare" }),
  ).toBeDisabled();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("menu")).toBeHidden();

  // Past the deadline (`now > timeoutAt`), it redraws on its own.
  await page.clock.fastForward(60_001);
  await expect(prepare).toBeEnabled();
  await panel.getByRole("button", { name: "Actions for EF-01" }).click();
  await expect(
    page.getByRole("menuitem", { name: "Force prepare" }),
  ).toBeEnabled();
});

test("keeps the last rows and the open execution when a refresh fails", async ({
  page,
}) => {
  const documents = withTrace();
  await stubExecutionFailedService(page, documents);
  const sent = await stubExecutionFailedCommands(page, documents);
  let failing = false;
  await page.route("**/execution_failed/snapshot/paged", async (route) => {
    if (!failing) return route.fallback();
    await route.fulfill({ status: 503, body: "refresh unavailable" });
  });
  const panel = await openDetail(page, "EF-01");

  // A spec applied from the detail reaches the service, which answers once
  // the snapshot is written; then every read fails.
  const form = panel.getByRole("form", { name: "Apply retry specification" });
  await form.getByLabel("Max retries").fill("5");
  failing = true;
  await form.getByRole("button", { name: "Apply retry spec" }).click();
  await expect.poll(() => sent.length).toBe(1);
  expect(sent[0]).toMatchObject({
    id: "EF-01",
    command: "apply_retry_spec",
    waitStage: "SNAPSHOT",
    body: { maxRetries: 5, minBackoff: 180, executionTimeout: 120 },
  });

  // The workbench, behind the drawer (so hidden from the accessibility
  // tree while it is open), says it could not load and keeps what it had.
  const workbench = page.getByRole("region", {
    name: "Active",
    includeHidden: true,
  });
  await expect(workbench.getByText(/Could not load the data/)).toBeVisible();
  await expect(
    workbench.getByRole("row", { name: /EF-45/, includeHidden: true }),
  ).toHaveCount(1);
  // The open execution stays open, its fields still there.
  await expect(panel.getByRole("heading", { name: "EF-01" })).toBeVisible();
  await expect(
    panel.getByRole("region", { name: "Error", exact: true }),
  ).toContainText("Inventory refused the reservation.");
});
