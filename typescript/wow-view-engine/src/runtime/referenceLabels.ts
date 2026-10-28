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
  /** A read that failed, for the host to hear of; never a caller's abort. */
  failed(remote: string, error: unknown): void;
}

/**
 * An engine's cache: its host's sources (an engine with no `resolveOptions`
 * throws for each, which reads as no source), its batch, and a failed read
 * told to `onError` as a `labels` query.
 */
export function engineLabels(engine: {
  resolveOptions(remote: string): OptionSource;
  readonly limits: RuntimeLimits;
  readonly environment: RuntimeEnvironment;
}): ReferenceLabels {
  return new ReferenceLabels({
    resolveOptions: remote => engine.resolveOptions(remote),
    batch: engine.limits.referenceBatch,
    failed: (remote, error) =>
      reportError(engine.environment, {
        kind: 'query',
        error,
        context: { operation: 'labels', remote },
      }),
  });
}

/** One `resolve` in flight, and how many callers still wait on it. */
interface Asked {
  readonly remote: string;
  readonly keys: readonly string[];
  readonly settled: Promise<void>;
  readonly controller: AbortController;
  waiters: number;
  /** Landed or failed: nothing left to call off. */
  done: boolean;
}

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
 */
export class ReferenceLabels implements ReferenceLabelReader {
  /** By remote, then id: the option, or `null` for one the source left out. */
  private readonly known = new Map<string, Map<string, FieldOption | null>>();
  private readonly asked = new Map<string, Map<string, Asked>>();
  private readonly sources = new Map<string, OptionSource | null>();
  private readonly listeners = new Set<() => void>();
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
    options: SourceOptions = {},
  ): Promise<void> {
    const { signal } = options;
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
    for (let start = 0; start < fresh.length; start += this.host.batch)
      waits.add(
        this.ask(remote, source, fresh.slice(start, start + this.host.batch)),
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

  private ask(remote: string, source: OptionSource, ids: ReferenceId[]): Asked {
    const controller = new AbortController();
    const inFlight = this.inFlight(remote);
    const keys = ids.map(keyOf);
    // A source that throws rather than rejects fails the same way.
    const settled = new Promise<FieldOption[]>(resolve =>
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
        asked.done = true;
        if (controller.signal.aborted) return;
        this.letGo(asked);
        this.changed();
      });
    const asked: Asked = {
      remote,
      keys,
      settled,
      controller,
      waiters: 0,
      done: false,
    };
    for (const key of keys) inFlight.set(key, asked);
    this.changed();
    return asked;
  }

  /**
   * Nobody waits on `asked` any more: it is called off, and its ids are let
   * go at once, so a caller arriving meanwhile asks afresh rather than
   * joining a request that will answer nothing.
   */
  private callOff(asked: Asked, reason: unknown): void {
    asked.controller.abort(reason);
    this.letGo(asked);
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
