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
import {
  ALICE,
  ERIN,
  fakeServices,
  problemAnswer,
  renderShop,
  result,
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

function hops(count: number) {
  return CHECKOUT_TO_PAID.slice(0, count).map((step, index) =>
    result({
      context: step.hop.context ?? "eshop-basket",
      hop: step.hop,
      function: {
        kind: FunctionKind.SAGA,
        context: step.hop.context ?? "eshop-basket",
        processor: "saga",
        name: `Step${index}`,
      },
      results: index === 0 ? { orderId: "alice-1" } : {},
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
    await waitFor(() =>
      expect(received.filter((r) => r.method === "POST")).toHaveLength(2),
    );
    const [change, remove] = received.filter((r) => r.method === "POST");
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
    expect(within(status).getByRole("link", { name: "alice-1" })).toHaveAttribute(
      "href",
      "/orders/alice-1",
    );
    const steps = screen.getAllByTestId("checkout-hop");
    expect(steps).toHaveLength(15);
    expect(steps.every((step) => step.dataset.state === "reached")).toBe(true);
    expect(within(steps[14]).getByText("Step14")).toBeInTheDocument();
    expect(screen.getByText(/15 of 15 hops/)).toBeInTheDocument();
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
      await screen.findByText(
        /the chain ended at handled\(eshop-payment\/OrderPaymentSaga\.onPaymentSucceeded\) without paying the order: Step13 sent no further command/,
      ),
    ).toBeInTheDocument();
    expect(screen.getByText(/committed\(eshop-ordering\) will not come/)).toBeInTheDocument();
    expect(screen.queryByText(/Paid:/)).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "alice-1" })).toBeInTheDocument();
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
    const steps = screen.getAllByTestId("checkout-hop");
    expect(steps[2].dataset.state).toBe("failed");
    expect(steps[3].dataset.state).toBe("not-reached");
  });

  it("shows the problem of a checkout refused at admission", async () => {
    const { transport } = basket(
      () =>
        problemAnswer(422, "Unprocessable Content", "eshop-basket.basket.empty", {
          detail: "An empty basket cannot be checked out.",
        }),
      [],
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
