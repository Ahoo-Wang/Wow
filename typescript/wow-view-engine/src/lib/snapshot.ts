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
  if (a === b) return true;
  if (!a || !b || typeof a !== 'object' || typeof b !== 'object') return false;
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length)
      return false;
    for (let index = 0; index < a.length; index++) {
      if (!sameJsonState(a[index] ?? null, b[index] ?? null)) return false;
    }
    return true;
  }
  const left = a as Record<string, unknown>,
    right = b as Record<string, unknown>;
  const keys = Object.keys(left).filter(key => left[key] !== undefined);
  if (
    keys.length !==
    Object.keys(right).filter(key => right[key] !== undefined).length
  )
    return false;
  return keys.every(
    key =>
      Object.prototype.hasOwnProperty.call(right, key) &&
      sameJsonState(left[key], right[key]),
  );
}
