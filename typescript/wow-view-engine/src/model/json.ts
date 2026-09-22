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

/** Any value a saved configuration may hold: configs are plain JSON. */
export type JsonValue =
  string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };

/**
 * Replaces enum members with their string values and leaves everything else
 * untouched, so a Wow protocol type can be reused in a configuration without
 * dragging its enums into stored data.
 */
export type LiteralEnums<T> = T extends string
  ? `${T}`
  : T extends object
    ? { [K in keyof T]: LiteralEnums<T[K]> }
    : T;

/**
 * The object without one optional member: what "unset" writes. A config
 * stays plain JSON, so a setting taken back leaves no `undefined` behind for
 * a comparison to trip over.
 */
export function without<T extends object, K extends keyof T>(
  value: T,
  key: K,
): Omit<T, K> {
  const next = { ...value };
  delete next[key];
  return next;
}
