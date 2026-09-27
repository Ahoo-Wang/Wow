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
  overviewExecutions,
  overviewStreams,
  OVERVIEW_NOW,
  stubExecutionFailedEvents,
} from "./support/overviewService.ts";
import { stubExecutionFailedService } from "./support/executionFailedService.ts";

// The console as the engine's reference host under a strict Content
// Security Policy (review 3, R3-P1-3 and R3-P1-4): no `'unsafe-inline'`
// anywhere, styles from the page's own origin or carrying the page's nonce.
// Every place is walked and a drag is made — the one moment the engine adds
// a `<style>` of its own — and not one violation may be reported.

const NONCE = "e2e-csp-nonce";

const POLICY = [
  "default-src 'self'",
  "script-src 'self'",
  `style-src 'self' 'nonce-${NONCE}'`,
  "img-src 'self' blob: data:",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
].join("; ");

type Violation = { directive: string; blocked: string; sample: string };

/**
 * Serves every page under the policy, its nonce published the way Vite's
 * `html.cspNonce` publishes it, and keeps each violation the page reports.
 */
async function underStrictPolicy(page: Page) {
  await page.addInitScript(() => {
    const seen: Violation[] = [];
    Object.defineProperty(window, "__violations", { value: seen });
    document.addEventListener("securitypolicyviolation", (event) =>
      seen.push({
        directive: event.violatedDirective,
        blocked: event.blockedURI,
        sample: event.sample,
      }),
    );
    localStorage.setItem("wow-dashboard-locale", "en");
  });
  await page.route(
    (url) =>
      url.origin === "http://127.0.0.1:4174" && !url.pathname.includes("."),
    async (route) => {
      if (route.request().resourceType() !== "document")
        return route.fallback();
      const response = await route.fetch();
      const html = (await response.text()).replace(
        "<head>",
        `<head><meta property="csp-nonce" nonce="${NONCE}" />`,
      );
      await route.fulfill({
        response,
        body: html,
        headers: {
          ...response.headers(),
          "content-security-policy": POLICY,
        },
      });
    },
  );
}

async function violations(page: Page): Promise<Violation[]> {
  return page.evaluate(
    () => (window as unknown as { __violations: Violation[] }).__violations,
  );
}

test("every place runs under a strict policy, a drag included", async ({
  page,
}, testInfo) => {
  test.skip(
    testInfo.project.name.startsWith("mobile"),
    "The drag is a mouse's; the places are walked on the desktop.",
  );
  await page.clock.setFixedTime(OVERVIEW_NOW);
  const documents = overviewExecutions();
  await stubExecutionFailedService(page, documents, { now: OVERVIEW_NOW });
  const streams = overviewStreams(documents);
  await stubExecutionFailedEvents(page, streams, { now: OVERVIEW_NOW });
  await page.route("**/execution_failed/event/paged", (route) =>
    route.fulfill({ json: { total: streams.length, list: streams } }),
  );
  await underStrictPolicy(page);

  // The overview: every panel drawn.
  await page.goto("/");
  await expect(
    page.getByRole("group", { name: "All active", exact: true }),
  ).toBeVisible();
  expect(await violations(page)).toEqual([]);

  // A record's detail, its stack highlighted.
  const id = documents[0]!.aggregateId;
  await page.goto(`/executions?id=${id}`);
  await expect(
    page.getByRole("button", { name: "Change function" }),
  ).toBeVisible();
  await page.keyboard.press("Escape");

  // The column settings: a row dragged by its handle, with the mouse —
  // the library adds its grabbing cursor as a `<style>` while it holds it.
  await page.getByRole("button", { name: "Columns", exact: true }).click();
  // (The row key's handle is disabled: it is always shown, first.)
  const handle = page
    .locator("[data-slot='drag-handle']:not([disabled])")
    .first();
  await expect(handle).toBeVisible();
  // Measured once the popover has settled, and a frame left between moves:
  // the library batches pointer moves onto animation frames (the stories'
  // `dragHandleOnto` says why each matters).
  await page.waitForTimeout(500);
  const from = (await handle.boundingBox())!;
  const x = from.x + from.width / 2;
  const y = from.y + from.height / 2;
  await page.mouse.move(x, y);
  await page.mouse.down();
  for (let step = 1; step <= 8; step += 1) {
    await page.mouse.move(x, y + step * 6);
    await page.waitForTimeout(30);
  }
  // While it is held, the library's style is on the page, under the nonce.
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          [...document.head.querySelectorAll("style")].filter(
            (style) => style.nonce !== "",
          ).length,
      ),
    )
    .toBeGreaterThan(0);
  await page.mouse.up();
  await page.keyboard.press("Escape");

  // The event streams and the boards.
  await page.goto("/events");
  await expect(page.getByRole("table").first()).toBeVisible();
  await page.goto("/boards?view=system%3Aoverview%3Afailures");
  await expect(
    page.getByRole("group", { name: "Where active failures go", exact: true }),
  ).toBeVisible();

  expect(await violations(page)).toEqual([]);
});
