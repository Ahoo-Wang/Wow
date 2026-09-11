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

import { AggregationGroupType as Group } from '@ahoo-wang/fetcher-wow';
import type { FilterFieldDefinition } from '../filter/filterModel.js';

export const ANALYSIS_LIMITS = Object.freeze({
  maxGroups: 32,
  maxMetrics: 64,
  maxSort: 32,
  defaultLimit: 100,
  maxLimit: 10000,
});

/** Shared builtin group compatibility for admission of compiler output and editor choices. */
export function analysisGroupValueType(
  type: FilterFieldDefinition['type'],
  group: Group,
  timeZone?: string,
  dateUnits?: readonly unknown[],
): 'string' | 'number' | 'boolean' | 'datetime' | undefined {
  if (group === Group.TERMS)
    return type === 'string' || type === 'number' || type === 'boolean'
      ? type
      : undefined;
  if (group === Group.HISTOGRAM)
    return type === 'number' ? 'number' : undefined;
  if (
    group === Group.DATE_HISTOGRAM &&
    (type === 'date' || type === 'datetime') &&
    timeZone &&
    dateUnits?.length
  )
    return 'datetime';
  return undefined;
}
