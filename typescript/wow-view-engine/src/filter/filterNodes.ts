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
import { DeletionState, FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import type { DeepReadonly } from '../lib/types.js';
import type {
  FilterComponentConfig,
  FilterEditorReference,
} from './filterModel.js';
import { definition, FILTER_OPERATORS } from './filterOperators.js';

export function newFilterNode(
  op: Op,
  field?: string,
  component: FilterEditorReference = { name: 'builtin' },
): FilterComponentConfig {
  const descriptor = definition(op);
  const node: FilterComponentConfig = {
    id: crypto.randomUUID(),
    operator: op,
    component: structuredClone(component),
    props: {},
    ...(field === undefined ? {} : { field }),
  };
  if (descriptor.category === 'logical') node.operands = [];
  if (descriptor.category === 'element') node.predicate = newFilterNode(Op.AND);
  if (op === Op.DELETION) node.props.state = DeletionState.ACTIVE;
  return node;
}

export function isSimpleFilter(
  draft: DeepReadonly<FilterComponentConfig>,
): boolean {
  function bound(node: DeepReadonly<FilterComponentConfig>): boolean {
    return (
      Object.prototype.hasOwnProperty.call(FILTER_OPERATORS, node.operator) &&
      typeof node.field === 'string' &&
      node.field.length > 0 &&
      node.operands === undefined &&
      (node.operator === Op.ELEMENT_MATCH
        ? !!node.predicate && scope(node.predicate, true)
        : FILTER_OPERATORS[node.operator].category === 'field' &&
          node.predicate === undefined)
    );
  }
  function scope(
    node: DeepReadonly<FilterComponentConfig>,
    allowEmpty = false,
  ): boolean {
    return (
      bound(node) ||
      (node.operator === Op.AND &&
        Array.isArray(node.operands) &&
        (allowEmpty || node.operands.length > 0) &&
        node.operands.every(bound) &&
        new Set(node.operands.map(child => child.field)).size ===
          node.operands.length)
    );
  }
  return draft.operator === Op.MATCH_ALL || scope(draft);
}
