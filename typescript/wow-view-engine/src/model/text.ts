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
 * Words written as keys (host-integration.md 3.1): a definition says
 * `text('executionFailed.title')` where a language would say 「失败执行」, so
 * one definition serves every language and a host's catalogue says them.
 *
 * A key travels as a string — every label slot of a definition, a system
 * view and a board is a `string`, and stays one — marked by a character no
 * wording uses (U+E000, private use). A literal string is still a label,
 * for a host of one language.
 *
 * **Transitional** (D2, user 2026-09-28): for now the engine reads every
 * definition it is given in the words of its `text` once, when it is
 * registered (`ViewEngineOptions.text`, `withText`), so one engine speaks
 * one language. H2 moves this to render time, through the messages
 * catalogue its Provider supplies, so one engine serves every language;
 * the keys a definition writes do not change.
 */

/** A key standing where a label goes; a string, so it goes anywhere one does. */
export type Text = string & { readonly __text: unique symbol };

/** How a host says a key in the language in force; `undefined` for none. */
export type TextResolver = (key: string) => string | undefined;

const MARK = '';

/** The key `key`, to stand where a label goes. */
export function text(key: string): Text {
  return `${MARK}${key}` as Text;
}

/** The key a label stands for, or `null` for a label that is words already. */
export function textKeyOf(value: string): string | null {
  return value.startsWith(MARK) ? value.slice(MARK.length) : null;
}

/**
 * `value` with every key in it said by `resolve`, and the same object where
 * it holds none. A key `resolve` has no words for is said as the key itself
 * — visible, rather than blank — and reported to `missing` with where it
 * sits, so admission can say so.
 */
export function withText<T>(
  value: T,
  resolve: TextResolver,
  missing: (key: string, path: readonly (string | number)[]) => void = () => {},
): T {
  const walk = (node: unknown, path: (string | number)[]): unknown => {
    if (typeof node === 'string') {
      const key = textKeyOf(node);
      if (key === null) return node;
      const said = resolve(key);
      if (said !== undefined) return said;
      missing(key, path);
      return key;
    }
    if (Array.isArray(node)) {
      let changed = false;
      const next = node.map((entry, index) => {
        const read = walk(entry, [...path, index]);
        if (read !== entry) changed = true;
        return read;
      });
      return changed ? next : node;
    }
    if (node !== null && typeof node === 'object') {
      let changed = false;
      const next: Record<string, unknown> = {};
      for (const [key, entry] of Object.entries(node)) {
        const read = walk(entry, [...path, key]);
        if (read !== entry) changed = true;
        next[key] = read;
      }
      return changed ? next : node;
    }
    return node;
  };
  return walk(value, []) as T;
}
