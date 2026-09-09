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

export function freeze<T>(value: T, ancestors = new Set<object>()): T {
  if (
    value === null ||
    value === undefined ||
    typeof value === 'string' ||
    typeof value === 'boolean'
  )
    return value;
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value !== 'object') throw new Error('视图数据必须可序列化为 JSON');
  if (Object.isFrozen(value)) return value;
  if (
    (!Array.isArray(value) &&
      Object.prototype.toString.call(value) !== '[object Object]') ||
    ancestors.has(value)
  )
    throw new Error('视图数据必须为无循环引用的 JSON 数据');
  ancestors.add(value);
  Object.values(value).forEach(item => freeze(item, ancestors));
  ancestors.delete(value);
  return Object.freeze(value);
}
export function copy<T>(value: T): T {
  try {
    return freeze(structuredClone(value));
  } catch (error) {
    throw Object.assign(
      new Error(`视图数据包含非 JSON 值：${message(error)}`),
      { cause: error },
    );
  }
}
export function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** Compare JSON snapshots independent of object key order; absent and undefined properties agree. */
export function sameJsonState(a: unknown, b: unknown): boolean {
  function canonical(value: unknown): unknown {
    if (Array.isArray(value)) return value.map(canonical);
    if (value && typeof value === 'object')
      return Object.fromEntries(
        Object.entries(value)
          .filter(([, value]) => value !== undefined)
          .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
          .map(([key, value]) => [key, canonical(value)]),
      );
    return value;
  }
  return JSON.stringify(canonical(a)) === JSON.stringify(canonical(b));
}
