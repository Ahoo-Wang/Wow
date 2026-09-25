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

import { expect, test, type APIRequestContext } from "@playwright/test";

// The smoke of the console against a real compensation server: nothing is
// stubbed. It runs only when WOW_COMPENSATION_URL names a server that serves
// this build (see playwright.config.ts and RELEASING.md §C′ step 5), and it
// writes: it seeds its own failed executions, under processor names no other
// run shares, so it can say exactly which rows it expects.

const RUN = Date.now().toString(36);
const PROCESSORS = [`SmokeSaga${RUN}A`, `SmokeSaga${RUN}B`];

async function seedFailedExecution(
  request: APIRequestContext,
  processorName: string,
) {
  const response = await request.post(
    "/execution_failed/create_execution_failed",
    {
      headers: { "Command-Wait-Stage": "SNAPSHOT" },
      data: {
        eventId: {
          id: `${processorName}-event`,
          version: 1,
          aggregateId: {
            contextName: "views-smoke",
            aggregateName: "order",
            aggregateId: `${processorName}-order`,
            tenantId: "(0)",
          },
        },
        function: {
          contextName: "views-smoke",
          processorName,
          name: "onOrderCreated",
          functionKind: "EVENT",
        },
        error: {
          errorCode: "VIEWS_SMOKE",
          errorMsg: `views smoke ${processorName}`,
          stackTrace: "",
          bindingErrors: [],
        },
        executeAt: Date.now(),
        recoverable: "RECOVERABLE",
      },
    },
  );
  expect(response.ok(), await response.text()).toBe(true);
  expect(await response.json()).toMatchObject({ succeeded: true });
}

test.beforeAll(async ({ request }) => {
  for (const processor of PROCESSORS)
    await seedFailedExecution(request, processor);
});

test("the preview route shows real rows and a filter narrows them", async ({
  page,
}) => {
  const failures: string[] = [];
  page.on("response", (response) => {
    if (response.status() >= 400)
      failures.push(`${response.status()} ${response.url()}`);
  });
  page.on("pageerror", (error) => failures.push(error.message));
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );

  // Straight to the route: the server answers it with the console.
  await page.goto("/executions");
  const workbench = page.getByRole("region", { name: "Active" });
  await expect(workbench).toBeVisible();

  // Real rows: the newest first, so both seeded executions are on page one.
  for (const processor of PROCESSORS)
    await expect(
      workbench.getByRole("row").filter({ hasText: processor }),
    ).toHaveCount(1);

  // A condition on the processor, picked in the filter editor and applied.
  const toggle = workbench.getByRole("button", { name: /^Filter/ });
  if ((await toggle.getAttribute("aria-expanded")) !== "true")
    await toggle.click();
  await workbench.getByRole("button", { name: "Add", exact: true }).click();
  const picker = page.getByRole("dialog");
  await picker
    .getByRole("checkbox", { name: "Processor", exact: true })
    .check();
  await picker.getByRole("button", { name: "Done" }).click();
  await expect(picker).toBeHidden();
  const value = workbench.getByRole("combobox", { name: "Processor value" });
  await value.fill(PROCESSORS[0]);
  await value.press("Enter");
  await workbench.getByRole("button", { name: "Apply", exact: true }).click();

  // A record is a row with its own box; "Select all rows" is not one.
  const rows = workbench.getByRole("checkbox", {
    name: /^Select (?!all rows$)/,
  });
  await expect(rows).toHaveCount(1);
  await expect(
    workbench.getByRole("row").filter({ hasText: PROCESSORS[0] }),
  ).toHaveCount(1);
  await expect(
    workbench.getByRole("row").filter({ hasText: PROCESSORS[1] }),
  ).toHaveCount(0);

  expect(failures).toEqual([]);
});

test("the time queues ask the server's clock and it answers", async ({
  page,
}) => {
  const failures: string[] = [];
  page.on("response", (response) => {
    if (response.status() >= 400)
      failures.push(`${response.status()} ${response.url()}`);
  });
  page.on("pageerror", (error) => failures.push(error.message));
  await page.addInitScript(() =>
    localStorage.setItem("wow-dashboard-locale", "en"),
  );

  // A new failure is retryable at once: in "To retry", whose condition
  // carries BEFORE_NOW for the timed-out preparations.
  await page.goto("/executions?view=system:execution-failed:to-retry");
  const toRetry = page.getByRole("region", { name: "To retry" });
  await expect(toRetry).toBeVisible();
  for (const processor of PROCESSORS)
    await expect(
      toRetry.getByRole("row").filter({ hasText: processor }),
    ).toHaveCount(1);

  // Its first retry is scheduled after the minimum backoff, so it is not
  // due yet: "Due for retry" asks `NOR AFTER_NOW` of the next retry. The
  // answer itself is read, since no row is what an unanswered page shows too.
  const answered = page.waitForResponse(
    (response) =>
      response.url().endsWith("/execution_failed/snapshot/paged") &&
      response.request().method() === "POST",
  );
  await page.goto("/executions?view=system:execution-failed:next-retry");
  const response = await answered;
  expect(response.ok(), await response.text()).toBe(true);
  expect(JSON.stringify(response.request().postDataJSON())).toContain(
    '"op":"AFTER_NOW"',
  );
  const { list } = (await response.json()) as {
    list: Array<{ state: { function: { processorName: string } } }>;
  };
  expect(
    list.filter(({ state }) =>
      PROCESSORS.includes(state.function.processorName),
    ),
  ).toEqual([]);
  await expect(
    page.getByRole("region", { name: "Due for retry" }),
  ).toBeVisible();

  expect(failures).toEqual([]);
});
