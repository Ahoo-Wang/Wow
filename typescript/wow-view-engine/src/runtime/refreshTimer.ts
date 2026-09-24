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

import { MAX_TIMER_DELAY_MS, type ViewConfig } from '../model/index.js';
import type { RuntimeEnvironment } from './environment.js';

/**
 * The one auto-refresh timer a runtime holds, however many components watch
 * it.
 *
 * A data view and a dashboard arm it for the same reasons and hold it for
 * the same four — an invalid draft, an editor with focus, a hidden page, a
 * request in flight (any panel's, on a dashboard) — so the arming is one
 * piece of code rather than two copies that drift. The runtime decides the
 * delay (`refreshDelayOf`) and publishes the due time it gets back; the
 * timer only keeps the handle and fires.
 *
 * `sync` is idempotent for an unchanged delay: a state change that leaves
 * the interval alone keeps the timer and its due time, which is what lets a
 * countdown on screen keep counting to the same moment through every
 * selection, sort and scroll.
 */
export class RefreshTimer {
  private handle: unknown;
  private delay: number | null = null;
  private due: number | null = null;

  constructor(
    private readonly environment: RuntimeEnvironment,
    private readonly fire: () => void,
  ) {}

  /**
   * Arms the timer for `delay`, keeps one already armed for the same delay,
   * stops it for `null`; answers the due time the runtime should publish —
   * read from the clock the timer runs on, so the countdown and the refresh
   * answer to one number.
   */
  sync(delay: number | null): number | null {
    if (delay === null) {
      this.stop();
      return null;
    }
    if (this.handle !== undefined && this.delay === delay) return this.due;
    this.stop();
    this.delay = delay;
    this.due = this.environment.now().getTime() + delay;
    this.handle = this.environment.setTimeout(() => {
      this.handle = undefined;
      this.delay = null;
      this.fire();
    }, delay);
    return this.due;
  }

  stop(): void {
    this.due = null;
    if (this.handle === undefined) return;
    this.environment.clearTimeout(this.handle);
    this.handle = undefined;
    this.delay = null;
  }
}

/**
 * A one-shot timer for a moment rather than a cadence: the end of the
 * period a metric card's headline skipped (`periodRollover`). `syncAt` keeps
 * the timer armed for an unchanged moment, however often the state around
 * it changes, and re-arms only when the moment moves; `null` stops it. A
 * moment already past fires at once — a page shown again after midnight
 * asks straight away.
 */
export class MomentTimer {
  private handle: unknown;
  private due: number | null = null;
  /**
   * The moment it last fired for. The same moment handed back — the refresh
   * it fired failed, and the answer on screen is still the old one — is not
   * fired for again: that would ask again at once, and again, for as long
   * as the source keeps failing. A new answer brings a new moment.
   */
  private spent: number | null = null;

  constructor(
    private readonly environment: RuntimeEnvironment,
    private readonly fire: () => void,
  ) {}

  syncAt(due: number | null): void {
    if (due !== null && due === this.spent) return;
    if (due === this.due && (due === null || this.handle !== undefined)) return;
    this.stop();
    if (due === null) return;
    this.due = due;
    // A host's `setTimeout` counts only so far: a moment past it is waited
    // for in steps, and fired for only once it has come.
    const delay = Math.min(
      Math.max(0, due - this.environment.now().getTime()),
      MAX_TIMER_DELAY_MS,
    );
    this.handle = this.environment.setTimeout(() => {
      this.handle = undefined;
      if (this.environment.now().getTime() < due) {
        this.due = null;
        this.syncAt(due);
        return;
      }
      this.due = null;
      this.spent = due;
      this.fire();
    }, delay);
  }

  stop(): void {
    this.due = null;
    if (this.handle === undefined) return;
    this.environment.clearTimeout(this.handle);
    this.handle = undefined;
  }
}

/**
 * The interval a config asks for, read as the untrusted thing it is. A
 * stored config with no `refresh` is admission's to report, and it is
 * reported; every state change still passes through here on the way to the
 * timer, and must not throw before the user can fix it.
 */
export function refreshIntervalOf(config: ViewConfig): number | null {
  const interval = (config.refresh as { interval?: unknown } | undefined)
    ?.interval;
  return typeof interval === 'number' && Number.isFinite(interval)
    ? interval
    : null;
}

/**
 * The delay to arm for, or `null` while the timer is held. `held` is the
 * runtime's own reading of its four reasons; the clamp keeps a long
 * interval inside what a host's `setTimeout` can count.
 */
export function refreshDelayOf(
  interval: number | null,
  held: boolean,
): number | null {
  if (held || interval === null) return null;
  return Math.min(interval * 1000, MAX_TIMER_DELAY_MS);
}
