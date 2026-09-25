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
import { FindCategory } from "../src/features/Failed/FindCategory.ts";
import { RetryConditions } from "../src/features/Failed/RetryConditions.ts";
import {
  executions,
  matches,
  stubExecutionFailedService,
  type Snapshot,
  type SnapshotQueries,
} from "./support/executionFailedService.ts";

// The old queue addresses (rebuild proposal, batch 5; Q3): each still opens,
// as a redirect to its system view on the failed executions' page, with what
// it came with — the dashboard's execution window (`start`, `end`) and a
// failure cluster (`cluster`) as the view's scope, the open execution (`id`)
// as the detail.

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

const QUEUES = [
  ["/active", "active", "Active", FindCategory.Active],
  ["/to-retry", "to-retry", "To retry", FindCategory.ToRetry],
  ["/executing", "executing", "Executing", FindCategory.Executing],
  ["/next-retry", "next-retry", "Due for retry", FindCategory.NextRetry],
  [
    "/non-retryable",
    "non-retryable",
    "Non-retryable",
    FindCategory.NonRetryable,
  ],
  ["/succeeded", "succeeded", "Succeeded", FindCategory.Succeeded],
  [
    "/unrecoverable",
    "unrecoverable",
    "Unrecoverable",
    FindCategory.Unrecoverable,
  ],
] as const;

type Condition = Parameters<typeof matches>[1];

/** What the old queue selected within `WINDOW`, at the pinned moment. */
function oldQueueInWindow(category: FindCategory): string[] {
  const queue = RetryConditions.categoryToCondition(category, NOW) as Condition;
  return DOCUMENTS.filter(
    (document) =>
      matches(document, queue, NOW) &&
      (document.state.executeAt as number) >= WINDOW.start &&
      (document.state.executeAt as number) < WINDOW.end,
  )
    .map(({ aggregateId }) => aggregateId)
    .sort();
}

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

for (const [path, id, title, category] of QUEUES)
  test(`the old ${path} address opens the ${title} view, its window kept`, async ({
    page,
  }) => {
    const queries = await stubExecutionFailedService(page, DOCUMENTS, {
      now: NOW,
    });
    const expected = oldQueueInWindow(category);
    expect(expected.length).toBeGreaterThan(0);

    await page.goto(`${path}?start=${WINDOW.start}&end=${WINDOW.end}`);

    await expect(page).toHaveURL(
      `/executions?view=${encodeURIComponent(`system:execution-failed:${id}`)}&start=${WINDOW.start}&end=${WINDOW.end}`,
    );
    const workbench = page.getByRole("region", { name: title });
    await expect(workbench).toBeVisible();
    await expect(narrowing(page)).toBeVisible();
    // The window is the view's scope: on its applied bar, with no ✕.
    await expect(
      workbench.locator("[data-scoped]").filter({ hasText: "Executed at" }),
    ).toBeVisible();
    await expect(
      workbench
        .getByRole("navigation", { name: "Pagination" })
        .getByText(`${expected.length} records in all`),
    ).toBeVisible();
    expect([...(queries.matched.at(-1) ?? [])].sort()).toEqual(expected);
    // Up to the millisecond before the exclusive end, as the queue read it.
    expect(lastFilter(queries)).toContain(
      `"op":"BETWEEN","field":"state.executeAt","lowerBound":${WINDOW.start},"upperBound":${WINDOW.end - 1}`,
    );
    // The address was replaced, not added to: back leaves the page.
    await page.goBack();
    await expect(page).not.toHaveURL(new RegExp(path));
  });

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
    `/active?${new URLSearchParams({ cluster: JSON.stringify(cluster) })}`,
  );
  await expect(page).toHaveURL(
    /\/executions\?view=system%3Aexecution-failed%3Aactive&cluster=/,
  );
  const workbench = page.getByRole("region", { name: "Active" });
  await expect(
    workbench
      .getByRole("navigation", { name: "Pagination" })
      .getByText(`${expected} records in all`),
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
        `${DOCUMENTS.filter(({ state }) => state.status !== "SUCCEEDED").length} records in all`,
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
  await page.goto("/active?cluster=%7Bbroken");
  await expect(page.getByText("Invalid cluster filter.")).toBeVisible();
  expect(queries.paged).toHaveLength(0);

  await page.getByRole("button", { name: "Clear cluster filter" }).click();
  await expect(page.getByRole("region", { name: "Active" })).toBeVisible();
  await expect(page).toHaveURL(
    /\/executions\?view=system%3Aexecution-failed%3Aactive$/,
  );
});

test("an old address with an execution's id opens it in the detail", async ({
  page,
}) => {
  await stubExecutionFailedService(page, DOCUMENTS, { now: NOW });
  await page.goto("/unrecoverable?id=EF-03");
  await expect(page).toHaveURL(
    /\/executions\?view=system%3Aexecution-failed%3Aunrecoverable&id=EF-03$/,
  );
  const panel = page.getByRole("dialog", { name: /EF-03/ });
  await expect(
    panel.getByRole("heading", { name: "EF-03", level: 2 }),
  ).toBeVisible();
  await expect(
    panel.getByRole("form", { name: "Apply retry specification" }),
  ).toBeVisible();
});
