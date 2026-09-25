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

/** A plain object of `entries` with its keys sorted. */
export function sortedObject(
  entries: Iterable<[string, unknown]>,
): Record<string, unknown> {
  return Object.fromEntries(
    [...entries].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)),
  );
}

/**
 * `value` as it goes over the wire — JSON, which drops `undefined` members —
 * with the keys of every object in it sorted. Key order carries no meaning on
 * the wire, so a golden file written from this stays unchanged when a
 * refactor builds the same fields in another order.
 */
export function canonicalJson(value: unknown): unknown {
  if (value === undefined) return undefined;
  return JSON.parse(JSON.stringify(value), (_key, member: unknown) =>
    member && typeof member === 'object' && !Array.isArray(member)
      ? sortedObject(Object.entries(member))
      : member,
  );
}
