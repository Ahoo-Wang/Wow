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

// The queues do not change meaning when they move onto the view engine
// (rebuild proposal, batch 2, criterion 1): over the same documents, with the
// clock pinned, each of the seven system views matches exactly the IDs its
// old queue matched. The old queues' conditions compared against the
// browser's clock (`RetryConditions`, still what the dashboard counts by);
// the views send `BEFORE_NOW`/`AFTER_NOW`, which the stub answers against the
// same pinned moment, as the service would against its own. Since batch 5
// each old address opens its view, so the comparison starts there.

const BASE = executions();

/** A preparation's deadline: exactly now, so the boundary is under test. */
const NOW = (BASE[9].state.retryState as { timeoutAt: number }).timeoutAt;

type RetryState = {
  timeoutAt: number;
  nextRetryAt: number;
  [key: string]: unknown;
};

/** `documents[index]` with its retry state changed, and nothing else. */
function retrying(document: Snapshot, change: Partial<RetryState>): Snapshot {
  return {
    ...document,
    state: {
      ...document.state,
      retryState: {
        ...(document.state.retryState as RetryState),
        ...change,
      },
    },
  };
}

/**
 * The 45 executions, with each edge of the moment held by one of them:
 * EF-10 (prepared) times out at exactly now, which the command side counts
 * as still executing (Q2); EF-26 (prepared) a millisecond before now, so it
 * has timed out; EF-25 (failed) is due at exactly now; EF-35 (failed) a
 * millisecond after. EF-01 and EF-02 have used up their retries, so the
 * non-retryable queue is not empty.
 */
const DOCUMENTS = BASE.map((document) => {
  switch (document.aggregateId) {
    case "EF-01":
    case "EF-02":
      return {
        ...document,
        state: {
          ...document.state,
          isBelowRetryThreshold: false,
          isRetryable: false,
        },
      };
    case "EF-26":
      return retrying(document, { timeoutAt: NOW - 1 });
    case "EF-25":
      return retrying(document, { nextRetryAt: NOW });
    case "EF-35":
      return retrying(document, { nextRetryAt: NOW + 1 });
    default:
      return document;
  }
});

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

/** A system view's own address. */
const VIEW = (id: string) => `/executions?view=system:execution-failed:${id}`;

test.beforeEach(async ({ page }) => {
  // The browser's clock is the old queues' moment.
  await page.clock.setFixedTime(NOW);
  await page.addInitScript(() => {
    if (!sessionStorage.getItem("queues-spec-started")) {
      sessionStorage.setItem("queues-spec-started", "1");
      localStorage.setItem("wow-dashboard-locale", "en");
    }
  });
});

/** The IDs the last page matched, before paging. */
function lastMatched(queries: SnapshotQueries): string[] {
  return [...(queries.matched.at(-1) ?? [])].sort();
}

/** The IDs the old queue's own condition selects, at the pinned moment. */
function oldQueue(category: FindCategory): string[] {
  const condition = RetryConditions.categoryToCondition(category, NOW);
  return DOCUMENTS.filter((document) =>
    matches(document, condition as Parameters<typeof matches>[1], NOW),
  )
    .map(({ aggregateId }) => aggregateId)
    .sort();
}

/** Opens `address` and reads what the view it lands on matched. */
async function systemView(
  page: Page,
  queries: SnapshotQueries,
  address: string,
  title: string,
) {
  const answered = page.waitForResponse("**/execution_failed/snapshot/paged");
  await page.goto(address);
  expect((await answered).ok()).toBe(true);
  const workbench = page.getByRole("region", { name: title });
  await expect(workbench).toBeVisible();
  const ids = lastMatched(queries);
  await expect(
    workbench
      .getByRole("navigation", { name: "Pagination" })
      .getByText(`${ids.length} records in all`),
  ).toBeVisible();
  return ids;
}

for (const [path, id, title, category] of QUEUES)
  test(`the old ${path} address opens the ${title} system view, which matches its queue`, async ({
    page,
  }) => {
    const queries = await stubExecutionFailedService(page, DOCUMENTS, {
      now: NOW,
    });

    const before = oldQueue(category);
    const after = await systemView(page, queries, path, title);

    await expect(page).toHaveURL(
      `/executions?view=${encodeURIComponent(`system:execution-failed:${id}`)}`,
    );
    expect(before.length).toBeGreaterThan(1);
    expect(after).toEqual(before);
  });

test("each edge of the moment falls on the side the command side reads", async ({
  page,
}) => {
  const queries = await stubExecutionFailedService(page, DOCUMENTS, {
    now: NOW,
  });

  // Timed out at exactly now is still executing; a millisecond earlier is not.
  const executing = await systemView(
    page,
    queries,
    VIEW("executing"),
    "Executing",
  );
  expect(executing).toContain("EF-10");
  expect(executing).not.toContain("EF-26");
  const toRetry = await systemView(page, queries, VIEW("to-retry"), "To retry");
  expect(toRetry).toContain("EF-26");
  expect(toRetry).not.toContain("EF-10");
  // Due at exactly now is due; a millisecond later is not yet.
  const due = await systemView(
    page,
    queries,
    VIEW("next-retry"),
    "Due for retry",
  );
  expect(due).toContain("EF-25");
  expect(due).not.toContain("EF-35");
  expect(toRetry).toContain("EF-35");

  // What the engine sent: the service's clock, not a moment of its own.
  const sent = JSON.stringify(queries.paged.at(-1)?.filter);
  expect(sent).toContain('"op":"AFTER_NOW"');
  expect(sent).toContain('"op":"BEFORE_NOW"');
  expect(sent).not.toContain(String(NOW));
});

test("the time queues are listed with the rest, their conditions readable", async ({
  page,
}) => {
  await stubExecutionFailedService(page, DOCUMENTS, { now: NOW });
  await page.goto("/executions?view=system:execution-failed:executing");
  const workbench = page.getByRole("region", { name: "Executing" });
  await expect(workbench).toBeVisible();
  // The view's own scope reads as the service's moment.
  await expect(workbench.getByText(/Retry timeout before now/)).toBeVisible();
});
