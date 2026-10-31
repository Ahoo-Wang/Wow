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
import { CommandResultStatus, FunctionKind } from "@ahoo-wang/wow-client";
import { describe, expect, it } from "vitest";
import { CHECKOUT_TO_PAID } from "@/shop/checkout";
import checkoutToPaid from "@/test/checkout-to-paid.sse?raw";
import checkoutTwoProducts from "@/test/checkout-two-products.sse?raw";
import {
  ALICE,
  ERIN,
  fakeServices,
  problemAnswer,
  renderShop,
  result,
  snapshot,
  stateView,
} from "@/test/fakeShop";

const ITEMS = [
  { productId: "mug", unitPrice: 8.5, quantity: 2 },
  { productId: "cap", unitPrice: 12, quantity: 1 },
];

function basket(
  checkoutAnswer: Parameters<typeof fakeServices>[0][number][2],
  items = ITEMS,
) {
  return fakeServices([
    [
      "GET",
      /^\/eshop-basket\/basket\/state$/,
      () => ({
        json: stateView("eshop-basket", "basket", "alice", { checkouts: 0, items }, "alice"),
      }),
    ],
    ["POST", /checkout-basket$/, checkoutAnswer],
    ["POST", /(change-basket-item-quantity|remove-basket-item)$/, () => ({ json: result() })],
  ]);
}

/** The number the ordering gives the order. */
const ORDER = "SO-261031-1";

/** The results of the first `count` hops of the chain: the ordering's act on the order, numbered [ORDER]. */
function hops(count: number) {
  return CHECKOUT_TO_PAID.slice(0, count).map((step, index) =>
    result({
      context: step.hop.context ?? "eshop-basket",
      ...(step.hop.context === "eshop-ordering" ? { aggregate: "order", id: ORDER } : {}),
      hop: step.hop,
      function: {
        kind: FunctionKind.SAGA,
        context: step.hop.context ?? "eshop-basket",
        processor: "saga",
        name: `Step${index}`,
      },
    }),
  );
}

describe("the basket", () => {
  it("shows the buyer's items and their total", async () => {
    const { transport } = basket(() => ({ events: [] }));
    renderShop("/basket", ALICE, transport);
    const rows = await screen.findAllByTestId("basket-item");
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText("$17.00")).toBeInTheDocument();
    expect(screen.getByTestId("total")).toHaveTextContent("$29.00");
  });

  it("changes a quantity, and removes an item at zero", async () => {
    const { transport, received } = basket(() => ({ events: [] }));
    renderShop("/basket", ALICE, transport);
    await userEvent.click(await screen.findByRole("button", { name: "One mug more" }));
    await userEvent.click(screen.getByRole("button", { name: "One cap less" }));
    const commands = () =>
      received.filter((r) => r.method === "POST" && !r.path.endsWith("/query"));
    await waitFor(() => expect(commands()).toHaveLength(2));
    const [change, remove] = commands();
    expect(change.path).toBe("/eshop-basket/basket/change-basket-item-quantity");
    expect(change.body).toEqual({ productId: "mug", quantity: 3 });
    expect(remove.path).toBe("/eshop-basket/basket/remove-basket-item");
    expect(remove.body).toEqual({ productId: "cap" });
  });

  it("reads a basket that does not exist yet as empty", async () => {
    const { transport } = fakeServices([
      [
        "GET",
        /^\/eshop-basket\/basket\/state$/,
        () => problemAnswer(404, "Not Found", "wow.aggregate.not-found"),
      ],
    ]);
    renderShop("/basket", ALICE, transport);
    expect(await screen.findByText(/Your basket is empty/)).toBeInTheDocument();
  });

  it("does not read a missing route as an empty basket", async () => {
    // Only an aggregate that does not exist reads as absent: a route the service lacks is a problem.
    renderShop("/basket", ALICE, fakeServices([]).transport);
    expect(await screen.findByText("wow.route.not-found")).toBeInTheDocument();
  });

  it("checks out, showing each hop of the chain to the paid order", async () => {
    const { transport } = basket(() => ({ events: hops(15) }));
    renderShop("/basket", ALICE, transport);
    await userEvent.click(await screen.findByRole("button", { name: "Check out" }));
    const status = await screen.findByText(/Paid:/);
    expect(within(status).getByRole("link", { name: ORDER })).toHaveAttribute(
      "href",
      `/orders/${ORDER}`,
    );
    const steps = screen.getAllByTestId("checkout-hop");
    expect(steps).toHaveLength(15);
    expect(steps.every((step) => step.dataset.state === "reached")).toBe(true);
    expect(within(steps[14]).getByText("Step14")).toBeInTheDocument();
    expect(screen.getByText(/15 of 15 hops/)).toBeInTheDocument();
  });

  it("names the products as the catalog does, and keeps the keyboard's place (UI-55, UI-59)", async () => {
    const { transport } = fakeServices([
      [
        "GET",
        /^\/eshop-basket\/basket\/state$/,
        () => ({
          json: stateView("eshop-basket", "basket", "alice", { checkouts: 0, items: ITEMS }, "alice"),
        }),
      ],
      [
        "POST",
        /^\/eshop-catalog\/product\/snapshot\/query$/,
        () => ({ json: [snapshot("mug", { name: "Wow mug", price: 8.5, status: "PUBLISHED" })] }),
      ],
      ["POST", /change-basket-item-quantity$/, () => ({ json: result() })],
    ]);
    renderShop("/basket", ALICE, transport);
    const more = await screen.findByRole("button", { name: "One Wow mug more" });
    // A product the catalog does not name is shown by its id.
    expect(screen.getByRole("button", { name: "One cap more" })).toBeInTheDocument();
    more.focus();
    await userEvent.keyboard("{Enter}");
    expect(more).toHaveFocus();
  });

  it("offers no checkout of an empty basket (UI-53)", async () => {
    const { transport } = basket(() => ({ events: [] }), []);
    renderShop("/basket", ALICE, transport);
    expect(await screen.findByText(/Your basket is empty/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Check out" })).toBeDisabled();
  });

  it("groups a basket of two products' results by hop and function, not by their place in the stream (UI-45)", async () => {
    // What the services streamed for alice-4 in a local run: 19 results for 15
    // hops, since the order reserves, and confirms, each of its two products.
    const { transport } = basket(() => ({ stream: checkoutTwoProducts }));
    renderShop("/basket", ALICE, transport);
    await userEvent.click(await screen.findByRole("button", { name: "Check out" }));
    expect(await screen.findByText(/Paid:/)).toBeInTheDocument();
    expect(screen.getByText(/15 of 15 hops/)).toBeInTheDocument();
    const steps = screen.getAllByTestId("checkout-hop");
    expect(steps.map((step) => step.dataset.state)).toEqual(Array(15).fill("reached"));
    const badges = (index: number) =>
      within(steps[index]).queryAllByText(/./, { selector: "[data-slot=badge]" }).map((b) => b.textContent);
    expect(badges(6)).toEqual(["ReserveStock ×2"]);
    expect(badges(7)).toEqual(["onReserveCommitted ×2"]);
    expect(badges(8)).toEqual(["ConfirmOrderStock ×2"]);
    expect(badges(9)).toEqual(["onOrderStockConfirmed ×2"]);
    expect(badges(10)).toEqual(["RequestPayment"]);
    expect(badges(12)).toEqual(["ConfirmPayment"]);
    expect(badges(14)).toEqual(["PayOrder"]);
  });

  it("starts afresh for another user: nothing of the last one's checkout shows (UI-46)", async () => {
    const { transport } = basket(() => ({ stream: checkoutToPaid }));
    renderShop("/basket", ALICE, transport);
    await userEvent.click(await screen.findByRole("button", { name: "Check out" }));
    expect(await screen.findByText(/Paid:/)).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "User" }), "bob");
    await screen.findByText("The basket of bob, from the basket service.");
    expect(screen.queryByText(/Paid:/)).not.toBeInTheDocument();
    expect(screen.queryAllByTestId("checkout-hop")).toHaveLength(0);
  });

  it("checks out on the stream as the services wrote it, every hop reached", async () => {
    // The 15 results the services streamed for alice's checkout in an
    // end-to-end run, byte for byte, each `succeeded` as Wow spells it on the
    // wire. The results of `hops` come from the client's own constants, so they
    // cannot tell when the page and the server spell a status apart.
    const { transport } = basket(() => ({ stream: checkoutToPaid }));
    renderShop("/basket", ALICE, transport);
    await userEvent.click(await screen.findByRole("button", { name: "Check out" }));
    expect(await screen.findByText(/Paid:/)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    // The order by the number the ordering gave it, which the order's hops name.
    expect(screen.getByRole("link", { name: ORDER })).toHaveAttribute("href", `/orders/${ORDER}`);
    const steps = screen.getAllByTestId("checkout-hop");
    expect(steps.map((step) => step.dataset.state)).toEqual(Array(15).fill("reached"));
    expect(within(steps[14]).getByText("PayOrder")).toBeInTheDocument();
    expect(screen.getByText(/15 of 15 hops/)).toBeInTheDocument();
  });

  it("shows a status the client does not know as itself, never as a failure", async () => {
    // The same stream from a server that spells its statuses otherwise: the
    // page stops at the first hop, which it cannot read as success, and says
    // what the server sent.
    const stream = checkoutToPaid.replaceAll('"status":"succeeded"', '"status":"SUCCEEDED"');
    const { transport } = basket(() => ({ stream }));
    renderShop("/basket", ALICE, transport);
    await userEvent.click(await screen.findByRole("button", { name: "Check out" }));
    expect(await screen.findByRole("alert")).toHaveTextContent('Unknown status "SUCCEEDED"');
    expect(screen.queryByText("Failed")).not.toBeInTheDocument();
    expect(screen.queryByText(/Paid:/)).not.toBeInTheDocument();
    const steps = screen.getAllByTestId("checkout-hop");
    expect(steps[0].dataset.state).toBe("unknown");
    expect(steps[1].dataset.state).toBe("not-reached");
    expect(screen.getByText(/1 of 15 hops/)).toBeInTheDocument();
  });

  it("says when the chain ends short of the paid order, as the server says", async () => {
    // A declined payment: OrderPaymentSaga.onPaymentSucceeded sends nothing, and the chain ends there (D212).
    const events = hops(14);
    events[13] = {
      ...events[13],
      chainEnded: true,
      pending: [CHECKOUT_TO_PAID[14].hop],
    };
    const { transport } = basket(() => ({ events }));
    renderShop("/basket", ALICE, transport);
    await userEvent.click(await screen.findByRole("button", { name: "Check out" }));
    expect(
      await screen.findByText("The payment was declined, and order", { exact: false }),
    ).toHaveTextContent(`The payment was declined, and order ${ORDER} was cancelled.`);
    expect(
      screen.getByText(
        /The chain ended at handled\(eshop-payment\/OrderPaymentSaga\.onPaymentSucceeded\) without paying the order: Step13 sent no further command/,
      ),
    ).toBeInTheDocument();
    expect(screen.getByText(/committed\(eshop-ordering\) will not come/)).toBeInTheDocument();
    expect(screen.queryByText(/Paid:/)).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: ORDER })).toBeInTheDocument();
    const steps = screen.getAllByTestId("checkout-hop");
    expect(steps[13].dataset.state).toBe("reached");
    expect(steps[14].dataset.state).toBe("not-reached");
  });

  it("marks the hop that was rejected, with its code", async () => {
    const rejected = result({
      context: "eshop-ordering",
      hop: CHECKOUT_TO_PAID[2].hop,
      status: CommandResultStatus.REJECTED,
      error: { code: "eshop-ordering.order.empty", message: "No items" },
    });
    const { transport } = basket(() => ({ events: [...hops(2), rejected] }));
    renderShop("/basket", ALICE, transport);
    await userEvent.click(await screen.findByRole("button", { name: "Check out" }));
    expect(await screen.findByText("eshop-ordering.order.empty")).toBeInTheDocument();
    // The ordering refused the order itself: no order was created, so none is numbered or named.
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    const steps = screen.getAllByTestId("checkout-hop");
    expect(steps[2].dataset.state).toBe("failed");
    expect(steps[3].dataset.state).toBe("not-reached");
  });

  it("names the order that a rejected reservation cancels, by its number", async () => {
    const rejected = result({
      context: "eshop-inventory",
      aggregate: "stock",
      hop: CHECKOUT_TO_PAID[6].hop,
      status: CommandResultStatus.REJECTED,
      error: { code: "eshop-inventory.stock.insufficient", message: "Short" },
    });
    const { transport } = basket(() => ({ events: [...hops(6), rejected] }));
    renderShop("/basket", ALICE, transport);
    await userEvent.click(await screen.findByRole("button", { name: "Check out" }));
    expect(await screen.findByText("eshop-inventory.stock.insufficient")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(`Order ${ORDER} was cancelled.`);
    expect(screen.getAllByTestId("checkout-hop")[6].dataset.state).toBe("failed");
  });

  it("shows the problem of a checkout refused at admission", async () => {
    const { transport } = basket(
      () =>
        problemAnswer(422, "Unprocessable Content", "eshop-basket.basket.empty", {
          detail: "An empty basket cannot be checked out.",
        }),
    );
    renderShop("/basket", ALICE, transport);
    await userEvent.click(await screen.findByRole("button", { name: "Check out" }));
    expect(await screen.findByText("eshop-basket.basket.empty")).toBeInTheDocument();
    expect(screen.getByText("Unprocessable Content (422)")).toBeInTheDocument();
    expect(screen.queryAllByTestId("checkout-hop")).toHaveLength(0);
  });

  it("is a buyer's page", async () => {
    renderShop("/basket", ERIN, fakeServices([]).transport);
    expect(await screen.findByText("Baskets are the buyers'")).toBeInTheDocument();
  });
});
