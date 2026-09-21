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

/**
 * What a filter is admitted and compiled as: the rules a tree is held to,
 * the Wow expression it becomes, the kinds and field groups an editor is
 * offered, and the malformed trees that must come back as issues rather than
 * as a TypeError. Time resolution lives in `filterTime.test.ts` and tree
 * editing in `filterTree.test.ts`.
 */

import { FilterOperator, StringComparison } from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';
import {
  METADATA_FIELD_KIND_IDS,
  type FilterOperatorName,
} from '../src/model/index.js';
import {
  builtinFieldKinds,
  compileFilter,
  createFieldKindRegistry,
  describeFilter,
  fieldGroups,
  emptyFilter,
  isSimpleTree,
  nodeAt,
  updateAt,
  validateFilter,
  validateViewConfigBase,
  withFieldKinds,
  type FieldDefinition,
  type FieldKind,
  type FilterLeaf,
  type FilterTree,
  type ViewConfigBase,
} from '../src/index.js';
import {
  andTree as tree,
  errorCodes as errors,
  filterContext as context,
  filterFields as fields,
} from './fixtures/filter.js';

describe('validateFilter', () => {
  it('refuses a field named twice in one group, under any operator', () => {
    const twice = (op: 'and' | 'or' | 'nor'): FilterTree => ({
      op,
      children: [
        { field: 'id', operator: 'EQ', value: 'a' },
        { field: 'amount', operator: 'GT', value: 1 },
        { field: 'id', operator: 'EQ', value: 'b' },
      ],
    });
    for (const op of ['and', 'or', 'nor'] as const)
      expect(validateFilter(fields, twice(op), builtinFieldKinds)).toEqual([
        expect.objectContaining({
          code: 'filter.field.duplicate-in-group',
          path: ['children', 2],
          params: { field: 'id' },
        }),
      ]);
  });

  it('holds a predicate to the rule even while it is still blank', () => {
    // The outer loop never enters a blank predicate — a blank condition is
    // not a mistake — but a field twice in it is a slip in the shape, not
    // in a value, and is found on the shape walk with the rest of the tree.
    const withItems: FieldDefinition[] = [
      ...fields,
      {
        name: 'items',
        label: 'Items',
        kind: 'elementMatch',
        elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
      },
    ];
    const twiceBlank: FilterTree = {
      op: 'and',
      children: [
        {
          field: 'items',
          operator: 'ELEMENT_MATCH',
          value: {
            op: 'and',
            children: [
              { field: 'items.sku', operator: 'EQ', value: '' },
              { field: 'items.sku', operator: 'EQ', value: '' },
            ],
          },
        },
      ],
    };
    expect(validateFilter(withItems, twiceBlank, builtinFieldKinds)).toEqual([
      expect.objectContaining({
        code: 'filter.field.duplicate-in-group',
        path: ['children', 0, 'children', 1],
      }),
    ]);
  });

  it('lets a field appear once per group, so a nested group asks again', () => {
    const nested: FilterTree = {
      op: 'and',
      children: [
        { field: 'id', operator: 'EQ', value: 'a' },
        {
          op: 'or',
          children: [{ field: 'id', operator: 'EQ', value: 'b' }],
        },
      ],
    };
    expect(validateFilter(fields, nested, builtinFieldKinds)).toEqual([]);
  });

  it('admits an empty tree', () => {
    expect(validateFilter(fields, emptyFilter(), builtinFieldKinds)).toEqual(
      [],
    );
  });

  it('reports an unknown field and an unsupported operator', () => {
    const issues = validateFilter(
      fields,
      tree(
        { field: 'missing', operator: 'EQ', value: 'x' },
        { field: 'amount', operator: 'CONTAINS', value: 'x' },
      ),
      builtinFieldKinds,
    );
    expect(errors(issues)).toEqual([
      'filter.field.unknown',
      'filter.operator.unsupported',
    ]);
  });

  it('reports an unregistered kind instead of silently degrading', () => {
    const custom: FieldDefinition[] = [
      { name: 'colour', label: 'Colour', kind: 'colour' },
    ];
    const issues = validateFilter(
      custom,
      tree({ field: 'colour', operator: 'EQ', value: 'red' }),
      builtinFieldKinds,
    );
    expect(errors(issues)).toEqual(['filter.kind.unregistered']);
  });

  it('narrows the operator set with the field declaration', () => {
    const narrowed: FieldDefinition[] = [
      { name: 'id', label: 'Order', kind: 'string', operators: ['EQ'] },
    ];
    expect(
      errors(
        validateFilter(
          narrowed,
          tree({ field: 'id', operator: 'CONTAINS', value: 'A' }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.operator.unsupported']);
  });

  it('checks each kind against its own value shape', () => {
    const issues = validateFilter(
      fields,
      tree(
        { field: 'amount', operator: 'BETWEEN', value: [10, 5] },
        { field: 'paid', operator: 'EQ', value: 'yes' },
        { field: 'status', operator: 'IN', value: ['UNKNOWN'] },
        { field: 'createdAt', operator: 'BETWEEN', value: { type: 'nope' } },
      ),
      builtinFieldKinds,
    );
    expect(errors(issues)).toEqual([
      'filter.value.inverted-range',
      'filter.value.expected-boolean',
      'filter.value.unknown-option',
      'filter.value.expected-date',
    ]);
  });

  it('rejects an oversized tree before anything recurses over it', () => {
    let deep: FilterTree = tree({ field: 'id', operator: 'EQ', value: 'A' });
    for (let depth = 0; depth < 20; depth += 1) deep = tree(deep);

    const issues = validateFilter(fields, deep, builtinFieldKinds);
    expect(errors(issues)).toEqual(['filter.tree.too-deep']);

    const wide = tree(
      ...Array.from({ length: 300 }, () => ({
        field: 'id',
        operator: 'EQ' as const,
        value: 'A',
      })),
    );
    expect(errors(validateFilter(fields, wide, builtinFieldKinds))).toEqual([
      'filter.tree.too-many-nodes',
    ]);
  });

  it('honours limits supplied by the caller', () => {
    const nested = tree(tree({ field: 'id', operator: 'EQ', value: 'A' }));
    expect(
      errors(
        validateFilter(fields, nested, builtinFieldKinds, {
          limits: { maxFilterDepth: 2, maxFilterNodes: 100 },
        }),
      ),
    ).toEqual(['filter.tree.too-deep']);
  });
});

describe('compileFilter', () => {
  it('compiles an empty tree to MATCH_ALL', () => {
    expect(
      compileFilter(fields, emptyFilter(), builtinFieldKinds, context),
    ).toEqual({ op: FilterOperator.MATCH_ALL });
  });

  it('unwraps a single condition and keeps the group operator otherwise', () => {
    const single = compileFilter(
      fields,
      tree({ field: 'id', operator: 'EQ', value: 'A1' }),
      builtinFieldKinds,
      context,
    );
    expect(single).toMatchObject({ op: FilterOperator.EQ, field: 'id' });

    const pair = compileFilter(
      fields,
      {
        op: 'or',
        children: [
          { field: 'id', operator: 'EQ', value: 'A1' },
          { field: 'amount', operator: 'GT', value: 10 },
        ],
      },
      builtinFieldKinds,
      context,
    );
    expect(pair).toMatchObject({ op: FilterOperator.OR });
  });

  it('drops empty groups, which Wow would reject as operands', () => {
    const compiled = compileFilter(
      fields,
      tree(tree(), { field: 'id', operator: 'EQ', value: 'A1' }),
      builtinFieldKinds,
      context,
    );
    expect(compiled).toMatchObject({ op: FilterOperator.EQ });
  });

  it('maps each kind onto its Wow operator', () => {
    const compiled = compileFilter(
      fields,
      tree(
        { field: 'status', operator: 'IN', value: ['PENDING'] },
        { field: 'amount', operator: 'BETWEEN', value: [1, 9] },
        {
          field: 'customer',
          operator: 'IN',
          value: { items: [{ id: 7, label: 'ACME' }] },
        },
        { field: 'id', operator: 'IS_NULL', value: null },
      ),
      builtinFieldKinds,
      context,
    );
    expect(compiled).toMatchObject({
      op: FilterOperator.AND,
      operands: [
        { op: FilterOperator.IN, field: 'status', values: ['PENDING'] },
        { op: FilterOperator.BETWEEN, field: 'amount' },
        { op: FilterOperator.IN, field: 'customer', values: [7] },
        { op: FilterOperator.IS_NULL, field: 'id' },
      ],
    });
  });

  it('throws on a tree that never passed validation', () => {
    expect(() =>
      compileFilter(
        fields,
        tree({ field: 'missing', operator: 'EQ', value: 'A' }),
        builtinFieldKinds,
        context,
      ),
    ).toThrow(/undeclared field/);
  });
});

/**
 * Text matching is a search, not an exact match, so it ignores case unless a
 * field says its case carries meaning. Before this, `filter.contains` was
 * called without the third argument and Wow's default made every text filter
 * case-sensitive, with no way to change it.
 */
describe('text comparison', () => {
  const contains = (fieldName: string, over: FieldDefinition[] = fields) =>
    compileFilter(
      over,
      tree({
        field: fieldName,
        operator: `${FilterOperator.CONTAINS}`,
        value: 'cn',
      }),
      builtinFieldKinds,
      context,
    );

  it('ignores case by default', () => {
    expect(contains('id')).toMatchObject({
      stringComparison: StringComparison.CASE_INSENSITIVE,
    });
  });

  it('respects a field that pins itself to exact case', () => {
    const cased: FieldDefinition[] = [
      {
        name: 'sku',
        label: 'SKU',
        kind: 'string',
        stringComparison: 'CASE_SENSITIVE',
      },
    ];

    expect(contains('sku', cased)).toMatchObject({
      stringComparison: StringComparison.CASE_SENSITIVE,
    });
  });

  const textOperators: [FilterOperatorName][] = [
    [`${FilterOperator.STARTS_WITH}`],
    [`${FilterOperator.ENDS_WITH}`],
  ];

  it.each(textOperators)('applies to %s as well', operator => {
    expect(
      compileFilter(
        fields,
        tree({ field: 'id', operator, value: 'cn' }),
        builtinFieldKinds,
        context,
      ),
    ).toMatchObject({ stringComparison: StringComparison.CASE_INSENSITIVE });
  });
});

/**
 * A condition the user has not finished writing is an ordinary state of an
 * editor, not a mistake. Before this, picking a field either reported an
 * error before the user could say anything — string, enum — or silently
 * applied a condition nobody asked for: `amount = 0`, `paid = true`,
 * "created today".
 */
describe('unfinished conditions', () => {
  const leaf = (field: string, operator: string, value: unknown) =>
    tree({ field, operator, value } as never);

  it.each([
    ['id', `${FilterOperator.EQ}`, ''],
    ['id', `${FilterOperator.IN}`, []],
    ['amount', `${FilterOperator.EQ}`, null],
    ['amount', `${FilterOperator.BETWEEN}`, null],
    ['paid', `${FilterOperator.EQ}`, null],
    ['createdAt', `${FilterOperator.BETWEEN}`, null],
  ])('admits %s left unfilled', (field, operator, value) => {
    expect(
      errors(
        validateFilter(fields, leaf(field, operator, value), builtinFieldKinds),
      ),
    ).toEqual([]);
  });

  it('keeps an unfilled condition out of the query', () => {
    // Not `amount = 0`, and not an error either: simply not yet a condition.
    expect(
      compileFilter(
        fields,
        leaf('amount', `${FilterOperator.EQ}`, null),
        builtinFieldKinds,
        context,
      ),
    ).toEqual({ op: FilterOperator.MATCH_ALL });
  });

  it('runs the conditions that are finished alongside one that is not', () => {
    const mixed = tree(
      { field: 'id', operator: `${FilterOperator.EQ}`, value: 'o-1' },
      {
        field: 'amount',
        operator: `${FilterOperator.EQ}`,
        value: null,
      } as never,
    );

    expect(
      compileFilter(fields, mixed, builtinFieldKinds, context),
    ).toMatchObject({
      op: FilterOperator.EQ,
      field: 'id',
      value: 'o-1',
    });
  });

  it('leaves an unfilled condition out of the applied summary', () => {
    const mixed = tree(
      { field: 'id', operator: `${FilterOperator.EQ}`, value: 'o-1' },
      {
        field: 'amount',
        operator: `${FilterOperator.EQ}`,
        value: null,
      } as never,
    );

    expect(
      describeFilter(fields, mixed, builtinFieldKinds).map(item => item.field),
    ).toEqual(['id']);
  });

  it('still refuses a value the kind cannot read', () => {
    // Unfilled is not the same as wrong, and only one of them is forgiven.
    expect(
      errors(
        validateFilter(
          fields,
          leaf('id', `${FilterOperator.EQ}`, 7),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.value.expected-string']);
  });

  it('never treats a presence condition as unfilled', () => {
    // `IS_NULL` is the whole condition; dropping it for looking empty would
    // delete what the user asked for.
    const presence = leaf('id', `${FilterOperator.IS_NULL}`, null);

    expect(errors(validateFilter(fields, presence, builtinFieldKinds))).toEqual(
      [],
    );
    expect(
      compileFilter(fields, presence, builtinFieldKinds, context),
    ).toMatchObject({
      op: FilterOperator.IS_NULL,
      field: 'id',
    });
  });
});

/**
 * Wow's logical operators are AND, OR and NOR, and a group is the only place
 * a configuration can say "none of these": a leaf negates through its own
 * operator, and a group has no operator to negate with.
 */

describe('nor groups', () => {
  const norTree = (...children: FilterTree['children']): FilterTree => ({
    op: 'nor',
    children,
  });
  const order = (value: string) => ({
    field: 'id',
    operator: `${FilterOperator.EQ}` as const,
    value,
  });

  it('is admitted like the other two', () => {
    expect(
      errors(validateFilter(fields, norTree(order('o-1')), builtinFieldKinds)),
    ).toEqual([]);
  });

  it('compiles to a NOR expression', () => {
    expect(
      compileFilter(
        fields,
        norTree(order('o-1'), order('o-2')),
        builtinFieldKinds,
        context,
      ),
    ).toMatchObject({
      op: FilterOperator.NOR,
      operands: [
        { op: FilterOperator.EQ, field: 'id', value: 'o-1' },
        { op: FilterOperator.EQ, field: 'id', value: 'o-2' },
      ],
    });
  });

  it('keeps its wrapper around a single child', () => {
    // An `and` or an `or` of one is that one condition, but a `nor` of one is
    // its negation: collapsing it would compile to exactly what it excludes.
    expect(
      compileFilter(fields, norTree(order('o-1')), builtinFieldKinds, context),
    ).toMatchObject({
      op: FilterOperator.NOR,
      operands: [{ op: FilterOperator.EQ, field: 'id', value: 'o-1' }],
    });
  });

  it('carries no condition when it is empty', () => {
    expect(
      compileFilter(fields, norTree(), builtinFieldKinds, context),
    ).toEqual({ op: FilterOperator.MATCH_ALL });
  });

  it('is not a simple tree', () => {
    // Simple mode shows one AND group of leaves; a negation is not that.
    expect(isSimpleTree(norTree(order('o-1')))).toBe(false);
  });

  it('still refuses an operator that is none of the three', () => {
    expect(
      errors(
        validateFilter(
          fields,
          { op: 'xor' as never, children: [order('o-1')] },
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.group.unknown-operator']);
  });
});

describe('fieldGroups', () => {
  const catalogue = [
    { id: 'money', label: 'Money', fields: ['amount', 'currency'] },
    { id: 'time', label: 'Time', fields: ['createdAt'] },
    { id: 'unused', label: 'Unused', fields: ['nobody'] },
  ];
  const name = (item: { name: string }) => item.name;

  it('lists the ungrouped fields first, then each group in its own order', () => {
    const grouped = fieldGroups(
      [
        { name: 'id' },
        { name: 'createdAt' },
        { name: 'currency' },
        { name: 'status' },
        { name: 'amount' },
      ],
      catalogue,
      name,
    );
    expect(
      grouped.map(entry => [
        entry.group?.label,
        entry.items.map(item => item.name),
      ]),
    ).toEqual([
      [undefined, ['id', 'status']],
      ['Money', ['amount', 'currency']],
      ['Time', ['createdAt']],
    ]);
  });

  it('keeps a field where it was listed first', () => {
    // Admission reports the definition; the picker does not list it twice.
    const twice = [
      { id: 'a', label: 'A', fields: ['x', 'x'] },
      { id: 'b', label: 'B', fields: ['x'] },
    ];
    expect(
      fieldGroups([{ name: 'x' }], twice, name).map(entry => [
        entry.group?.id,
        entry.items.length,
      ]),
    ).toEqual([['a', 1]]);
  });

  it('is one nameless group without a catalogue', () => {
    expect(fieldGroups([{ name: 'a' }, { name: 'b' }], [], name)).toEqual([
      { group: undefined, items: [{ name: 'a' }, { name: 'b' }] },
    ]);
    expect(fieldGroups([], catalogue, name)).toEqual([]);
  });
});

describe('the field kind registry', () => {
  it('ships the document-field kinds and the metadata kinds', () => {
    expect([...builtinFieldKinds.keys()].sort()).toEqual(
      [
        'boolean',
        'date',
        'datetime',
        'enum',
        'number',
        'reference',
        'string',
        'array',
        'elementMatch',
        'search',
        ...METADATA_FIELD_KIND_IDS,
      ].sort(),
    );
  });

  it('takes a custom kind without the kernel knowing anything about it', () => {
    const colour: FieldKind = {
      id: 'colour',
      operators: ['EQ'],
      defaultOperator: 'EQ',
      emptyValue: () => '#000000',
      validate: ({ value, path }) =>
        typeof value === 'string' && /^#[0-9a-f]{6}$/i.test(value)
          ? []
          : [{ code: 'colour.invalid', severity: 'error', path }],
      compile: ({ leaf, field }) => ({
        op: FilterOperator.EQ,
        field: field.name,
        value: leaf.value as string,
      }),
      editor: () => ({ input: 'text' }),
      describe: ({ leaf, field }) => ({
        text: `${field.label} = ${String(leaf.value)}`,
        value: { kind: 'text', value: String(leaf.value) },
      }),
    };

    const kinds = withFieldKinds(builtinFieldKinds, [colour]);
    const custom: FieldDefinition[] = [
      { name: 'colour', label: 'Colour', kind: 'colour' },
    ];
    const valid = tree({ field: 'colour', operator: 'EQ', value: '#ff8800' });

    expect(validateFilter(custom, valid, kinds)).toEqual([]);
    expect(compileFilter(custom, valid, kinds, context)).toMatchObject({
      op: FilterOperator.EQ,
      value: '#ff8800',
    });
    expect(
      errors(
        validateFilter(
          custom,
          tree({ field: 'colour', operator: 'EQ', value: 'red' }),
          kinds,
        ),
      ),
    ).toEqual(['colour.invalid']);
    // The built-in registry is untouched.
    expect(builtinFieldKinds.has('colour')).toBe(false);
  });

  it('builds a registry from scratch', () => {
    expect(createFieldKindRegistry([]).size).toBe(0);
  });
});

/**
 * A tree arrives from a store, so an entry that is not a node — `null`, a
 * number, an object with neither `children` nor `field` — is a finding at
 * its path, never a `TypeError` from the first pass to dereference it.
 */

describe('malformed trees', () => {
  const order = (id: string): FilterLeaf => ({
    field: 'id',
    operator: 'EQ',
    value: id,
  });
  const malformed = (...children: unknown[]): FilterTree =>
    ({ op: 'and', children }) as unknown as FilterTree;

  it.each([
    ['null', null],
    ['undefined', undefined],
    ['a number', 5],
    ['an array', []],
    ['an object with neither children nor field', { op: 'and' }],
    ['a leaf whose field is not a string', { field: 7, operator: 'EQ' }],
    ['a leaf without an operator', { field: 'id', value: 'x' }],
  ])('reports %s at its path instead of throwing', (_name, child) => {
    expect(
      validateFilter(fields, malformed(order('o-1'), child), builtinFieldKinds),
    ).toEqual([
      { code: 'filter.node.invalid', severity: 'error', path: ['children', 1] },
    ]);
  });

  it('reports a root that is not a group', () => {
    for (const root of [null, undefined, 5, order('o-1'), { op: 'and' }])
      expect(validateFilter(fields, root as never, builtinFieldKinds)).toEqual([
        { code: 'filter.node.invalid', severity: 'error', path: [] },
      ]);
  });

  it('finds every malformed entry, at any depth, in one round', () => {
    const found = validateFilter(
      fields,
      malformed(null, { op: 'or', children: [order('o-1'), 'x'] }),
      builtinFieldKinds,
    );
    expect(found.map(i => i.path)).toEqual([
      ['children', 0],
      ['children', 1, 'children', 1],
    ]);
  });

  it('charges a malformed entry to the node budget', () => {
    const nulls = Array.from({ length: 300 }, () => null);
    expect(
      errors(validateFilter(fields, malformed(...nulls), builtinFieldKinds)),
    ).toEqual(['filter.tree.too-many-nodes']);
  });

  it('is skipped by the walk, so a summary and a query survive it', () => {
    const damaged = malformed(null, order('o-1'), 5);

    expect(
      describeFilter(fields, damaged, builtinFieldKinds).map(i => i.field),
    ).toEqual(['id']);
    expect(compileFilter(fields, damaged, builtinFieldKinds, context)).toEqual(
      compileFilter(fields, tree(order('o-1')), builtinFieldKinds, context),
    );
    expect(
      compileFilter(fields, null as never, builtinFieldKinds, context),
    ).toEqual(compileFilter(fields, emptyFilter(), builtinFieldKinds, context));
  });

  it('is never a simple tree and never a node the editor can address', () => {
    const damaged = malformed(null, order('o-1'));

    expect(isSimpleTree(damaged)).toBe(false);
    expect(isSimpleTree(null as never)).toBe(false);
    expect(nodeAt(damaged, [0])).toBeNull();
    expect(nodeAt(damaged, [1])).toEqual(order('o-1'));
    expect(updateAt(damaged, [0], () => null).children).toBe(damaged.children);
  });
});

/**
 * The shared part of a config is read by every kernel, so a missing or
 * unreadable member is reported at its path rather than dereferenced.
 */
describe('validateViewConfigBase', () => {
  const check = (overrides: Record<string, unknown>) =>
    validateViewConfigBase(
      fields,
      {
        filter: emptyFilter(),
        filterMode: 'simple',
        refresh: { interval: null },
        ...overrides,
      } as unknown as ViewConfigBase,
      builtinFieldKinds,
    );

  it('reports a filter that is not a group', () => {
    const roots = [undefined, null, 5, 'x', [], { op: 'and' }];
    for (const filter of [...roots, { field: 'id', operator: 'EQ' }])
      expect(check({ filter })).toEqual([
        { code: 'config.filter.invalid', severity: 'error', path: ['filter'] },
      ]);
  });

  it('reports a filter mode that is neither of the two', () => {
    for (const filterMode of [undefined, null, 5, 'plain'])
      expect(errors(check({ filterMode }))).toEqual([
        'config.filterMode.unknown',
      ]);
  });

  it('reports a refresh setting that is not there to read', () => {
    for (const refresh of [undefined, null, 5, 'x', []])
      expect(check({ refresh }).map(i => i.code)).toEqual([
        'config.refresh.missing',
      ]);
    for (const interval of [undefined, 'x', true, {}])
      expect(check({ refresh: { interval } }).map(i => i.code)).toEqual([
        'config.refresh.not-an-integer',
      ]);
  });

  it('reports a config that is not an object at all', () => {
    for (const config of [null, undefined, 5, 'x', []])
      expect(
        validateViewConfigBase(fields, config as never, builtinFieldKinds),
      ).toEqual([{ code: 'config.invalid', severity: 'error', path: [] }]);
  });
});
