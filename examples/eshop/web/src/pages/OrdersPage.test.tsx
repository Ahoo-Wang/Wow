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

const ORDER = {
  status: "PAID" as const,
  items: [{ productId: "mug", unitPrice: 8.5, quantity: 2 }],
  confirmedProducts: ["mug"],
  totalAmount: 17,
  paymentId: "SO-261031-1",
  cancelReason: null,
};

function ordering(shipAnswer = (): Answer => ({ json: result() })) {
  return fakeServices([
    [
      "POST",
      /^\/eshop-ordering\/order\/snapshot\/query$/,
      () => ({ json: [snapshot("SO-261031-1", ORDER, { ownerId: "alice" })] }),
    ],
    [
      "GET",
      /^\/eshop-ordering\/order\/SO-261031-1\/state$/,
      () => ({ json: stateView("eshop-ordering", "order", "SO-261031-1", ORDER, "alice") }),
    ],
    [
      "GET",
      /^\/eshop-payment\/payment\/SO-261031-1\/state$/,
      () => ({
        json: stateView("eshop-payment", "payment", "SO-261031-1", {
          amount: 17,
          orderId: "SO-261031-1",
          status: "SUCCEEDED",
        }),
      }),
    ],
    ["POST", /ship-order$/, shipAnswer],
  ]);
}

describe("the orders", () => {
  it("lists a buyer's orders, newest first, without shipping", async () => {
    const { transport, received } = ordering();
    renderShop("/orders", ALICE, transport);
    const row = await screen.findByTestId("order");
    expect(within(row).getByText("PAID")).toBeInTheDocument();
    expect(within(row).getByText("$17.00")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Ship/ })).toBeNull();
    expect(received[0].body).toMatchObject({
      kind: "list",
      filter: { op: "match-all" },
      sort: [{ field: "firstEventTime", direction: "desc" }],
    });
  });

  it("lets an employee ship a paid order", async () => {
    const { transport, received } = ordering();
    renderShop("/orders", ERIN, transport);
    await userEvent.click(await screen.findByRole("button", { name: "Ship SO-261031-1" }));
    await waitFor(() =>
      expect(received.some((r) => r.path.endsWith("ship-order"))).toBe(true),
    );
    const ship = received.find((r) => r.path.endsWith("ship-order"));
    expect(ship?.path).toBe("/eshop-ordering/order/SO-261031-1/ship-order");
    expect(ship?.headers).toMatchObject({ "wow-user": "erin", "wow-roles": "employee" });
  });

  it("shows an order with its items and its payment", async () => {
    const { transport } = ordering();
    renderShop("/orders/SO-261031-1", ALICE, transport);
    expect(await screen.findByText("Order SO-261031-1")).toBeInTheDocument();
    expect(screen.getByText("Confirmed")).toBeInTheDocument();
    expect(screen.getByTestId("payment")).toHaveTextContent("SUCCEEDED");
    expect(screen.queryByRole("button", { name: "Ship the order" })).toBeNull();
  });

  it("shows why shipping was refused", async () => {
    const { transport } = ordering(() =>
      problemAnswer(409, "Conflict", "eshop-ordering.order.wrong-status"),
    );
    renderShop("/orders/SO-261031-1", ERIN, transport);
    await userEvent.click(await screen.findByRole("button", { name: "Ship the order" }));
    expect(
      await screen.findByText("eshop-ordering.order.wrong-status"),
    ).toBeInTheDocument();
  });

  it("shows an order whose payment service does not answer, its payment unknown (UI-51)", async () => {
    const { transport } = fakeServices([
      [
        "GET",
        /^\/eshop-ordering\/order\/SO-261031-1\/state$/,
        () => ({ json: stateView("eshop-ordering", "order", "SO-261031-1", ORDER, "alice") }),
      ],
      [
        "GET",
        /^\/eshop-payment\/payment\/SO-261031-1\/state$/,
        () => ({ status: 502, json: { title: "Bad Gateway", status: 502 } }),
      ],
    ]);
    renderShop("/orders/SO-261031-1", ERIN, transport);
    expect(await screen.findByText("Order SO-261031-1")).toBeInTheDocument();
    expect(await screen.findByTestId("payment")).toHaveTextContent("unavailable (Service unavailable)");
    expect(screen.getByRole("alert")).toHaveTextContent(
      "The payment service did not answer (502 Bad Gateway). Try again in a moment.",
    );
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
    // The order is the ordering service's: an employee still ships it.
    expect(screen.getByRole("button", { name: "Ship the order" })).toBeEnabled();
  });

  it("says a cancelled order had no payment and its stock was released (UI-54)", async () => {
    const cancelled = { ...ORDER, status: "CANCELLED" as const, cancelReason: "Out of stock" };
    const { transport } = fakeServices([
      [
        "GET",
        /^\/eshop-ordering\/order\/SO-261031-1\/state$/,
        () => ({ json: stateView("eshop-ordering", "order", "SO-261031-1", cancelled, "alice") }),
      ],
      [
        "GET",
        /^\/eshop-payment\/payment\/SO-261031-1\/state$/,
        () => problemAnswer(404, "Not Found", "wow.aggregate.not-found"),
      ],
    ]);
    renderShop("/orders/SO-261031-1", ALICE, transport);
    expect(await screen.findByText("Released")).toBeInTheDocument();
    expect(await screen.findByTestId("payment")).toHaveTextContent(
      "none: the order was cancelled before its payment",
    );
    expect(screen.getByRole("link", { name: "Back to orders" })).toHaveAttribute("href", "/orders");
  });

  it("ships an order once, however often Ship is pressed (UI-58)", async () => {
    let answer: (value: Answer) => void = () => {};
    const pending = new Promise<Answer>((resolve) => (answer = resolve));
    const { transport, received } = ordering();
    const slow: typeof fetch = async (input, init) => {
      if (String(input).endsWith("ship-order")) {
        received.push({ method: "POST", path: "/eshop-ordering/order/SO-261031-1/ship-order", headers: {}, body: {} });
        await pending;
        return new Response(JSON.stringify(result()), { headers: { "Content-Type": "application/json" } });
      }
      return transport(input, init);
    };
    renderShop("/orders", ERIN, slow);
    const ship = await screen.findByRole("button", { name: "Ship SO-261031-1" });
    await userEvent.click(ship);
    await userEvent.click(ship);
    expect(ship).toBeDisabled();
    answer({ json: result() });
    await waitFor(() => expect(ship).toBeEnabled());
    expect(received.filter((r) => r.path.endsWith("ship-order"))).toHaveLength(1);
  });

  it("shows another buyer's order as not found", async () => {
    const { transport } = fakeServices([
      [
        "GET",
        /state$/,
        () => problemAnswer(404, "Not Found", "wow.aggregate.not-found"),
      ],
    ]);
    renderShop("/orders/SO-261031-2", ALICE, transport);
    expect(await screen.findByText("wow.aggregate.not-found")).toBeInTheDocument();
  });
});
