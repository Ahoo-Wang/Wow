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

import type { Locator, Page } from "@playwright/test";
import { expect, test } from "./support/test.ts";
import { stubExecutionFailedService } from "./support/executionFailedService.ts";
import {
  OVERVIEW_NOW,
  overviewExecutions,
  overviewStreams,
  stubExecutionFailedEvents,
} from "./support/overviewService.ts";

// The shell is the viewport's height, never more: the top bar stands, and
// what is under it scrolls inside its own page. A workbench fills that page
// exactly — its table scrolls, its pager sits at the bottom of the screen —
// so the reader never scrolls the document to reach the pager on top of the
// table's own scroll (2026-09-30: at 863px the document scrolled by 70px).

const SIZES = [
  { width: 1280, height: 720 },
  { width: 1440, height: 900 },
] as const;

test.use({ timezoneId: "UTC" });

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );
  await page.clock.setFixedTime(OVERVIEW_NOW);
  const documents = overviewExecutions();
  await stubExecutionFailedService(page, documents, { now: OVERVIEW_NOW });
  const streams = overviewStreams(documents);
  await stubExecutionFailedEvents(page, streams, { now: OVERVIEW_NOW });
  await page.route("**/execution_failed/event/paged", (route) =>
    route.fulfill({
      json: { total: streams.length, list: streams.slice(0, 20) },
    }),
  );
});

/** The document is exactly the viewport: nothing of the page scrolls it. */
async function expectNoPageScroll(page: Page) {
  const { scrollHeight, innerHeight } = await page.evaluate(() => ({
    scrollHeight: document.documentElement.scrollHeight,
    innerHeight: window.innerHeight,
  }));
  expect(scrollHeight).toBe(innerHeight);
}

/** The whole of `locator` is on the screen, without scrolling anything. */
async function expectInViewport(page: Page, locator: Locator) {
  const box = (await locator.boundingBox())!;
  const viewport = page.viewportSize()!;
  expect(box.y).toBeGreaterThanOrEqual(0);
  expect(box.y + box.height).toBeLessThanOrEqual(viewport.height);
}

const WORKBENCHES = [
  ["/executions", "Active"],
  ["/events", "All event streams"],
] as const;

for (const size of SIZES)
  test.describe(`${size.width}×${size.height}`, () => {
    test.skip(({ isMobile }) => isMobile, "a desktop size");
    test.use({ viewport: size });

    for (const [path, title] of WORKBENCHES)
      test(`${path} fills the screen and keeps its pager on it`, async ({
        page,
      }) => {
        await page.goto(path);
        const workbench = page.getByRole("region", { name: title });
        await expect(workbench.getByRole("table")).toBeVisible();
        const pager = workbench.getByRole("navigation", {
          name: "Pagination",
        });
        await expect(pager).toBeVisible();

        await expectNoPageScroll(page);
        await expectInViewport(page, pager);
        // The table takes the room the screen has, not a share of the
        // viewport: its bottom meets the pager's top (the room below the
        // rows is the table's own, `row-room`), and it scrolls inside.
        const table = (await workbench
          .locator("[data-slot='record-table']")
          .boundingBox())!;
        const under = (await pager.boundingBox())!;
        expect(under.y - (table.y + table.height)).toBeLessThan(24);
      });

    test("/boards fills the screen", async ({ page }) => {
      await page.goto("/boards");
      await expect(
        page.getByRole("group", { name: "All active", exact: true }),
      ).toBeVisible();
      await expectNoPageScroll(page);
      await expectInViewport(
        page,
        page.locator("#main-content").getByRole("region").first(),
      );
    });

    test("the overview scrolls inside its page, under the top bar", async ({
      page,
    }) => {
      await page.goto("/");
      await expect(
        page.getByRole("group", { name: "All active", exact: true }),
      ).toBeVisible();
      await expectNoPageScroll(page);
      // The board is taller than the screen, and something inside the
      // content area — not the document — scrolls it.
      const scrolls = await page
        .locator("#main-content")
        .evaluate((main) =>
          [main, ...main.querySelectorAll("*")].some(
            (element) =>
              ["auto", "scroll"].includes(getComputedStyle(element).overflowY) &&
              element.scrollHeight > element.clientHeight,
          ),
        );
      expect(scrolls).toBe(true);
      await expectInViewport(page, page.locator("header").first());
    });
  });

test("a phone scrolls the page under the bar, never the document", async ({
  page,
  isMobile,
}) => {
  test.skip(!isMobile, "a phone's size");
  for (const path of ["/", "/executions", "/events", "/boards"]) {
    await page.goto(path);
    await expect(page.locator("#main-content")).not.toBeEmpty();
    await page.waitForLoadState("networkidle");
    await expectNoPageScroll(page);
  }
});
