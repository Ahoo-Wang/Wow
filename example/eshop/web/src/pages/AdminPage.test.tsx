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
  stateView,
} from "@/test/fakeShop";

function catalog(createAnswer = (): Answer => ({ json: result() })) {
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
      "GET",
      /^\/eshop-inventory\/stock\/mug\/state$/,
      () => ({
        json: stateView("eshop-inventory", "stock", "mug", {
          available: 7,
          onHand: 10,
          reservations: {},
        }),
      }),
    ],
    ["POST", /create-product$/, createAnswer],
    ["POST", /\/(create-brand|create-category|change-product-price|publish-product|receive-stock)$/, () => ({ json: result() })],
  ]);
}

describe("the catalog admin", () => {
  it("shows each product with its brand, category and stock", async () => {
    const { transport } = catalog();
    renderShop("/admin", ERIN, transport);
    const row = await screen.findByTestId("admin-product");
    expect(within(row).getByText("mug · Wow · Mugs")).toBeInTheDocument();
    expect(await within(row).findByText("7 / 10")).toBeInTheDocument();
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
    expect(create?.headers).toMatchObject({ "x-user": "erin", "x-role": "employee" });
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
    await userEvent.click(within(row).getByRole("button", { name: "Change" }));
    await userEvent.click(within(row).getByRole("button", { name: "Publish" }));
    await userEvent.type(within(row).getByLabelText("Quantity of mug received"), "5");
    await userEvent.click(within(row).getByRole("button", { name: "Receive" }));
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

  it("shows a rejection with its violations", async () => {
    const { transport } = catalog(() =>
      problemAnswer(422, "Unprocessable Content", "eshop-catalog.product.invalid-price", {
        detail: "A price is positive.",
        violations: [{ path: "price", code: "positive", message: "must be positive" }],
      }),
    );
    renderShop("/admin", ERIN, transport);
    const form = await screen.findByRole("form", { name: "New product" });
    await userEvent.type(within(form).getByLabelText("Name"), "Free mug");
    await userEvent.type(within(form).getByLabelText("Price"), "0");
    await userEvent.click(within(form).getByRole("button", { name: "Create product" }));
    expect(
      await screen.findByText("eshop-catalog.product.invalid-price"),
    ).toBeInTheDocument();
    expect(screen.getByText("A price is positive.")).toBeInTheDocument();
    expect(screen.getByText(/must be positive/)).toBeInTheDocument();
  });

  it("tells a buyer the catalog refuses its changes", async () => {
    renderShop("/admin", ALICE, catalog().transport);
    expect(await screen.findByRole("note")).toHaveTextContent(
      "Only an employee changes the catalog",
    );
  });
});
