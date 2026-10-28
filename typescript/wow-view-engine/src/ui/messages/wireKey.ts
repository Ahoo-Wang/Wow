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
 * The key of a word for a Wow wire value: `prefix.VALUE`, typed as that
 * literal so the catalogue's `MessageKey` still names it. A catalogue writes
 * `[wireKey('label.operator', FilterOperators.EQ)]: 'is'` rather than the
 * key spelled out, so the key follows the value when Wow respells it
 * and the templates that read it (`label.operator.${operator}`) still find it.
 */
export function wireKey<P extends string, V extends string>(
  prefix: P,
  value: V,
): `${P}.${V}` {
  return `${prefix}.${value}`;
}

/**
 * A table of words by wire value as catalogue entries, each keyed
 * {@link wireKey}`(prefix, value)`: a catalogue whose words are the
 * kernel's own reads them from the one table rather than copying it.
 */
export function wireKeys<P extends string, V extends string>(
  prefix: P,
  words: Readonly<Record<V, string>>,
): { readonly [K in V as `${P}.${K}`]: string } {
  return Object.fromEntries(
    Object.entries<string>(words).map(([value, word]) => [
      wireKey(prefix, value),
      word,
    ]),
  ) as { readonly [K in V as `${P}.${K}`]: string };
}
