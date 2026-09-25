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
  mongoDescriptors,
  searchingSnapshotDescriptor,
  stubDescriptors,
} from "./support/descriptorService.ts";
import {
  executions,
  stubExecutionFailedService,
} from "./support/executionFailedService.ts";

// The console reads the service's query capability descriptors (N5 C6) and
// the engine offers only what they admit. 「搜索错误」 is the case that
// differs by storage (G15): a MongoDB server without a text index has no
// full-text search, so the box is not drawn and no search is ever sent;
// where the storage searches errors, it is drawn and searches by phrase.

const DOCUMENTS = executions();

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );
});

async function openActive(page: Page) {
  await page.goto("/executions");
  const workbench = page.getByRole("region", { name: "Active" });
  await expect(workbench).toBeVisible();
  await expect(page.getByRole("checkbox", { name: /^Select EF-/ })).toHaveCount(
    20,
  );
  return workbench;
}

test("on MongoDB the error search is not offered, and none is sent", async ({
  page,
}) => {
  const queries = await stubExecutionFailedService(page, DOCUMENTS);
  const reads = await stubDescriptors(page, mongoDescriptors());
  const workbench = await openActive(page);

  await expect(
    workbench.getByRole("searchbox", { name: "Search errors" }),
  ).toHaveCount(0);
  // Read before the first query, once for the page.
  expect(reads.snapshot).toBe(1);
  expect(JSON.stringify(queries.paged)).not.toContain('"op":"SEARCH"');

  // The other system views run as before under the narrowed definition.
  const toRetry = page.getByRole("button", {
    name: "To retry system",
    exact: true,
  });
  const expand = page.getByRole("button", { name: "Show the view list" });
  if ((await expand.count()) > 0) await expand.click();
  await toRetry.click();
  await expect(page.getByRole("region", { name: "To retry" })).toBeVisible();
  expect(JSON.stringify(queries.paged)).not.toContain('"op":"SEARCH"');
});

test("where the storage searches errors, the search box searches by phrase", async ({
  page,
}) => {
  const queries = await stubExecutionFailedService(page, DOCUMENTS);
  await stubDescriptors(page, {
    snapshot: searchingSnapshotDescriptor(),
    event: mongoDescriptors().event,
  });
  const workbench = await openActive(page);

  const search = workbench.getByRole("searchbox", { name: "Search errors" });
  await search.fill("gateway timed out");
  await search.press("Enter");
  await expect
    .poll(() => JSON.stringify(queries.paged.at(-1)?.filter))
    .toContain('"op":"SEARCH"');
  expect(JSON.stringify(queries.paged.at(-1)?.filter)).toContain(
    '"mode":"PHRASE"',
  );
});
