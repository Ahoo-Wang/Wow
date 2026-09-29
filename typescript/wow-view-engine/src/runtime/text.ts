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
 * The words an engine checks its definitions' keys against (`text(key)`,
 * host-integration.md 3.1, D2).
 *
 * The engine never says a key: the definitions, the stored configs, every
 * runtime's state and everything it hands out keep them, and the UI says
 * them where it shows them (`useSay`). What is here is the other half: the
 * words the engine was built with (`ViewEngineOptions.text`), which a
 * surface falls back on, and the words its Provider set last (`set`), which
 * `setText` checks for keys they lack. A change of words tells no runtime
 * anything — nothing the engine holds is in any language.
 */

import type { TextResolver } from '../model/index.js';

export class EngineText {
  private resolver: TextResolver | undefined;
  private knownKeys: ReadonlySet<string> | undefined;

  /**
   * `start` is the words the engine was built with, said wherever the words
   * set later lack a key; `known` the keys the definitions write (read once,
   * on first use).
   */
  constructor(
    readonly start: TextResolver | undefined,
    private readonly known: () => ReadonlySet<string>,
  ) {}

  /** Checks another language from now on. True when that changed anything. */
  set(resolver: TextResolver | undefined): boolean {
    if (resolver === this.resolver) return false;
    this.resolver = resolver;
    return true;
  }

  /** The words for `key` in force: the ones set, else the ones started with. */
  resolve(key: string): string | undefined {
    return this.resolver?.(key) ?? this.start?.(key);
  }

  /** Whether `resolver` has words for any key the definitions write. */
  saysAny(resolver: TextResolver): boolean {
    this.knownKeys ??= this.known();
    for (const key of this.knownKeys)
      if (resolver(key) !== undefined) return true;
    return false;
  }
}
