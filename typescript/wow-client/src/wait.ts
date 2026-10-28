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
 * How far a command is waited for, and for how long: the hops, in order, and
 * a timeout in milliseconds. Kotlin's `me.ahoo.wow.api.result.WaitPlan`,
 * with its constants and constructors: {@link WaitPlan.of} the hops a
 * {@link wait} builder makes, {@link WaitPlan.within} another timeout.
 *
 * @example
 * ```typescript
 * const plan = WaitPlan.of(
 *   wait.committed(),
 *   wait.handled('BasketCheckoutSaga.onBasketCheckedOut', 'eshop-basket'),
 * ).within(30_000);
 * ```
 */
export class WaitPlan {
  /** The timeout of a wait whose caller gives none: 10 seconds. */
  static readonly DEFAULT_TIMEOUT = 10_000;

  /**
   * The plan of a caller that asked for none: until the snapshot holds the
   * commit, so the caller reads its own write; until `committed` for an
   * aggregate without a snapshot subscription. A request with it sends no
   * `Wow-Wait`, so the service picks.
   */
  static readonly DEFAULT = new WaitPlan([wait.snapshot()], undefined, true);

  /**
   * Until the snapshot holds the commit, asked for explicitly: rejected at
   * admission for an aggregate without a snapshot subscription.
   */
  static readonly SNAPSHOT = new WaitPlan([wait.snapshot()]);

  /** Until the events are appended. */
  static readonly COMMITTED = new WaitPlan([wait.committed()]);

  /** Until the command passed admission. */
  static readonly ACCEPTED = new WaitPlan([wait.accepted()]);

  /** Until `hops` are reached, in order. */
  static of(...hops: Hop[]): WaitPlan {
    return new WaitPlan(hops);
  }

  /** The hops to reach, in order; at least one. */
  readonly hops: readonly Hop[];
  /** How long to wait, in milliseconds. */
  readonly timeout: number;
  /** Whether this is {@link WaitPlan.DEFAULT}, the service's choice. */
  readonly isDefault: boolean;

  private constructor(
    hops: readonly Hop[],
    timeout: number = WaitPlan.DEFAULT_TIMEOUT,
    isDefault = false,
  ) {
    if (hops.length === 0) {
      throw new TypeError('A wait plan has at least one hop.');
    }
    checkTimeout(timeout);
    this.hops = Object.freeze([...hops]);
    this.timeout = timeout;
    this.isDefault = isDefault;
    Object.freeze(this);
  }

  /** This plan, waiting `timeout` milliseconds. */
  within(timeout: number): WaitPlan {
    return new WaitPlan(this.hops, timeout, this.isDefault);
  }
}

/**
 * A wait plan as the `Wow-Wait` header spells it: its hops, in order,
 * separated by `>`; the inverse of {@link parseWaitPlan}.
 *
 * @param plan - The plan
 */
export function formatWaitPlan(plan: WaitPlan): string {
  return plan.hops.map(formatHop).join(' > ');
}

const HOP = /^([A-Za-z]+)\s*(?:\(\s*([^()]*?)\s*\))?$/;

/**
 * The plan a `Wow-Wait` value spells, as the service reads it: hops
 * separated by `>`, each `accepted`, `committed`, `snapshot` or
 * `handled(subscription)`, with its context in parentheses
 * (`committed(ctx)`, `handled(ctx/subscription)`); the inverse of
 * {@link formatWaitPlan}. Throws a `TypeError` for anything else.
 *
 * @param value - The header's value
 */
export function parseWaitPlan(value: string): WaitPlan {
  if (value.trim() === '') {
    throw new TypeError('A wait plan has at least one hop.');
  }
  return WaitPlan.of(...value.split('>').map(part => parseHop(part.trim())));
}

/**
 * The hop one part of a `Wow-Wait` value spells; see {@link parseWaitPlan}.
 *
 * @param value - The part, such as `committed(ctx)`
 */
export function parseHop(value: string): Hop {
  const match = HOP.exec(value);
  if (match === null) throw new TypeError(`Not a hop: '${value}'.`);
  const keyword = match[1].toLowerCase();
  const argument = match[2];
  switch (keyword) {
    case 'accepted':
      return wait.accepted(argument);
    case 'committed':
      return wait.committed(argument);
    case 'snapshot':
      return wait.snapshot(argument);
    case 'handled': {
      if (argument === undefined) {
        throw new TypeError(
          `A handled hop names its subscription: '${value}'.`,
        );
      }
      const parts = argument.split('/');
      if (parts.length > 2) {
        throw new TypeError(
          `A handled hop is (subscription) or (context/subscription): '${argument}'.`,
        );
      }
      return parts.length === 1
        ? wait.handled(parts[0])
        : wait.handled(parts[1].trim(), parts[0].trim());
    }
    default:
      throw new TypeError(`Not a stage: '${keyword}'.`);
  }
}

/**
 * A wait timeout as the `Wow-Wait-Timeout` header spells it: milliseconds
 * as `<n>s` when whole seconds, else `<n>ms`.
 *
 * @param timeout - Milliseconds
 */
export function formatWaitTimeout(timeout: number): string {
  checkTimeout(timeout);
  return timeout % 1000 === 0 ? `${timeout / 1000}s` : `${timeout}ms`;
}

function checkTimeout(timeout: number): void {
  if (!Number.isInteger(timeout) || timeout <= 0) {
    throw new TypeError(
      `A wait timeout is a positive whole number of milliseconds: ${timeout}.`,
    );
  }
}
