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

import { expect, test, type Page, type Route } from "@playwright/test";
import { expectNoAxeViolations } from "./support/axe.ts";
import { stubExecutionFailedService } from "./support/executionFailedService.ts";
import {
  OVERVIEW_NOW,
  overviewExecutions,
  overviewStreams,
  stubExecutionFailedEvents,
} from "./support/overviewService.ts";

// Criterion 5 of the rebuild proposal: axe-core finds nothing against WCAG
// 2.0 / 2.1 A and AA on the seven queues, the analyses, the detail drawer,
// the overview, the event streams and the dashboard workbench, at both
// widths. The service is stubbed; the real-server walkthrough ran the same
// over a seeded MongoDB (the proposal's validation report).

const VIEWS = [
  ["active", "Active"],
  ["to-retry", "To retry"],
  ["executing", "Executing"],
  ["next-retry", "Due for retry"],
  ["non-retryable", "Non-retryable"],
  ["unrecoverable", "Unrecoverable"],
  ["succeeded", "Succeeded"],
  ["all", "All"],
  ["by-status", "By status"],
  ["by-processor", "Active failures by processor"],
  ["daily", "New failures per day"],
  ["clusters", "Failure clusters"],
] as const;

test.use({ timezoneId: "UTC" });

/** The streams of every execution, answered as a page of them. */
async function stubStreamPages(page: Page) {
  const streams = overviewStreams(overviewExecutions()).map((stream) => ({
    ...stream,
    commandId: `${stream.id}-command`,
  }));
  const answer = async (route: Route) => {
    const query = route.request().postDataJSON() ?? {};
    const text = JSON.stringify(query.filter ?? {});
    const owner = /"value":"(EF-\d+)"/.exec(text)?.[1];
    const list = owner
      ? streams.filter((stream) => stream.aggregateId === owner)
      : streams;
    await route.fulfill({
      json: { total: list.length, list: list.slice(0, 10) },
    });
  };
  await page.route("**/execution_failed/event/paged", answer);
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );
  await page.clock.setFixedTime(OVERVIEW_NOW);
  const documents = overviewExecutions();
  await stubExecutionFailedService(page, documents, { now: OVERVIEW_NOW });
  await stubExecutionFailedEvents(page, overviewStreams(documents), {
    now: OVERVIEW_NOW,
  });
  await stubStreamPages(page);
});

test("the overview board", async ({ page }) => {
  await page.goto("/");
  await expect(
    page
      .getByRole("group", { name: "All active", exact: true })
      .locator("[data-slot='metric-value']"),
  ).toBeVisible();
  await expectNoAxeViolations(page, "/");
});

for (const [id, title] of VIEWS)
  test(`the ${title} view`, async ({ page }) => {
    await page.goto(
      `/executions?view=${encodeURIComponent(`system:execution-failed:${id}`)}`,
    );
    const region = page.getByRole("region", { name: title, exact: true });
    await expect(region).toBeVisible();
    // Rows, or the view's own word that there are none.
    await expect(
      region
        .getByRole("table")
        .or(region.getByText(/^(No records|Nothing to show)/))
        .first(),
    ).toBeVisible();
    await expectNoAxeViolations(page, id);
  });

test("the detail drawer, and an event stream opened from its history", async ({
  page,
}) => {
  const id = overviewExecutions()[0].aggregateId;
  await page.goto(`/executions?id=${id}`);
  const drawer = page.getByRole("dialog", { name: new RegExp(id) });
  await expect(
    drawer.getByRole("region", { name: "Apply retry specification" }),
  ).toBeVisible();
  await expectNoAxeViolations(page, "the detail drawer");

  const history = drawer.locator('[data-section="history"]');
  await history.scrollIntoViewIfNeeded();
  await history.getByRole("row").nth(1).click();
  await expect(page.getByText("Stream ID", { exact: true })).toBeVisible();
  await expectNoAxeViolations(page, "the nested event stream");
});

test("the event streams and the dashboard workbench", async ({ page }) => {
  await page.goto("/executions/events");
  await expect(
    page.getByRole("region", { name: "All event streams" }).getByRole("table"),
  ).toBeVisible();
  await expectNoAxeViolations(page, "/executions/events");

  await page.goto("/boards");
  await expect(
    page.getByRole("group", { name: "All active", exact: true }),
  ).toBeVisible();
  await expectNoAxeViolations(page, "/boards");
});

test("an empty service", async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
  await stubExecutionFailedService(page, [], { now: OVERVIEW_NOW });
  await stubExecutionFailedEvents(page, [], { now: OVERVIEW_NOW });
  await page.goto("/");
  await expect(
    page
      .getByRole("group", { name: "All active", exact: true })
      .locator("[data-slot='metric-value']"),
  ).toHaveText("0");
  await expectNoAxeViolations(page, "/ with nothing in it");
  await page.goto("/executions");
  await expect(page.getByRole("region", { name: "Active" })).toBeVisible();
  await expectNoAxeViolations(page, "/executions with nothing in it");
});
