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
 * Whether a list holds a value read from outside — a stored config, a
 * fetched schema, a descriptor — and, when it does, that value as one of
 * the list's own. `Array.prototype.includes` asks for a value of the list's
 * type before it has been checked to be one.
 */
export function includesValue<T>(
  list: readonly T[],
  value: unknown,
): value is T {
  return (list as readonly unknown[]).includes(value);
}

const EXACT_NUMBER = /^-?(?:0|[1-9]\d{0,299})(?:\.\d{1,300})?$/;

/**
 * Whether a value is a number a source sent as its exact text, to keep its
 * digits: `@ahoo-wang/wow-client` reads an `int64`, a `bigint` or a
 * `decimal` field so, a Kotlin `Long` or `BigDecimal` arriving as
 * `'9007199254740993'` or `'1234567.50'`. It is written as JSON writes a
 * number, digits with an optional fraction, no exponent and no grouping.
 * Every reader that prints or adds numbers takes it for the number it is:
 * a cell, its text and its file, a page's summary.
 */
export function isExactNumberText(value: unknown): value is `${number}` {
  return typeof value === 'string' && EXACT_NUMBER.test(value);
}
