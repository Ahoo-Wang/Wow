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

/** The UI, as the global setup started it. */
function web(path = "/"): string {
  const base = process.env.ESHOP_WEB_URL;
  if (!base) throw new Error("ESHOP_WEB_URL is not set: run through playwright.config.ts.");
  return base + path;
}

async function actAs(page: Page, user: string) {
  await page.getByLabel("User").selectOption(user);
}

/** Fills one of the admin's forms and submits it. */
async function submit(
  page: Page,
  form: string,
  values: Record<string, string>,
  selects: Record<string, string> = {},
) {
  const scope = page.getByRole("form", { name: form });
  for (const [label, value] of Object.entries(values))
    await scope.getByLabel(label, { exact: true }).fill(value);
  for (const [label, value] of Object.entries(selects))
    await scope.getByLabel(label, { exact: true }).selectOption(value);
  await scope.getByRole("button", { name: `Create ${form.slice(4).toLowerCase()}` }).click();
}

test.describe.configure({ mode: "serial" });

test("the shop end to end: catalog, basket, checkout to payment, orders, shipping", async ({
  page,
}) => {
  await page.goto(web("/"));

  await test.step("an employee builds the catalog and receives stock", async () => {
    await actAs(page, "erin");
    await page.getByRole("link", { name: "Catalog admin" }).click();
    await submit(page, "New brand", { Id: "wow", Name: "Wow" });
    await submit(page, "New category", { Id: "mugs", Name: "Mugs" });
    await expect(
      page.getByRole("form", { name: "New product" }).getByRole("option", { name: "Mugs" }),
    ).toBeAttached();
    await submit(
      page,
      "New product",
      { Id: "mug", Name: "Wow mug", Description: "A mug", Price: "8.50" },
      { Brand: "wow", Category: "mugs" },
    );
    const mug = page.locator('[data-product="mug"]');
    await expect(mug).toContainText("DRAFT");
    await mug.getByRole("button", { name: "Publish" }).click();
    await expect(mug).toContainText("PUBLISHED");
    await mug.getByLabel("Quantity of mug received").fill("10");
    await mug.getByRole("button", { name: "Receive" }).click();
    await expect(mug.getByTestId("stock")).toHaveText("10 / 10");
    // Over the simulated gateway's limit (1000.00): its payment is declined.
    await submit(
      page,
      "New product",
      { Id: "tv", Name: "Wow TV", Description: "", Price: "1500" },
      { Brand: "wow", Category: "mugs" },
    );
    const tv = page.locator('[data-product="tv"]');
    await tv.getByRole("button", { name: "Publish" }).click();
    await expect(tv).toContainText("PUBLISHED");
    await tv.getByLabel("Quantity of tv received").fill("1");
    await tv.getByRole("button", { name: "Receive" }).click();
    await expect(tv.getByTestId("stock")).toHaveText("1 / 1");
  });

  await test.step("an invalid command shows its problem", async () => {
    await submit(page, "New product", { Id: "", Name: "Free mug", Description: "", Price: "0" });
    await expect(page.getByTestId("problem-code")).toHaveText("wow.command.invalid");
  });

  await test.step("a buyer finds the product and fills the basket", async () => {
    await actAs(page, "alice");
    await page.getByRole("link", { name: "Shop" }).click();
    await page.getByLabel("Search").fill("MUG");
    await page.getByRole("button", { name: "Search" }).click();
    await expect(page.getByTestId("product")).toHaveCount(1);
    await page.getByRole("button", { name: "Add Wow mug to the basket" }).click();
    await expect(page.getByRole("status")).toHaveText("Added Wow mug to your basket.");
    await page.getByRole("link", { name: "Basket" }).click();
    await page.getByRole("button", { name: "One mug more" }).click();
    await expect(page.getByTestId("quantity")).toHaveText("2");
    await expect(page.getByTestId("total")).toHaveText("$17.00");
  });

  await test.step("the checkout follows the order to its payment, hop by hop", async () => {
    await page.getByRole("button", { name: "Check out" }).click();
    await expect(page.getByText("Paid:")).toBeVisible({ timeout: 45_000 });
    const hops = page.getByTestId("checkout-hop");
    await expect(hops).toHaveCount(15);
    await expect(page.locator('[data-testid="checkout-hop"][data-state="reached"]')).toHaveCount(15);
    await expect(hops.last()).toContainText("PayOrder");
    await page.getByRole("link", { name: "alice-1" }).click();
    await expect(page.getByTestId("order-status")).toHaveText("PAID");
    await expect(page.getByTestId("payment")).toContainText("SUCCEEDED");
  });

  await test.step("a short stock rejects the reservation and the chain stops there", async () => {
    await page.getByRole("link", { name: "Shop" }).click();
    await page.getByRole("button", { name: "Add Wow mug to the basket" }).click();
    await expect(page.getByRole("status")).toBeVisible();
    await page.getByRole("link", { name: "Basket" }).click();
    for (let quantity = 2; quantity <= 9; quantity++) {
      await page.getByRole("button", { name: "One mug more" }).click();
      await expect(page.getByTestId("quantity")).toHaveText(String(quantity));
    }
    await page.getByRole("button", { name: "Check out" }).click();
    await expect(page.locator('[data-state="failed"]')).toHaveCount(1, { timeout: 45_000 });
    await expect(page.getByRole("alert")).toBeVisible();
  });

  await test.step("a buyer reads only its own orders", async () => {
    await page.getByRole("link", { name: "Orders" }).click();
    await expect(page.getByTestId("order")).toHaveCount(2);
    await actAs(page, "bob");
    await expect(page.getByText("No orders")).toBeVisible();
    // Another buyer's order is not found for it, as one that does not exist (decision D254 ④).
    await page.goto(web("/orders/alice-1"));
    await expect(page.getByTestId("problem-code")).toHaveText("wow.aggregate.not-found");
  });

  await test.step("a declined payment ends the chain short of the paid order, and cancels it", async () => {
    await page.goto(web("/"));
    await page.getByRole("button", { name: "Add Wow TV to the basket" }).click();
    await expect(page.getByRole("status")).toBeVisible();
    await page.getByRole("link", { name: "Basket" }).click();
    await page.getByRole("button", { name: "Check out" }).click();
    // The server ends the chain at the payment saga's hop, which sent nothing for a declined payment (D212).
    await expect(
      page.getByText(
        /the chain ended at handled\(eshop-payment\/OrderPaymentSaga\.onPaymentSucceeded\) without paying the order/,
      ),
    ).toBeVisible({ timeout: 45_000 });
    await expect(page.getByText(/committed\(eshop-ordering\) will not come/)).toBeVisible();
    await page.getByRole("link", { name: "bob-1" }).click();
    await expect(page.getByTestId("order-status")).toHaveText("CANCELLED");
    await expect(page.getByTestId("payment")).toContainText("FAILED");
  });

  await test.step("an employee ships the paid order", async () => {
    await page.goto(web("/orders"));
    await actAs(page, "erin");
    const paid = page.getByTestId("order").filter({ hasText: "alice-1" });
    await paid.getByRole("button", { name: "Ship alice-1" }).click();
    await expect(paid.getByTestId("order-status")).toHaveText("SHIPPED");
    await expect(page.getByTestId("order").filter({ hasText: "alice-2" })).toContainText(
      "CANCELLED",
    );
  });
});
