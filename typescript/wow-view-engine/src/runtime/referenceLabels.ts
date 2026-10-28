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

import type { FieldOption, RuntimeLimits } from '../model/index.js';
import type { RuntimeEnvironment } from './environment.js';
import { reportError } from './failure/failures.js';
import type { OptionSource, SourceOptions } from './source.js';

/** A reference id, as a row holds it and `OptionSource.resolve` is asked. */
export type ReferenceId = string | number;

/**
 * What a reference id reads as right now. Anything but `resolved` shows the
 * id itself: `pending` while it is asked, `unresolved` when the source left
 * it out, its read failed (asked again next time) or nobody asked yet.
 */
export type ReferenceLabel =
  | { readonly status: 'resolved'; readonly option: FieldOption }
  | { readonly status: 'pending' }
  | { readonly status: 'unresolved' };

/**
 * How a view reads reference ids as names (D354): what one id reads as now,
 * and a subscription that says when that may have changed. The engine
 * resolves what its views show; a host's own renderer only reads.
 */
export interface ReferenceLabelReader {
  /** What `id` of `remote` reads as now. */
  label(remote: string, id: ReferenceId): ReferenceLabel;
  /** The text `id` reads as: its label once resolved, else the id. */
  text(remote: string, id: ReferenceId): string;
  /** Told after every change to what an id reads as. */
  subscribe(listener: () => void): () => void;
  /** Moves on every change, so a reader can tell a stale reading. */
  readonly version: number;
}

/** What `ReferenceLabels` stands on: the host's sources and its budget. */
export interface ReferenceLabelsHost {
  /** The host's `resolveOptions`; left out, every id reads as itself. */
  resolveOptions?: ((remote: string) => OptionSource) | undefined;
  /** The most ids one `resolve` is asked (`RuntimeLimits.referenceBatch`). */
  batch: number;
  /**
   * The most `resolve` requests in flight at once, across every remote
   * (`RuntimeLimits.referenceConcurrency`); the rest wait their turn.
   */
  concurrency: number;
  /**
   * How long one request may take, in milliseconds
   * (`RuntimeLimits.referenceTimeoutMs`); past it the request fails like
   * any failure. Left out, a request may take as long as its source does.
   */
  timeoutMs?: number;
  /** The clock the timeout runs on; the ambient one when left out. */
  timers?: Pick<RuntimeEnvironment, 'setTimeout' | 'clearTimeout'>;
  /** A read that failed, for the host to hear of; never a caller's abort. */
  failed(remote: string, error: unknown): void;
}

/**
 * Who a lookup is for. A view's own (`screen`: a page, a result's groups,
 * the conditions it shows, a record read whole) goes out before an
 * export's, which takes a slot only while no view's lookup is waiting.
 */
export type LabelLane = 'screen' | 'export';

/** What a `want` is asked with: its caller's signal, and its lane. */
export interface LabelWant extends SourceOptions {
  /** `screen` when left out. */
  lane?: LabelLane;
}

/** A lookup that took longer than `RuntimeLimits.referenceTimeoutMs`. */
export class ReferenceLabelTimeoutError extends Error {
  constructor(
    readonly remote: string,
    readonly timeoutMs: number,
  ) {
    super(`Reading the names of ${remote} took longer than ${timeoutMs} ms`);
    this.name = 'ReferenceLabelTimeoutError';
  }
}

/**
 * An engine's cache: its host's sources (an engine with no `resolveOptions`
 * throws for each, which reads as no source), its batch and concurrency,
 * and a failed read told to `onError` as a `labels` query.
 */
export function engineLabels(engine: {
  resolveOptions(remote: string): OptionSource;
  readonly limits: RuntimeLimits;
  readonly environment: RuntimeEnvironment;
}): ReferenceLabels {
  return new ReferenceLabels({
    resolveOptions: remote => engine.resolveOptions(remote),
    batch: engine.limits.referenceBatch,
    concurrency: engine.limits.referenceConcurrency,
    timeoutMs: engine.limits.referenceTimeoutMs,
    timers: engine.environment,
    failed: (remote, error) =>
      reportError(engine.environment, {
        kind: 'query',
        error,
        context: { operation: 'labels', remote },
      }),
  });
}

/** One `resolve`, sent or queued, and how many callers wait on it. */
interface Asked {
  readonly remote: string;
  readonly keys: readonly string[];
  readonly settled: Promise<void>;
  readonly controller: AbortController;
  lane: LabelLane;
  waiters: number;
  /** Landed, failed, timed out or let go: nothing left to call off. */
  done: boolean;
  /** Sends the request; `undefined` once it is sent or dropped. */
  begin: (() => void) | undefined;
  /** Gives back its slot, if it holds one, and settles `settled`. */
  release: () => void;
}

const AMBIENT_TIMERS: Pick<RuntimeEnvironment, 'setTimeout' | 'clearTimeout'> =
  {
    setTimeout: (callback, ms) => globalThis.setTimeout(callback, ms),
    clearTimeout: handle => {
      globalThis.clearTimeout(handle as ReturnType<typeof setTimeout>);
    },
  };

const PENDING: ReferenceLabel = { status: 'pending' };
const UNRESOLVED: ReferenceLabel = { status: 'unresolved' };

/** Ids are matched as text: a row may hold `7` where the source says `'7'`. */
const keyOf = (id: ReferenceId): string => String(id);

/**
 * The labels of reference ids, one cache for the whole engine (D354): every
 * runtime and dashboard panel reads it, so an id one view resolved is known
 * to the next, and the host invalidates it in one call.
 *
 * Callers ask in batches (`want`) — a page's ids, a result's group keys, an
 * export's rows — and read one id at a time (`label`). An id known, or being
 * asked, is never asked again: a caller with ids already in flight joins
 * that request, and the request is called off only when every caller that
 * waits on it has called off. What a source leaves out is remembered as
 * unresolved; a read that failed is forgotten, so the next `want` asks again.
 *
 * At most `concurrency` requests are out at once, whatever their remote: the
 * rest queue in the order they were asked and go out as others settle, so an
 * export of ten thousand rows never fires fifty lookups at once — and a
 * view's own lookups go ahead of an export's, so a file being written never
 * holds back the names on screen. A queued request is joined like one in
 * flight; one whose every caller has called off is dropped before it is
 * sent, and one already sent gives its slot back at once, whether or not
 * its source stops. One past `timeoutMs` fails as any read does.
 */
export class ReferenceLabels implements ReferenceLabelReader {
  /** By remote, then id: the option, or `null` for one the source left out. */
  private readonly known = new Map<string, Map<string, FieldOption | null>>();
  private readonly asked = new Map<string, Map<string, Asked>>();
  private readonly sources = new Map<string, OptionSource | null>();
  private readonly listeners = new Set<() => void>();
  /** Requests waiting for a slot, oldest first. */
  private readonly queue: Asked[] = [];
  private running = 0;
  private seq = 0;

  constructor(private readonly host: ReferenceLabelsHost) {}

  /**
   * The host's source for `remote`, asked of `resolveOptions` once; `null`
   * when there is none to ask.
   */
  sourceOf(remote: string): OptionSource | null {
    if (!this.sources.has(remote)) {
      let source: OptionSource | null = null;
      try {
        source = this.host.resolveOptions?.(remote) ?? null;
      } catch {
        // A remote the host does not serve reads its ids as themselves;
        // admission says so where a definition names it.
      }
      this.sources.set(remote, source);
    }
    return this.sources.get(remote) ?? null;
  }

  /** What `id` of `remote` reads as now. */
  label(remote: string, id: ReferenceId): ReferenceLabel {
    const key = keyOf(id);
    const option = this.known.get(remote)?.get(key);
    if (option) return { status: 'resolved', option };
    if (option === null) return UNRESOLVED;
    return this.asked.get(remote)?.has(key) ? PENDING : UNRESOLVED;
  }

  /** The text `id` reads as: its label once resolved, else the id. */
  text(remote: string, id: ReferenceId): string {
    const read = this.label(remote, id);
    return read.status === 'resolved' ? read.option.label : String(id);
  }

  /**
   * Resolves what of `ids` is not known yet, at most `batch` to a request,
   * and settles once each of them has: resolved, left out, or failed — a
   * failure reads as the id and is told to the host, never thrown. Rejects
   * only with the caller's own abort.
   */
  want(
    remote: string,
    ids: Iterable<ReferenceId>,
    options: LabelWant = {},
  ): Promise<void> {
    const { signal, lane = 'screen' } = options;
    // The caller's own abort reason, an `AbortError` unless it named one.
    if (signal?.aborted) return Promise.reject(signal.reason as Error);
    const source = this.sourceOf(remote);
    if (!source) return Promise.resolve();
    const known = this.known.get(remote);
    const inFlight = this.inFlight(remote);
    const waits = new Set<Asked>();
    const fresh: ReferenceId[] = [];
    const seen = new Set<string>();
    for (const id of ids) {
      if (id === null || id === undefined || id === '') continue;
      const key = keyOf(id);
      if (seen.has(key) || known?.has(key)) continue;
      seen.add(key);
      const joined = inFlight.get(key);
      if (joined) waits.add(joined);
      else fresh.push(id);
    }
    // A view joining an export's queued lookup takes it into its own lane.
    if (lane === 'screen')
      for (const asked of waits) if (asked.begin) asked.lane = 'screen';
    for (let start = 0; start < fresh.length; start += this.host.batch)
      waits.add(
        this.ask(
          remote,
          source,
          fresh.slice(start, start + this.host.batch),
          lane,
        ),
      );
    if (waits.size === 0) return Promise.resolve();
    for (const asked of waits) asked.waiters += 1;
    const settled = Promise.all([...waits].map(asked => asked.settled));
    if (!signal) return settled.then(() => undefined);
    return new Promise<void>((resolve, reject) => {
      const leave = () => {
        for (const asked of waits)
          if ((asked.waiters -= 1) === 0 && !asked.done)
            this.callOff(asked, signal.reason);
        reject(signal.reason as Error);
      };
      signal.addEventListener('abort', leave, { once: true });
      void settled.then(() => {
        signal.removeEventListener('abort', leave);
        resolve();
      });
    });
  }

  /**
   * Forgets what is known: of one remote, or of every one; of some ids, or
   * all of them. A request in flight still lands, and is read as current.
   */
  invalidate(remote?: string, ids?: Iterable<ReferenceId>): void {
    if (remote === undefined) this.known.clear();
    else if (ids === undefined) this.known.delete(remote);
    else {
      const known = this.known.get(remote);
      for (const id of ids) known?.delete(keyOf(id));
    }
    this.changed();
  }

  /** Told after every change to what an id reads as. */
  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  /** Moves on every change, so a reader can tell a stale reading. */
  get version(): number {
    return this.seq;
  }

  private inFlight(remote: string): Map<string, Asked> {
    let asked = this.asked.get(remote);
    if (!asked) this.asked.set(remote, (asked = new Map<string, Asked>()));
    return asked;
  }

  /** A request for `ids`, sent now if a slot is free, else queued. */
  private ask(
    remote: string,
    source: OptionSource,
    ids: ReferenceId[],
    lane: LabelLane,
  ): Asked {
    const controller = new AbortController();
    const inFlight = this.inFlight(remote);
    const keys = ids.map(keyOf);
    let finish!: () => void;
    let holds = false;
    let timer: unknown;
    const timers = this.host.timers ?? AMBIENT_TIMERS;
    const asked: Asked = {
      remote,
      keys,
      settled: new Promise<void>(resolve => (finish = resolve)),
      controller,
      lane,
      waiters: 0,
      done: false,
      begin: () => {
        asked.begin = undefined;
        this.running += 1;
        holds = true;
        const { timeoutMs } = this.host;
        if (timeoutMs !== undefined && timeoutMs > 0)
          timer = timers.setTimeout(
            () => this.timedOut(asked, timeoutMs),
            timeoutMs,
          );
        void this.resolve(asked, source, ids).then(asked.release);
      },
      // Once, whichever comes first: the answer, the last caller leaving,
      // or the timeout. A source that never answers holds no slot past it.
      release: () => {
        timers.clearTimeout(timer);
        if (holds) {
          holds = false;
          this.running -= 1;
          this.pump();
        }
        finish();
      },
    };
    for (const key of keys) inFlight.set(key, asked);
    this.queue.push(asked);
    this.pump();
    this.changed();
    return asked;
  }

  /**
   * Sends what is queued while a slot is free: a view's lookups first, in
   * the order they were asked, then an export's.
   */
  private pump(): void {
    const cap = Math.max(1, Math.floor(this.host.concurrency) || 1);
    while (this.running < cap && this.queue.length > 0) {
      const screen = this.queue.findIndex(asked => asked.lane === 'screen');
      const [next] = this.queue.splice(screen === -1 ? 0 : screen, 1);
      next?.begin?.();
    }
  }

  /**
   * `asked` ran past its time: it fails as any read does — told to the
   * host, forgotten, its ids shown as themselves — and is called off, so
   * its late answer is dropped and its slot is free at once.
   */
  private timedOut(asked: Asked, timeoutMs: number): void {
    if (asked.done) return;
    asked.done = true;
    this.host.failed(
      asked.remote,
      new ReferenceLabelTimeoutError(asked.remote, timeoutMs),
    );
    asked.controller.abort(
      new ReferenceLabelTimeoutError(asked.remote, timeoutMs),
    );
    this.letGo(asked);
    asked.release();
    this.changed();
  }

  /** Asks the source, and keeps or forgets what it answers. */
  private resolve(
    asked: Asked,
    source: OptionSource,
    ids: ReferenceId[],
  ): Promise<void> {
    const { remote, keys, controller } = asked;
    // A source that throws rather than rejects fails the same way.
    return new Promise<FieldOption[]>(resolve =>
      resolve(source.resolve(ids, { signal: controller.signal })),
    )
      .then(
        options => {
          const byKey = new Map(
            options.map(option => [keyOf(option.value), option]),
          );
          if (controller.signal.aborted) return;
          let known = this.known.get(remote);
          if (!known)
            this.known.set(
              remote,
              (known = new Map<string, FieldOption | null>()),
            );
          for (const key of keys) known.set(key, byKey.get(key) ?? null);
        },
        (error: unknown) => {
          if (!controller.signal.aborted) this.host.failed(remote, error);
        },
      )
      .finally(() => {
        if (asked.done) return;
        asked.done = true;
        if (controller.signal.aborted) return;
        this.letGo(asked);
        this.changed();
      });
  }

  /**
   * Nobody waits on `asked` any more: it is called off — dropped, if it is
   * still queued — and its ids are let go at once, so a caller arriving
   * meanwhile asks afresh rather than joining a request that will answer
   * nothing.
   */
  private callOff(asked: Asked, reason: unknown): void {
    asked.done = true;
    asked.controller.abort(reason);
    if (asked.begin) {
      asked.begin = undefined;
      this.queue.splice(this.queue.indexOf(asked), 1);
    }
    this.letGo(asked);
    asked.release();
    this.changed();
  }

  private letGo(asked: Asked): void {
    const inFlight = this.asked.get(asked.remote);
    for (const key of asked.keys)
      if (inFlight?.get(key) === asked) inFlight.delete(key);
  }

  private changed(): void {
    this.seq += 1;
    for (const listener of [...this.listeners]) listener();
  }
}
