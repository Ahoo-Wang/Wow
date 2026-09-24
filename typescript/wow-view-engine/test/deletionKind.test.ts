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

import { DeletionState, FilterOperator } from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileFilter,
  describeFilter,
  impliedDeletion,
  validateFilter,
} from '../src/filter/index.js';
import { validateDefinition } from '../src/runtime/index.js';
import type {
  FieldDefinition,
  FilterTree,
  FilterValue,
  ViewDefinition,
} from '../src/model/index.js';

/**
 * D17-2: whether soft-deleted records are shown is a dimension a definition
 * declares. Declared, it is a condition with three answers; left blank, the
 * source's own default — not deleted — is in force and is said so.
 */
const fields: FieldDefinition[] = [
  { name: '@deleted', label: 'Deleted', kind: 'deletion' },
  { name: 'warehouse', label: 'Warehouse', kind: 'string', sortable: true },
];
const context = { now: new Date('2026-09-21T00:00:00Z'), timeZone: 'UTC' };

function tree(value: FilterValue): FilterTree {
  return {
    op: 'and',
    children: [{ field: '@deleted', operator: 'DELETION', value }],
  };
}

function errors(issues: { severity: string; code: string }[]): string[] {
  return issues.filter(i => i.severity === 'error').map(i => i.code);
}

describe('deletion field kind', () => {
  it('is admitted by definition admission as a fieldless kind', () => {
    const definition: ViewDefinition = {
      id: 'orders',
      title: 'Orders',
      kind: 'data',
      source: 'orders',
      fields,
      record: { rowKey: 'warehouse', paging: 'paged', layouts: ['table'] },
    };
    expect(errors(validateDefinition(definition, builtinFieldKinds))).toEqual(
      [],
    );
    // Not a column: it names no document field to show.
    expect(builtinFieldKinds.get('deletion')?.fieldless).toBe(true);
  });

  it('offers one operator and starts blank, which asks nothing', () => {
    const kind = builtinFieldKinds.get('deletion');
    expect(kind?.operators).toEqual(['DELETION']);
    expect(kind?.emptyValue('DELETION', fields[0]!)).toBeNull();
    expect(kind?.editor('DELETION', fields[0]!)).toEqual({ input: 'deletion' });
  });

  /**
   * The three readings. A blank condition compiles to nothing at all, so
   * the query carries no `DELETION` filter and the source's default — not
   * deleted — answers; the other two are written out.
   */
  it('compiles the default reading as no condition and the choices as DELETION', () => {
    expect(
      compileFilter(fields, tree(null), builtinFieldKinds, context),
    ).toEqual({ op: FilterOperator.MATCH_ALL });
    expect(
      compileFilter(fields, tree('DELETED'), builtinFieldKinds, context),
    ).toEqual({ op: FilterOperator.DELETION, state: DeletionState.DELETED });
    expect(
      compileFilter(fields, tree('ALL'), builtinFieldKinds, context),
    ).toEqual({ op: FilterOperator.DELETION, state: DeletionState.ALL });
    // Written out explicitly, "not deleted" is the same query the default
    // runs; it is only no longer implied.
    expect(
      compileFilter(fields, tree('ACTIVE'), builtinFieldKinds, context),
    ).toEqual({ op: FilterOperator.DELETION, state: DeletionState.ACTIVE });
  });

  it('refuses a reading Wow does not have', () => {
    expect(
      errors(validateFilter(fields, tree('GONE'), builtinFieldKinds)),
    ).toEqual(['filter.value.expected-deletion-state']);
    expect(
      errors(validateFilter(fields, tree('ALL'), builtinFieldKinds)),
    ).toEqual([]);
    // Blank is unfinished, not wrong.
    expect(
      errors(validateFilter(fields, tree(null), builtinFieldKinds)),
    ).toEqual([]);
  });

  it('describes a written reading by its token, for the bar to word', () => {
    const [item] = describeFilter(fields, tree('DELETED'), builtinFieldKinds);
    expect(item).toMatchObject({
      field: '@deleted',
      kind: 'deletion',
      operator: 'DELETION',
      value: { kind: 'text', value: 'DELETED' },
    });
  });

  describe('the reading nobody wrote', () => {
    const empty: FilterTree = { op: 'and', children: [] };

    it('is "not deleted" while no tree answers the declared dimension', () => {
      expect(impliedDeletion(fields, [empty, null], builtinFieldKinds)).toEqual(
        [
          {
            path: [],
            text: 'Deleted ACTIVE',
            unresolved: false,
            field: '@deleted',
            label: 'Deleted',
            kind: 'deletion',
            operator: 'DELETION',
            value: { kind: 'text', value: 'ACTIVE' },
          },
        ],
      );
      // A blank pill has not answered either.
      expect(
        impliedDeletion(fields, [tree(null)], builtinFieldKinds),
      ).toHaveLength(1);
    });

    it('is nothing once the view or the host scope has answered', () => {
      expect(impliedDeletion(fields, [tree('ALL')], builtinFieldKinds)).toEqual(
        [],
      );
      expect(
        impliedDeletion(fields, [empty, tree('DELETED')], builtinFieldKinds),
      ).toEqual([]);
    });

    it('does not exist for a definition that declared no such dimension', () => {
      expect(impliedDeletion([fields[1]!], [empty], builtinFieldKinds)).toEqual(
        [],
      );
    });
  });
});
