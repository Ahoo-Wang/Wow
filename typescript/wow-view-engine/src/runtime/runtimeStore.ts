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

import { dequal } from 'dequal';
import type { Issue, ViewConfig, ViewInstance } from '../model/index.js';
import type { RuntimeEnvironment } from './environment.js';
import { listenerSet } from './listeners.js';
import { rekeyed, type EngineText } from './text.js';
import { NO_REFUSAL, sameRefusal } from './scope.js';
import {
  MomentTimer,
  RefreshTimer,
  refreshDelayOf,
  refreshIntervalOf,
} from './refreshTimer.js';
import type { ViewRuntimeState } from './viewRuntimeTypes.js';

/** An `error` blocks apply and every write; a `warning` only reports. */
export function hasError(issues: readonly Issue[]): boolean {
  return issues.some(entry => entry.severity === 'error');
}

/**
 * What the store asks of the runtime holding it: the few steps that are not
 * the store's to decide.
 *
 * Each one is where the two runtimes genuinely differ. `admit` is the one
 * rule a class holds its configs to — a data view judges against its
 * definition, a dashboard against its panels and their references — and
 * `apply` is what promotion means there: one query, or N children brought
 * into line. The rest is the same both times, and therefore lives in the
 * store rather than in two copies of it.
 */
export interface RuntimeStoreHost<S extends ViewRuntimeState<ViewConfig>> {
  /** Admission of a draft as it would run, scope filter included. */
  admit(draft: S['draft']): Issue[];
  /** Promotes the draft and puts it in force; `revert` re-applies through it. */
  apply(): void;
  /** What the refresh timer fires when it comes due. */
  refresh(): void;
  /**
   * The runtime's own reason to hold the timer, beyond the three the store
   * reads for itself (an invalid draft, an editor with focus, a hidden page).
   *
   * That reason is "a request in flight", and only the runtime knows whose:
   * a data view's is its own, a dashboard's is any panel's. A data view
   * inside a dashboard answers true for good — the board times every panel,
   * so a panel runs no clock of its own.
   */
  holding(): boolean;
  /**
   * The interval the timer keeps, where it is not `applied`'s: a
   * dashboard's reader may keep one of their own for the opening, beside
   * the draft (D26 Q35). Left out, `applied`'s.
   */
  interval?(): number | null;
  /** Whatever the runtime holds besides the store, let go of on `dispose`. */
  release(): void;
  /**
   * A draft that has just replaced the one on screen without an edit having
   * made it — `revert`. A dashboard starts loading the references the
   * restored panels name; a data view has nothing to do here, which is the
   * one line the two `revert`s used to differ by.
   */
  restored?(draft: S['draft']): void;
  /**
   * Whether these issues stop the runtime from applying, and so hold its
   * timer. Any error does, for a data view — its config is one query. A
   * dashboard answers only for errors about the board itself: a panel's own
   * error stops that panel, and the board keeps applying and refreshing the
   * rest (`blocksBoard`).
   */
  blocking?(issues: readonly Issue[]): boolean;
  /**
   * The moment what is on screen stops being true of its own accord, on the
   * environment's clock — a metric card's headline, once the period it
   * skipped as under way has ended (`periodRollover`) — or `null`. The store
   * refreshes then, whatever the auto-refresh cadence, and held for the
   * same reasons it is: a hidden page asks once it is shown again.
   */
  expiresAt?(): number | null;
}

/**
 * How long past the moment a period ends the question is asked again: the
 * source has had a second to close the bucket that just ended.
 */
export const ROLLOVER_GRACE_MS = 1000;

export interface RuntimeStoreOptions<
  S extends ViewRuntimeState<ViewConfig>,
> extends RuntimeStoreHost<S> {
  /** The state the view opens on, already judged by the caller's `admit`. */
  state: S;
  environment: RuntimeEnvironment;
  /**
   * What the scope this view opened under was refused for, or empty while it
   * is in force. It is settled before the store exists — the config and the
   * injected condition are admitted together (D17-5) — so it comes in rather
   * than being asked for.
   */
  refusedScope?: Issue[];
  /**
   * The words the engine says its definitions' keys in (`EngineText`): the
   * snapshot is handed out read in them, and read again when they change.
   * Left out, as it is.
   */
  text?: EngineText;
}

/**
 * The store half of a runtime: the state it holds, who is watching it, the
 * refresh timer's bookkeeping, and whether the draft has moved away from what
 * was saved.
 *
 * A `ViewRuntime` is two things at once. One is a kind of view — a record
 * page with paging and a selection, or a board of panels — and that part is
 * the runtime class's own. The other is a `subscribe` / `getSnapshot` store,
 * and that part was the same code twice over, down to the comments: commit
 * before notifying, keep the previous refusal while it says the same thing,
 * write the due time where the timer is armed and clear it where it stops.
 * Those rules are here now, once, and the two runtimes hold one of these
 * each.
 *
 * It is generic in the whole snapshot rather than in the config, because a
 * dashboard's snapshot is a `ViewRuntimeState` with the panels added: the
 * store patches and hands out whatever its holder declared, without knowing
 * what else is in it.
 */
export class RuntimeStore<S extends ViewRuntimeState<ViewConfig>> {
  private readonly listeners = listenerSet();
  private readonly environment: RuntimeEnvironment;
  private readonly host: RuntimeStoreHost<S>;
  private readonly timer: RefreshTimer;
  private readonly rollover: MomentTimer;
  private readonly unwatchVisibility: () => void;

  private current: S;
  private readonly text: EngineText | undefined;
  /** The last snapshot handed out, of which state, in which words. */
  private read!: S;
  private readOf: S | undefined;
  /** Each draft handed out, said, to the draft it was said from. */
  private readonly handed = new WeakMap<object, S['draft']>();
  private readAt = -1;
  private readonly unwatchText: (() => void) | undefined;
  private refusal: Issue[];
  private stopped = false;

  constructor(options: RuntimeStoreOptions<S>) {
    this.current = options.state;
    this.environment = options.environment;
    this.host = options;
    this.refusal = options.refusedScope ?? NO_REFUSAL;
    this.timer = new RefreshTimer(options.environment, () =>
      this.host.refresh(),
    );
    this.rollover = new MomentTimer(options.environment, () =>
      this.host.refresh(),
    );
    this.unwatchVisibility = options.environment.visibility.subscribe(() =>
      this.retime(),
    );
    this.text = options.text;
    // Another language is the same state in other words: told as a change.
    this.unwatchText = options.text?.subscribe(() => this.notify());
  }

  /** The snapshot as it stands, for the runtime's own commands to read. */
  get state(): S {
    return this.current;
  }

  /**
   * See `ViewRuntime.getSnapshot`; the runtime hands this one out, in the
   * words in force (`EngineText`). Its own commands read `state`, which
   * keeps the keys.
   */
  getSnapshot(): S {
    const text = this.text;
    if (!text) return this.current;
    if (this.readOf !== this.current || this.readAt !== text.version) {
      const said = text.say(this.current);
      // A stored view's title is its reader's words, markers and all; a
      // system view's, or one nobody saved yet — a board's own panel, a
      // view made from a definition — is its definition's.
      const saved = this.current.saved;
      const own = saved !== null && saved.scope !== 'system';
      this.read =
        own && said.title !== this.current.title
          ? { ...said, title: this.current.title }
          : said;
      this.readOf = this.current;
      this.readAt = text.version;
      // What was handed out, for a save of it to find its keys again.
      this.handed.set(this.read.draft, this.current.draft);
    }
    return this.read;
  }

  /** `value` in the words in force (`EngineText`), for a reading the runtime derives. */
  say<T>(value: T): T {
    return this.text ? this.text.say(value) : value;
  }

  /**
   * The members a new baseline moves: `stored`, with the keys found again
   * where it holds what the reader saw of them (`keyed`). `sent` is the
   * draft the save sent, as it was handed out then: a save lands after the
   * draft has moved on, or after the words have changed, and is keyed
   * against what it sent, not against what is on screen when it lands.
   */
  baseline(stored: ViewInstance, sent?: ViewConfig): Partial<S> {
    const saved = this.keyed(stored, sent);
    const dirty = this.isDirty(this.current.draft, saved);
    // Members every snapshot has; see `revert`.
    return {
      saved,
      title: saved.title,
      scope: saved.scope,
      dirty,
    } as Partial<S>;
  }

  /**
   * `saved` with its config's words put back as the keys they were said
   * from (`rekeyed`), so dirty and revert hold in every language after:
   * keys never reach the store, but the baseline keeps them. Against the
   * draft that was sent where it is known, else the draft on hand in the
   * words in force.
   */
  keyed(saved: ViewInstance, sent?: ViewConfig): ViewInstance {
    if (!this.text) return saved;
    const raw = sent && this.handed.get(sent);
    const config =
      sent && raw
        ? rekeyed(saved.config, sent, raw)
        : rekeyed(
            saved.config,
            this.text.say(this.current.draft),
            this.current.draft,
          );
    return config === saved.config ? saved : { ...saved, config };
  }

  /** True once disposed: every command of the runtime is a no-op from then on. */
  get disposed(): boolean {
    return this.stopped;
  }

  /** See `ViewRuntime.refusedScope`; written by `refuse`, read through the runtime. */
  get refusedScope(): Issue[] {
    return this.refusal;
  }

  subscribe(listener: () => void): () => void {
    return this.listeners.subscribe(listener);
  }

  setState(patch: Partial<S>): void {
    this.current = { ...this.current, ...patch };
    this.syncTimer();
    // Commit first, notify second: a listener always reads the new snapshot.
    this.notify();
  }

  notify(): void {
    this.listeners.emit();
  }

  /**
   * Whether the draft says something other than what was saved.
   *
   * A view that was never saved has nothing to compare against, and closing
   * it would lose everything, so it counts as dirty from the start.
   * A baseline saved from a draft that holds keys keeps them (`keyed`), so
   * the two are compared as they are, in no language.
   */
  isDirty(draft: S['draft'], saved: ViewInstance | null): boolean {
    return saved === null || !dequal(draft, saved.config);
  }

  /**
   * Records what the scope on hand was refused for, and tells the subscribers
   * when that answer changed.
   *
   * A refusal changes nothing else — no state, no query — so without this a
   * screen showing it would have to be told by whoever made the injection,
   * which is how a refusal on open came to say something else entirely. The
   * previous answer is kept while it says the same thing, so a host that
   * builds its condition in render is not re-rendered forever.
   */
  refuse(refusal: Issue[]): Issue[] {
    if (sameRefusal(this.refusal, refusal)) return this.refusal;
    this.refusal = refusal;
    this.notify();
    return this.refusal;
  }

  /**
   * Discards the edits and re-runs what was saved.
   *
   * It re-applies rather than only restoring the draft, because the results
   * on screen may already answer a question the user has just taken back —
   * leaving them there would show the reverted config's rows under the saved
   * config's name. A draft the store's own config cannot pass admission for
   * is restored all the same and left for the user to fix, since refusing
   * would strand them on edits they asked to be rid of. A view that was never
   * saved has no baseline to return to, so it is a no-op there.
   */
  revert(): void {
    const saved = this.current.saved;
    if (this.stopped || saved === null) return;
    const draft = saved.config as S['draft'];
    const issues = this.host.admit(draft);
    const ran = this.current.applied;
    // The three members every snapshot has; `Partial<S>` cannot be proven of
    // a literal while `S` is a parameter, and `ViewRuntimeState` declares them.
    this.setState({
      draft,
      issues,
      dirty: this.isDirty(draft, saved),
    } as Partial<S>);
    this.host.restored?.(draft);
    if (!dequal(ran, draft) && !this.blocking(issues)) this.host.apply();
  }

  /**
   * Re-syncs the timer for something that is not a state change of the
   * runtime's own — the page being hidden or shown, a panel's query starting
   * or landing — and notifies only if the due time moved.
   *
   * Visibility does not change `draft`, `applied` or the query, so there
   * would be nothing to tell a subscriber about without this, and a countdown
   * on screen would go on counting to a timer that is no longer armed. It is
   * at most twice a round however many panels there are, which is what keeps
   * a grid from re-rendering on every panel request.
   */
  retime(): void {
    const before = this.current;
    this.syncTimer();
    if (this.current === before) return;
    this.notify();
  }

  dispose(): void {
    if (this.stopped) return;
    this.stopped = true;
    this.stopTimer();
    this.unwatchVisibility();
    this.unwatchText?.();
    this.host.release();
    // The last notification: a subscriber that reads `disposed` sees it now
    // rather than on some later render it happens to get.
    this.notify();
    this.listeners.clear();
  }

  /**
   * Auto-refresh: the four reasons to hold the timer are read here — three of
   * them off the snapshot and the environment, the fourth asked of the runtime
   * (`RuntimeStoreHost.holding`) — and arming is `RefreshTimer`'s. The due
   * time is written into the snapshot without notifying: every caller is
   * either `setState`, which notifies after it, or `retime`, which notifies
   * for it.
   */
  private syncTimer(): void {
    const held =
      this.stopped ||
      this.current.editing ||
      this.blocking(this.current.issues) ||
      !this.environment.visibility.isVisible() ||
      this.host.holding();
    const interval = this.host.interval
      ? this.host.interval()
      : refreshIntervalOf(this.current.applied);
    this.setDueAt(this.timer.sync(refreshDelayOf(interval, held)));
    // The period's end is not a cadence the reader chose, so an editor in
    // focus does not put it off — the question asked again is the one on
    // screen — but a hidden page does, and a broken draft. A request in
    // flight holds it until it lands: its answer is the one to judge.
    const expires = this.host.expiresAt?.() ?? null;
    this.rollover.syncAt(
      this.stopped ||
        this.blocking(this.current.issues) ||
        !this.environment.visibility.isVisible() ||
        this.current.query.status === 'loading' ||
        expires === null
        ? null
        : expires + ROLLOVER_GRACE_MS,
    );
  }

  /** See `RuntimeStoreHost.blocking`; any error, unless the runtime says otherwise. */
  private blocking(issues: readonly Issue[]): boolean {
    return this.host.blocking ? this.host.blocking(issues) : hasError(issues);
  }

  private setDueAt(at: number | null): void {
    if (this.current.nextRefreshAt === at) return;
    this.current = { ...this.current, nextRefreshAt: at };
  }

  private stopTimer(): void {
    this.timer.stop();
    this.rollover.stop();
    this.setDueAt(null);
  }
}
