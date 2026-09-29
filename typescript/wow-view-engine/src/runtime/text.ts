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

/**
 * The language an engine says its definitions' keys in (`text(key)`,
 * host-integration.md 3.1, D2): one engine, every language.
 *
 * The definitions, the stored configs and each runtime's own state keep
 * their keys; what the engine hands out — a runtime's snapshot and
 * definition, the definitions it lists — is read in the words of the
 * resolver in force, as it is read, and read again when the resolver
 * changes (`set`, which the Provider calls when its locale or messages
 * change): every open runtime tells its subscribers, and the UI draws the
 * same state in the new words. Nothing is rebuilt.
 *
 * A value is read once per resolver: the reading of an object is kept by
 * its identity until the language changes, so a snapshot read twice is the
 * same object, as `useSyncExternalStore` needs. A value with no key in it
 * is itself.
 */

import { sayKeys, type TextResolver } from '../model/index.js';
import { listenerSet } from './listeners.js';

/** How deep a value is read for keys, as `withText` reads one. */
const MAX_DEPTH = 64;

export class EngineText {
  private resolver: TextResolver | undefined;
  private said = new WeakMap<object, unknown>();
  private readonly listeners = listenerSet();
  private knownKeys: ReadonlySet<string> | undefined;
  /** Advanced whenever the words change; see `RuntimeStore.getSnapshot`. */
  version = 0;

  /**
   * `start` is the words the engine was built with, said wherever the words
   * set later lack a key; `known` the keys the definitions write — only
   * those are ever said (read once, on first use).
   */
  constructor(
    private readonly start: TextResolver | undefined,
    private readonly known: () => ReadonlySet<string>,
  ) {}

  /**
   * Says the keys in another language from now on, and tells every reader.
   * True when that changed anything.
   */
  set(resolver: TextResolver | undefined): boolean {
    if (resolver === this.resolver) return false;
    this.resolver = resolver;
    this.said = new WeakMap();
    this.version += 1;
    this.listeners.emit();
    return true;
  }

  /** The words for `key` in force: the ones set, else the ones started with. */
  resolve(key: string): string | undefined {
    return this.resolver?.(key) ?? this.start?.(key);
  }

  /**
   * `value` in the words in force; the same reading until they change.
   *
   * Every object inside it is read once too and kept by its identity, so a
   * new snapshot that shares its result, its definition or its config with
   * the last one reads only what changed. Only a definition's keys are said
   * — a string between the marks that no definition writes is somebody's
   * words — and a result's `rows` are data, never walked.
   */
  say<T>(value: T): T {
    this.knownKeys ??= this.known();
    if (this.knownKeys.size === 0) return value;
    return this.read(value, 0) as T;
  }

  /** Whether `resolver` has words for any key the definitions write. */
  saysAny(resolver: TextResolver): boolean {
    this.knownKeys ??= this.known();
    for (const key of this.knownKeys)
      if (resolver(key) !== undefined) return true;
    return false;
  }

  /** Told whenever the words change. */
  subscribe(listener: () => void): () => void {
    return this.listeners.subscribe(listener);
  }

  private read(node: unknown, depth: number): unknown {
    if (typeof node === 'string')
      return sayKeys(
        node,
        key => this.resolve(key),
        undefined,
        key => this.knownKeys?.has(key) ?? false,
      );
    if (node === null || typeof node !== 'object' || depth > MAX_DEPTH)
      return node;
    if (this.said.has(node)) return this.said.get(node);
    let read: unknown = node;
    if (Array.isArray(node)) {
      let changed = false;
      const next = node.map(entry => {
        const said = this.read(entry, depth + 1);
        if (said !== entry) changed = true;
        return said;
      });
      if (changed) read = next;
    } else if (node instanceof Map) {
      // The engine's definitions, by id.
      let changed = false;
      const next = new Map<unknown, unknown>();
      for (const [key, entry] of node as Map<unknown, unknown>) {
        const said = this.read(entry, depth + 1);
        if (said !== entry) changed = true;
        next.set(key, said);
      }
      if (changed) read = next;
    } else if (isPlain(node)) {
      let changed = false;
      const next: Record<string, unknown> = {};
      for (const [key, entry] of Object.entries(node)) {
        // A result's rows are what the source said: data, never keys.
        const said = key === 'rows' ? entry : this.read(entry, depth + 1);
        if (said !== entry) changed = true;
        next[key] = said;
      }
      if (changed) read = next;
    }
    this.said.set(node, read);
    return read;
  }
}

/**
 * A record's worth of data, which may hold a key; a `Date`, a `Set` or a
 * class instance is read as it is.
 */
function isPlain(node: object): boolean {
  const prototype: unknown = Object.getPrototypeOf(node);
  return prototype === Object.prototype || prototype === null;
}

/**
 * `stored` with every string that is what `said` held at the same place put
 * back as what `raw` held there: a config the reader saved in the words
 * they saw, read again as the keys those words were said from. What the
 * store changed or added is kept as it came; the same object where nothing
 * is put back.
 */
export function rekeyed<T>(stored: T, said: unknown, raw: unknown): T {
  return walkBack(stored, said, raw, 0) as T;
}

function walkBack(
  stored: unknown,
  said: unknown,
  raw: unknown,
  depth: number,
): unknown {
  if (said === raw || depth > MAX_DEPTH) return stored;
  if (typeof stored === 'string')
    return stored === said && typeof raw === 'string' ? raw : stored;
  if (Array.isArray(stored)) {
    if (!Array.isArray(said) || !Array.isArray(raw)) return stored;
    let changed = false;
    const next = stored.map((entry: unknown, at) => {
      const back = walkBack(entry, said[at], raw[at], depth + 1);
      if (back !== entry) changed = true;
      return back;
    });
    return changed ? next : stored;
  }
  if (
    stored === null ||
    typeof stored !== 'object' ||
    !isPlain(stored) ||
    said === null ||
    typeof said !== 'object' ||
    raw === null ||
    typeof raw !== 'object'
  )
    return stored;
  let changed = false;
  const next: Record<string, unknown> = {};
  for (const [key, entry] of Object.entries(stored)) {
    const back = walkBack(
      entry,
      (said as Record<string, unknown>)[key],
      (raw as Record<string, unknown>)[key],
      depth + 1,
    );
    if (back !== entry) changed = true;
    next[key] = back;
  }
  return changed ? next : stored;
}
