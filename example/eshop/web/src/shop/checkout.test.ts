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

import { formatWaitPlan, WowProblemError } from "@ahoo-wang/wow-client/v10";
import { describe, expect, it } from "vitest";
import { ALICE, fakeServices, problemAnswer, result } from "@/test/fakeShop";
import { shopClients } from "./clients";
import {
  checkout,
  CHECKOUT_TO_PAID,
  isPaid,
  orderIdOf,
  type CheckoutProgress,
} from "./checkout";

/** The README's checkout wait, "Waiting for the order", hop for hop. */
const README_CHAIN =
  "committed(eshop-basket) > handled(eshop-basket/BasketCheckoutSaga.onBasketCheckedOut) > committed(eshop-ordering)" +
  " > handled(eshop-ordering/OrderProcessSaga.onOrderSubmitted) > committed(eshop-ordering)" +
  " > handled(eshop-ordering/OrderProcessSaga.onOrderAwaitingValidation) > committed(eshop-inventory)" +
  " > handled(eshop-ordering/OrderProcessSaga.onReserveCommitted) > committed(eshop-ordering)" +
  " > handled(eshop-payment/OrderPaymentSaga.onOrderStockConfirmed) > committed(eshop-payment)" +
  " > handled(eshop-payment/PaymentGatewaySaga.onPaymentRequested) > committed(eshop-payment)" +
  " > handled(eshop-payment/OrderPaymentSaga.onPaymentSucceeded) > committed(eshop-ordering)";

/** The results of the first `count` hops of the chain, the basket's first. */
function hops(count: number) {
  return CHECKOUT_TO_PAID.slice(0, count).map((step, index) =>
    result({
      context: step.hop.context ?? "eshop-basket",
      aggregate: index < 2 ? "basket" : "order",
      id: index < 2 ? "alice" : "alice-1",
      hop: step.hop,
      results: index === 0 ? { orderId: "alice-1" } : {},
    }),
  );
}

describe("the checkout", () => {
  it("waits for the 15 hops of the README, from the basket to the paid order", () => {
    expect(CHECKOUT_TO_PAID).toHaveLength(15);
    expect(formatWaitPlan(CHECKOUT_TO_PAID.map((step) => step.hop))).toBe(
      README_CHAIN,
    );
  });

  it("sends the chain as Wow-Wait and reports each hop's result as it streams", async () => {
    const { transport, received } = fakeServices([
      ["POST", /checkout-basket$/, () => ({ events: hops(15) })],
    ]);
    const seen: CheckoutProgress[] = [];
    const progress = await checkout(shopClients(ALICE, transport), "alice", (p) =>
      seen.push(p),
    );
    expect(received[0].path).toBe("/eshop-basket/basket/alice/checkout-basket");
    expect(received[0].headers["wow-wait"]).toBe(README_CHAIN);
    expect(received[0].headers["wow-wait-timeout"]).toBe("30s");
    expect(received[0].headers.accept).toBe("text/event-stream");
    expect(received[0].headers["x-user"]).toBe("alice");
    expect(progress).toMatchObject({ orderId: "alice-1", done: true });
    expect(progress.results).toHaveLength(15);
    expect(progress.failure).toBeUndefined();
    expect(seen.map((p) => p.results.length)).toEqual([
      1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 15,
    ]);
  });

  it("ends at a hop that did not succeed", async () => {
    const rejected = result({
      context: "eshop-inventory",
      aggregate: "stock",
      status: "REJECTED",
      error: { code: "eshop-inventory.stock.insufficient", message: "Short" },
    });
    const { transport } = fakeServices([
      ["POST", /checkout-basket$/, () => ({ events: [...hops(6), rejected] })],
    ]);
    const progress = await checkout(shopClients(ALICE, transport), "alice", () => {});
    expect(progress.results).toHaveLength(7);
    expect(progress.failure).toBe(progress.results[6]);
    expect(progress.done).toBe(true);
  });

  it("ends where the server says the chain ended, short of its plan", async () => {
    const events = hops(14);
    const ended = {
      ...events[13],
      chainEnded: true,
      pending: [CHECKOUT_TO_PAID[14].hop],
    };
    const { transport } = fakeServices([
      ["POST", /checkout-basket$/, () => ({ events: [...events.slice(0, 13), ended] })],
    ]);
    const progress = await checkout(shopClients(ALICE, transport), "alice", () => {});
    expect(progress.results).toHaveLength(14);
    expect(progress.ended).toEqual(ended);
    expect(progress.failure).toBeUndefined();
    expect(progress.done).toBe(true);
    expect(isPaid(progress)).toBe(false);
  });

  it("is paid only when the chain reached its plan's last hop", async () => {
    const { transport } = fakeServices([
      ["POST", /checkout-basket$/, () => ({ events: hops(15) })],
    ]);
    const progress = await checkout(shopClients(ALICE, transport), "alice", () => {});
    expect(isPaid(progress)).toBe(true);
    expect(isPaid({ ...progress, done: false })).toBe(false);
    expect(isPaid({ results: [], done: true })).toBe(false);
  });

  it("rejects with the problem when the checkout is refused at admission", async () => {
    const { transport } = fakeServices([
      [
        "POST",
        /checkout-basket$/,
        () => problemAnswer(422, "Unprocessable", "eshop-basket.basket.empty"),
      ],
    ]);
    const failure = checkout(shopClients(ALICE, transport), "alice", () => {});
    await expect(failure).rejects.toBeInstanceOf(WowProblemError);
    await expect(failure).rejects.toMatchObject({
      code: "eshop-basket.basket.empty",
      status: 422,
    });
  });

  it("reads the order id from a result, or from an order's hop", () => {
    expect(orderIdOf(result({ results: { orderId: "bob-2" } }))).toBe("bob-2");
    expect(
      orderIdOf(result({ context: "eshop-ordering", aggregate: "order", id: "bob-3" })),
    ).toBe("bob-3");
    expect(orderIdOf(result())).toBeUndefined();
  });
});
