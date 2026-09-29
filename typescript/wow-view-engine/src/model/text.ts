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
 * view and a board is a `string`, and stays one — between two characters no
 * wording uses (U+E000 and U+E001, private use). The closing one lets a key
 * be read where code has put it inside a longer string — a condition's
 * summary, 「{field} · {element}」 — so it is said there too. A literal
 * string is still a label, for a host of one language.
 *
 * A key is said at the leaf (D2, user 2026-09-28): the definitions, the
 * stored configs, every runtime's state and snapshot, and what an editor
 * takes and gives back keep their keys; only where text is shown or leaves
 * the engine — a rendered label, a chart's option, an export, an accessible
 * name, a title — is it said (`say`, and `useSay` in `/ui`), in the words
 * of the Provider in force, else the ones the engine was built with
 * (`ViewEngineOptions.text`). So one engine serves every language, and a
 * change of language only redraws.
 */

/** A key standing where a label goes; a string, so it goes anywhere one does. */
export type Text = string & { readonly __text: unique symbol };

/** How a host says a key in the language in force; `undefined` for none. */
export type TextResolver = (key: string) => string | undefined;

/** The words keys are said in: a resolver, or a catalogue by key. */
export type TextWords =
  TextResolver | Readonly<Record<string, string | undefined>>;

const MARK = '\uE000';
const END = '\uE001';
/** Every key inside a string, wherever it sits. */
const KEYS = /\uE000([^\uE000\uE001]*)\uE001/g;

/** How deep a value is read for keys; see `withText`. */
const MAX_DEPTH = 64;

/** The key `key`, to stand where a label goes. */
export function text(key: string): Text {
  return `${MARK}${key}${END}` as Text;
}

/** The key a label stands for, or `null` for a label that is words already. */
export function textKeyOf(value: string): string | null {
  const key = value.slice(MARK.length, -END.length);
  return value === `${MARK}${key}${END}` && !/[\uE000\uE001]/.test(key)
    ? key
    : null;
}

/**
 * `value` with every key in it said by `resolve` — the whole string, or
 * keys code has put inside a longer one. A key `resolve` has no words for
 * is said as the key itself, and reported to `missing`. With `known`, only
 * the keys it knows are keys: anything else between the marks is left as
 * it came (a reader's title, a row's cell).
 */
export function sayKeys(
  value: string,
  resolve: TextResolver,
  missing: (key: string) => void = () => {},
  known?: (key: string) => boolean,
): string {
  if (!value.includes(MARK)) return value;
  return value.replace(KEYS, (whole, key: string) => {
    if (known && !known(key)) return whole;
    const said = resolve(key);
    if (said !== undefined) return said;
    missing(key);
    return key;
  });
}

/**
 * `value` with every key in it said in `words` (D2): what a label reads as
 * where it is shown or leaves the engine. A key `words` lack reads as the
 * key itself — visible, never a marker. The same string where it holds no
 * key.
 */
export function say(value: string, words: TextWords): string {
  // Only a string holds a key; anything else a caller let through is shown
  // as it came, never thrown over.
  if (typeof value !== 'string' || !value.includes(MARK)) return value;
  const resolve: TextResolver =
    typeof words === 'function'
      ? words
      : key =>
          Object.prototype.hasOwnProperty.call(words, key)
            ? words[key]
            : undefined;
  return sayKeys(value, resolve);
}

/** Every key written anywhere in `value`: what a definition says in words. */
export function textKeysIn(value: unknown): Set<string> {
  const keys = new Set<string>();
  withText(value, key => {
    keys.add(key);
    return undefined;
  });
  return keys;
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
    // A label sits a few levels in; a tree deeper than any budget admits
    // (one refused for its depth) is left as it is rather than walked.
    if (path.length > MAX_DEPTH) return node;
    if (typeof node === 'string')
      return sayKeys(node, resolve, key => missing(key, path));
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
