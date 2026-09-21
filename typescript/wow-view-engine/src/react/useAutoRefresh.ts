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
  useCallback,
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
} from 'react';
import type { RuntimeLimits, ViewConfig } from '../model/index.js';
import { refreshIntervalOf, type ViewRuntime } from '../runtime/index.js';
import { useViewRuntime } from './useViewEngine.js';

/**
 * The ladder an interval control offers from, before the limits cut it and
 * the interval in force is folded in.
 *
 * Three rungs, and deliberately few. Half a minute is the shortest cadence a
 * person reads as "live" without the screen redrawing under their hands, five
 * minutes the longest before "keeps itself up to date" stops being the reason
 * anyone opened this view; a quarter of an hour and an hour were rungs nobody
 * picked and everybody had to read past. A ladder is a menu, and a menu with
 * seven answers to a question asked twice a year costs more to skim than the
 * two answers it leaves out are worth. Every rung divides into whole seconds
 * or minutes, so each has a label nobody has to decode — and a view already
 * saved at some other number keeps its own rung (see {@link useAutoRefresh}),
 * so shortening the ladder strands nobody.
 */
const REFRESH_INTERVALS = [30, 60, 300];

/** How often the countdown redraws while a timer is armed. */
const TICK_MS = 1000;

/** Stable identity for "no runtime, nothing on offer". */
const NO_INTERVALS: readonly number[] = [];

/**
 * Whether the kernel would run this many seconds: a whole number inside the
 * budget.
 *
 * `validateRefresh` refuses a fractional interval as surely as one out of
 * range (`config.refresh.not-an-integer`), so both are the same answer here.
 * An interval that fails this is one the timer will never use — offering it
 * would offer a config that cannot run, and saying it would name a cadence
 * nothing keeps.
 */
function runnable(
  seconds: number | null,
  limits: RuntimeLimits | undefined,
): seconds is number {
  return (
    seconds !== null &&
    limits !== undefined &&
    Number.isInteger(seconds) &&
    seconds >= limits.minRefreshInterval &&
    seconds <= limits.maxRefreshInterval
  );
}

/**
 * How often this view renews its own answer, and the way to run one now.
 *
 * There is no state here beside the config: the interval *is*
 * `ViewConfigBase.refresh.interval`, saved with the view and read by the
 * runtime's one timer. A control that kept its own copy would give the saved
 * config, the timer and the screen three opinions about the same number. The
 * two numbers below are one member read at its two ages — what is running
 * and what is set — not two states.
 */
export interface RefreshController {
  /**
   * The interval **in force**: seconds between automatic refreshes, or
   * `null` when the view does not refresh itself.
   *
   * It is `applied`'s, because that is the one the runtime's timer reads. A
   * credential about what is happening has to answer to what is happening —
   * the same reason `AppliedBar` describes the result rather than the draft.
   * Choosing an interval edits and applies in one gesture, so this and
   * {@link chosen} agree except while a draft the kernel refuses holds
   * `apply` back, and then it is this one that is true.
   *
   * A stored value the kernel would refuse — fractional, or outside the
   * budget — reads as `null` rather than as itself: the timer will never use
   * it (an `applied` the kernel refuses can only come from a draft it
   * refuses, which is already holding the timer), so naming it would promise
   * a cadence nothing keeps. The refusal is the strip's to report.
   */
  interval: number | null;
  /**
   * What the view is **set** to: the draft's interval, which is what a save
   * would write and what the menu marks as picked.
   *
   * It is the editor's value, like the layout or the page size, and it is
   * kept apart from {@link interval} rather than folded into it because one
   * mark saying two things is how a credential starts lying. When they
   * differ, the draft is refused and the strip above the result says so.
   */
  chosen: number | null;
  /**
   * The intervals on offer, ascending, already cut to what
   * `RuntimeLimits.minRefreshInterval` and `maxRefreshInterval` admit. An
   * interval the limits refuse is absent rather than disabled (D4), so an
   * empty list means this view has no interval to choose and a control over
   * it has nothing to open.
   */
  intervals: readonly number[];
  /**
   * Whether admission has something to say about this view's `refresh`
   * member: missing, not an object, or an interval that is not a whole
   * number of seconds in range.
   *
   * A control reads it to know it still has work to do when it has nothing
   * to offer. Turning refresh **off** writes `{ interval: null }`, which is
   * the repair for every one of those refusals, so a menu that hid itself
   * because the limits left no rung would strand the user on a config that
   * blocks Apply and Save with no control on screen able to mend it — the
   * package's own rule is that a reported error is always reachable.
   */
  unsound: boolean;
  /**
   * Writes `refresh.interval` and applies it, the way a sort or a column
   * change does: without the apply the runtime's timer, which reads
   * `applied`, would never hear about it.
   */
  setInterval(interval: number | null): void;
  /** One refresh, now. Unchanged by any of the above. */
  now(): void;
  /** True while a query of this view is in flight. */
  loading: boolean;
  /**
   * When the next automatic refresh is due, on the runtime's clock, or `null`
   * while no timer is armed — the interval is off, or the runtime is holding
   * the timer for one of its four reasons.
   *
   * It is the runtime's `nextRefreshAt`, handed on as it stands. A control
   * counts down to it; it does not count for itself, because a second clock
   * beside the timer's is a second opinion about when the data will move.
   */
  dueAt: number | null;
  /**
   * Whole seconds from now until {@link dueAt}, read against the runtime's
   * own clock, or `null` when nothing is armed. Never negative: a timer that
   * is late is at zero, not behind.
   *
   * A function rather than a number, because it answers "now", and the
   * controller is built once per render while a countdown asks once a second.
   * {@link useRefreshCountdown} is what asks.
   */
  remaining(): number | null;
}

/**
 * The auto-refresh of one open view, as a control can offer it.
 *
 * The runtime decides when the timer runs and when it pauses — an invalid
 * draft, an editor with focus, a hidden page, a request in flight — and this
 * adds no rule of its own. All it does is make the interval reachable.
 */
export function useAutoRefresh(
  runtime: ViewRuntime<ViewConfig> | null,
): RefreshController {
  const state = useViewRuntime(runtime);
  // Both read through the runtime's own reading of the member: a config
  // arrives from a store, and neither "what the timer uses" nor "what a save
  // would write" may throw on the way to the screen.
  const applied = state ? refreshIntervalOf(state.applied) : null;
  const chosen = state ? refreshIntervalOf(state.draft) : null;
  const limits = runtime?.limits;

  return {
    interval: runnable(applied, limits) ? applied : null,
    chosen,
    // Every admission finding about this member, whatever its code: missing,
    // fractional, too short, too long. Read off `issues` rather than judged
    // again here, so the control and the kernel cannot come to different
    // conclusions about the same config.
    unsound: (state?.issues ?? []).some(found => found.path[0] === 'refresh'),
    intervals: useMemo(() => {
      if (!limits) return NO_INTERVALS;
      // What is picked joins the ladder when the kernel would run it: a view
      // saved at 45 seconds has to offer the rung it is sitting on, or the
      // menu would show nothing marked. One the kernel refuses does not —
      // that config is already refused, said in the strip above the result,
      // and the way out of it is a rung that works or Off, not the number
      // that broke. The ladder answers to the draft rather than to what is
      // in force, because the ladder is what the menu marks.
      const offered = REFRESH_INTERVALS.filter(seconds =>
        runnable(seconds, limits),
      );
      if (runnable(chosen, limits)) offered.push(chosen);
      return [...new Set(offered)].sort((left, right) => left - right);
    }, [chosen, limits]),
    setInterval: useCallback(
      (next: number | null) => {
        if (!runtime) return;
        runtime.edit({ refresh: { interval: next } });
        runtime.apply();
      },
      [runtime],
    ),
    now: useCallback(() => runtime?.refresh(), [runtime]),
    loading: state?.query.status === 'loading',
    dueAt: state?.nextRefreshAt ?? null,
    // Stable per runtime, so a ticker can depend on it without restarting on
    // every render of the surface around it. It reads the snapshot at call
    // time rather than the one this render closed over, because a countdown
    // asks between renders.
    remaining: useCallback(() => {
      if (!runtime) return null;
      const due = runtime.getSnapshot().nextRefreshAt;
      if (due === null) return null;
      const left = due - runtime.environment.now().getTime();
      return Math.max(0, Math.ceil(left / 1000));
    }, [runtime]),
  };
}

/**
 * Whole seconds until the next automatic refresh, redrawn once a second.
 *
 * The ticker is a pulse, not a measurement: every reading comes from the
 * runtime's due time against the runtime's clock, so a tick that arrives
 * late, early or not at all changes when the number is redrawn and never
 * what it says. It runs only while a timer is armed — no interval, or a
 * runtime holding its timer, means no ticker — and only while the component
 * asking is mounted, which is the control that draws the count and not the
 * workbench around it: a view re-rendering its whole result once a second to
 * move one digit is the cost this hook exists to keep out of the table.
 */
export function useRefreshCountdown(refresh: RefreshController): number | null {
  const { dueAt, remaining } = refresh;
  // Read once as the store is built, so the control never paints one frame
  // of the cadence on its way to the count.
  const ticker = useState(() =>
    countdownStore(dueAt === null ? null : remaining()),
  )[0];
  useEffect(() => {
    ticker.set(dueAt === null ? null : remaining());
    if (dueAt === null) return;
    const tick = setInterval(() => ticker.set(remaining()), TICK_MS);
    return () => clearInterval(tick);
  }, [dueAt, remaining, ticker]);
  return useSyncExternalStore(ticker.subscribe, ticker.get, ticker.get);
}

/**
 * The seconds left, as a store the ticker writes and React reads.
 *
 * A reading is made by a timer, which belongs in an effect, and shown on
 * screen, which needs a render. `setState` from an effect body is the
 * cascading-render pattern `react-hooks` refuses; a store the effect writes
 * and `useSyncExternalStore` subscribes to is the shape it points at instead
 * — the same one `useOpenView` keeps its refusals in, and the one every
 * runtime in this package already has.
 */
function countdownStore(initial: number | null) {
  let seconds = initial;
  const listeners = new Set<() => void>();
  return {
    get: (): number | null => seconds,
    set(next: number | null): void {
      if (seconds === next) return;
      seconds = next;
      for (const listener of [...listeners]) listener();
    },
    subscribe(listener: () => void): () => void {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}
