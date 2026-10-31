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
import {
  CommandResultStatus,
  type CommandResult,
} from "@ahoo-wang/wow-client";
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
  resultsByStep,
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
 * A step's state, from the results that reached it (one per product where
 * the chain fans out). The step of the result that stopped the chain failed
 * when its status says so; a status this client does not know is not a
 * failure it can show. Of the steps no result reached, the first is current
 * while the stream runs.
 */
function stateOf(
  results: readonly CommandResult[],
  index: number,
  current: number,
  progress: CheckoutProgress,
): StepState {
  const { failure } = progress;
  if (results.length > 0) {
    if (!failure || !results.includes(failure)) return "reached";
    return KNOWN_STATUSES.includes(failure.status) ? "failed" : "unknown";
  }
  if (progress.done) return "not-reached";
  return index === current ? "current" : "pending";
}

const ICONS = {
  reached: <CircleCheckIcon aria-hidden className="shrink-0 text-primary" />,
  failed: <CircleXIcon aria-hidden className="shrink-0 text-destructive" />,
  unknown: <CircleHelpIcon aria-hidden className="shrink-0 text-destructive" />,
  current: <Spinner aria-hidden className="shrink-0" />,
  pending: (
    <CircleDashedIcon aria-hidden className="shrink-0 text-muted-foreground" />
  ),
  "not-reached": (
    <CircleDashedIcon aria-hidden className="shrink-0 text-muted-foreground" />
  ),
};

/** The functions of a step's results, each once, with how many results it had. */
function functionsOf(results: readonly CommandResult[]): string[] {
  const counts = new Map<string, number>();
  results.forEach((result) =>
    counts.set(result.function.name, (counts.get(result.function.name) ?? 0) + 1),
  );
  return [...counts].map(([name, count]) =>
    count > 1 ? `${name} ×${count}` : name,
  );
}

/** What the checkout came to, in a buyer's words. */
function Outcome({ progress }: { progress: CheckoutProgress }) {
  const { orderId, ended, failure } = progress;
  if (!orderId) return null;
  const order = (
    <Link to={`/orders/${encodeURIComponent(orderId)}`}>{orderId}</Link>
  );
  if (isPaid(progress)) {
    return (
      <p className="text-sm" role="status">
        Paid: {order}
      </p>
    );
  }
  if (ended) {
    return (
      <div className="flex flex-col gap-1" role="status">
        <p className="text-sm">
          The payment was declined, and order {order} was cancelled.
        </p>
        <p className="text-xs break-words text-muted-foreground">
          The chain ended at {hopText(ended.hop)} without paying the order:{" "}
          {ended.function.name} sent no further command (a declined payment
          cancels the order), so{" "}
          {(ended.pending ?? []).map(hopText).join(" > ")} will not come.
        </p>
      </div>
    );
  }
  if (failure) {
    return (
      <p className="text-sm" role="status">
        Order {order} was cancelled.
      </p>
    );
  }
  return (
    <p className="text-sm" role="status">
      Order {order}
    </p>
  );
}

/**
 * The checkout's wait chain, hop by hop, as its server-sent results arrive:
 * each hop's stage, context and subscription, and the functions that reached
 * it, grouped by hop and function, never by their place in the stream.
 */
export function CheckoutSteps({ progress }: { progress: CheckoutProgress }) {
  const steps = resultsByStep(progress.results);
  const reached = steps.filter((results) => results.length > 0).length;
  const current = steps.findIndex((results) => results.length === 0);
  return (
    <Card>
      <CardHeader>
        <CardTitle>Checkout</CardTitle>
        <CardDescription>
          One <code>Wow-Wait</code> follows the order from the basket to its
          payment: {reached} of {CHECKOUT_TO_PAID.length} hops.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ol className="flex flex-col gap-2" aria-label="Checkout hops">
          {CHECKOUT_TO_PAID.map((step, index) => {
            const results = steps[index];
            const state = stateOf(results, index, current, progress);
            return (
              <li
                key={index}
                data-testid="checkout-hop"
                data-state={state}
                className="flex items-start gap-2 text-sm"
              >
                {ICONS[state]}
                {/* At phone width the hop's text and its functions stack under its label, the text full width. */}
                <div className="flex min-w-0 flex-1 flex-col gap-1 sm:flex-row sm:items-center sm:gap-2">
                  <span className="font-medium">{step.label}</span>
                  <code className="text-xs break-all text-muted-foreground sm:break-normal">
                    {hopText(step.hop)}
                  </code>
                  <div className="flex flex-wrap gap-1 sm:ml-auto sm:shrink-0 sm:justify-end">
                    {functionsOf(results).map((name) => (
                      <Badge key={name} variant="outline">
                        {name}
                      </Badge>
                    ))}
                  </div>
                </div>
              </li>
            );
          })}
        </ol>
        {progress.failure && <ProblemAlert error={progress.failure} />}
        <Outcome progress={progress} />
      </CardContent>
    </Card>
  );
}
