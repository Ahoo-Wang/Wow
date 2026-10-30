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

import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import {
  ALICE,
  type Answer,
  ERIN,
  fakeServices,
  problemAnswer,
  renderShop,
  result,
  snapshot,
} from "@/test/fakeShop";

function catalog(
  createAnswer = (): Answer => ({ json: result() }),
  publishAnswer = (): Answer => ({ json: result() }),
) {
  return fakeServices([
    [
      "POST",
      /^\/eshop-catalog\/brand\/snapshot\/query$/,
      () => ({ json: [snapshot("wow", { name: "Wow" })] }),
    ],
    [
      "POST",
      /^\/eshop-catalog\/category\/snapshot\/query$/,
      () => ({ json: [snapshot("mugs", { name: "Mugs" })] }),
    ],
    [
      "POST",
      /^\/eshop-catalog\/product\/snapshot\/query$/,
      () => ({
        json: [
          snapshot("mug", {
            name: "Wow mug",
            description: "",
            price: 8.5,
            status: "DRAFT",
            brandId: "wow",
            categoryId: "mugs",
          }),
        ],
      }),
    ],
    [
      "POST",
      /^\/eshop-inventory\/stock\/snapshot\/query$/,
      () => ({ json: [snapshot("mug", { available: 7, onHand: 10, reservations: {} })] }),
    ],
    ["POST", /create-product$/, createAnswer],
    ["POST", /publish-product$/, publishAnswer],
    [
      "POST",
      /\/(create-brand|create-category|rename-brand|rename-category|change-product-price|update-product|discontinue-product|receive-stock)$/,
      () => ({ json: result() }),
    ],
  ]);
}

describe("the catalog admin", () => {
  it("shows each product with its brand, category and stock, read in one query", async () => {
    const { transport, received } = catalog();
    renderShop("/admin", ERIN, transport);
    const row = await screen.findByTestId("admin-product");
    expect(within(row).getByText("mug · Wow · Mugs")).toBeInTheDocument();
    expect(await within(row).findByText("7 / 10")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Stock (available / on hand)" })).toBeInTheDocument();
    expect(
      received.filter((r) => r.path.startsWith("/eshop-inventory/")).map((r) => r.path),
    ).toEqual(["/eshop-inventory/stock/snapshot/query"]);
    expect(screen.getByRole("list", { name: "Brands" })).toHaveTextContent("Wow");
    expect(screen.getByRole("list", { name: "Categories" })).toHaveTextContent("Mugs");
  });

  it("creates a product as a draft of the brand and category chosen", async () => {
    const { transport, received } = catalog();
    renderShop("/admin", ERIN, transport);
    const form = await screen.findByRole("form", { name: "New product" });
    await waitFor(() =>
      expect(within(form).getAllByRole("option", { name: "Wow" })).toHaveLength(1),
    );
    await userEvent.type(within(form).getByLabelText("Id"), "cup");
    await userEvent.type(within(form).getByLabelText("Name"), "Wow cup");
    await userEvent.type(within(form).getByLabelText("Price"), "4.25");
    await userEvent.selectOptions(within(form).getByLabelText("Brand"), "wow");
    await userEvent.selectOptions(within(form).getByLabelText("Category"), "mugs");
    await userEvent.click(within(form).getByRole("button", { name: "Create product" }));
    await waitFor(() =>
      expect(received.some((r) => r.path.endsWith("create-product"))).toBe(true),
    );
    const create = received.find((r) => r.path.endsWith("create-product"));
    expect(create?.path).toBe("/eshop-catalog/product/cup/create-product");
    expect(create?.headers).toMatchObject({ "wow-user": "erin", "wow-roles": "employee" });
    expect(create?.body).toEqual({
      name: "Wow cup",
      price: 4.25,
      description: "",
      brandId: "wow",
      categoryId: "mugs",
    });
  });

  it("changes a price, publishes, and receives stock", async () => {
    const { transport, received } = catalog();
    renderShop("/admin", ERIN, transport);
    const row = await screen.findByTestId("admin-product");
    await userEvent.type(within(row).getByLabelText("New price of mug"), "9");
    await userEvent.click(within(row).getByRole("button", { name: "Change the price of Wow mug" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Changed the price of Wow mug to $9.00.");
    expect(within(row).getByLabelText("New price of mug")).toHaveValue(null);
    await userEvent.click(within(row).getByRole("button", { name: "Publish Wow mug" }));
    await userEvent.type(within(row).getByLabelText("Quantity of mug received"), "5");
    await userEvent.click(within(row).getByRole("button", { name: "Receive stock of Wow mug" }));
    await waitFor(() =>
      expect(received.some((r) => r.path.endsWith("receive-stock"))).toBe(true),
    );
    const posts = received.filter((r) => r.method === "POST" && !r.path.endsWith("query"));
    expect(posts.map((r) => [r.path, r.body])).toEqual([
      ["/eshop-catalog/product/mug/change-product-price", { price: 9 }],
      ["/eshop-catalog/product/mug/publish-product", {}],
      ["/eshop-inventory/stock/mug/receive-stock", { quantity: 5 }],
    ]);
  });

  it("edits a product's brand and category, and discontinues it (UI-48)", async () => {
    const { transport, received } = catalog();
    renderShop("/admin", ERIN, transport);
    await userEvent.click(await screen.findByRole("button", { name: "Edit Wow mug" }));
    const edit = screen.getByRole("form", { name: "Edit mug" });
    await userEvent.clear(within(edit).getByLabelText("Name"));
    await userEvent.type(within(edit).getByLabelText("Name"), "Wow mug XL");
    await userEvent.selectOptions(within(edit).getByLabelText("Brand"), "");
    await userEvent.click(within(edit).getByRole("button", { name: "Save Wow mug" }));
    await waitFor(() =>
      expect(received.some((r) => r.path.endsWith("update-product"))).toBe(true),
    );
    expect(received.find((r) => r.path.endsWith("update-product"))).toMatchObject({
      path: "/eshop-catalog/product/mug/update-product",
      body: { name: "Wow mug XL", description: "", brandId: null, categoryId: "mugs" },
    });
    const discontinue = screen.getByRole("form", { name: "Discontinue mug" });
    await userEvent.type(within(discontinue).getByLabelText(/Discontinue for good/), "No longer made");
    await userEvent.click(within(discontinue).getByRole("button", { name: "Discontinue Wow mug" }));
    await waitFor(() =>
      expect(received.find((r) => r.path.endsWith("discontinue-product"))?.body).toEqual({
        reason: "No longer made",
      }),
    );
  });

  it("renames a brand in place", async () => {
    const { transport, received } = catalog();
    renderShop("/admin", ERIN, transport);
    const brands = await screen.findByRole("list", { name: "Brands" });
    await userEvent.type(within(brands).getByLabelText("New name of Wow"), "Wow!");
    await userEvent.click(within(brands).getByRole("button", { name: "Rename Wow" }));
    await waitFor(() =>
      expect(received.find((r) => r.path.endsWith("rename-brand"))).toMatchObject({
        path: "/eshop-catalog/brand/wow/rename-brand",
        body: { name: "Wow!" },
      }),
    );
    expect(await within(brands).findByRole("status")).toHaveTextContent("Renamed Wow to Wow!.");
  });

  it("shows a refusal under the row it answers, not at the top of the page (UI-47)", async () => {
    const { transport } = catalog(undefined, () =>
      problemAnswer(409, "Incomplete", "eshop-catalog.product.incomplete", {
        detail: "The product needs a brand and a category.",
      }),
    );
    renderShop("/admin", ERIN, transport);
    await userEvent.click(await screen.findByRole("button", { name: "Publish Wow mug" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("eshop-catalog.product.incomplete");
    // The alert is in the product table, right after the row: where the employee pressed Publish.
    expect(screen.getByRole("table", { name: "Catalog products" })).toContainElement(alert);
  });

  it("shows an invalid command with its violations", async () => {
    const { transport } = catalog(() =>
      problemAnswer(400, "Bad Request", "wow.command.invalid", {
        detail: "CreateProduct is invalid.",
        violations: [{ path: "price", code: "positive", message: "must be greater than 0" }],
      }),
    );
    renderShop("/admin", ERIN, transport);
    const form = await screen.findByRole("form", { name: "New product" });
    await userEvent.type(within(form).getByLabelText("Name"), "Free mug");
    await userEvent.type(within(form).getByLabelText("Price"), "0");
    await userEvent.click(within(form).getByRole("button", { name: "Create product" }));
    expect(await screen.findByText("wow.command.invalid")).toBeInTheDocument();
    expect(screen.getByText("CreateProduct is invalid.")).toBeInTheDocument();
    expect(screen.getByText(/must be greater than 0/)).toBeInTheDocument();
  });

  it("tells a buyer the catalog refuses its changes", async () => {
    renderShop("/admin", ALICE, catalog().transport);
    expect(await screen.findByRole("note")).toHaveTextContent(
      "Only an employee changes the catalog and the stock",
    );
  });
});
