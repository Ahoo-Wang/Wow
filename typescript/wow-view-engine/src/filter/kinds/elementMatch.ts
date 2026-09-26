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
} from '@ahoo-wang/wow-client';
import {
  isFieldlessKind,
  type FieldDefinition,
  type FieldGroupDefinition,
  type FilterNode,
  type FilterTree,
  type FilterValue,
  type Issue,
} from '../../model/index.js';
import { compileFilter, type FilterCompileContext } from '../compile.js';
import {
  describeFilter,
  groupJoinWord,
  isGroupItem,
  type FilterSummaryItem,
} from '../describe.js';
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
  describePresenceParts,
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
/**
 * The groups a predicate's field picker lists an element's fields under
 * when its elements differ by variant (#3519): one per variant, in the
 * order the discriminator's options give (else by value), each naming the
 * fields only that variant has, by the names `elementFields` gives them.
 * A field several variants share, and every field of an element that does
 * not vary, is listed before the groups.
 */
export function variantGroups(field: FieldDefinition): FieldGroupDefinition[] {
  const key = field.variantKey;
  if (!key) return [];
  const elements = field.elements ?? [];
  const options = elements.find(element => element.name === key)?.options;
  const order = new Map(
    (options ?? []).map((option, index) => [String(option.value), index]),
  );
  const label = (value: string) =>
    options?.find(option => String(option.value) === value)?.label ?? value;
  const groups = new Map<string, string[]>();
  for (const element of elements) {
    const [only, ...others] = element.variants ?? [];
    if (only === undefined || others.length > 0) continue;
    groups.set(only, [
      ...(groups.get(only) ?? []),
      `${field.name}.${element.name}`,
    ]);
  }
  return [...groups.entries()]
    .sort(
      ([one], [other]) =>
        (order.get(one) ?? Infinity) - (order.get(other) ?? Infinity) ||
        one.localeCompare(other),
    )
    .map(([value, fields]) => ({
      id: `variant:${value}`,
      label: label(value),
      fields,
    }));
}

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

    // Wow reads a predicate's fields relative to the element — `quantity`,
    // not `items.quantity`, which it would look up as `items.items.quantity`
    // and never find. The config names them from outside, so the tree is
    // re-addressed from inside the array before it is compiled, against the
    // element's own declarations; an element of an element is re-addressed
    // again by its own compile.
    const context: FilterCompileContext = { now, timeZone };
    const inside = insideElement(field.name, readValue<FilterTree>(leaf.value));
    const predicate = compileFilter(
      field.elements ?? [],
      inside,
      kinds,
      context,
    );
    return filter.elementMatch(
      field.name,
      withVariant(field, inside, predicate) as ElementFilterExpression,
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
    const presence = describePresenceParts(leaf.operator, field);
    if (presence) return presence;
    if (leaf.operator === 'IS_EMPTY')
      return {
        text: `${field.label} has no entries`,
        value: { kind: 'none' },
      };

    // A value that is not a condition describes nothing: reading it as an
    // empty predicate would announce "has any entry", a condition nobody
    // wrote and the query does not carry.
    const value = leaf.value;
    if (!isTree(value)) return { text: field.label, value: { kind: 'blank' } };

    const described = describeFilter(
      elementFields(field),
      readValue<FilterTree>(value),
      kinds,
    );
    // The predicate's own operator, as `describeFilter` reads a group's:
    // joining an `or` with "and" states the opposite of what is in force.
    return {
      text:
        described.length === 0
          ? `${field.label} has any entry`
          : `${field.label} has an entry where ${described.map(item => item.text).join(groupJoinWord(value.op))}`,
      // The predicate is not a value to show beside the operator; it is the
      // conditions in `items`, which the bar reads out in its own wording.
      value: { kind: 'none' },
      items: predicateItems(described),
      group: value.op,
    };
  },
};

/**
 * The conditions a predicate holds, said once.
 *
 * `describeFilter` folds a root that is not "all of" into a single group item
 * carrying that operator, because at the top of a bar the items sit side by
 * side and nothing else would say how they combine. Here the operator is
 * stated beside them anyway, so passing the fold on read it twice — "any of
 * (any of A, B)" — and a one-condition `nor` read as its own negation
 * negated, which is the opposite of the query that ran. The fold is
 * recognised by its empty path: it stands for the root, not for a group
 * anybody wrote.
 */
function predicateItems(
  described: readonly FilterSummaryItem[],
): readonly FilterSummaryItem[] {
  const [only] = described;
  return described.length === 1 && isGroupItem(only) && only.path.length === 0
    ? (only.items ?? [])
    : described;
}

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
    // A time since another moment is a root filter too: Wow keeps
    // `EXPRESSION` out of `ELEMENT_MATCH` (N3).
    if (node.operator === 'EXPRESSION') {
      issues.push(issue('filter.element.duration', path));
      continue;
    }
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

/**
 * The predicate as the element sees it: every field the config wrote as
 * `array.field` becomes `field`, at every depth — a nested element match's
 * own predicate included, since its leaves were written from the root too.
 */
/**
 * A predicate naming fields only some variants have, held to the elements of
 * those variants (#3519): a payload field of one event type means that
 * type's events, and the descriptor asks for the discriminator beside it so
 * both hold for the same element. Left as it is where the predicate says
 * the variant itself, or names no variant's field.
 */
function withVariant(
  field: FieldDefinition,
  inside: FilterTree,
  predicate: FilterExpression,
): FilterExpression {
  const key = field.variantKey;
  if (!key) return predicate;
  const byName = new Map(
    (field.elements ?? []).map(element => [element.name, element]),
  );
  const values = new Set<string>();
  for (const { node } of walkFilter(inside)) {
    if (isFilterGroup(node)) continue;
    if (node.field === key) return predicate;
    for (const value of byName.get(node.field)?.variants ?? [])
      values.add(value);
  }
  if (values.size === 0) return predicate;
  return filter.and([predicate, filter.isIn(key, [...values].sort())]);
}

function insideElement(array: string, tree: FilterTree): FilterTree {
  const prefix = `${array}.`;
  const relative = (name: string) =>
    name.startsWith(prefix) ? name.slice(prefix.length) : name;
  const visit = (node: FilterNode): FilterNode => {
    if (isFilterGroup(node))
      return { ...node, children: node.children.map(visit) };
    return {
      ...node,
      field: relative(node.field),
      value: isTree(node.value)
        ? (visitTree(node.value) as unknown as FilterValue)
        : node.value,
    };
  };
  const visitTree = (inner: FilterTree): FilterTree => ({
    ...inner,
    children: inner.children.map(visit),
  });
  return visitTree(tree);
}

function isTree(value: unknown): value is FilterTree {
  return (
    typeof value === 'object' &&
    value !== null &&
    Array.isArray((value as FilterTree).children)
  );
}
