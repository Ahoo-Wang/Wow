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

import { readFile } from "node:fs/promises";
import { expect, test, type Page } from "@playwright/test";
import {
  executions,
  stubExecutionFailedService,
} from "./support/executionFailedService.ts";

// 「失败执行」: the view engine's workbench over the failed executions
// (rebuild proposal, batch 1; the queues' page since batch 5). The service is stubbed; the queries the engine
// sends are answered by filtering, sorting and paging the same 45 documents.

const DOCUMENTS = executions();
const ACTIVE = DOCUMENTS.filter(({ state }) => state.status !== "SUCCEEDED");
const ACTIVE_PAYMENTS = ACTIVE.filter(
  ({ state }) =>
    (state.function as { processorName: string }).processorName ===
    "PaymentSaga",
);

test.beforeEach(async ({ page }) => {
  // The engine's own words are English here; the console follows the browser.
  await page.addInitScript(() => {
    if (!sessionStorage.getItem("views-spec-started")) {
      sessionStorage.setItem("views-spec-started", "1");
      localStorage.setItem("wow-dashboard-locale", "en");
    }
  });
});

/** The records on screen, by the box that selects each. */
function records(page: Page) {
  return page.getByRole("checkbox", { name: /^Select EF-/ });
}

/** The workbench's list of views, which a narrow column opens folded. */
async function showViewList(page: Page) {
  const toggle = page.getByRole("button", {
    name: /^(Show|Hide) the view list$/,
  });
  await expect(toggle).toBeVisible();
  const expand = page.getByRole("button", { name: "Show the view list" });
  if ((await expand.count()) > 0) await expand.click();
  await expect(
    page.getByRole("button", { name: "Hide the view list" }),
  ).toBeVisible();
}

async function openPage(page: Page) {
  await page.goto("/executions");
  const workbench = page.getByRole("region", { name: "Active" });
  await expect(workbench).toBeVisible();
  await expect(records(page)).toHaveCount(20);
  return workbench;
}

test("opens the failed executions on the Active system view", async ({
  page,
}) => {
  await stubExecutionFailedService(page, DOCUMENTS);
  const workbench = await openPage(page);

  // The console's page title and the workbench's own heading.
  await expect(
    page.locator(".app-topbar").getByRole("heading", {
      level: 1,
      name: "Failed executions",
    }),
  ).toBeVisible();
  await expect(
    workbench.getByRole("heading", { name: "Active" }),
  ).toBeVisible();
  await expect(
    workbench
      .getByRole("navigation", { name: "Pagination" })
      .getByText(`${ACTIVE.length} records in all`),
  ).toBeVisible();

  // The system views, record and analysis, in the workbench's list.
  await showViewList(page);
  for (const title of [
    "Active",
    "To retry",
    "Executing",
    "Due for retry",
    "Non-retryable",
    "Unrecoverable",
    "Succeeded",
    "All",
    "By status",
    "Active failures by processor",
    "New failures per day",
  ])
    await expect(
      page.getByRole("button", { name: `${title} system`, exact: true }),
    ).toBeVisible();
});

test("filters, pages, switches to cards and exports", async ({ page }) => {
  const queries = await stubExecutionFailedService(page, DOCUMENTS);
  const workbench = await openPage(page);

  // Filter: the error search narrows the rows to the payment failures.
  await workbench
    .getByRole("searchbox", { name: "Search errors" })
    .fill("gateway timed out");
  await workbench
    .getByRole("searchbox", { name: "Search errors" })
    .press("Enter");
  await expect(records(page)).toHaveCount(ACTIVE_PAYMENTS.length);
  await expect(
    workbench.getByRole("row").filter({ hasText: "PaymentSaga" }),
  ).toHaveCount(ACTIVE_PAYMENTS.length);
  await expect(
    workbench.getByRole("row").filter({ hasText: "OrderSaga" }),
  ).toHaveCount(0);
  expect(JSON.stringify(queries.paged.at(-1)?.filter)).toContain(
    '"op":"SEARCH"',
  );
  await workbench.getByRole("button", { name: "Clear the search" }).click();
  await expect(records(page)).toHaveCount(20);

  // Page: the second page holds the rest of the active executions.
  await workbench.getByRole("button", { name: "Next page" }).click();
  await expect(records(page)).toHaveCount(ACTIVE.length - 20);
  expect(queries.paged.at(-1)?.pagination.index).toBe(2);

  // Cards: the same records, titled by their processor.
  await workbench.getByRole("button", { name: "Cards" }).click();
  await expect(workbench.getByRole("table")).toHaveCount(0);
  await expect(
    workbench.getByText("OrderSaga", { exact: true }).first(),
  ).toBeVisible();

  // Export: every active execution under the current conditions, as CSV.
  await workbench.getByRole("button", { name: "Export" }).click();
  // Nothing is selected, so it is every record the conditions match.
  const dialog = page.getByRole("dialog", { name: "Export" });
  await expect(dialog.getByText(`${ACTIVE.length} records`)).toBeVisible();
  const download = page.waitForEvent("download");
  await dialog.getByRole("button", { name: "Export", exact: true }).click();
  const file = await download;
  expect(file.suggestedFilename()).toMatch(/\.csv$/);
  const csv = await readFile(await file.path(), "utf8");
  const lines = csv.trim().split(/\r?\n/);
  expect(lines).toHaveLength(ACTIVE.length + 1);
  expect(lines[0]).toContain("Processor");
});

test("saves a personal view that is still there after a reload", async ({
  page,
}) => {
  await stubExecutionFailedService(page, DOCUMENTS);
  const workbench = await openPage(page);

  await workbench.getByRole("button", { name: "Cards" }).click();
  await workbench.getByRole("button", { name: "Save as" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("textbox", { name: "Title" }).fill("My failures");
  // Saved in this browser: the only audience local storage can offer.
  await expect(dialog.getByText("Saved in this browser")).toBeVisible();
  await dialog.getByRole("button", { name: "Create view" }).click();

  const saved = page.getByRole("region", { name: "My failures" });
  await expect(saved).toBeVisible();
  await expect(page).toHaveURL(/[?&]view=/);
  // Listed among the views of this browser, and nowhere shared.
  await showViewList(page);
  await expect(page.getByText("My views (this browser)")).toBeVisible();
  await expect(
    page.getByRole("button", { name: /^My failures/ }),
  ).toBeVisible();

  await page.reload();
  const reopened = page.getByRole("region", { name: "My failures" });
  await expect(reopened).toBeVisible();
  // It opens as it was saved: cards, not the table.
  await expect(reopened.getByRole("table")).toHaveCount(0);
  await showViewList(page);
  await expect(
    page.getByRole("button", { name: /^My failures/ }),
  ).toBeVisible();
});
