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

import { type Hop, Stage } from './wire.js';

/** The name of the built-in subscription that writes snapshots. */
export const SNAPSHOT_SUBSCRIPTION = 'snapshot';

const NAME = /^[A-Za-z0-9_.-]+$/;

function checkName(kind: string, value: string): string {
  if (!NAME.test(value)) {
    throw new TypeError(`Not a ${kind} name: '${value}'.`);
  }
  return value;
}

function hop(stage: Stage, context?: string, subscription?: string): Hop {
  return {
    stage,
    ...(context === undefined
      ? {}
      : { context: checkName('context', context) }),
    ...(subscription === undefined
      ? {}
      : { subscription: checkName('subscription', subscription) }),
  };
}

/**
 * Builds the hops of a wait chain. A context left out is
 * the command's own. A saga's `handled` hop may be followed by hops of the
 * commands it derived, in their contexts:
 *
 * @example
 * ```typescript
 * const plan = [
 *   wait.committed(),
 *   wait.handled('BasketCheckoutSaga.onBasketCheckedOut', 'eshop-basket'),
 *   wait.committed('eshop-ordering'),
 * ];
 * ```
 */
export const wait = {
  /** Until the command passed admission; the route answers 202. */
  accepted: (context?: string): Hop => hop(Stage.ACCEPTED, context),
  /** Until the command's events are appended to the log. */
  committed: (context?: string): Hop => hop(Stage.COMMITTED, context),
  /** Until the snapshot holds the commit: the caller reads its own write. */
  snapshot: (context?: string): Hop =>
    hop(Stage.HANDLED, context, SNAPSHOT_SUBSCRIPTION),
  /** Until the subscription named `processor.function` handled the commit. */
  handled: (subscription: string, context?: string): Hop =>
    hop(Stage.HANDLED, context, subscription),
} as const;

/**
 * A hop as `Wow-Wait` spells it: `accepted`, `committed`, `snapshot` and
 * `handled(subscription)`, each with its context when it has one:
 * `committed(ctx)`, `snapshot(ctx)`, `handled(ctx/subscription)`.
 *
 * @param value - The hop
 */
export function formatHop(value: Hop): string {
  const context = value.context ?? undefined;
  const subscription = value.subscription ?? undefined;
  if (value.stage === Stage.HANDLED) {
    if (subscription === undefined) {
      throw new TypeError('A handled hop names its subscription.');
    }
    if (subscription === SNAPSHOT_SUBSCRIPTION) {
      return context === undefined ? 'snapshot' : `snapshot(${context})`;
    }
    return context === undefined
      ? `handled(${subscription})`
      : `handled(${context}/${subscription})`;
  }
  if (subscription !== undefined) {
    throw new TypeError(`Only a handled hop names a subscription.`);
  }
  const keyword = value.stage.toLowerCase();
  return context === undefined ? keyword : `${keyword}(${context})`;
}

/**
 * A wait plan as the `Wow-Wait` header spells it: its hops, in order,
 * separated by `>`. A string is taken as already spelled.
 *
 * @param plan - The hops, or the header's value
 */
export function formatWaitPlan(plan: readonly Hop[] | string): string {
  if (typeof plan === 'string') return plan;
  if (plan.length === 0) {
    throw new TypeError('A wait plan has at least one hop.');
  }
  return plan.map(formatHop).join(' > ');
}

/**
 * A wait timeout as the `Wow-Wait-Timeout` header spells it: milliseconds
 * as `<n>s` when whole seconds, else `<n>ms`. A string (`10s`, `PT10S`) is
 * taken as already spelled.
 *
 * @param timeout - Milliseconds, or the header's value
 */
export function formatWaitTimeout(timeout: number | string): string {
  if (typeof timeout === 'string') return timeout;
  if (!Number.isInteger(timeout) || timeout <= 0) {
    throw new TypeError(
      `A wait timeout is a positive whole number of milliseconds: ${timeout}.`,
    );
  }
  return timeout % 1000 === 0 ? `${timeout / 1000}s` : `${timeout}ms`;
}
