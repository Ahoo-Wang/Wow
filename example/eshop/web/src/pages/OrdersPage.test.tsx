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
  paymentId: "alice-1",
  cancelReason: null,
};

function ordering(shipAnswer = (): Answer => ({ json: result() })) {
  return fakeServices([
    [
      "POST",
      /^\/eshop-ordering\/order\/snapshot\/query$/,
      () => ({ json: [snapshot("alice-1", ORDER, { ownerId: "alice" })] }),
    ],
    [
      "GET",
      /^\/eshop-ordering\/order\/alice-1\/state$/,
      () => ({ json: stateView("eshop-ordering", "order", "alice-1", ORDER, "alice") }),
    ],
    [
      "GET",
      /^\/eshop-payment\/payment\/alice-1\/state$/,
      () => ({
        json: stateView("eshop-payment", "payment", "alice-1", {
          amount: 17,
          orderId: "alice-1",
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
      filter: { op: "MATCH_ALL" },
      sort: [{ field: "firstEventTime", direction: "DESC" }],
    });
  });

  it("lets an employee ship a paid order", async () => {
    const { transport, received } = ordering();
    renderShop("/orders", ERIN, transport);
    await userEvent.click(await screen.findByRole("button", { name: "Ship alice-1" }));
    await waitFor(() =>
      expect(received.some((r) => r.path.endsWith("ship-order"))).toBe(true),
    );
    const ship = received.find((r) => r.path.endsWith("ship-order"));
    expect(ship?.path).toBe("/eshop-ordering/order/alice-1/ship-order");
    expect(ship?.headers).toMatchObject({ "wow-user": "erin", "wow-roles": "employee" });
  });

  it("shows an order with its items and its payment", async () => {
    const { transport } = ordering();
    renderShop("/orders/alice-1", ALICE, transport);
    expect(await screen.findByText("Order alice-1")).toBeInTheDocument();
    expect(screen.getByText("Confirmed")).toBeInTheDocument();
    expect(screen.getByTestId("payment")).toHaveTextContent("SUCCEEDED");
    expect(screen.queryByRole("button", { name: "Ship the order" })).toBeNull();
  });

  it("shows why shipping was refused", async () => {
    const { transport } = ordering(() =>
      problemAnswer(409, "Conflict", "eshop-ordering.order.wrong-status"),
    );
    renderShop("/orders/alice-1", ERIN, transport);
    await userEvent.click(await screen.findByRole("button", { name: "Ship the order" }));
    expect(
      await screen.findByText("eshop-ordering.order.wrong-status"),
    ).toBeInTheDocument();
  });

  it("shows another buyer's order as not found", async () => {
    const { transport } = fakeServices([
      [
        "GET",
        /state$/,
        () => problemAnswer(404, "Not Found", "wow.aggregate.not-found"),
      ],
    ]);
    renderShop("/orders/bob-1", ALICE, transport);
    expect(await screen.findByText("wow.aggregate.not-found")).toBeInTheDocument();
  });
});
