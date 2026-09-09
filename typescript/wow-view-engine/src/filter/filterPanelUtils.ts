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

import { FilterOperator } from '@ahoo-wang/fetcher-wow';

export const logicalOperators = [
  FilterOperator.AND,
  FilterOperator.OR,
  FilterOperator.NOR,
] as const;
export const groupLabels = {
  AND: '满足全部条件',
  OR: '满足任一条件',
  NOR: '全部条件均不满足',
};
export const filterLayout =
  'fve:grid fve:grid-cols-[repeat(auto-fill,minmax(min(100%,24rem),1fr))] fve:items-start fve:gap-2';
export function message(error: unknown) {
  return (
    (error instanceof Error ? error.message : String(error)) ||
    '筛选器处理失败。'
  );
}
export function ownValue<T>(
  values: Record<string, T>,
  id: string,
): T | undefined {
  return Object.prototype.hasOwnProperty.call(values, id)
    ? values[id]
    : undefined;
}
export function without(values: Record<string, string>, id: string) {
  if (ownValue(values, id) === undefined) return values;
  return Object.fromEntries(
    Object.entries(values).filter(([key]) => key !== id),
  );
}
