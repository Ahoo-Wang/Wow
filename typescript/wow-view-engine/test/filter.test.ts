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
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileFilter,
  createFieldKindRegistry,
  describeFilter,
  emptyFilter,
  insertAt,
  isSimpleTree,
  mergeFilters,
  nodeAt,
  removeAt,
  resolveDateTimeRange,
  updateAt,
  validateFilter,
  withFieldKinds,
  type FieldDefinition,
  type FieldKind,
  type FilterLeaf,
  type FilterTree,
} from '../src/index.js';

const fields: FieldDefinition[] = [
  { name: 'id', label: 'Order', kind: 'string' },
  { name: 'amount', label: 'Amount', kind: 'number' },
  { name: 'paid', label: 'Paid', kind: 'boolean' },
  {
    name: 'status',
    label: 'Status',
    kind: 'enum',
    options: [
      { value: 'PENDING', label: 'Pending' },
      { value: 'SHIPPED', label: 'Shipped' },
    ],
  },
  { name: 'createdAt', label: 'Created', kind: 'datetime' },
  {
    name: 'customer',
    label: 'Customer',
    kind: 'reference',
    remote: 'customers',
  },
];

const context = { now: new Date('2026-09-16T10:30:00.000Z'), timeZone: 'UTC' };

function tree(...children: FilterTree['children']): FilterTree {
  return { op: 'and', children };
}

function errors(issues: { severity: string; code: string }[]): string[] {
  return issues.filter(i => i.severity === 'error').map(i => i.code);
}

describe('validateFilter', () => {
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
      'filter.value.expected-number-range',
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

describe('isSimpleTree', () => {
  it('admits one AND group of leaves and nothing else', () => {
    expect(
      isSimpleTree(tree({ field: 'id', operator: 'EQ', value: 'A' })),
    ).toBe(true);
    expect(isSimpleTree({ op: 'or', children: [] })).toBe(false);
    expect(isSimpleTree(tree(tree()))).toBe(false);
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

describe('relative and preset dates', () => {
  const now = new Date('2026-09-16T10:30:00.000Z');

  it('evaluates a relative window against the injected moment', () => {
    const range = resolveDateTimeRange(
      { type: 'relative', amount: 7, unit: 'day' },
      now,
      'UTC',
    );
    expect(range).toEqual({
      from: '2026-09-09T10:30:00.000Z',
      to: '2026-09-16T10:30:00.000Z',
    });
  });

  it('keeps a quarter equal to three months', () => {
    const quarter = resolveDateTimeRange(
      { type: 'relative', amount: 1, unit: 'quarter' },
      now,
      'UTC',
    );
    const months = resolveDateTimeRange(
      { type: 'relative', amount: 3, unit: 'month' },
      now,
      'UTC',
    );
    expect(quarter).toEqual(months);
  });

  it('resolves presets on calendar boundaries of the given zone', () => {
    expect(
      resolveDateTimeRange({ type: 'preset', preset: 'today' }, now, 'UTC')
        .from,
    ).toBe('2026-09-16T00:00:00.000Z');

    // Shanghai is UTC+8, so its day started the previous UTC evening.
    expect(
      resolveDateTimeRange(
        { type: 'preset', preset: 'today' },
        now,
        'Asia/Shanghai',
      ).from,
    ).toBe('2026-09-15T16:00:00.000Z');

    // 16 September 2026 is a Wednesday; the ISO week starts on the Monday.
    expect(
      resolveDateTimeRange({ type: 'preset', preset: 'thisWeek' }, now, 'UTC')
        .from,
    ).toBe('2026-09-14T00:00:00.000Z');

    expect(
      resolveDateTimeRange(
        { type: 'preset', preset: 'thisQuarter' },
        now,
        'UTC',
      ),
    ).toEqual({
      from: '2026-07-01T00:00:00.000Z',
      to: '2026-09-30T23:59:59.999Z',
    });

    expect(
      resolveDateTimeRange({ type: 'preset', preset: 'yesterday' }, now, 'UTC'),
    ).toEqual({
      from: '2026-09-15T00:00:00.000Z',
      to: '2026-09-15T23:59:59.999Z',
    });
  });

  it('supports every relative unit', () => {
    const from = (unit: 'hour' | 'day' | 'week' | 'month' | 'year') =>
      resolveDateTimeRange({ type: 'relative', amount: 2, unit }, now, 'UTC')
        .from;
    expect(from('hour')).toBe('2026-09-16T08:30:00.000Z');
    expect(from('day')).toBe('2026-09-14T10:30:00.000Z');
    expect(from('week')).toBe('2026-09-02T10:30:00.000Z');
    expect(from('month')).toBe('2026-07-16T10:30:00.000Z');
    expect(from('year')).toBe('2024-09-16T10:30:00.000Z');
  });

  it('supports every calendar preset', () => {
    const range = (preset: 'thisMonth' | 'thisYear') =>
      resolveDateTimeRange({ type: 'preset', preset }, now, 'UTC');
    expect(range('thisMonth')).toEqual({
      from: '2026-09-01T00:00:00.000Z',
      to: '2026-09-30T23:59:59.999Z',
    });
    expect(range('thisYear')).toEqual({
      from: '2026-01-01T00:00:00.000Z',
      to: '2026-12-31T23:59:59.999Z',
    });
  });

  it('leaves an absolute value alone and allows an open upper bound', () => {
    expect(
      resolveDateTimeRange(
        { type: 'absolute', from: '2026-01-01T00:00:00.000Z' },
        now,
        'UTC',
      ),
    ).toEqual({ from: '2026-01-01T00:00:00.000Z' });
  });

  it('compiles an open-ended range to GTE and a closed one to BETWEEN', () => {
    const open = compileFilter(
      fields,
      tree({
        field: 'createdAt',
        operator: 'BETWEEN',
        value: { type: 'absolute', from: '2026-01-01T00:00:00.000Z' },
      }),
      builtinFieldKinds,
      context,
    );
    expect(open).toMatchObject({ op: FilterOperator.GTE, field: 'createdAt' });

    const closed = compileFilter(
      fields,
      tree({
        field: 'createdAt',
        operator: 'BETWEEN',
        value: { type: 'preset', preset: 'today' },
      }),
      builtinFieldKinds,
      context,
    );
    expect(closed).toMatchObject({
      op: FilterOperator.BETWEEN,
      field: 'createdAt',
      lowerBound: '2026-09-16T00:00:00.000Z',
    });
  });
});

/**
 * An absolute value is the one place a stored string still needs reading, and
 * for a while its `timeZone` was stored, validated and then ignored: two
 * conditions differing only by zone compiled to the same query.
 */
describe('absolute dates and their zone', () => {
  const between = (from: string, to: string, timeZone?: string) =>
    compileFilter(
      fields,
      tree({
        field: 'createdAt',
        operator: `${FilterOperator.BETWEEN}`,
        value: {
          type: 'absolute',
          from,
          to,
          ...(timeZone ? { timeZone } : {}),
        },
      }),
      builtinFieldKinds,
      context,
    );

  it('reads a wall-clock string in the zone the condition names', () => {
    const tokyo = between('2026-01-01', '2026-01-02', 'Asia/Tokyo');

    // Midnight in Tokyo is 15:00 the previous day in UTC.
    expect(tokyo).toMatchObject({
      lowerBound: '2025-12-31T15:00:00.000Z',
      upperBound: '2026-01-01T15:00:00.000Z',
    });
    expect(tokyo).not.toEqual(between('2026-01-01', '2026-01-02'));
  });

  it('falls back to the runtime zone when the condition names none', () => {
    expect(between('2026-01-01', '2026-01-02')).toMatchObject({
      lowerBound: '2026-01-01T00:00:00.000Z',
    });
  });

  it('leaves an instant that carries its own offset alone', () => {
    // `...Z` already names one moment; resolving it again would move it.
    expect(
      between('2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z', 'Asia/Tokyo'),
    ).toMatchObject({ lowerBound: '2026-01-01T00:00:00Z' });
  });

  it('refuses a zone no runtime can resolve', () => {
    const found = validateFilter(
      fields,
      tree({
        field: 'createdAt',
        operator: `${FilterOperator.GTE}`,
        value: { type: 'absolute', from: '2026-01-01', timeZone: 'Not/AZone' },
      }),
      builtinFieldKinds,
    );

    // Without this the compiler throws a RangeError where a query was due.
    expect(errors(found)).toEqual(['filter.value.unknown-time-zone']);
  });
});

describe('describeFilter', () => {
  it('summarises applied conditions with the field label', () => {
    const items = describeFilter(
      fields,
      tree(
        { field: 'status', operator: 'IN', value: ['PENDING'] },
        { field: 'amount', operator: 'BETWEEN', value: [1, 9] },
      ),
      builtinFieldKinds,
    );
    expect(items.map(item => item.text)).toEqual([
      'Status IN Pending',
      'Amount 1 ~ 9',
    ]);
    expect(items[0].path).toEqual(['children', 0]);
  });

  it('marks a condition whose field disappeared instead of hiding it', () => {
    const items = describeFilter(
      fields,
      tree({ field: 'gone', operator: 'EQ', value: 'x' }),
      builtinFieldKinds,
    );
    expect(items[0]).toMatchObject({ field: 'gone', unresolved: true });
  });

  it('marks a leaf the kind cannot read rather than throwing', () => {
    // A stored value the field no longer admits: `status` is an enum, whose
    // operators take a list. Summarising it must not take the view down.
    const items = describeFilter(
      fields,
      tree({ field: 'status', operator: 'EQ', value: 'PENDING' }),
      builtinFieldKinds,
    );

    expect(items[0]).toMatchObject({ field: 'status', unresolved: true });
    expect(items[0].text).toContain('EQ');
  });
});

describe('the field kind registry', () => {
  it('ships the seven built-in kinds', () => {
    expect([...builtinFieldKinds.keys()].sort()).toEqual([
      'boolean',
      'date',
      'datetime',
      'enum',
      'number',
      'reference',
      'string',
    ]);
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
      describe: ({ leaf, field }) => `${field.label} = ${String(leaf.value)}`,
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

describe('tree editing', () => {
  const leaf = (field: string, value: string): FilterLeaf => ({
    field,
    operator: `${FilterOperator.EQ}`,
    value,
  });

  const nested: FilterTree = {
    op: 'and',
    children: [
      leaf('id', 'a'),
      { op: 'or', children: [leaf('id', 'b'), leaf('id', 'c')] },
    ],
  };

  it('addresses a node by its path', () => {
    expect(nodeAt(nested, [])).toBe(nested);
    expect(nodeAt(nested, [0])).toMatchObject({ value: 'a' });
    expect(nodeAt(nested, [1, 1])).toMatchObject({ value: 'c' });
    expect(nodeAt(nested, [9])).toBeNull();
    // A leaf has no children to descend into.
    expect(nodeAt(nested, [0, 0])).toBeNull();
  });

  it('replaces a node deep in the tree without touching its siblings', () => {
    const next = updateAt(nested, [1, 0], node => ({
      ...(node as FilterLeaf),
      value: 'changed',
    }));

    expect(nodeAt(next, [1, 0])).toMatchObject({ value: 'changed' });
    expect(nodeAt(next, [1, 1])).toBe(nodeAt(nested, [1, 1]));
    expect(nodeAt(next, [0])).toBe(nodeAt(nested, [0]));
    expect(nested).toEqual(nested);
  });

  it('leaves the tree alone for a path that leads nowhere', () => {
    expect(updateAt(nested, [], () => null)).toBe(nested);
    expect(updateAt(nested, [9], () => null)).toEqual(nested);
    // Descending through a leaf is not a path.
    expect(updateAt(nested, [0, 0], () => null)).toEqual(nested);
  });

  it('removes a node at any depth', () => {
    expect(removeAt(nested, [1, 0])).toMatchObject({
      children: [{ value: 'a' }, { children: [{ value: 'c' }] }],
    });
    expect(removeAt(nested, [0]).children).toHaveLength(1);
  });

  it('appends to the root or to a nested group', () => {
    expect(insertAt(nested, [], leaf('id', 'd')).children).toHaveLength(3);
    expect(
      nodeAt(insertAt(nested, [1], leaf('id', 'd')), [1, 2]),
    ).toMatchObject({ value: 'd' });
    // A leaf is not a group, so there is nothing to append to.
    expect(insertAt(nested, [0], leaf('id', 'd'))).toEqual(nested);
  });

  it('merges trees by AND, flattening and dropping the empty ones', () => {
    const merged = mergeFilters(
      { op: 'and', children: [leaf('id', 'a')] },
      emptyFilter(),
      null,
      { op: 'or', children: [leaf('id', 'b')] },
    );

    expect(merged.op).toBe('and');
    expect(merged.children).toEqual([
      leaf('id', 'a'),
      { op: 'or', children: [leaf('id', 'b')] },
    ]);
    expect(mergeFilters()).toEqual(emptyFilter());
  });
});
