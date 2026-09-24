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

import { FilterOperator, SearchMode } from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileFilter,
  describeFilter,
  validateFilter,
} from '../src/filter/index.js';
import { validateDefinition } from '../src/runtime/index.js';
import { defaultRecordConfig, validateRecord } from '../src/record/index.js';
import type {
  FieldDefinition,
  FilterTree,
  FilterValue,
} from '../src/model/index.js';
import { ordersDefinition } from './fixtures.js';

/**
 * The box at the top of a list page. Wow's `SEARCH` names no field, so this
 * kind's `name` is a handle the way a metadata kind's is, and which fields
 * the query reads belongs to the definition.
 */
const fields: FieldDefinition[] = [
  { name: 'title', label: 'Title', kind: 'string' },
  { name: 'body', label: 'Body', kind: 'string' },
  { name: '@search', label: 'Search', kind: 'search' },
  {
    name: '@scoped',
    label: 'Search text',
    kind: 'search',
    searchFields: ['title', 'body'],
    searchMode: 'PHRASE',
  },
];

const context = { now: new Date('2026-09-17T00:00:00Z'), timeZone: 'UTC' };

const tree = (field: string, value: FilterValue): FilterTree => ({
  op: 'and',
  children: [{ field, operator: 'SEARCH', value }],
});

function errors(issues: { severity: string; code: string }[]): string[] {
  return issues.filter(i => i.severity === 'error').map(i => i.code);
}

const compile = (field: string, value: FilterValue) =>
  compileFilter(fields, tree(field, value), builtinFieldKinds, context);

describe('the search kind', () => {
  it('compiles to a filter that names no field', () => {
    expect(compile('@search', 'blue widget')).toEqual({
      op: FilterOperator.SEARCH,
      query: 'blue widget',
      mode: SearchMode.TERMS,
      fields: [],
    });
  });

  it('reads the fields and the mode the definition declared', () => {
    expect(compile('@scoped', 'blue widget')).toMatchObject({
      query: 'blue widget',
      mode: SearchMode.PHRASE,
      fields: ['title', 'body'],
    });
  });

  it('trims what it sends', () => {
    expect(compile('@search', '  spaced  ')).toMatchObject({
      query: 'spaced',
    });
  });

  it.each([[''], ['   ']])('treats %s as a question not yet asked', value => {
    // Wow refuses a blank query by throwing, and whitespace would pass the
    // general emptiness rule and reach it.
    expect(
      errors(validateFilter(fields, tree('@search', value), builtinFieldKinds)),
    ).toEqual([]);
    expect(compile('@search', value)).toEqual({
      op: FilterOperator.MATCH_ALL,
    });
  });

  it('admits a query someone actually typed', () => {
    expect(
      errors(
        validateFilter(fields, tree('@search', 'blue'), builtinFieldKinds),
      ),
    ).toEqual([]);
  });

  it('refuses a value that is not text', () => {
    expect(
      errors(validateFilter(fields, tree('@search', 7), builtinFieldKinds)),
    ).toEqual(['filter.value.expected-text']);
  });

  it('offers one operator and no presence questions', () => {
    // Its name is a handle, and `IS_NULL` carries a field name.
    const kind = builtinFieldKinds.get('search')!;

    expect(kind.operators).toEqual(['SEARCH']);
    expect(kind.emptyValue('SEARCH', fields[2])).toBe('');
    expect(kind.editor('SEARCH', fields[2])).toEqual({ input: 'text' });
  });

  it('summarises what was typed', () => {
    expect(
      describeFilter(fields, tree('@search', 'blue'), builtinFieldKinds).map(
        item => item.text,
      ),
    ).toEqual(['Search blue']);
  });
});

describe('a handle is not a record field', () => {
  const definition = ordersDefinition({
    fields: [
      { name: 'warehouse', label: 'W', kind: 'string' },
      { name: '@search', label: 'Search', kind: 'search' },
      { name: '@ownerId', label: 'Owner', kind: 'ownerId' },
    ],
    record: { rowKey: 'warehouse', paging: 'paged', layouts: ['table'] },
    analysis: undefined,
    views: [],
  });

  it('is left out of the default layout', () => {
    // A column on a handle reads nothing from a row and would be empty for
    // every record ever shown.
    const config = defaultRecordConfig(definition);

    expect(config.table.columns.map(column => column.field)).toEqual([
      'warehouse',
    ]);
    expect(config.card.fields).not.toContain('@search');
  });

  it('is refused as a column a saved config asks for', () => {
    expect(
      validateRecord(
        definition,
        defaultRecordConfig({
          ...definition,
          record: { ...definition.record!, layouts: ['table'] },
        }),
        builtinFieldKinds,
      ),
    ).toEqual([]);

    expect(
      validateRecord(
        definition,
        {
          ...defaultRecordConfig(definition),
          table: { columns: [{ field: '@search' }] },
        },
        builtinFieldKinds,
      ).map(found => found.code),
    ).toEqual(['record.field.not-a-column']);
  });
});

describe('a handle cannot be asked of one entry', () => {
  const withEntries: FieldDefinition[] = [
    { name: '@search', label: 'Search', kind: 'search' },
    {
      name: 'items',
      label: 'Items',
      kind: 'elementMatch',
      elements: [
        { name: 'sku', label: 'SKU', kind: 'string' },
        { name: 'code', label: 'Code', kind: 'documentId' },
        { name: 'text', label: 'Text', kind: 'search' },
      ],
    },
  ];

  it('refuses a search inside an element predicate', () => {
    // `ElementMatchFilter` refuses a root filter in its constructor, so a
    // definition the engine called usable would throw when the condition ran.
    const inner: FilterTree = {
      op: 'and',
      children: [{ field: 'items.text', operator: 'SEARCH', value: 'blue' }],
    };

    expect(
      validateFilter(
        withEntries,
        {
          op: 'and',
          children: [
            {
              field: 'items',
              operator: 'ELEMENT_MATCH',
              value: inner as never,
            },
          ],
        },
        builtinFieldKinds,
      ).map(found => found.code),
    ).toContain('filter.element.root-filter');
  });

  it('refuses a metadata handle the entries happen to declare', () => {
    const inner: FilterTree = {
      op: 'and',
      children: [{ field: 'items.code', operator: 'ID', value: 'x' }],
    };

    expect(
      validateFilter(
        withEntries,
        {
          op: 'and',
          children: [
            {
              field: 'items',
              operator: 'ELEMENT_MATCH',
              value: inner as never,
            },
          ],
        },
        builtinFieldKinds,
      ).map(found => found.code),
    ).toContain('filter.element.root-filter');
  });
});

describe('a search field is admitted with its definition', () => {
  const codes = (overrides: Partial<FieldDefinition>) =>
    validateDefinition(
      ordersDefinition({
        fields: [
          { name: 'warehouse', label: 'W', kind: 'string' },
          { name: '@search', label: 'Search', kind: 'search', ...overrides },
        ],
        record: undefined,
        analysis: undefined,
        views: [],
      }),
      builtinFieldKinds,
    ).map(found => found.code);

  it('accepts fields the definition declares', () => {
    expect(codes({ searchFields: ['warehouse'] })).toEqual([]);
  });

  it('refuses one it does not', () => {
    // `filter.search` would throw on it while compiling a query.
    expect(codes({ searchFields: ['warehouse', 'ghost'] })).toEqual([
      'definition.field.search-fields-unknown',
    ]);
  });

  it.each([['TERMS'], ['PHRASE']] as const)('accepts the %s mode', mode => {
    expect(codes({ searchMode: mode })).toEqual([]);
  });

  it('refuses a mode Wow does not know', () => {
    expect(codes({ searchMode: 'FUZZY' as never })).toEqual([
      'definition.field.search-mode-invalid',
    ]);
  });
});
