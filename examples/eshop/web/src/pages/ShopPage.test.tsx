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

import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import {
  ALICE,
  ERIN,
  fakeServices,
  problemAnswer,
  renderShop,
  result,
  snapshot,
} from "@/test/fakeShop";
import { productFilter } from "./ShopPage";

const MUG = snapshot("mug", {
  name: "Wow mug",
  description: "A mug",
  price: 8.5,
  status: "PUBLISHED",
  brandId: "wow",
  categoryId: "mugs",
});

function catalog(products: object[] = [MUG]) {
  return fakeServices([
    [
      "POST",
      /^\/eshop-catalog\/category\/snapshot\/query$/,
      () => ({ json: [snapshot("mugs", { name: "Mugs" })] }),
    ],
    [
      "POST",
      /^\/eshop-catalog\/product\/snapshot\/query$/,
      () => ({ json: { total: products.length, exact: true, list: products } }),
    ],
    ["POST", /add-basket-item$/, () => ({ json: result() })],
  ]);
}

describe("the shop", () => {
  it("lists the published products a buyer may buy, with the caller's headers", async () => {
    const { transport, received } = catalog();
    renderShop("/", ALICE, transport);
    expect(await screen.findByText("Wow mug")).toBeInTheDocument();
    expect(screen.getByText("$8.50")).toBeInTheDocument();
    const query = received.find((r) => r.path.startsWith("/eshop-catalog/product"));
    expect(query?.headers["wow-user"]).toBe("alice");
    expect(query?.headers["wow-owner"]).toBe("alice");
    expect(query?.headers["wow-roles"]).toBeUndefined();
    expect(query?.body).toMatchObject({
      kind: "paged",
      filter: { op: "eq", field: "state.status", value: "PUBLISHED" },
      sort: [{ field: "state.name", direction: "asc" }],
      pagination: { index: 1, size: 12 },
    });
  });

  it("searches by name and category with the query DSL", async () => {
    const { transport, received } = catalog();
    renderShop("/", ALICE, transport);
    await screen.findByText("Wow mug");
    await userEvent.selectOptions(await screen.findByLabelText("Category"), "mugs");
    await userEvent.type(screen.getByLabelText("Search"), "mug");
    await userEvent.selectOptions(screen.getByLabelText("Sort by"), "price-desc");
    await userEvent.click(screen.getByRole("button", { name: "Search" }));
    await waitFor(() =>
      expect(received.at(-1)?.body).toMatchObject({
        filter: {
          op: "and",
          operands: [
            { op: "eq", field: "state.status", value: "PUBLISHED" },
            { op: "eq", field: "state.categoryId", value: "mugs" },
            {
              op: "contains",
              field: "state.name",
              value: "mug",
              stringComparison: "case-insensitive",
            },
          ],
        },
        sort: [{ field: "state.price", direction: "desc" }],
      }),
    );
  });

  it("adds a product to the buyer's basket at its price", async () => {
    const { transport, received } = catalog();
    renderShop("/", ALICE, transport);
    await userEvent.click(
      await screen.findByRole("button", { name: "Add Wow mug to the basket" }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent(
      "Added Wow mug to your basket.",
    );
    const add = received.find((r) => r.path.endsWith("add-basket-item"));
    expect(add?.path).toBe("/eshop-basket/basket/alice/add-basket-item");
    expect(add?.body).toEqual({ productId: "mug", unitPrice: 8.5, quantity: 1 });
  });

  it("shows every status to an employee, who buys nothing", async () => {
    const draft = snapshot("cup", { name: "Cup", description: "", price: 3, status: "DRAFT" });
    const { transport, received } = catalog([MUG, draft]);
    renderShop("/", ERIN, transport);
    expect(await screen.findByText("DRAFT")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Add .* to the basket/ })).toBeNull();
    const query = received.find((r) => r.path.startsWith("/eshop-catalog/product"));
    expect(query?.headers["wow-roles"]).toBe("employee");
    expect(query?.body).toMatchObject({ filter: { op: "match-all" } });
  });

  it("shows the problem a query answers", async () => {
    const { transport } = fakeServices([
      [
        "POST",
        /snapshot\/query$/,
        () =>
          problemAnswer(400, "Bad Request", "wow.query.invalid", {
            detail: "No field state.colour",
          }),
      ],
    ]);
    renderShop("/", ALICE, transport);
    expect(await screen.findAllByRole("alert")).not.toHaveLength(0);
    expect(screen.getByText("wow.query.invalid")).toBeInTheDocument();
    expect(screen.getByText("No field state.colour")).toBeInTheDocument();
  });

  it("builds its filter from the search", () => {
    expect(productFilter("", "", false)).toEqual({ op: "match-all" });
    expect(productFilter(" mug ", "", false)).toMatchObject({
      op: "contains",
      value: "mug",
    });
  });
});
