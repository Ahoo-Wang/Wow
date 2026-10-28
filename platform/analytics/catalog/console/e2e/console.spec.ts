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
import { CONNECTION, DATABASE, putTable, views } from "./warehouse.ts";
import { DevelopmentCallerHeaders } from "@ahoo-wang/wow-client";

/*
 * The analytics catalog console end to end (roadmap 7.2c): the console as
 * the catalog server serves it, against the server's own routes on its
 * memory backend, its warehouse the server's warehouse simulator. Everything
 * the console does goes through the catalog as a data owner's would.
 */

interface Caller {
  user: string;
  roles: string[];
}

const EDITOR: Caller = { user: "eve", roles: ["analytics.catalog.editor"] };
const APPROVER: Caller = { user: "abe", roles: ["analytics.catalog.approver"] };
const VIEWER: Caller = { user: "vic", roles: ["analytics.catalog.viewer"] };

/** The console in English, as `caller` (none: the console asks first). */
async function open(page: Page, caller: Caller | null, path = "/#/sources") {
  await page.addInitScript(
    ([who]) => {
      localStorage.setItem("wow-analytics-catalog-console.locale", "en");
      if (who)
        localStorage.setItem(
          "wow-analytics-catalog-console.caller",
          JSON.stringify({ ...who, tenant: "(platform)" }),
        );
    },
    [caller] as const,
  );
  await page.goto(path);
}

/** Confirms the action `name` the panel offers, in its dialog. */
async function act(page: Page, name: string) {
  await page.getByRole("button", { name, exact: true }).first().click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name, exact: true }).click();
}

let table: string;
let source: string;
let dataset: string;

test.describe.configure({ mode: "serial" });

test.beforeAll(async ({ request }) => {
  const suffix = Date.now().toString(36);
  table = `orders_${suffix}`;
  dataset = `shop.orders_${suffix}`;
  source = await putTable(request, table, [
    { name: "tenant", type: "String" },
    { name: "buyer", type: "String" },
    { name: "total", type: "Int64" },
  ]);
});

test("the catalog server serves the console, which asks for a caller first", async ({
  page,
}) => {
  await open(page, null);
  await expect(page.getByText("Sign in to read the catalog")).toBeVisible();
  await page.getByLabel("User").fill(EDITOR.user);
  await page.getByLabel("Roles").fill(EDITOR.roles.join(","));
  await page.getByRole("button", { name: "Use this caller" }).click();
  await expect(
    page.getByRole("button", { name: "All system", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: /Caller: eve/ })).toBeVisible();
});

test("an analyst drafts a source, declares its scope and reviews its columns", async ({
  page,
}) => {
  await open(page, EDITOR, "/#/draft");
  const form = page.getByRole("form", { name: "Draft a source" });
  await form.getByLabel("Connection").fill(CONNECTION);
  await form.getByLabel("Database").fill(DATABASE);
  await form.getByLabel("Table").fill(table);
  await form.getByRole("button", { name: "Draft" }).click();
  await expect(form.getByText(`Drafted ${source}.`)).toBeVisible();

  await page.goto(`/#/sources?id=${encodeURIComponent(source)}`);
  const scope = page.getByRole("form", { name: "Scope" });
  await scope.getByRole("combobox", { name: "Tenant column" }).fill("tenant");
  await scope.getByRole("button", { name: "Save" }).click();
  await expect(scope.getByText("Saved.")).toBeVisible();

  const sensitivity = page.getByRole("form", { name: "Sensitivity" });
  await sensitivity.getByLabel("Level buyer").selectOption("DISPLAY");
  await sensitivity.getByLabel("Keep first buyer").fill("1");
  await sensitivity.getByRole("button", { name: "Save" }).click();
  await expect(sensitivity.getByText("Saved.")).toBeVisible();
});

test("an approver marks the source reviewed", async ({ page, request }) => {
  await open(page, APPROVER, `/#/sources?id=${encodeURIComponent(source)}`);
  await act(page, "Mark reviewed");
  await expect
    .poll(async () => {
      const state = await request.get(
        `/analytics-catalog/source/${encodeURIComponent(source)}/state`,
        { headers: { [DevelopmentCallerHeaders.USER]: APPROVER.user, [DevelopmentCallerHeaders.ROLES]: APPROVER.roles[0] } },
      );
      return ((await state.json()) as { state: { status: string } }).state
        .status;
    })
    .toBe("REVIEWED");
});

test("an analyst drafts a dataset over it and publishes it", async ({
  page,
  request,
}) => {
  await open(page, EDITOR, "/#/draft");
  const form = page.getByRole("form", { name: "Draft a dataset" });
  const [context, name] = dataset.split(".");
  await form.getByLabel("Context", { exact: true }).fill(context);
  await form.getByLabel("Name", { exact: true }).fill(name);
  await form.getByRole("textbox", { name: /^Definition/ }).fill(
    JSON.stringify({
      subject: "o",
      sources: [{ alias: "o", sourceId: source }],
      columns: [
        { name: "buyer", from: "o.buyer" },
        { name: "total", from: "o.total" },
      ],
    }),
  );
  await form.getByRole("button", { name: "Draft" }).click();
  await expect(form.getByText(`Drafted ${dataset}.`)).toBeVisible();

  await page.goto(`/#/datasets?id=${encodeURIComponent(dataset)}`);
  await act(page, "Publish");
  await expect
    .poll(() => views(request))
    .toContain(`wow_datasets.ds_${context}_${name}_v1`);
  await expect(page.getByText("PUBLISHED").first()).toBeVisible();
});

test("a viewer reads the catalog, and the service's refusal is said", async ({
  page,
}) => {
  await open(page, VIEWER, `/#/datasets?id=${encodeURIComponent(dataset)}`);
  await expect(page.getByText(/This caller has neither/)).toBeVisible();
  await act(page, "Publish");
  await expect(
    page.getByRole("alert").filter({ hasText: "(wow.auth.forbidden)" }),
  ).toBeVisible();
});
