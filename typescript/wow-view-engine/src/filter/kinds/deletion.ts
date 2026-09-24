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
  DeletionState,
  filter,
  type FilterExpression,
} from '@ahoo-wang/wow-client';
import type { FieldDefinition, FilterTree } from '../../model/index.js';
import type { FilterSummaryItem } from '../describe.js';
import {
  isBlankLeafValue,
  issue,
  type FieldKind,
  type FieldKindRegistry,
} from '../fieldKind.js';
import { isFilterLeaf, walkFilter } from '../tree.js';

/** The three readings Wow's `DELETION` filter admits, in the order offered. */
export const DELETION_STATES: readonly DeletionState[] = [
  DeletionState.ACTIVE,
  DeletionState.DELETED,
  DeletionState.ALL,
];

export function isDeletionState(value: unknown): value is DeletionState {
  return (DELETION_STATES as readonly unknown[]).includes(value);
}

/**
 * Whether soft-deleted records are shown: Wow's `DELETION` filter, as a
 * dimension a definition declares (D4) rather than one every view carries.
 *
 * A Wow source answers a query with the records that are not deleted unless
 * the query says otherwise, and that is the whole point of declaring this
 * kind: a list that silently mixed deleted records into the live ones would
 * be a wrong number said with confidence. Declared, it is a condition like
 * any other — a field in the picker, a pill with one operator and three
 * answers, a badge on the applied bar. Undeclared, nothing on screen says
 * "deleted", because the definition did not make it the user's to decide.
 *
 * The default reading is D17-2: **not deleted**, for a view whose config
 * never wrote this condition — an old config, a fresh one, a pill left
 * blank — and the applied bar says so (`impliedDeletion`), because a reading
 * in force that nobody wrote is still in force. "Deleted only" and "deleted
 * included" are explicit choices.
 *
 * Fieldless, like the metadata kinds beside it: the field's `name` is a
 * handle for the editor and the label, and it compiles to a filter that
 * names no document field.
 */
export const deletionFieldKind: FieldKind = {
  id: 'deletion',
  operators: ['DELETION'],
  defaultOperator: 'DELETION',
  emptyValue() {
    // Not `ACTIVE`: a blank pill asks nothing, and the source's own default
    // answers it. Writing the default in would make every fresh condition
    // look like a choice somebody made.
    return null;
  },
  fieldless: true,
  isBlank({ value }) {
    return value === null || value === undefined || value === '';
  },
  validate({ value, path }) {
    return isDeletionState(value)
      ? []
      : [issue('filter.value.expected-deletion-state', path)];
  },
  compile({ leaf }): FilterExpression {
    return filter.deletion(leaf.value as DeletionState);
  },
  editor() {
    return { input: 'deletion' };
  },
  describe({ leaf, field }) {
    if (!isDeletionState(leaf.value))
      return { text: field.label, value: { kind: 'blank' } };
    return {
      text: `${field.label} ${leaf.value}`,
      value: { kind: 'text', value: leaf.value },
    };
  },
};

/**
 * The deletion reading in force that nobody wrote (D17-2).
 *
 * For every declared deletion field that no tree in `trees` — the view's
 * own conditions as they ran, the host's scope — answers with a value, the
 * source's default applies: not deleted. The bar shows it beside the
 * conditions that were written, with no way to remove it, because it is
 * not a condition in the config; adding the field and choosing otherwise
 * is how it changes.
 */
export function impliedDeletion(
  fields: readonly FieldDefinition[],
  trees: readonly (FilterTree | null | undefined)[],
  kinds: FieldKindRegistry,
): FilterSummaryItem[] {
  return fields.flatMap(field => {
    const kind = kinds.get(field.kind);
    if (!kind || field.kind !== 'deletion') return [];
    const answered = trees.some(
      tree =>
        tree !== null &&
        tree !== undefined &&
        saysDeletion(tree, field, kind, kinds),
    );
    if (answered) return [];
    return [
      {
        path: [],
        text: `${field.label} ${DeletionState.ACTIVE}`,
        unresolved: false,
        field: field.name,
        label: field.label,
        kind: field.kind,
        operator: 'DELETION',
        value: { kind: 'text', value: DeletionState.ACTIVE },
      },
    ];
  });
}

function saysDeletion(
  tree: FilterTree,
  field: FieldDefinition,
  kind: FieldKind,
  kinds: FieldKindRegistry,
): boolean {
  for (const { node } of walkFilter(tree)) {
    if (
      isFilterLeaf(node) &&
      node.field === field.name &&
      !isBlankLeafValue(node.value, node.operator, field, kind, kinds)
    )
      return true;
  }
  return false;
}
