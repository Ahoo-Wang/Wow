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

import { Stage } from './wire.js';

/** The name of the built-in subscription that writes snapshots. */
export const SNAPSHOT_SUBSCRIPTION = 'wow.snapshot';

/**
 * The name a `queryable` hop gives the snapshot subscription of the store an
 * aggregate type's snapshot queries read: its copy's where it has one, else
 * its own store's, as `snapshot` is.
 */
export const QUERYABLE_SUBSCRIPTION = 'wow.queryable';

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
 * One hop of a wait chain: a stage, in a context (the
 * command's own when absent), of a subscription when the stage is
 * `handled`. The snapshot is the subscription named `wow.snapshot`
 * (`SNAPSHOT_SUBSCRIPTION`), which a `Wow-Wait` header writes `snapshot`;
 * the store the snapshot queries read is `wow.queryable`
 * (`QUERYABLE_SUBSCRIPTION`), written `queryable`.
 */
export interface Hop {
  /**
   * The stage; a server newer than this client may answer one it does not
   * list.
   */
  readonly stage: Stage | (string & {});
  readonly context?: string | null;
  readonly subscription?: string | null;
}

/**
 * Builds the hops of a wait chain, as Kotlin's `Hop.committed()` does. A
 * context left out is the command's own. A saga's `handled` hop may be
 * followed by hops of the commands it derived, in their contexts:
 *
 * @example
 * ```typescript
 * const plan = [
 *   Hop.committed(),
 *   Hop.handled('BasketCheckoutSaga.onBasketCheckedOut', 'eshop-basket'),
 *   Hop.committed('eshop-ordering'),
 * ];
 * ```
 */
export const Hop = {
  /** Until the command passed admission; the route answers 202. */
  accepted: (context?: string): Hop => hop(Stage.ACCEPTED, context),
  /** Until the command's events are appended to the log. */
  committed: (context?: string): Hop => hop(Stage.COMMITTED, context),
  /** Until the snapshot holds the commit: the caller reads its own write. */
  snapshot: (context?: string): Hop =>
    hop(Stage.HANDLED, context, SNAPSHOT_SUBSCRIPTION),
  /**
   * Until the store the aggregate type's snapshot queries read holds the
   * commit: a list or a count the caller runs next sees it. The same as
   * `snapshot` for a type whose queries read its own store.
   */
  queryable: (context?: string): Hop =>
    hop(Stage.HANDLED, context, QUERYABLE_SUBSCRIPTION),
  /** Until the subscription named `processor.function` handled the commit. */
  handled: (subscription: string, context?: string): Hop =>
    hop(Stage.HANDLED, context, subscription),
} as const;

/**
 * A hop as `Wow-Wait` spells it: `accepted`, `committed`, `snapshot`,
 * `queryable` and `handled(subscription)`, each with its context when it has
 * one: `committed(ctx)`, `snapshot(ctx)`, `queryable(ctx)`,
 * `handled(ctx/subscription)`.
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
    if (subscription === QUERYABLE_SUBSCRIPTION) {
      return context === undefined ? 'queryable' : `queryable(${context})`;
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
 * {@link Hop} builders make, {@link WaitPlan.within} another timeout.
 *
 * @example
 * ```typescript
 * const plan = WaitPlan.of(
 *   Hop.committed(),
 *   Hop.handled('BasketCheckoutSaga.onBasketCheckedOut', 'eshop-basket'),
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
  static readonly DEFAULT = new WaitPlan([Hop.snapshot()], undefined, true);

  /**
   * Until the snapshot holds the commit, asked for explicitly: rejected at
   * admission for an aggregate without a snapshot subscription.
   */
  static readonly SNAPSHOT = new WaitPlan([Hop.snapshot()]);

  /** Until the events are appended. */
  static readonly COMMITTED = new WaitPlan([Hop.committed()]);

  /** Until the command passed admission. */
  static readonly ACCEPTED = new WaitPlan([Hop.accepted()]);

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

/** The longest plan {@link parseWaitPlan} reads, as the service: 32 hops of 128 characters. */
const MAX_PLAN_LENGTH = 4096;

/** The most hops {@link parseWaitPlan} reads, as the service reads. */
const MAX_PLAN_HOPS = 32;

/**
 * The plan a `Wow-Wait` value spells, as the service reads it: hops
 * separated by `>`, each `accepted`, `committed`, `snapshot`, `queryable` or
 * `handled(subscription)`, with its context in parentheses
 * (`committed(ctx)`, `handled(ctx/subscription)`); the inverse of
 * {@link formatWaitPlan}. Throws a `TypeError` for anything else, and, as the
 * service refuses them before reading any hop, for a value longer than 4,096
 * characters or of more than 32 hops. A hop is read in one pass, in time
 * linear in its length.
 *
 * @param value - The header's value
 */
export function parseWaitPlan(value: string): WaitPlan {
  if (value.length > MAX_PLAN_LENGTH) {
    throw new TypeError(
      `A wait plan is at most ${MAX_PLAN_LENGTH} characters; this one has ${value.length}.`,
    );
  }
  if (value.trim() === '') {
    throw new TypeError('A wait plan has at least one hop.');
  }
  const parts = value.split('>');
  if (parts.length > MAX_PLAN_HOPS) {
    throw new TypeError(
      `A wait plan has at most ${MAX_PLAN_HOPS} hops; this one has ${parts.length}.`,
    );
  }
  return WaitPlan.of(...parts.map(part => parseHop(part.trim())));
}

/** Whether `char` is white space as a hop ignores it, as the service does: a space, a tab, LF, VT, FF or CR. */
function isSpace(char: string): boolean {
  return char === ' ' || (char >= '\t' && char <= '\r');
}

/** `value` without the white space after it ({@link isSpace}), in one pass. */
function trimSpaceEnd(value: string): string {
  let end = value.length;
  while (end > 0 && isSpace(value[end - 1])) end--;
  return value.slice(0, end);
}

/** `value` without the white space around it ({@link isSpace}), in one pass. */
function trimSpace(value: string): string {
  const kept = trimSpaceEnd(value);
  let start = 0;
  while (start < kept.length && isSpace(kept[start])) start++;
  return kept.slice(start);
}

/**
 * The hop one part of a `Wow-Wait` value spells; see {@link parseWaitPlan}:
 * a keyword of ASCII letters, then, after optional white space, an argument
 * in parentheses that holds none, white space around it ignored.
 *
 * @param value - The part, such as `committed(ctx)`
 */
export function parseHop(value: string): Hop {
  const open = value.indexOf('(');
  const keyword = trimSpaceEnd(open < 0 ? value : value.slice(0, open));
  if (keyword === '' || !/^[A-Za-z]+$/.test(keyword)) {
    throw new TypeError(`Not a hop: '${value}'.`);
  }
  const argument = open < 0 ? undefined : argumentOf(value, open);
  switch (keyword.toLowerCase()) {
    case 'accepted':
      return Hop.accepted(argument);
    case 'committed':
      return Hop.committed(argument);
    case 'snapshot':
      return Hop.snapshot(argument);
    case 'queryable':
      return Hop.queryable(argument);
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
        ? Hop.handled(parts[0])
        : Hop.handled(parts[1].trim(), parts[0].trim());
    }
    default:
      throw new TypeError(`Not a stage: '${keyword.toLowerCase()}'.`);
  }
}

/**
 * The argument of the hop `value`, whose `(` is at `open`: what lies before
 * its last character, `)`, trimmed; no other parenthesis between them.
 */
function argumentOf(value: string, open: number): string {
  const close = value.length - 1;
  if (
    close <= open ||
    value[close] !== ')' ||
    value.slice(open + 1, close).search(/[()]/) >= 0
  ) {
    throw new TypeError(`Not a hop: '${value}'.`);
  }
  return trimSpace(value.slice(open + 1, close));
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
