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

import { FilterOperator, StringComparison } from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';
import {
  METADATA_FIELD_KIND_IDS,
  type FilterOperatorName,
} from '../src/model/index.js';
import {
  builtinFieldKinds,
  compileFilter,
  DATE_TIME_PRESETS,
  createFieldKindRegistry,
  describeFilter,
  fieldGroups,
  emptyFilter,
  isEmptyFilter,
  insertAt,
  isSimpleTree,
  MAX_RELATIVE_DATE_AMOUNT,
  mergeFilters,
  nodeAt,
  RELATIVE_DATE_UNITS,
  removeAt,
  resolveDateTimeBound,
  resolveDateTimeRange,
  sameFilterNode,
  sameFilterTree,
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
 * A window measured from now, in either direction. "The last 7 days" asks
 * what happened; "the next 7 days" asks what is due, and only the second was
 * inexpressible — `amount` had to be positive and the window always ran
 * backwards.
 */
describe('relative windows and named periods', () => {
  const resolve = (value: unknown) =>
    compileFilter(
      fields,
      tree({
        field: 'createdAt',
        operator: `${FilterOperator.BETWEEN}`,
        value: value as never,
      }),
      builtinFieldKinds,
      context,
    ) as { lowerBound: string; upperBound: string };

  it('runs a relative window backwards by default', () => {
    const past = resolve({ type: 'relative', amount: 7, unit: 'day' });

    expect(past.upperBound).toBe(context.now.toISOString());
    expect(Date.parse(past.lowerBound)).toBeLessThan(context.now.getTime());
  });

  it('runs it forwards when the condition says so', () => {
    const future = resolve({
      type: 'relative',
      amount: 7,
      unit: 'day',
      direction: 'future',
    });

    expect(future.lowerBound).toBe(context.now.toISOString());
    expect(Date.parse(future.upperBound)).toBeGreaterThan(
      context.now.getTime(),
    );
  });

  it('refuses a direction it does not know', () => {
    expect(
      errors(
        validateFilter(
          fields,
          tree({
            field: 'createdAt',
            operator: `${FilterOperator.BETWEEN}`,
            value: {
              type: 'relative',
              amount: 7,
              unit: 'day',
              direction: 'sideways',
            } as never,
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.value.expected-date']);
  });

  it.each(DATE_TIME_PRESETS)('resolves the %s period', preset => {
    const window = resolve({ type: 'preset', preset });

    // Every named period is a real window, and none of them is inverted.
    expect(Date.parse(window.lowerBound)).toBeLessThan(
      Date.parse(window.upperBound),
    );
  });

  it('places last, this and next in order', () => {
    const last = resolve({ type: 'preset', preset: 'lastMonth' });
    const current = resolve({ type: 'preset', preset: 'thisMonth' });
    const next = resolve({ type: 'preset', preset: 'nextMonth' });

    expect(Date.parse(last.upperBound)).toBeLessThan(
      Date.parse(current.lowerBound),
    );
    expect(Date.parse(current.upperBound)).toBeLessThan(
      Date.parse(next.lowerBound),
    );
  });

  it.each([
    [undefined, 'last 7 day'],
    ['past', 'last 7 day'],
    ['future', 'next 7 day'],
  ])('summarises a %s window as %s', (direction, want) => {
    // A forward window described as "last" would contradict the query that
    // actually ran, in the one place a user checks what is in force.
    expect(
      describeFilter(
        fields,
        tree({
          field: 'createdAt',
          operator: `${FilterOperator.BETWEEN}`,
          value: {
            type: 'relative',
            amount: 7,
            unit: 'day',
            ...(direction === undefined ? {} : { direction }),
          } as never,
        }),
        builtinFieldKinds,
      )[0].text,
    ).toContain(want);
  });

  it('summarises a period as words rather than as its key', () => {
    expect(
      describeFilter(
        fields,
        tree({
          field: 'createdAt',
          operator: `${FilterOperator.BETWEEN}`,
          value: { type: 'preset', preset: 'nextQuarter' } as never,
        }),
        builtinFieldKinds,
      )[0].text,
    ).toContain('next quarter');
  });

  it('keeps a quarter three months wide either side of this one', () => {
    const last = resolve({ type: 'preset', preset: 'lastQuarter' });
    const next = resolve({ type: 'preset', preset: 'nextQuarter' });

    // September 2026 sits in Q3, so its neighbours are Q2 and Q4.
    expect(last.lowerBound.slice(0, 7)).toBe('2026-04');
    expect(next.lowerBound.slice(0, 7)).toBe('2026-10');
  });
});

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

    // Midnight in Tokyo is 15:00 the previous day in UTC, and a day named
    // as the upper bound runs to its last millisecond.
    expect(tokyo).toMatchObject({
      lowerBound: '2025-12-31T15:00:00.000Z',
      upperBound: '2026-01-02T14:59:59.999Z',
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

/**
 * A bound written as a calendar day means the whole day. Resolving `to:
 * 2026-01-31` to that day's first instant made `BETWEEN 01-01..01-31` stop
 * before the 31st began, and `LTE 01-31` exclude the very day it named.
 */
describe('a calendar day as a bound', () => {
  const dayFields: FieldDefinition[] = [
    { name: 'orderedOn', label: 'Ordered', kind: 'date' },
  ];
  const shanghai = { ...context, timeZone: 'Asia/Shanghai' };
  const compileDay = (
    operator: FilterOperatorName,
    value: Record<string, unknown>,
  ) =>
    compileFilter(
      dayFields,
      tree({
        field: 'orderedOn',
        operator,
        value: { type: 'absolute', ...value },
      }),
      builtinFieldKinds,
      shanghai,
    );

  it('runs a range through the last millisecond of its final day', () => {
    expect(
      compileDay('BETWEEN', { from: '2026-01-01', to: '2026-01-31' }),
    ).toEqual({
      op: FilterOperator.BETWEEN,
      field: 'orderedOn',
      lowerBound: '2025-12-31T16:00:00.000Z',
      upperBound: '2026-01-31T15:59:59.999Z',
    });
  });

  it('takes "on or before a day" to the end of it, and "on or after" to its start', () => {
    expect(compileDay('LTE', { from: '2026-01-31' })).toMatchObject({
      op: FilterOperator.LTE,
      value: '2026-01-31T15:59:59.999Z',
    });
    expect(compileDay('GTE', { from: '2026-01-31' })).toMatchObject({
      op: FilterOperator.GTE,
      value: '2026-01-30T16:00:00.000Z',
    });
  });

  it('keeps a bound with a time of day at that time', () => {
    expect(
      compileDay('BETWEEN', { from: '2026-01-01', to: '2026-01-31T09:00' }),
    ).toMatchObject({ upperBound: '2026-01-31T01:00:00.000Z' });
    expect(compileDay('LTE', { from: '2026-01-31T09:00' })).toMatchObject({
      value: '2026-01-31T01:00:00.000Z',
    });
  });

  it('leaves a bound that names its own offset untouched', () => {
    expect(
      compileDay('BETWEEN', {
        from: '2026-01-01',
        to: '2026-01-31T00:00:00+08:00',
      }),
    ).toMatchObject({ upperBound: '2026-01-31T00:00:00+08:00' });
    expect(
      compileDay('LTE', { from: '2026-01-31T00:00:00-05:00' }),
    ).toMatchObject({ value: '2026-01-31T00:00:00-05:00' });
  });

  it('does not mistake the dashes of a plain date for an offset', () => {
    // Had `-01-31` matched as an offset, the day would have passed through
    // as-is instead of being read in the zone and widened to its end.
    const value = { type: 'absolute' as const, from: '2026-01-31' };
    expect(resolveDateTimeRange(value, context.now, 'Asia/Shanghai')).toEqual({
      from: '2026-01-30T16:00:00.000Z',
    });
    expect(
      resolveDateTimeBound(value, context.now, 'Asia/Shanghai', 'end'),
    ).toBe('2026-01-31T15:59:59.999Z');
  });

  it("still lets the condition's own zone win over the runtime's", () => {
    expect(
      compileDay('BETWEEN', {
        from: '2026-01-01',
        to: '2026-01-31',
        timeZone: 'Asia/Tokyo',
      }),
    ).toMatchObject({
      lowerBound: '2025-12-31T15:00:00.000Z',
      upperBound: '2026-01-31T14:59:59.999Z',
    });
  });

  it('gives a relative or preset value the edge asked for', () => {
    const today = { type: 'preset' as const, preset: 'today' as const };
    expect(resolveDateTimeBound(today, context.now, 'UTC', 'start')).toBe(
      '2026-09-16T00:00:00.000Z',
    );
    expect(resolveDateTimeBound(today, context.now, 'UTC', 'end')).toBe(
      '2026-09-16T23:59:59.999Z',
    );
  });
});

/**
 * Wow's logical operators are AND, OR and NOR, and a group is the only place
 * a configuration can say "none of these": a leaf negates through its own
 * operator, and a group has no operator to negate with.
 */
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

  it('hands the base back as it stands when there is nothing to merge', () => {
    // No wrapper an admission never saw: an `or` root stays an `or` root.
    const any: FilterTree = {
      op: 'or',
      children: [
        { field: 'id', operator: 'EQ', value: 'a' },
        { field: 'amount', operator: 'GT', value: 1 },
      ],
    };
    expect(mergeFilters(any)).toBe(any);
    expect(mergeFilters(any, null, emptyFilter())).toBe(any);
    expect(mergeFilters(null)).toEqual(emptyFilter());
  });

  it('does not drop a tree that lost its shape as if it were empty', () => {
    // A stored filter whose only entry is malformed says nothing valid, but
    // it is not empty: dropping it behind an injected scope would run the
    // query wider than the view was saved to be, and report nothing.
    const broken = { op: 'and', children: [null] } as unknown as FilterTree;
    const scope: FilterTree = {
      op: 'and',
      children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
    };

    expect(isEmptyFilter(broken)).toBe(false);
    expect(isEmptyFilter(emptyFilter())).toBe(true);
    const merged = mergeFilters(broken, scope);
    expect(merged.children).toHaveLength(2);
    expect(
      validateFilter(fields, merged, builtinFieldKinds).map(found => ({
        code: found.code,
        path: found.path,
      })),
    ).toEqual([{ code: 'filter.node.invalid', path: ['children', 0] }]);
  });
});

/**
 * A tree arrives from a store, so an entry that is not a node — `null`, a
 * number, an object with neither `children` nor `field` — is a finding at
 * its path, never a `TypeError` from the first pass to dereference it.
 */
describe('node and tree comparison', () => {
  const leaf = (value: unknown): FilterLeaf => ({
    field: 'id',
    operator: `${FilterOperator.EQ}`,
    value: value as FilterLeaf['value'],
  });

  it('compares a leaf by field, operator and value', () => {
    expect(sameFilterNode(leaf('a'), leaf('a'))).toBe(true);
    expect(sameFilterNode(leaf('a'), leaf('b'))).toBe(false);
    expect(
      sameFilterNode(leaf('a'), { ...leaf('a'), field: 'warehouse' }),
    ).toBe(false);
    expect(
      sameFilterNode(leaf('a'), {
        ...leaf('a'),
        operator: `${FilterOperator.NE}`,
      }),
    ).toBe(false);
  });

  it('reads a value that travelled through a store as the same value', () => {
    // A stored config comes back as new objects every time, so identity is
    // no answer: a list condition would read as edited on every render.
    expect(sameFilterNode(leaf(['a', 'b']), leaf(['a', 'b']))).toBe(true);
    expect(sameFilterNode(leaf(['a', 'b']), leaf(['b', 'a']))).toBe(false);
    expect(sameFilterNode(leaf(['a']), leaf(['a', 'b']))).toBe(false);
    expect(sameFilterNode(leaf(['a']), leaf('a'))).toBe(false);
    expect(
      sameFilterNode(leaf({ from: 1, to: 2 }), leaf({ to: 2, from: 1 })),
    ).toBe(true);
    expect(sameFilterNode(leaf({ from: 1 }), leaf({ from: 1, to: 2 }))).toBe(
      false,
    );
    expect(sameFilterNode(leaf({ from: 1 }), leaf({ to: 1 }))).toBe(false);
    expect(sameFilterNode(leaf(null), leaf('a'))).toBe(false);
  });

  it('compares a group by its operator, not by what is in it', () => {
    const or: FilterTree = { op: 'or', children: [leaf('a')] };
    expect(sameFilterNode(or, { op: 'or', children: [leaf('z')] })).toBe(true);
    expect(sameFilterNode(or, { op: 'and', children: [leaf('a')] })).toBe(
      false,
    );
    // A group and a leaf are never the same thing, whichever side it is on.
    expect(sameFilterNode(or, leaf('a'))).toBe(false);
    expect(sameFilterNode(leaf('a'), or)).toBe(false);
  });

  it('treats a missing node as unequal to any node', () => {
    expect(sameFilterNode(null, null)).toBe(true);
    expect(sameFilterNode(null, leaf('a'))).toBe(false);
    expect(sameFilterNode(leaf('a'), null)).toBe(false);
  });

  it('compares a whole tree, children and all', () => {
    const tree: FilterTree = {
      op: 'and',
      children: [leaf('a'), { op: 'or', children: [leaf('b')] }],
    };
    expect(sameFilterTree(tree, structuredClone(tree))).toBe(true);
    expect(
      sameFilterTree(tree, {
        op: 'and',
        children: [leaf('a'), { op: 'or', children: [leaf('z')] }],
      }),
    ).toBe(false);
    expect(sameFilterTree(tree, emptyFilter())).toBe(false);
  });

  /**
   * The editor asks what changed on every render, and it asks it of a
   * *draft* — something `validateFilter` has not admitted and may never
   * admit. A recursive comparison would exhaust the stack on one, which is a
   * crash during render rather than a finding.
   */
  it('answers a tree deeper than any stack without throwing', () => {
    const deep = (depth: number): FilterTree => {
      let node: FilterTree = { op: 'and', children: [leaf('a')] };
      for (let level = 0; level < depth; level += 1)
        node = { op: 'and', children: [node] };
      return node;
    };

    // Far past any call stack, and still under the node budget: a real
    // answer, arrived at iteratively.
    expect(sameFilterTree(deep(15_000), deep(15_000))).toBe(true);
    expect(sameFilterTree(deep(15_000), deep(15_001))).toBe(false);
    // Past the budget the answer is "not the same" — the only safe one, and
    // a tree the panel refuses to draw anyway.
    expect(sameFilterTree(deep(40_000), deep(40_000))).toBe(false);
    // The budget is a parameter, so a caller with tighter limits may say so.
    expect(sameFilterTree(deep(20), deep(20), 4)).toBe(false);
  });

  it('answers a cyclic value without walking it for ever', () => {
    const left: Record<string, unknown> = { from: 1 };
    left.self = left;
    const right: Record<string, unknown> = { from: 1 };
    right.self = right;

    // Two cycles that mean the same thing are still not the same answer a
    // finite walk can give, so the budget ends it at "no".
    expect(sameFilterNode(leaf(left), leaf(right))).toBe(false);
    // The same object is the same value without looking inside it at all.
    expect(sameFilterNode(leaf(left), leaf(left))).toBe(true);

    const cyclic = (): FilterTree => {
      const tree: FilterTree = { op: 'and', children: [] };
      tree.children.push(tree);
      return tree;
    };
    expect(sameFilterTree(cyclic(), cyclic())).toBe(false);
    const one = cyclic();
    expect(sameFilterTree(one, one)).toBe(true);
  });
});

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

/**
 * "7 days" is a distance from now. A window has one edge at now and one at
 * that distance, and a single bound at now is not what anyone typed, so a
 * relative value stands on its far edge whichever operator asks. A preset
 * is a calendar period and keeps the edge the operator asks for.
 */
describe('a relative value as a single bound', () => {
  const last = { type: 'relative', amount: 7, unit: 'day' };
  const next = { ...last, direction: 'future' };
  const today = { type: 'preset', preset: 'today' };
  const weekAgo = '2026-09-09T10:30:00.000Z';
  const weekAhead = '2026-09-23T10:30:00.000Z';
  const bound = (operator: 'GTE' | 'LTE', value: unknown) =>
    (
      compileFilter(
        fields,
        tree({ field: 'createdAt', operator, value: value as never }),
        builtinFieldKinds,
        context,
      ) as { value: string }
    ).value;
  const text = (operator: string, value: unknown) =>
    describeFilter(
      fields,
      tree({ field: 'createdAt', operator, value } as never),
      builtinFieldKinds,
    )[0].text;

  it('stands on the edge that is not now', () => {
    expect(bound('GTE', last)).toBe(weekAgo);
    expect(bound('LTE', last)).toBe(weekAgo);
    expect(bound('GTE', next)).toBe(weekAhead);
    expect(bound('LTE', next)).toBe(weekAhead);
    expect(resolveDateTimeBound(last as never, context.now, 'UTC', 'end')).toBe(
      weekAgo,
    );
  });

  it('keeps a preset on the edge asked for', () => {
    expect(bound('GTE', today)).toBe('2026-09-16T00:00:00.000Z');
    expect(bound('LTE', today)).toBe('2026-09-16T23:59:59.999Z');
  });

  it('says which instant it compares against', () => {
    expect(text('GTE', last)).toBe('Created on or after 7 day ago');
    expect(text('LTE', last)).toBe('Created on or before 7 day ago');
    expect(text('GTE', next)).toBe('Created on or after 7 day ahead');
    expect(text('LTE', next)).toBe('Created on or before 7 day ahead');
    expect(text('GTE', today)).toBe('Created on or after today');
    expect(text('LTE', today)).toBe('Created on or before today');
    expect(text('BETWEEN', last)).toBe('Created last 7 day');
  });

  it('names the bound an absolute value stands on', () => {
    const day = { type: 'absolute', from: '2026-01-01' };
    const range = { ...day, to: '2026-01-31' };
    expect(text('GTE', day)).toBe('Created on or after 2026-01-01');
    expect(text('LTE', day)).toBe('Created on or before 2026-01-01');
    expect(text('GTE', range)).toBe('Created on or after 2026-01-01');
    expect(text('LTE', range)).toBe('Created on or before 2026-01-31');
  });
});

/**
 * A relative amount past what a `Date` can hold made dayjs produce an
 * invalid instant, and `compileFilter` threw `RangeError` on a tree the
 * validator had admitted. The validator is the gate; the compiler is total.
 */
describe('an unbounded relative amount', () => {
  const huge = { type: 'relative', amount: 1e15, unit: 'day' } as const;
  const ahead = { ...huge, direction: 'future' } as const;
  const at = (operator: string, value: unknown) =>
    tree({ field: 'createdAt', operator, value } as never);

  it('is refused by validation, with the bound it crossed', () => {
    expect(
      validateFilter(fields, at('BETWEEN', huge), builtinFieldKinds),
    ).toEqual([
      {
        code: 'filter.value.relative-too-large',
        severity: 'error',
        path: ['children', 0],
        params: { max: MAX_RELATIVE_DATE_AMOUNT },
      },
    ]);
  });

  it('admits the bound itself in every unit and direction', () => {
    for (const unit of RELATIVE_DATE_UNITS)
      for (const direction of ['past', 'future']) {
        const value = {
          type: 'relative',
          amount: MAX_RELATIVE_DATE_AMOUNT,
          unit,
          direction,
        };
        expect(
          validateFilter(fields, at('BETWEEN', value), builtinFieldKinds),
        ).toEqual([]);
        const range = resolveDateTimeRange(value as never, context.now, 'UTC');
        expect(Number.isNaN(Date.parse(range.from))).toBe(false);
        expect(Number.isNaN(Date.parse(range.to as string))).toBe(false);
      }
  });

  it('does not make compilation throw', () => {
    const earliest = new Date(-8.64e15).toISOString();
    const latest = new Date(8.64e15).toISOString();

    expect(() =>
      compileFilter(fields, at('BETWEEN', huge), builtinFieldKinds, context),
    ).not.toThrow();
    expect(() =>
      compileFilter(fields, at('LTE', ahead), builtinFieldKinds, context),
    ).not.toThrow();
    expect(resolveDateTimeRange(huge, context.now, 'UTC')).toEqual({
      from: earliest,
      to: context.now.toISOString(),
    });
    expect(resolveDateTimeRange(ahead, context.now, 'UTC')).toEqual({
      from: context.now.toISOString(),
      to: latest,
    });
  });
});
