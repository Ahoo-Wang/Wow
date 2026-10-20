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

import { formatHop, type CommandResult, type Hop } from "@ahoo-wang/wow-client";
import { eshopBasketWait } from "@/generated/basket/eshop-basket";
import { eshopInventoryWait } from "@/generated/inventory/eshop-inventory";
import { eshopOrderingWait } from "@/generated/ordering/eshop-ordering";
import { eshopPaymentWait } from "@/generated/payment/eshop-payment";
import type { ShopClients } from "./clients";

/** One hop of the checkout's wait, with what it means to the buyer. */
export interface CheckoutStep {
  readonly hop: Hop;
  readonly label: string;
}

/**
 * The checkout's `Wow-Wait` that follows the order to its payment (eShop
 * README, "Waiting for the order"): 15 hops, every saga on the way and the
 * commit it leads to, until the payment's `PayOrder` is committed in the
 * ordering context. The basket knows only the ordering's Manifest; the hops of
 * the inventory and the payment are checked where the chain arrives (D190).
 */
export const CHECKOUT_TO_PAID: readonly CheckoutStep[] = [
  { hop: eshopBasketWait.committed(), label: "Basket checked out" },
  {
    hop: eshopBasketWait.handled("BasketCheckoutSaga.onBasketCheckedOut"),
    label: "Checkout saga submits the order",
  },
  { hop: eshopOrderingWait.committed(), label: "Order submitted" },
  {
    hop: eshopOrderingWait.handled("OrderProcessSaga.onOrderSubmitted"),
    label: "Order process starts the stock validation",
  },
  { hop: eshopOrderingWait.committed(), label: "Order awaits its stock" },
  {
    hop: eshopOrderingWait.handled("OrderProcessSaga.onOrderAwaitingValidation"),
    label: "Order process reserves the stock",
  },
  { hop: eshopInventoryWait.committed(), label: "Stock reserved" },
  {
    hop: eshopOrderingWait.handled("OrderProcessSaga.onReserveCommitted"),
    label: "Order process confirms the reservation",
  },
  { hop: eshopOrderingWait.committed(), label: "Order stock confirmed" },
  {
    hop: eshopPaymentWait.handled("OrderPaymentSaga.onOrderStockConfirmed"),
    label: "Payment requested",
  },
  { hop: eshopPaymentWait.committed(), label: "Payment recorded" },
  {
    hop: eshopPaymentWait.handled("PaymentGatewaySaga.onPaymentRequested"),
    label: "Gateway charges the payment",
  },
  { hop: eshopPaymentWait.committed(), label: "Gateway answered" },
  {
    hop: eshopPaymentWait.handled("OrderPaymentSaga.onPaymentSucceeded"),
    label: "Payment saga pays the order",
  },
  { hop: eshopOrderingWait.committed(), label: "Order paid" },
];

/** How long the checkout waits for the whole chain. */
export const CHECKOUT_TIMEOUT = "30s";

/** The checkout's progress: the results of the hops reached so far. */
export interface CheckoutProgress {
  readonly results: readonly CommandResult[];
  /** The order the checkout placed, once a result names it. */
  readonly orderId?: string;
  /** Set when a hop ended the chain without success. */
  readonly failure?: CommandResult;
  /**
   * Set when the chain ended before its plan did (decision D212): the last
   * result, at the hop that sent nothing further (a declined payment's
   * `onPaymentSucceeded`), naming the hops that will not come as `pending`.
   */
  readonly ended?: CommandResult;
  readonly done: boolean;
}

/**
 * Whether the checkout paid its order: the chain reached the last hop of its
 * plan, as the server says by ending neither with a failure nor early.
 */
export function isPaid(progress: CheckoutProgress): boolean {
  return (
    progress.done &&
    progress.results.length > 0 &&
    !progress.failure &&
    !progress.ended
  );
}

/** The order id a checkout's result names: its `orderId` result, or the order a hop of the ordering acted on. */
export function orderIdOf(result: CommandResult): string | undefined {
  const named = (result.results as { orderId?: unknown }).orderId;
  if (typeof named === "string") return named;
  const { type, id } = result.ref;
  return type.context === "eshop-ordering" && type.name === "order"
    ? id
    : undefined;
}

/**
 * Checks `basketId` out, waiting for the whole chain as server-sent events:
 * `onProgress` gets each hop's result as it arrives. An admission refusal
 * (an empty basket, say) rejects with its `WowProblemError`; a hop that does
 * not succeed ends the progress with its result as the `failure`, and a chain
 * that ends before its plan does with its last result as `ended`.
 */
export async function checkout(
  clients: ShopClients,
  basketId: string,
  onProgress: (progress: CheckoutProgress) => void,
): Promise<CheckoutProgress> {
  const stream = await clients.baskets.checkoutBasketStream(
    basketId,
    {},
    {
      wait: {
        plan: CHECKOUT_TO_PAID.map((step) => step.hop),
        timeout: CHECKOUT_TIMEOUT,
      },
    },
  );
  const results: CommandResult[] = [];
  let progress: CheckoutProgress = { results, done: false };
  const reader = stream.getReader();
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    results.push(value);
    progress = {
      results: [...results],
      orderId: progress.orderId ?? orderIdOf(value),
      failure: value.status === "SUCCEEDED" ? undefined : value,
      ended: value.chainEnded ? value : undefined,
      done: false,
    };
    onProgress(progress);
    if (progress.failure || progress.ended) break;
  }
  progress = { ...progress, done: true };
  onProgress(progress);
  return progress;
}

/** A hop as the `Wow-Wait` header writes it. */
export function hopText(hop: Hop): string {
  return formatHop(hop);
}
