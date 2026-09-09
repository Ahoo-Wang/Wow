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
import type { FilterComponentConfig } from './filterModel.js';
import { newFilterNode } from './filterNodes.js';
import { FILTER_OPERATORS } from './filterOperators.js';

export function appendNode(
  target: FilterComponentConfig,
  child: FilterComponentConfig,
): FilterComponentConfig {
  if (target.operands)
    return { ...target, operands: [...target.operands, child] };
  if (target.operator === FilterOperator.MATCH_ALL) return child;
  return { ...newFilterNode(FilterOperator.AND), operands: [target, child] };
}
export function transitionFilterOperator(
  node: FilterComponentConfig,
  op: FilterOperator,
): FilterComponentConfig {
  if (node.operator === op) return structuredClone(node);
  const next = {
    ...newFilterNode(op, node.field, node.component),
    id: node.id,
  };
  if (node.component.name !== 'builtin') {
    next.props = structuredClone(node.props);
    return next;
  }
  const before = FILTER_OPERATORS[node.operator]?.input,
    after = FILTER_OPERATORS[op]?.input;
  if (before === after) {
    const keys =
      after === 'value'
        ? ['value']
        : after === 'values'
          ? ['values']
          : after === 'between'
            ? ['lowerBound', 'upperBound']
            : after === 'time'
              ? ['time']
              : after === 'days'
                ? ['days']
                : [];
    for (const key of keys)
      if (key in node.props)
        Object.assign(next.props, { [key]: node.props[key] });
  }
  if (
    FILTER_OPERATORS[node.operator]?.relativeTime &&
    FILTER_OPERATORS[op]?.relativeTime
  ) {
    for (const key of ['zoneId', 'datePattern', 'timeUnit'] as const)
      if (key in node.props)
        Object.assign(next.props, { [key]: node.props[key] });
  }
  return next;
}
