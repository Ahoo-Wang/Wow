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

import {
  filter,
  type ElementFilterExpression,
  type FilterExpression,
} from '@ahoo-wang/fetcher-wow';
import {
  isFieldlessKind,
  type FieldDefinition,
  type FilterGroupOperator,
  type FilterTree,
  type Issue,
} from '../../model/index.js';
import { compileFilter, type FilterCompileContext } from '../compile.js';
import { describeFilter } from '../describe.js';
import {
  issue,
  readValue,
  type FieldKind,
  type FieldKindRegistry,
} from '../fieldKind.js';
import { emptyFilter, isFilterGroup, walkFilter } from '../tree.js';
import { isBlankFilter, validateFilter } from '../validate.js';
import {
  compilePresence,
  describePresence,
  PRESENCE_OPERATORS,
} from './presence.js';

/**
 * The fields of one element, named as a condition names them.
 *
 * A config points at an element field from outside, so it writes the full
 * `array.field`; the declaration inside the array writes the relative name.
 * This is the one place the two meet.
 */
export function elementFields(
  field: FieldDefinition,
): readonly FieldDefinition[] {
  return (field.elements ?? []).map(element => ({
    ...element,
    name: `${field.name}.${element.name}`,
  }));
}

/**
 * A condition on the elements of an array: "some line has sku X and qty > 2".
 *
 * Its value is a condition rather than a scalar, which is what separates it
 * from every other kind. Wow's `ELEMENT_MATCH` carries a whole predicate, and
 * a predicate is what a user writes here — the same tree, the same editor,
 * scoped to the fields the array's elements declare.
 *
 * The distinction it exists for: two conditions on `items.sku` and `items.qty`
 * written side by side at the top level are satisfied by *any* elements, one
 * matching each. Inside an element match they must be satisfied by the *same*
 * element, which is almost always what someone asking the question meant.
 */
export const elementMatchFieldKind: FieldKind = {
  id: 'elementMatch',
  // Its name is a real path, unlike a metadata kind's, so the presence
  // questions apply to it as they do to any other field: an array can be
  // absent as well as empty, and those are different answers.
  operators: ['ELEMENT_MATCH', 'IS_EMPTY', ...PRESENCE_OPERATORS],
  defaultOperator: 'ELEMENT_MATCH',
  scalar: false,

  emptyValue() {
    return emptyFilter();
  },

  /**
   * A predicate that says nothing asks nothing, exactly as an empty group
   * does — and it says nothing whether it holds no condition or only
   * conditions that are themselves still unfilled. Counting leaves would call
   * the second case a question, and `compileFilter` would then drop the blank
   * leaf and answer `MATCH_ALL`, so the match would narrow the result to
   * "the array is non-empty" without anyone having asked that. A value that
   * is not a condition at all is a different thing: unfilled is not the same
   * as wrong, and only emptiness is forgiven.
   */
  isBlank({ value, operator, field, kinds }) {
    if (operator !== 'ELEMENT_MATCH') return false;
    return isTree(value) && isBlankFilter(elementFields(field), value, kinds);
  },

  /**
   * The budget counts this tree with the outer one; see `checkShape`.
   *
   * Gated on the operator exactly as `isBlank` and `validate` are. A leaf
   * keeps its value when the operator changes, so a predicate left behind
   * under `IS_EMPTY` is not asked anything — answering with it would charge
   * the budget for a tree no pass ever validates.
   */
  nested(value, field, operator) {
    if (operator !== 'ELEMENT_MATCH') return null;
    if (!isTree(value)) return null;
    return { tree: value, fields: elementFields(field) };
  },

  validate({ value, operator, field, kinds, path, limits }) {
    if (operator !== 'ELEMENT_MATCH') return [];
    if (!isTree(value)) return [issue('filter.value.expected-predicate', path)];
    if (field.elements === undefined)
      return [
        issue('filter.field.holds-no-elements', path, { field: field.name }),
      ];

    // The predicate is admitted against the element's fields, so a condition
    // naming a root field — or one the element does not declare — is reported
    // here rather than compiled into a predicate Wow cannot answer. It is
    // admitted under the caller's budget rather than the default one, because
    // this predicate is part of the filter around it; and `checkShape` has
    // already walked it through `nested`, so walking it again would charge
    // its nodes a second time against that one total.
    const scoped = elementFields(field);
    return [
      ...validateFilter(scoped, value, kinds, {
        ...(limits ? { limits } : {}),
        shapeChecked: true,
      }),
      ...rootFilters(value, scoped, kinds),
    ].map(found => ({ ...found, path: [...path, ...found.path] }));
  },

  compile({ leaf, field, kinds, now, timeZone }): FilterExpression {
    const presence = compilePresence(field.name, leaf.operator);
    if (presence) return presence;
    if (leaf.operator === 'IS_EMPTY') return filter.isEmpty(field.name);

    const context: FilterCompileContext = { now, timeZone };
    const predicate = compileFilter(
      elementFields(field),
      readValue<FilterTree>(leaf.value),
      kinds,
      context,
    );
    return filter.elementMatch(
      field.name,
      predicate as ElementFilterExpression,
    );
  },

  editor(operator) {
    // Not a value input: the editor renders the same condition builder the
    // outer filter uses, over the element's fields.
    return operator === 'ELEMENT_MATCH'
      ? { input: 'predicate' }
      : { input: 'none' };
  },

  describe({ leaf, field, kinds }) {
    const presence = describePresence(leaf.operator);
    if (presence) return `${field.label} ${presence}`;
    if (leaf.operator === 'IS_EMPTY') return `${field.label} has no entries`;

    // A value that is not a condition describes nothing: reading it as an
    // empty predicate would announce "has any entry", a condition nobody
    // wrote and the query does not carry.
    const value = leaf.value;
    if (!isTree(value)) return field.label;

    const inner = describeFilter(
      elementFields(field),
      readValue<FilterTree>(value),
      kinds,
    ).map(item => item.text);
    // The predicate's own operator, as `describeFilter` reads a group's:
    // joining an `or` with "and" states the opposite of what is in force.
    return inner.length === 0
      ? `${field.label} has any entry`
      : `${field.label} has an entry where ${inner.join(joinWord(value.op))}`;
  },
};

/**
 * Conditions inside a predicate that Wow calls root filters.
 *
 * Search and the metadata filters name no field, so they cannot be asked of
 * one entry — `ElementMatchFilter` refuses them in its constructor, and a
 * definition the engine called usable would throw the moment the condition
 * ran.
 *
 * One tree is enough to walk. A nested element match is a leaf here, and its
 * own `validate` asks the same question of its own predicate, so the
 * recursion Wow performs happens a level at a time on the way down.
 */
function rootFilters(
  tree: FilterTree,
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
): Issue[] {
  const byName = new Map(fields.map(field => [field.name, field]));
  const issues: Issue[] = [];

  for (const { node, path } of walkFilter(tree)) {
    if (isFilterGroup(node)) continue;
    const field = byName.get(node.field);
    // The registered kind answers this, not a list of built-in ids: a custom
    // kind that compiles to `SEARCH` or a metadata filter is a root filter
    // too, and slipping into a predicate makes Wow throw.
    if (!field || !isFieldlessKind(field.kind, kinds.get(field.kind))) continue;
    issues.push(
      issue('filter.element.root-filter', path, { field: field.name }),
    );
  }
  return issues;
}

/** How a group's own operator reads between its conditions. */
function joinWord(op: FilterGroupOperator): string {
  return op === 'or' ? ' or ' : op === 'nor' ? ' nor ' : ' and ';
}

function isTree(value: unknown): value is FilterTree {
  return (
    typeof value === 'object' &&
    value !== null &&
    Array.isArray((value as FilterTree).children)
  );
}
