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

/** The product queries the shop sent, in order: the storefront's, not the stock's. */
function productQueries(received: { path: string; body: unknown }[]) {
  return received.filter((r) => r.path === "/eshop-catalog/product/snapshot/query");
}

function catalog(products: object[] = [MUG], stock: object[] = []) {
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
    ["POST", /^\/eshop-inventory\/stock\/snapshot\/query$/, () => ({ json: stock })],
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
      expect(productQueries(received).at(-1)?.body).toMatchObject({
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

  it("keeps its search, category, sort and page in the address, so a link, a reload and Back show them (UI-56)", async () => {
    const { transport, received } = catalog();
    const { router } = renderShop("/?q=mug&category=mugs&sort=price-desc&page=2", ALICE, transport);
    await screen.findByText("Wow mug");
    expect(screen.getByLabelText("Search")).toHaveValue("mug");
    expect(productQueries(received).at(-1)?.body).toMatchObject({
      sort: [{ field: "state.price", direction: "desc" }],
      pagination: { index: 2, size: 12 },
    });
    await userEvent.selectOptions(screen.getByLabelText("Sort by"), "name");
    await waitFor(() =>
      expect(router.state.location.search).toBe("?q=mug&category=mugs"),
    );
    await router.navigate(-1);
    await waitFor(() =>
      expect(router.state.location.search).toBe("?q=mug&category=mugs&sort=price-desc&page=2"),
    );
    expect(screen.getByLabelText("Sort by")).toHaveValue("price-desc");
  });

  it("shows each product's stock in one query, and offers no product that is out of it (UI-55)", async () => {
    const cup = snapshot("cup", { name: "Cup", description: "", price: 3, status: "PUBLISHED" });
    const { transport, received } = catalog(
      [MUG, cup],
      [snapshot("mug", { available: 3, onHand: 5, reservations: {} })],
    );
    renderShop("/", ALICE, transport);
    expect(await screen.findByText("3 in stock")).toBeInTheDocument();
    expect(await screen.findByText("Out of stock")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add Cup to the basket" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Add Wow mug to the basket" })).toBeEnabled();
    const stocks = received.filter((r) => r.path === "/eshop-inventory/stock/snapshot/query");
    expect(stocks).toHaveLength(1);
    expect(stocks[0].body).toMatchObject({ filter: { op: "ids", values: ["mug", "cup"] } });
  });

  it("adds a product to the buyer's basket at its price, saying so on its card", async () => {
    const { transport, received } = catalog([MUG], [
      snapshot("mug", { available: 3, onHand: 3, reservations: {} }),
    ]);
    renderShop("/", ALICE, transport);
    await userEvent.click(
      await screen.findByRole("button", { name: "Add Wow mug to the basket" }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent(
      "Added Wow mug to your basket.",
    );
    const add = received.find((r) => r.path.endsWith("add-basket-item"));
    expect(add?.path).toBe("/eshop-basket/basket/add-basket-item");
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
