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

import {
  CommandResultStatus,
  formatHop,
  type CommandResult,
  type Hop,
  WaitPlan,
} from "@ahoo-wang/wow-client";
import { eshopBasketWait } from "@/generated/basket/eshop-basket";
import { eshopInventoryWait } from "@/generated/inventory/eshop-inventory";
import { eshopOrderingWait } from "@/generated/ordering/eshop-ordering";
import { eshopPaymentWait } from "@/generated/payment/eshop-payment";
import type { ShopClients } from "./clients";

/** One hop of the checkout's wait, with what it means to the buyer. */
export interface CheckoutStep {
  readonly hop: Hop;
  readonly label: string;
  /**
   * The functions whose results reach this step, where the plan has the
   * same hop more than once (`committed(eshop-ordering)` is four steps): the
   * commands committed there. A step whose hop is unique leaves it out.
   */
  readonly functions?: readonly string[];
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
  {
    hop: eshopOrderingWait.committed(),
    label: "Order submitted",
    functions: ["SubmitOrder"],
  },
  {
    hop: eshopOrderingWait.handled("OrderProcessSaga.onOrderSubmitted"),
    label: "Order process starts the stock validation",
  },
  {
    hop: eshopOrderingWait.committed(),
    label: "Order awaits its stock",
    functions: ["AwaitStockValidation"],
  },
  {
    hop: eshopOrderingWait.handled("OrderProcessSaga.onOrderAwaitingValidation"),
    label: "Order process reserves the stock",
  },
  { hop: eshopInventoryWait.committed(), label: "Stock reserved" },
  {
    hop: eshopOrderingWait.handled("OrderProcessSaga.onReserveCommitted"),
    label: "Order process confirms the reservation",
  },
  {
    hop: eshopOrderingWait.committed(),
    label: "Order stock confirmed",
    functions: ["ConfirmOrderStock"],
  },
  {
    hop: eshopPaymentWait.handled("OrderPaymentSaga.onOrderStockConfirmed"),
    label: "Payment saga requests the payment",
  },
  {
    hop: eshopPaymentWait.committed(),
    label: "Payment requested",
    functions: ["RequestPayment"],
  },
  {
    hop: eshopPaymentWait.handled("PaymentGatewaySaga.onPaymentRequested"),
    label: "Gateway charges the payment",
  },
  {
    hop: eshopPaymentWait.committed(),
    label: "Gateway answered",
    functions: ["ConfirmPayment", "FailPayment"],
  },
  {
    hop: eshopPaymentWait.handled("OrderPaymentSaga.onPaymentSucceeded"),
    label: "Payment saga handles the gateway's answer",
  },
  {
    hop: eshopOrderingWait.committed(),
    label: "Order paid",
    functions: ["PayOrder"],
  },
];

/** How long the checkout waits for the whole chain, in milliseconds. */
export const CHECKOUT_TIMEOUT = 30_000;

/** The checkout's progress: the results of the hops reached so far. */
export interface CheckoutProgress {
  readonly results: readonly CommandResult[];
  /**
   * The order the checkout placed, its number (`SO-261031-42`), once a hop of
   * the ordering names it: the order's commit, `committed(eshop-ordering)`.
   */
  readonly orderId?: string;
  /**
   * Set when a hop ended the chain without success: rejected, failed, or with
   * a status this client does not know, which it cannot read as success.
   */
  readonly failure?: CommandResult;
  /**
   * Set when the chain ended before its plan did (decision D212): the last
   * result, at the hop that sent nothing further (a declined payment's
   * `onPaymentSucceeded`), naming the hops that will not come as `pending`.
   */
  readonly ended?: CommandResult;
  readonly done: boolean;
}

/** Whether `result` is of `hop`: the same stage, context and subscription. */
function isOf(result: CommandResult, hop: Hop): boolean {
  return (
    result.hop.stage === hop.stage &&
    result.hop.context === hop.context &&
    (result.hop.subscription ?? null) === (hop.subscription ?? null)
  );
}

/**
 * The results of each step of {@link CHECKOUT_TO_PAID}, in the order they
 * arrived: by each result's hop and, where the plan has that hop more than
 * once, by its function, never by its position in the stream. A step the
 * chain reached once per product (a basket of two products reserves two
 * stocks, and confirms each) holds a result per product, so the stream has
 * more results than the plan has hops. A result of a function no step of its
 * hop names goes to the first of them that has none yet; a result of a hop
 * the plan does not have, to none.
 */
export function resultsByStep(
  results: readonly CommandResult[],
): CommandResult[][] {
  const steps: CommandResult[][] = CHECKOUT_TO_PAID.map(() => []);
  for (const result of results) {
    const candidates = CHECKOUT_TO_PAID.flatMap((step, index) =>
      isOf(result, step.hop) ? [index] : [],
    );
    const index =
      candidates.length === 1
        ? candidates[0]
        : (candidates.find((candidate) =>
            CHECKOUT_TO_PAID[candidate].functions?.includes(result.function.name),
          ) ??
          candidates.find((candidate) => steps[candidate].length === 0) ??
          candidates.at(-1));
    if (index !== undefined) steps[index].push(result);
  }
  return steps;
}

/** How many steps of the plan the results reached. */
export function stepsReached(progress: CheckoutProgress): number {
  return resultsByStep(progress.results).filter((step) => step.length > 0)
    .length;
}

/**
 * Whether the checkout paid its order: the chain reached the last hop of its
 * plan, as the server says by ending neither with a failure nor early.
 */
export function isPaid(progress: CheckoutProgress): boolean {
  return (
    progress.done &&
    !progress.failure &&
    !progress.ended &&
    (resultsByStep(progress.results).at(-1)?.length ?? 0) > 0
  );
}

/**
 * The order a checkout's result names: the order a hop of the ordering acted
 * on. Its id is its number, which the ordering gave it (decision D373): the
 * basket's own hops name no order.
 */
export function orderIdOf(result: CommandResult): string | undefined {
  const { type, id } = result.ref;
  return type.context === "eshop-ordering" && type.name === "order"
    ? id
    : undefined;
}

// region checkout
/**
 * Checks the buyer's own basket out, waiting for the whole chain as server-sent events:
 * `onProgress` gets each hop's result as it arrives. An admission refusal
 * (an empty basket, say) rejects with its problem (a `WowProblemError`); a hop that does
 * not succeed ends the progress with its result as the `failure`, and a chain
 * that ends before its plan does with its last result as `ended`.
 */
export async function checkout(
  clients: ShopClients,
  onProgress: (progress: CheckoutProgress) => void,
): Promise<CheckoutProgress> {
  const stream = await clients.basket.basket.commands.checkoutBasketOwnStream(
    {},
    {
      wait: WaitPlan.of(...CHECKOUT_TO_PAID.map((step) => step.hop)),
      timeout: CHECKOUT_TIMEOUT,
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
      failure:
        value.status === CommandResultStatus.SUCCEEDED ? undefined : value,
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
// endregion

/** A hop as the `Wow-Wait` header writes it. */
export function hopText(hop: Hop): string {
  return formatHop(hop);
}
