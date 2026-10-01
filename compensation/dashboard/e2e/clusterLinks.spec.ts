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

import type { Page } from "@playwright/test";
import { expect, test } from "./support/test.ts";
import {
  executions,
  stubExecutionFailedService,
  type Snapshot,
  type SnapshotQueries,
} from "./support/executionFailedService.ts";
import { recordsInAll } from "./support/wording.ts";

// A failure cluster link on the failed executions' page (`cluster`): the
// view narrowed to the cluster until the narrowing is taken off. The old
// queue addresses that carried it are gone with the old shell
// (console-redesign.md §0, Q1); batch 2 moves the narrowing to the engine's
// scope.

/**
 * The 45 executions, with EF-11 out of retries, so every queue holds some
 * of the first day: the old queues had no execution in the non-retryable
 * one otherwise.
 */
const DOCUMENTS: Snapshot[] = executions().map((document) =>
  document.aggregateId === "EF-11"
    ? {
        ...document,
        state: { ...document.state, isBelowRetryThreshold: false },
      }
    : document,
);

/** A second before EF-06's preparation times out: it is still executing. */
const NOW =
  (
    DOCUMENTS.find(({ aggregateId }) => aggregateId === "EF-06")!.state
      .retryState as { timeoutAt: number }
  ).timeoutAt - 1000;

/** The first day of the documents: EF-01, EF-06, … EF-41 failed on it. */
const WINDOW = {
  start: Date.parse("2026-09-18T00:00:00.000Z"),
  end: Date.parse("2026-09-19T00:00:00.000Z"),
};

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(NOW);
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );
});

/** The note the page shows while a link narrows the view. */
function narrowing(page: Page) {
  return page.getByText(
    "This view is narrowed by the link it was opened from.",
  );
}

/** The last condition the workbench sent, as the service received it. */
function lastFilter(queries: SnapshotQueries): string {
  return JSON.stringify(queries.paged.at(-1)?.filter);
}

test("a cluster link opens its executions alone, until the narrowing goes", async ({
  page,
}) => {
  const queries = await stubExecutionFailedService(page, DOCUMENTS, {
    now: NOW,
  });
  const cluster = {
    errorCode: "PAYMENTSAGA_FAILED",
    contextName: "order-service",
    processorName: "PaymentSaga",
    functionName: "onPaymentRequested",
    functionKind: "EVENT",
    start: WINDOW.start,
    end: WINDOW.end + 4 * 86_400_000,
  };
  const expected = DOCUMENTS.filter(
    (document: Snapshot) =>
      document.state.status !== "SUCCEEDED" &&
      (document.state.function as { processorName: string }).processorName ===
        "PaymentSaga",
  ).length;

  await page.goto(
    `/executions?${new URLSearchParams({
      view: "system:execution-failed:active",
      cluster: JSON.stringify(cluster),
    })}`,
  );
  const workbench = page.getByRole("region", { name: "Active" });
  await expect(
    workbench
      .getByRole("navigation", { name: "Pagination" })
      .getByText(recordsInAll(expected)),
  ).toBeVisible();
  for (const field of [
    "state.error.errorCode",
    "state.function.contextName",
    "state.function.processorName",
    "state.function.name",
    "state.function.functionKind",
    "state.executeAt",
  ])
    expect(lastFilter(queries)).toContain(field);
  await expect(workbench.locator("[data-scoped]")).toHaveCount(6);

  // Taken off: the same view, every active execution, and a clean address.
  await page.getByRole("button", { name: "Remove the narrowing" }).click();
  await expect(page).toHaveURL(
    /\/executions\?view=system%3Aexecution-failed%3Aactive$/,
  );
  await expect(narrowing(page)).toBeHidden();
  await expect(workbench.locator("[data-scoped]")).toHaveCount(0);
  await expect(
    workbench
      .getByRole("navigation", { name: "Pagination" })
      .getByText(
        recordsInAll(
          DOCUMENTS.filter(({ state }) => state.status !== "SUCCEEDED").length,
        ),
      ),
  ).toBeVisible();
  expect(lastFilter(queries)).not.toContain("state.function.processorName");
});

test("a malformed cluster link is said, and queries nothing", async ({
  page,
}) => {
  const queries = await stubExecutionFailedService(page, DOCUMENTS, {
    now: NOW,
  });
  await page.goto(
    "/executions?view=system%3Aexecution-failed%3Aactive&cluster=%7Bbroken",
  );
  await expect(page.getByText("Invalid cluster filter.")).toBeVisible();
  expect(queries.paged).toHaveLength(0);

  await page.getByRole("button", { name: "Clear cluster filter" }).click();
  await expect(page.getByRole("region", { name: "Active" })).toBeVisible();
  await expect(page).toHaveURL(
    /\/executions\?view=system%3Aexecution-failed%3Aactive$/,
  );
});
