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

async function openDetails(page: Page, projectName: string) {
  if (projectName === "mobile-chromium") {
    await page
      .getByRole("button", { name: `View execution ${execution.id}` })
      .click();
    await expect(
      page.getByRole("dialog", { name: "Execution failed details" }),
    ).toBeVisible();
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
  await expect
    .poll(() => queryBody?.sort)
    .toEqual([{ field: "aggregateId", direction: "DESC" }]);
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
