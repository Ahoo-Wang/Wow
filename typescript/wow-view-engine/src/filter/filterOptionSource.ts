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

import type { CursorPage, CursorQuery } from '@ahoo-wang/fetcher-wow';
import type { FilterOption } from './filterTypes.js';
export type FilterOptionValue = string | number;
export type FilterOptionItem = FilterOption<FilterOptionValue>;
export interface FilterOptionSource {
  search(
    query: Pick<CursorQuery, 'cursor' | 'size'> & { search: string },
    signal: AbortSignal,
  ): Promise<CursorPage<FilterOptionItem>>;
  resolve(
    values: readonly FilterOptionValue[],
    signal: AbortSignal,
  ): Promise<{ list: FilterOptionItem[]; missing: FilterOptionValue[] }>;
}
export function isFilterOptionValue(
  value: unknown,
): value is FilterOptionValue {
  return (
    typeof value === 'string' ||
    (typeof value === 'number' && Number.isFinite(value))
  );
}
export function readFilterOptions(input: unknown): FilterOptionItem[] {
  if (!Array.isArray(input)) throw new TypeError('候选列表必须是数组');
  return input.map(item => {
    if (
      !item ||
      typeof item !== 'object' ||
      !isFilterOptionValue(item.value) ||
      typeof item.label !== 'string' ||
      (item.disabled !== undefined && typeof item.disabled !== 'boolean') ||
      (item.group !== undefined && typeof item.group !== 'string')
    )
      throw new TypeError('候选项的值、标签或分组无效');
    return {
      value: item.value,
      label: item.label,
      ...(item.disabled === undefined ? {} : { disabled: item.disabled }),
      ...(item.group === undefined ? {} : { group: item.group }),
    };
  });
}
export function readFilterOptionPage(
  input: unknown,
): CursorPage<FilterOptionItem> {
  if (
    !input ||
    typeof input !== 'object' ||
    !('list' in input) ||
    !('nextCursor' in input) ||
    (input.nextCursor !== null &&
      (typeof input.nextCursor !== 'string' || !input.nextCursor))
  )
    throw new TypeError('候选分页响应无效');
  return { list: readFilterOptions(input.list), nextCursor: input.nextCursor };
}
export function readResolvedFilterOptions(
  input: unknown,
  values: readonly FilterOptionValue[],
) {
  if (
    !input ||
    typeof input !== 'object' ||
    !('list' in input) ||
    !('missing' in input) ||
    !Array.isArray(input.missing) ||
    !input.missing.every(isFilterOptionValue)
  )
    throw new TypeError('候选回填响应无效');
  const list = readFilterOptions(input.list),
    missing: FilterOptionValue[] = input.missing;
  const received = [...list.map(item => item.value), ...missing];
  if (
    new Set(received).size !== received.length ||
    received.length !== values.length ||
    received.some(value => !values.includes(value))
  )
    throw new TypeError('候选回填必须完整且唯一地标识已找到或缺失的值');
  return { list, missing };
}
