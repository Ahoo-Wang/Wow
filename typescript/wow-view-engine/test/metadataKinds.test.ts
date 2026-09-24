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

import { FilterOperator } from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileFilter,
  describeFilter,
  validateFilter,
} from '../src/filter/index.js';
import { validateDefinition } from '../src/runtime/index.js';
import type {
  FieldDefinition,
  FilterOperatorName,
  FilterTree,
  FilterValue,
  ViewDefinition,
} from '../src/model/index.js';

/**
 * Wow's metadata filters name no field: `{ op: 'OWNER_ID', value }` carries
 * the operator and the value and nothing else. These kinds are how a config
 * reaches them, and what they answer is the ordinary business question of who
 * is looking — a user's own documents, a workspace, a tenant for an operator.
 */
const fields: FieldDefinition[] = [
  { name: '@id', label: 'Order no.', kind: 'documentId' },
  { name: '@aggregateId', label: 'Aggregate', kind: 'aggregateId' },
  { name: '@tenantId', label: 'Tenant', kind: 'tenantId' },
  { name: '@ownerId', label: 'Created by', kind: 'ownerId' },
  { name: '@spaceId', label: 'Workspace', kind: 'spaceId' },
  {
    name: '@ownerPicked',
    label: 'Owner',
    kind: 'ownerId',
    remote: 'users',
  },
];

const context = { now: new Date('2026-09-17T00:00:00Z'), timeZone: 'UTC' };

function tree(...children: FilterTree['children']): FilterTree {
  return { op: 'and', children };
}

function errors(issues: { severity: string; code: string }[]): string[] {
  return issues.filter(i => i.severity === 'error').map(i => i.code);
}

function compile(node: FilterTree['children'][number]) {
  return compileFilter(fields, tree(node), builtinFieldKinds, context);
}

describe('metadata field kinds', () => {
  it('are admitted by definition admission under the @ spelling', () => {
    const definition: ViewDefinition = {
      id: 'orders',
      title: 'Orders',
      kind: 'data',
      source: 'orders',
      // The row key is a document field the rows carry and the query sorts
      // on last; a metadata kind names no such field.
      fields: [
        ...fields,
        { name: 'orderNo', label: 'Order no.', kind: 'string', sortable: true },
      ],
      record: { rowKey: 'orderNo', paging: 'paged', layouts: ['table'] },
    };

    expect(validateDefinition(definition, builtinFieldKinds)).toEqual([]);
  });

  it.each([
    ['@id', 'ID', 'o-1', { op: FilterOperator.ID, value: 'o-1' }],
    [
      '@aggregateId',
      'AGGREGATE_ID',
      'a-1',
      { op: FilterOperator.AGGREGATE_ID, value: 'a-1' },
    ],
    [
      '@tenantId',
      'TENANT_ID',
      't-1',
      { op: FilterOperator.TENANT_ID, value: 't-1' },
    ],
    [
      '@ownerId',
      'OWNER_ID',
      'u-1',
      { op: FilterOperator.OWNER_ID, value: 'u-1' },
    ],
    [
      '@spaceId',
      'SPACE_ID',
      's-1',
      { op: FilterOperator.SPACE_ID, value: 's-1' },
    ],
  ] as const)(
    'compiles %s to its metadata filter',
    (field, operator, value, want) => {
      // The field name is a handle for the editor and the label; what reaches
      // the server names no field at all.
      expect(compile({ field, operator, value })).toEqual(want);
    },
  );

  it.each([
    ['@id', 'IDS', FilterOperator.IDS],
    ['@aggregateId', 'AGGREGATE_IDS', FilterOperator.AGGREGATE_IDS],
  ] as const)('compiles %s to the plural form', (field, operator, op) => {
    expect(compile({ field, operator, value: ['a', 'b'] })).toEqual({
      op,
      values: ['a', 'b'],
    });
  });

  it('takes a picked candidate and sends only its id', () => {
    // A raw owner id means nothing to a reader, so a definition that declares
    // candidates gets a picker and the label travels with the value.
    expect(
      compile({
        field: '@ownerPicked',
        operator: 'OWNER_ID',
        value: { items: [{ id: 'u-7', label: 'Ada' }] },
      }),
    ).toEqual({ op: FilterOperator.OWNER_ID, value: 'u-7' });
  });

  it('summarises a picked candidate by its label', () => {
    expect(
      describeFilter(
        fields,
        tree({
          field: '@ownerPicked',
          operator: 'OWNER_ID',
          value: { items: [{ id: 'u-7', label: 'Ada' }] },
        }),
        builtinFieldKinds,
      ).map(item => item.text),
    ).toEqual(['Owner Ada']);
  });

  const usable: [string, FilterOperatorName, FilterValue][] = [
    ['@ownerId', 'OWNER_ID', 'u-1'],
    ['@id', 'ID', 'o-1'],
    ['@id', 'IDS', ['o-1', 'o-2']],
    ['@ownerPicked', 'OWNER_ID', { items: [{ id: 'u-7', label: 'Ada' }] }],
  ];

  it.each(usable)('admits a usable %s value', (field, operator, value) => {
    expect(
      errors(
        validateFilter(
          fields,
          tree({ field, operator, value }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
  });

  it('starts a picker empty and a text field blank', () => {
    const picker = builtinFieldKinds.get('ownerId')!;
    const plain = fields.find(f => f.name === '@ownerId')!;
    const remote = fields.find(f => f.name === '@ownerPicked')!;

    expect(picker.emptyValue('OWNER_ID', remote)).toEqual({ items: [] });
    expect(picker.emptyValue('OWNER_ID', plain)).toBe('');
    expect(
      builtinFieldKinds.get('documentId')!.emptyValue('IDS', plain),
    ).toEqual([]);
  });

  it('asks for a picker only where candidates were declared', () => {
    const owner = builtinFieldKinds.get('ownerId')!;

    expect(
      owner.editor(
        'OWNER_ID',
        fields.find(f => f.name === '@ownerPicked')!,
      ),
    ).toEqual({ input: 'remote', remote: 'users' });
    expect(
      owner.editor(
        'OWNER_ID',
        fields.find(f => f.name === '@ownerId')!,
      ),
    ).toEqual({ input: 'text' });
  });

  it('collects several ids in one input for the plural operator', () => {
    const document = builtinFieldKinds.get('documentId')!;
    const field = fields.find(f => f.name === '@id')!;

    expect(document.editor('IDS', field)).toEqual({
      input: 'text',
      multiple: true,
    });
    expect(document.editor('ID', field)).toEqual({
      input: 'text',
      multiple: false,
    });
  });

  const summaries: [string, FilterOperatorName, FilterValue, string][] = [
    ['@ownerId', 'OWNER_ID', 'u-1', 'Created by u-1'],
    ['@id', 'IDS', ['o-1', 'o-2'], 'Order no. o-1, o-2'],
  ];

  it.each(summaries)('summarises %s', (field, operator, value, want) => {
    expect(
      describeFilter(
        fields,
        tree({ field, operator, value }),
        builtinFieldKinds,
      ).map(item => item.text),
    ).toEqual([want]);
  });

  it('refuses a value that is neither an id nor a candidate', () => {
    expect(
      errors(
        validateFilter(
          fields,
          tree({ field: '@ownerId', operator: 'OWNER_ID', value: 42 }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.value.expected-id']);
  });

  it('refuses two candidates where Wow takes one', () => {
    // `MetadataValueFilter` has no plural form for owner, tenant or space.
    expect(
      errors(
        validateFilter(
          fields,
          tree({
            field: '@ownerPicked',
            operator: 'OWNER_ID',
            value: {
              items: [
                { id: 'u-7', label: 'Ada' },
                { id: 'u-8', label: 'Grace' },
              ],
            },
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.value.expects-one']);
  });

  it.each([['not-a-list'], [[1, 2]]])(
    'refuses %s where a list of ids was due',
    value => {
      expect(
        errors(
          validateFilter(
            fields,
            tree({ field: '@id', operator: 'IDS', value: value as never }),
            builtinFieldKinds,
          ),
        ),
      ).toEqual(['filter.value.expected-id-list']);
    },
  );

  it('summarises a value it should never have been given', () => {
    // `describe` runs on whatever the leaf holds. Validation refuses a bare
    // number, but a summary line must not answer with `[object Object]`.
    const kind = builtinFieldKinds.get('ownerId')!;
    const field = fields.find(entry => entry.name === '@ownerId')!;

    expect(
      kind.describe({
        leaf: { field: '@ownerId', operator: 'OWNER_ID', value: 7 },
        field,
        kinds: builtinFieldKinds,
      }).text,
    ).toBe('Created by 7');
    expect(
      kind.describe({
        leaf: { field: '@ownerId', operator: 'OWNER_ID', value: { a: 1 } },
        field,
        kinds: builtinFieldKinds,
      }),
    ).toMatchObject({ text: 'Created by', value: { kind: 'blank' } });
  });

  it('refuses an id that is only whitespace', () => {
    // A typed blank is unfinished, not wrong, and never reaches validation.
    // One that arrives inside a picked candidate, or inside a finished list,
    // is an id Wow can look nothing up by.
    expect(
      errors(
        validateFilter(
          fields,
          tree({
            field: '@ownerPicked',
            operator: 'OWNER_ID',
            value: { items: [{ id: '   ', label: 'Ada' }] },
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.value.expected-id']);

    expect(
      errors(
        validateFilter(
          fields,
          tree({ field: '@id', operator: 'IDS', value: ['o-1', '  '] }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.value.expected-id-list']);
  });

  it('treats an empty id list as not yet filled in', () => {
    // Adding a row and choosing IDS puts `[]` in it. That is a question not
    // yet asked, not a mistake: it must neither block apply nor reach the
    // query.
    const leaf = { field: '@id', operator: 'IDS' as const, value: [] };
    expect(
      errors(validateFilter(fields, tree(leaf), builtinFieldKinds)),
    ).toEqual([]);
    expect(compile(leaf)).toEqual({ op: FilterOperator.MATCH_ALL });
    expect(describeFilter(fields, tree(leaf), builtinFieldKinds)).toEqual([]);
  });

  /**
   * Every metadata kind's own starting value, for every operator it offers:
   * `''` for a typed id, `[]` for a list of them, `{ items: [] }` where a
   * picker was declared. A kind's `isBlank` replaces the default rule, so a
   * kind that starts from three shapes must recognise all three, or the row
   * is reported as an error the moment it is added.
   */
  const starting = fields.flatMap(field => {
    const kind = builtinFieldKinds.get(field.kind)!;
    return kind.operators.map(
      operator =>
        [field.name, operator, kind.emptyValue(operator, field)] as [
          string,
          FilterOperatorName,
          FilterValue,
        ],
    );
  });

  it.each(starting)(
    'starts %s %s from a value that is not yet a condition',
    (field, operator, value) => {
      const leaf = { field, operator, value };
      expect(
        errors(validateFilter(fields, tree(leaf), builtinFieldKinds)),
      ).toEqual([]);
      expect(compile(leaf)).toEqual({ op: FilterOperator.MATCH_ALL });
      expect(describeFilter(fields, tree(leaf), builtinFieldKinds)).toEqual([]);
    },
  );

  it.each([
    ['@tenantId', 'TENANT_ID', '   '],
    ['@ownerId', 'OWNER_ID', null],
    ['@id', 'ID', null],
  ] as [string, FilterOperatorName, FilterValue][])(
    'reads %s %s %j as nothing typed',
    (field, operator, value) => {
      // Whitespace is nothing typed, as it is for a search; and a config from
      // a store may hold `null` where the editor would have written `''`.
      const leaf = { field, operator, value };
      expect(
        errors(validateFilter(fields, tree(leaf), builtinFieldKinds)),
      ).toEqual([]);
      expect(compile(leaf)).toEqual({ op: FilterOperator.MATCH_ALL });
    },
  );

  it('offers no plural operator where Wow has none', () => {
    for (const name of ['@tenantId', '@ownerId', '@spaceId']) {
      const field = fields.find(entry => entry.name === name);
      const kind = builtinFieldKinds.get(field!.kind);
      expect(kind?.operators).toHaveLength(1);
    }
  });

  it('offers no presence operators', () => {
    // `IS_NULL` carries a field name, and these kinds' names are labels
    // rather than paths; one leaf must not mean both.
    const kind = builtinFieldKinds.get('ownerId');
    expect(kind?.operators).not.toContain('IS_NULL');
  });
});
