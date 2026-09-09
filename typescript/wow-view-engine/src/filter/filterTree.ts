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

import { sameJsonState } from '../lib/snapshot.js';
import { FilterOperator, type FilterExpression } from '@ahoo-wang/fetcher-wow';
import { newFilterNode } from './filterNodes.js';
import type {
  FilterComponentConfig,
  FilterFieldDefinition,
} from './filterModel.js';
import type { DeepReadonly } from '../lib/types.js';

/** Ignore only redundant singleton AND/OR wrappers; keep persisted structure unchanged. */
export function sameFilterQuery(
  a: DeepReadonly<FilterExpression> | null | undefined,
  b: DeepReadonly<FilterExpression> | null | undefined,
): boolean {
  function normalize(
    value: DeepReadonly<FilterExpression> | null | undefined,
  ): unknown {
    if (!value) return value;
    if ('operands' in value) {
      if (
        (value.op === FilterOperator.AND || value.op === FilterOperator.OR) &&
        value.operands.length === 1
      )
        return normalize(value.operands[0]);
      return { ...value, operands: value.operands.map(normalize) };
    }
    return 'predicate' in value
      ? { ...value, predicate: normalize(value.predicate) }
      : value;
  }
  return sameJsonState(normalize(a), normalize(b));
}
export function sameFilterNode(
  a: DeepReadonly<FilterComponentConfig>,
  b: DeepReadonly<FilterComponentConfig>,
): boolean {
  function content(node: DeepReadonly<FilterComponentConfig>): unknown {
    return {
      ...node,
      id: undefined,
      operands: node.operands?.map(content),
      predicate: node.predicate ? content(node.predicate) : undefined,
    };
  }
  return sameJsonState(content(a), content(b));
}
export function replaceFilterNode(
  root: FilterComponentConfig,
  id: string,
  next?: FilterComponentConfig,
): FilterComponentConfig | undefined {
  if (root.id === id) return next;
  if (root.operands)
    return {
      ...root,
      operands: root.operands.flatMap(node => {
        const updated = replaceFilterNode(node, id, next);
        return updated ? [updated] : [];
      }),
    };
  if (root.predicate)
    return {
      ...root,
      predicate:
        replaceFilterNode(root.predicate, id, next) ??
        newFilterNode(FilterOperator.AND),
    };
  return root;
}
export interface FilterNodeLocation {
  node: FilterComponentConfig;
  fields: readonly FilterFieldDefinition[];
  scope: string;
}
export function locateFilterNodes(
  root: FilterComponentConfig,
  fields: readonly FilterFieldDefinition[],
): FilterNodeLocation[] {
  const result: FilterNodeLocation[] = [];
  function visit(
    node: FilterComponentConfig,
    fields: readonly FilterFieldDefinition[],
    scope: string,
  ) {
    result.push({ node, fields, scope });
    node.operands?.forEach(child => visit(child, fields, scope));
    if (node.predicate)
      visit(
        node.predicate,
        fields.find(field => field.field === node.field)?.fields ?? [],
        `${scope}/${node.id}`,
      );
  }
  visit(root, fields, 'root');
  return result;
}
