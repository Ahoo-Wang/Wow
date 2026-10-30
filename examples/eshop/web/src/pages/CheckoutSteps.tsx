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

import { Link } from "react-router";
import {
  CircleCheckIcon,
  CircleDashedIcon,
  CircleHelpIcon,
  CircleXIcon,
} from "lucide-react";
import { CommandResultStatus } from "@ahoo-wang/wow-client";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Spinner } from "@/components/ui/spinner";
import {
  CHECKOUT_TO_PAID,
  hopText,
  isPaid,
  type CheckoutProgress,
} from "@/shop/checkout";
import { ProblemAlert } from "@/shop/problems";

type StepState =
  | "reached"
  | "failed"
  | "unknown"
  | "current"
  | "pending"
  | "not-reached";

const KNOWN_STATUSES: readonly string[] = Object.values(CommandResultStatus);

/**
 * A hop's state. The hop that stopped the chain failed when its status says
 * so; a status this client does not know is not a failure it can show.
 */
function stateOf(index: number, progress: CheckoutProgress): StepState {
  const reached = progress.results.length;
  const { failure } = progress;
  if (index < reached) {
    if (!failure || index !== reached - 1) return "reached";
    return KNOWN_STATUSES.includes(failure.status) ? "failed" : "unknown";
  }
  if (progress.done) return "not-reached";
  return index === reached ? "current" : "pending";
}

const ICONS = {
  reached: <CircleCheckIcon aria-hidden className="text-primary" />,
  failed: <CircleXIcon aria-hidden className="text-destructive" />,
  unknown: <CircleHelpIcon aria-hidden className="text-destructive" />,
  current: <Spinner aria-hidden />,
  pending: <CircleDashedIcon aria-hidden className="text-muted-foreground" />,
  "not-reached": (
    <CircleDashedIcon aria-hidden className="text-muted-foreground" />
  ),
};

/**
 * The checkout's wait chain, hop by hop, as its server-sent results arrive:
 * each hop's stage, context and subscription, and the function that reached
 * it.
 */
export function CheckoutSteps({ progress }: { progress: CheckoutProgress }) {
  const paid = isPaid(progress);
  const { ended } = progress;
  return (
    <Card>
      <CardHeader>
        <CardTitle>Checkout</CardTitle>
        <CardDescription>
          One <code>Wow-Wait</code> follows the order from the basket to its
          payment: {progress.results.length} of {CHECKOUT_TO_PAID.length} hops.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ol className="flex flex-col gap-1" aria-label="Checkout hops">
          {CHECKOUT_TO_PAID.map((step, index) => {
            const state = stateOf(index, progress);
            const result = progress.results[index];
            return (
              <li
                key={index}
                data-testid="checkout-hop"
                data-state={state}
                className="flex items-center gap-2 text-sm"
              >
                {ICONS[state]}
                <span className="font-medium">{step.label}</span>
                <code className="truncate text-xs text-muted-foreground">
                  {hopText(step.hop)}
                </code>
                {result && (
                  <Badge variant="outline" className="ml-auto">
                    {result.function.name}
                  </Badge>
                )}
              </li>
            );
          })}
        </ol>
        {progress.failure && <ProblemAlert error={progress.failure} />}
        {progress.orderId && (
          <p className="text-sm" role="status">
            {paid ? "Paid: " : "Order "}
            <Link to={`/orders/${encodeURIComponent(progress.orderId)}`}>
              {progress.orderId}
            </Link>
            {ended &&
              ` — the chain ended at ${hopText(ended.hop)} without paying the order:` +
                ` ${ended.function.name} sent no further command (a declined payment cancels the order),` +
                ` so ${(ended.pending ?? []).map(hopText).join(" > ")} will not come.`}
          </p>
        )}
      </CardContent>
    </Card>
  );
}
