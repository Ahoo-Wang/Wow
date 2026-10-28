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
  filter as wow,
  FilterOperator,
  TimeUnit,
} from '@ahoo-wang/wow-client/dsl';
import { describe, expect, it } from 'vitest';
import {
  FIELDLESS_FIELD_KIND_IDS,
  isFieldlessKind,
} from '../src/model/index.js';
import {
  booleanFieldKind,
  clearFilter,
  compileFilter,
  compilePresence,
  countLeaves,
  dateFieldKind,
  dateTimeFieldKind,
  describePresence,
  emptyFilter,
  enumFieldKind,
  filterFields,
  isDateTimeFilterValue,
  isEmptyFilter,
  isExecutableFilter,
  isFilterGroup,
  isFilterLeaf,
  isNumberRange,
  isReferenceFilterValue,
  numberFieldKind,
  operatorsOf,
  readValue,
  referenceFieldKind,
  stringFieldKind,
  validateFilter,
  builtinFieldKinds,
  type FieldDefinition,
  type FieldKind,
  type FilterLeaf,
  type FilterOperatorName,
  type FilterTree,
} from '../src/index.js';
import { isPlainObject } from '../src/filter/values.js';

const now = new Date('2026-09-16T10:30:00.000Z');
const timeZone = 'UTC';

function field(overrides: Partial<FieldDefinition> = {}): FieldDefinition {
  return { name: 'value', label: 'Value', kind: 'string', ...overrides };
}

function leaf(operator: FilterOperatorName, value: unknown): FilterLeaf {
  return { field: 'value', operator, value: value as FilterLeaf['value'] };
}

function compile(kind: FieldKind, one: FilterLeaf, def = field()) {
  return kind.compile({
    leaf: one,
    field: def,
    kinds: builtinFieldKinds,
    now,
    timeZone,
  });
}

function codes(
  kind: FieldKind,
  value: unknown,
  operator: FilterOperatorName,
  def = field(),
) {
  return kind
    .validate({
      value,
      operator,
      field: def,
      kinds: builtinFieldKinds,
      path: [],
    })
    .map(i => i.code);
}

/** The kinds that hold a list, and ask about its entries. */
const LIST_KINDS = ['array', 'elementMatch'];

describe('presence operators', () => {
  it('compile and describe without reading a value', () => {
    for (const operator of [
      FilterOperator.IS_NULL,
      FilterOperator.IS_NOT_NULL,
      FilterOperator.EXISTS,
      FilterOperator.NOT_EXISTS,
    ] as FilterOperatorName[]) {
      expect(compilePresence('value', operator)).toMatchObject({
        field: 'value',
      });
      expect(describePresence(operator)).toBeTruthy();
    }
    expect(compilePresence('value', FilterOperator.EQ)).toBeNull();
    expect(describePresence(FilterOperator.EQ)).toBeNull();
  });

  /**
   * A list asks 「没有条目」 and 「有条目」 instead (`entries.ts`, second
   * review R1-P1-7): the four presence questions beside `IS_EMPTY` were five
   * empties nobody could tell apart.
   */
  it('are asked of a list as questions of its entries', () => {
    for (const id of LIST_KINDS) {
      const kind = builtinFieldKinds.get(id)!;
      expect(kind.operators, id).toEqual(
        expect.arrayContaining([
          FilterOperator.IS_EMPTY,
          FilterOperator.IS_NOT_NULL,
        ]),
      );
      for (const operator of [
        FilterOperator.IS_NULL,
        FilterOperator.EXISTS,
        FilterOperator.NOT_EXISTS,
      ])
        expect(kind.operators, id).not.toContain(operator);
      expect(kind.relations?.[FilterOperator.IS_NOT_NULL], id).toBe(
        'has-entries',
      );
    }
  });

  it('are withheld by exactly the kinds that name no field', () => {
    // The rule is not "metadata": it is that `IS_NULL` carries a field name
    // and these kinds' names are handles for the editor. Search joined them
    // for the same reason, which is why the list says what it means.
    const without = [...builtinFieldKinds.values()]
      .filter(kind => !kind.operators.includes(FilterOperator.IS_NULL))
      .map(kind => kind.id)
      .sort();

    expect(without).toEqual(
      [...FIELDLESS_FIELD_KIND_IDS, ...LIST_KINDS].sort(),
    );
  });

  it('say so on the kind, so a custom one can join them', () => {
    // The id list cannot have heard of an application's own kind, and a
    // custom kind that compiles to `SEARCH` or a metadata filter is a root
    // filter too — Wow throws when one reaches an element predicate.
    const flagged = [...builtinFieldKinds.values()]
      .filter(kind => kind.fieldless)
      .map(kind => kind.id)
      .sort();

    expect(flagged).toEqual([...FIELDLESS_FIELD_KIND_IDS].sort());
    expect(isFieldlessKind('colour')).toBe(false);
    expect(isFieldlessKind('colour', { fieldless: true })).toBe(true);
  });

  it('are offered by every kind that names a document field', () => {
    for (const kind of builtinFieldKinds.values()) {
      // The field-less kinds are the exception, and deliberately: `IS_NULL`
      // carries a field name while their own `name` is a label, so one leaf
      // would mean two different things depending on its operator.
      if (FIELDLESS_FIELD_KIND_IDS.includes(kind.id)) continue;
      if (LIST_KINDS.includes(kind.id)) continue;
      expect(kind.operators).toContain(FilterOperator.IS_NULL);
      expect(
        kind.validate({
          value: null,
          operator: FilterOperator.IS_NULL,
          field: field(),
          kinds: builtinFieldKinds,
          path: [],
        }),
      ).toEqual([]);
      expect(kind.editor(FilterOperator.IS_NULL, field())).toEqual({
        input: 'none',
      });
      expect(
        kind.describe({
          leaf: leaf(FilterOperator.IS_NULL, null),
          field: field(),
          kinds: builtinFieldKinds,
        }).text,
      ).toContain('Value');
    }
  });
});

describe('string kind', () => {
  const kind = stringFieldKind;

  it('starts unfilled, whatever the operator', () => {
    // A row the user has only just added has not asked anything yet, so its
    // starting value must not narrow the list.
    expect(kind.emptyValue(FilterOperator.EQ, field())).toBe('');
    expect(kind.emptyValue(FilterOperator.IN, field())).toEqual([]);
  });

  it('accepts a string, a list, and the emptiness operators', () => {
    expect(codes(kind, 'A', FilterOperator.EQ)).toEqual([]);
    expect(codes(kind, ['A', 'B'], FilterOperator.IN)).toEqual([]);
    expect(codes(kind, null, FilterOperator.IS_EMPTY_STRING)).toEqual([]);
    expect(codes(kind, 7, FilterOperator.EQ)).toEqual([
      'filter.value.expected-string',
    ]);
    expect(codes(kind, [7], FilterOperator.IN)).toEqual([
      'filter.value.expected-string-list',
    ]);
    expect(codes(kind, [], FilterOperator.IN)).toEqual([
      'filter.value.required',
    ]);
  });

  it('maps every operator onto Wow', () => {
    const expected: [FilterOperatorName, FilterOperator, unknown][] = [
      [FilterOperator.EQ, FilterOperator.EQ, 'A'],
      [FilterOperator.NE, FilterOperator.NE, 'A'],
      [FilterOperator.CONTAINS, FilterOperator.CONTAINS, 'A'],
      [FilterOperator.STARTS_WITH, FilterOperator.STARTS_WITH, 'A'],
      [FilterOperator.ENDS_WITH, FilterOperator.ENDS_WITH, 'A'],
      [FilterOperator.IN, FilterOperator.IN, ['A']],
      [FilterOperator.NOT_IN, FilterOperator.NOT_IN, ['A']],
      [FilterOperator.IS_EMPTY_STRING, FilterOperator.IS_EMPTY_STRING, null],
      [
        FilterOperator.IS_NOT_EMPTY_STRING,
        FilterOperator.IS_NOT_EMPTY_STRING,
        null,
      ],
      [FilterOperator.IS_NULL, FilterOperator.IS_NULL, null],
    ];
    for (const [operator, op, value] of expected) {
      expect(compile(kind, leaf(operator, value))).toMatchObject({ op });
    }
  });

  it('describes text and list inputs', () => {
    expect(kind.editor(FilterOperator.EQ, field())).toEqual({
      input: 'text',
      multiple: false,
    });
    expect(kind.editor(FilterOperator.IN, field())).toEqual({
      input: 'text',
      multiple: true,
    });
    expect(kind.editor(FilterOperator.IS_EMPTY_STRING, field())).toEqual({
      input: 'none',
    });
    expect(
      kind.describe({
        leaf: leaf(FilterOperator.IN, ['A', 'B']),
        field: field(),
        kinds: builtinFieldKinds,
      }).text,
    ).toBe('Value in A, B');
    expect(
      kind.describe({
        leaf: leaf(FilterOperator.EQ, 'A'),
        field: field(),
        kinds: builtinFieldKinds,
      }).text,
    ).toBe('Value eq A');
  });
});

describe('number kind', () => {
  const kind = numberFieldKind;
  const def = field({ kind: 'number' });

  it('starts unfilled rather than at zero', () => {
    // `0` and `[0, 0]` are conditions in their own right; seeding either
    // would filter the list to them the moment the row appeared.
    expect(kind.emptyValue(FilterOperator.EQ, def)).toBeNull();
    expect(kind.emptyValue(FilterOperator.BETWEEN, def)).toBeNull();
    expect(kind.emptyValue(FilterOperator.IN, def)).toEqual([]);
  });

  it('rejects an inverted range, a non-number and an empty list', () => {
    expect(codes(kind, [1, 2], FilterOperator.BETWEEN, def)).toEqual([]);
    // Two numbers the wrong way round is a range that starts after it ends —
    // the same finding a date range reports, under the same code. Reading
    // "enter a range of two numbers" beside two numbers said nothing.
    expect(codes(kind, [2, 1], FilterOperator.BETWEEN, def)).toEqual([
      'filter.value.inverted-range',
    ]);
    expect(codes(kind, ['x', 'y'], FilterOperator.BETWEEN, def)).toEqual([
      'filter.value.expected-number-range',
    ]);
    expect(codes(kind, 'x', FilterOperator.EQ, def)).toEqual([
      'filter.value.expected-number',
    ]);
    expect(codes(kind, ['x'], FilterOperator.IN, def)).toEqual([
      'filter.value.expected-number-list',
    ]);
    expect(codes(kind, [], FilterOperator.IN, def)).toEqual([
      'filter.value.required',
    ]);
  });

  it('maps every comparison onto Wow', () => {
    const expected: [FilterOperatorName, FilterOperator, unknown][] = [
      [FilterOperator.EQ, FilterOperator.EQ, 1],
      [FilterOperator.NE, FilterOperator.NE, 1],
      [FilterOperator.GT, FilterOperator.GT, 1],
      [FilterOperator.GTE, FilterOperator.GTE, 1],
      [FilterOperator.LT, FilterOperator.LT, 1],
      [FilterOperator.LTE, FilterOperator.LTE, 1],
      [FilterOperator.BETWEEN, FilterOperator.BETWEEN, [1, 2]],
      [FilterOperator.IN, FilterOperator.IN, [1]],
      [FilterOperator.NOT_IN, FilterOperator.NOT_IN, [1]],
      [FilterOperator.EXISTS, FilterOperator.EXISTS, null],
    ];
    for (const [operator, op, value] of expected) {
      expect(compile(kind, leaf(operator, value), def)).toMatchObject({ op });
    }
  });

  it('describes a range, a list and a single value', () => {
    expect(kind.editor(FilterOperator.BETWEEN, def)).toEqual({
      input: 'number',
      range: true,
    });
    expect(kind.editor(FilterOperator.IN, def)).toEqual({
      input: 'number',
      multiple: true,
    });
    expect(
      kind.describe({
        leaf: leaf(FilterOperator.BETWEEN, [1, 2]),
        field: def,
        kinds: builtinFieldKinds,
      }).text,
    ).toBe('Value 1 ~ 2');
    expect(
      kind.describe({
        leaf: leaf(FilterOperator.IN, [1, 2]),
        field: def,
        kinds: builtinFieldKinds,
      }).text,
    ).toBe('Value in 1, 2');
    expect(
      kind.describe({
        leaf: leaf(FilterOperator.GT, 1),
        field: def,
        kinds: builtinFieldKinds,
      }).text,
    ).toBe('Value gt 1');
  });
});

describe('boolean kind', () => {
  const kind = booleanFieldKind;
  const def = field({ kind: 'boolean' });

  it('accepts only a boolean', () => {
    expect(kind.emptyValue(FilterOperator.EQ, def)).toBeNull();
    expect(codes(kind, true, FilterOperator.EQ, def)).toEqual([]);
    expect(codes(kind, 'true', FilterOperator.EQ, def)).toEqual([
      'filter.value.expected-boolean',
    ]);
  });

  it('compiles, describes and asks for a boolean input', () => {
    expect(compile(kind, leaf(FilterOperator.EQ, true), def)).toMatchObject({
      op: FilterOperator.EQ,
      value: true,
    });
    expect(compile(kind, leaf(FilterOperator.NE, false), def)).toMatchObject({
      op: FilterOperator.NE,
    });
    expect(kind.editor(FilterOperator.EQ, def)).toEqual({ input: 'boolean' });
    expect(
      kind.describe({
        leaf: leaf(FilterOperator.EQ, true),
        field: def,
        kinds: builtinFieldKinds,
      }).text,
    ).toBe('Value eq true');
  });
});

describe('enum kind', () => {
  const kind = enumFieldKind;
  const def = field({
    kind: 'enum',
    options: [
      { value: 'A', label: 'Alpha' },
      { value: 2, label: 'Two' },
    ],
  });

  it('checks the selection against the declared options', () => {
    expect(kind.emptyValue(FilterOperator.IN, def)).toEqual([]);
    expect(codes(kind, ['A', 2], FilterOperator.IN, def)).toEqual([]);
    expect(codes(kind, ['Z'], FilterOperator.IN, def)).toEqual([
      'filter.value.unknown-option',
    ]);
    expect(codes(kind, [], FilterOperator.IN, def)).toEqual([
      'filter.value.required',
    ]);
    expect(codes(kind, 'A', FilterOperator.IN, def)).toEqual([
      'filter.value.expected-option-list',
    ]);
    // A field without declared options accepts any scalar.
    expect(
      codes(kind, ['Z'], FilterOperator.IN, field({ kind: 'enum' })),
    ).toEqual([]);
  });

  it('compiles both directions and labels the description', () => {
    expect(compile(kind, leaf(FilterOperator.IN, ['A']), def)).toMatchObject({
      op: FilterOperator.IN,
    });
    expect(
      compile(kind, leaf(FilterOperator.NOT_IN, ['A']), def),
    ).toMatchObject({
      op: FilterOperator.NOT_IN,
    });
    expect(kind.editor(FilterOperator.IN, def)).toMatchObject({
      input: 'select',
      multiple: true,
      options: def.options,
    });
    expect(
      kind.describe({
        leaf: leaf(FilterOperator.IN, ['A', 2, 'Z']),
        field: def,
        kinds: builtinFieldKinds,
      }).text,
    ).toBe('Value in Alpha, Two, Z');
  });
});

describe('reference kind', () => {
  const kind = referenceFieldKind;
  const def = field({ kind: 'reference', remote: 'customers' });
  const value = { items: [{ id: 7, label: 'ACME' }] };

  it('requires well-formed items and a candidate source', () => {
    expect(kind.emptyValue(FilterOperator.IN, def)).toEqual({ items: [] });
    expect(codes(kind, value, FilterOperator.IN, def)).toEqual([]);
    expect(codes(kind, { items: [] }, FilterOperator.IN, def)).toEqual([
      'filter.value.required',
    ]);
    expect(codes(kind, { items: [{ id: 7 }] }, FilterOperator.IN, def)).toEqual(
      ['filter.value.expected-reference-list'],
    );
    expect(
      codes(kind, value, FilterOperator.IN, field({ kind: 'reference' })),
    ).toEqual(['filter.field.reference-without-source']);
  });

  it('compiles to the ids and describes with the snapshot labels', () => {
    expect(compile(kind, leaf(FilterOperator.IN, value), def)).toMatchObject({
      op: FilterOperator.IN,
      values: [7],
    });
    expect(
      compile(kind, leaf(FilterOperator.NOT_IN, value), def),
    ).toMatchObject({
      op: FilterOperator.NOT_IN,
    });
    expect(kind.editor(FilterOperator.IN, def)).toMatchObject({
      input: 'remote',
      remote: 'customers',
    });
    expect(
      kind.describe({
        leaf: leaf(FilterOperator.IN, value),
        field: def,
        kinds: builtinFieldKinds,
      }).text,
    ).toBe('Value in ACME');
  });
});

describe('date kinds', () => {
  const def = field({ kind: 'datetime' });

  it('start unfilled rather than at today', () => {
    // Seeding a window would cut the list to a single day as soon as a date
    // field was picked, which nobody asked for.
    expect(
      dateTimeFieldKind.emptyValue(FilterOperator.BETWEEN, def),
    ).toBeNull();
  });

  it('rejects an unparsable or inverted absolute range', () => {
    expect(
      codes(
        dateTimeFieldKind,
        { type: 'absolute', from: 'x' },
        FilterOperator.BETWEEN,
        def,
      ),
    ).toEqual(['filter.value.unparsable-date']);
    expect(
      codes(
        dateTimeFieldKind,
        { type: 'absolute', from: '2026-01-01', to: 'x' },
        FilterOperator.BETWEEN,
        def,
      ),
    ).toEqual(['filter.value.unparsable-date']);
    expect(
      codes(
        dateTimeFieldKind,
        { type: 'absolute', from: '2026-02-01', to: '2026-01-01' },
        FilterOperator.BETWEEN,
        def,
      ),
    ).toEqual(['filter.value.inverted-range']);
    expect(
      codes(
        dateTimeFieldKind,
        { type: 'relative', amount: 0, unit: 'day' },
        FilterOperator.BETWEEN,
        def,
      ),
    ).toEqual(['filter.value.expected-date']);
  });

  it('compiles GTE and LTE from the resolved window', () => {
    // A field that declares no `temporal` keeps epoch milliseconds, as a Wow
    // snapshot does, so each bound is the integer the store compares with.
    const preset = leaf(FilterOperator.GTE, {
      type: 'preset',
      preset: 'today',
    });
    expect(compile(dateTimeFieldKind, preset, def)).toMatchObject({
      op: FilterOperator.GTE,
      value: Date.parse('2026-09-16T00:00:00.000Z'),
    });
    const upper = leaf(FilterOperator.LTE, { type: 'preset', preset: 'today' });
    expect(compile(dateTimeFieldKind, upper, def)).toMatchObject({
      op: FilterOperator.LTE,
      value: Date.parse('2026-09-16T23:59:59.999Z'),
    });
    const open = leaf(FilterOperator.LTE, {
      type: 'absolute',
      from: '2026-01-01T00:00:00.000Z',
    });
    expect(compile(dateTimeFieldKind, open, def)).toMatchObject({
      op: FilterOperator.LTE,
      value: Date.parse('2026-01-01T00:00:00.000Z'),
    });
  });

  it('compiles a relative single bound on its far edge', () => {
    // The near edge of "last 7 days" is now, and `LTE now` is not what
    // anyone typed: both operators compare against the window's far edge,
    // in whole days (D39).
    const last = { type: 'relative', amount: 7, unit: 'day' };
    const next = { ...last, direction: 'future' };
    const weekAgo = Date.parse('2026-09-10T00:00:00.000Z');
    const weekAhead = Date.parse('2026-09-22T23:59:59.999Z');

    expect(
      compile(dateTimeFieldKind, leaf(FilterOperator.GTE, last), def),
    ).toMatchObject({
      op: FilterOperator.GTE,
      value: weekAgo,
    });
    expect(
      compile(dateTimeFieldKind, leaf(FilterOperator.LTE, last), def),
    ).toMatchObject({
      op: FilterOperator.LTE,
      value: weekAgo,
    });
    expect(
      compile(dateTimeFieldKind, leaf(FilterOperator.GTE, next), def),
    ).toMatchObject({
      op: FilterOperator.GTE,
      value: weekAhead,
    });
    expect(
      compile(dateTimeFieldKind, leaf(FilterOperator.LTE, next), def),
    ).toMatchObject({
      op: FilterOperator.LTE,
      value: weekAhead,
    });
  });

  it('refuses a relative amount past the bound, and never throws past it', () => {
    const huge = { type: 'relative', amount: 1e15, unit: 'hour' };

    expect(codes(dateTimeFieldKind, huge, FilterOperator.BETWEEN, def)).toEqual(
      ['filter.value.relative-too-large'],
    );
    expect(codes(dateTimeFieldKind, huge, FilterOperator.LTE, def)).toEqual([
      'filter.value.relative-too-large',
    ]);
    for (const operator of [
      FilterOperator.BETWEEN,
      FilterOperator.GTE,
      FilterOperator.LTE,
    ] as const)
      expect(() =>
        compile(dateTimeFieldKind, leaf(operator, huge), def),
      ).not.toThrow();
  });

  it('lets a condition override the runtime zone', () => {
    const shanghai = leaf(FilterOperator.BETWEEN, {
      type: 'absolute',
      from: '2026-01-01T00:00:00.000Z',
      to: '2026-01-02T00:00:00.000Z',
      timeZone: 'Asia/Shanghai',
    });
    expect(compile(dateTimeFieldKind, shanghai, def)).toMatchObject({
      op: FilterOperator.BETWEEN,
    });
  });

  it("compares with the service's clock, before or after now", () => {
    // No value and no `ctx.now`: the service reads its own clock, so the
    // operator is the whole condition and a saved one never goes stale.
    for (const operator of [
      FilterOperator.BEFORE_NOW,
      FilterOperator.AFTER_NOW,
    ] as const) {
      expect(dateTimeFieldKind.operators).toContain(operator);
      expect(dateFieldKind.operators).toContain(operator);
      expect(codes(dateTimeFieldKind, null, operator, def)).toEqual([]);
      expect(dateTimeFieldKind.editor(operator, def)).toEqual({
        input: 'none',
      });
    }
    expect(
      compile(dateTimeFieldKind, leaf(FilterOperator.BEFORE_NOW, null), def),
    ).toEqual({
      op: FilterOperator.BEFORE_NOW,
      field: 'value',
      offset: 'PT0S',
      timeUnit: TimeUnit.MILLISECONDS,
    });
    expect(
      compile(dateTimeFieldKind, leaf(FilterOperator.AFTER_NOW, null), def),
    ).toEqual({
      op: FilterOperator.AFTER_NOW,
      field: 'value',
      offset: 'PT0S',
      timeUnit: TimeUnit.MILLISECONDS,
    });
    // A time kept in seconds is compared in seconds.
    const seconds = field({
      kind: 'datetime',
      temporal: { type: 'epoch', timeUnit: TimeUnit.SECONDS },
    });
    expect(
      compile(
        dateTimeFieldKind,
        leaf(FilterOperator.BEFORE_NOW, null),
        seconds,
      ),
    ).toMatchObject({
      op: FilterOperator.BEFORE_NOW,
      timeUnit: TimeUnit.SECONDS,
    });
    expect(
      dateTimeFieldKind.describe({
        leaf: leaf(FilterOperator.BEFORE_NOW, null),
        field: def,
        kinds: builtinFieldKinds,
      }),
    ).toEqual({ text: 'Value before now', value: { kind: 'none' } });
    expect(
      dateTimeFieldKind.describe({
        leaf: leaf(FilterOperator.AFTER_NOW, null),
        field: def,
        kinds: builtinFieldKinds,
      }).text,
    ).toBe('Value after now');
  });

  it('admits and compiles a now condition as a whole tree', () => {
    // A system view's fixed scope: PREPARED and not yet timed out, which is
    // `NOR BEFORE_NOW`, since `AFTER_NOW` is strict and misses the moment
    // itself.
    const fields = [
      field({ name: 'status', kind: 'string' }),
      field({ name: 'timeoutAt', kind: 'datetime' }),
    ];
    const tree: FilterTree = {
      op: 'and',
      children: [
        { field: 'status', operator: FilterOperator.EQ, value: 'PREPARED' },
        {
          op: 'nor',
          children: [
            {
              field: 'timeoutAt',
              operator: FilterOperator.BEFORE_NOW,
              value: null,
            },
          ],
        },
      ],
    };
    expect(validateFilter(fields, tree, builtinFieldKinds)).toEqual([]);
    expect(isExecutableFilter(fields, tree, builtinFieldKinds)).toBe(true);
    expect(
      compileFilter(fields, tree, builtinFieldKinds, { now, timeZone }),
    ).toEqual(
      wow.and([
        wow.eq('status', 'PREPARED'),
        wow.nor([wow.beforeNow('timeoutAt')]),
      ]),
    );
  });

  it('picks the editor from the operator and the value variant', () => {
    expect(dateTimeFieldKind.editor(FilterOperator.BETWEEN, def)).toEqual({
      input: 'dateRange',
      range: true,
      withTime: true,
    });
    expect(dateFieldKind.editor(FilterOperator.GTE, def)).toEqual({
      input: 'date',
      withTime: false,
    });
    expect(
      dateTimeFieldKind.editor(FilterOperator.BETWEEN, def, {
        type: 'relative',
        amount: 7,
        unit: 'day',
      }),
    ).toEqual({ input: 'relativeDate', range: true, withTime: true });
    expect(
      dateTimeFieldKind.editor(FilterOperator.GTE, def, {
        type: 'relative',
        amount: 7,
        unit: 'day',
      }),
    ).toEqual({ input: 'relativeDate', withTime: true });
  });

  it('describes each value variant', () => {
    const describe_ = (value: unknown) =>
      dateTimeFieldKind.describe({
        leaf: leaf(FilterOperator.BETWEEN, value),
        field: def,
        kinds: builtinFieldKinds,
      }).text;
    expect(describe_({ type: 'preset', preset: 'today' })).toBe('Value today');
    expect(describe_({ type: 'relative', amount: 7, unit: 'day' })).toBe(
      'Value last 7 days',
    );
    expect(describe_({ type: 'absolute', from: 'a', to: 'b' })).toBe(
      'Value a ~ b',
    );
    expect(describe_({ type: 'absolute', from: 'a' })).toBe('Value from a');
  });

  it('describes a single bound by the instant it compares against', () => {
    const describe_ = (operator: FilterOperatorName, value: unknown) =>
      dateTimeFieldKind.describe({
        leaf: leaf(operator, value),
        field: def,
        kinds: builtinFieldKinds,
      }).text;
    const last = { type: 'relative', amount: 7, unit: 'day' };

    expect(describe_(FilterOperator.GTE, last)).toBe(
      'Value on or after 7 days ago',
    );
    expect(
      describe_(FilterOperator.LTE, { ...last, direction: 'future' }),
    ).toBe('Value on or before 7 days ahead');
    expect(
      describe_(FilterOperator.LTE, { type: 'preset', preset: 'lastMonth' }),
    ).toBe('Value on or before last month');
    expect(
      describe_(FilterOperator.LTE, { type: 'absolute', from: 'a', to: 'b' }),
    ).toBe('Value on or before b');
  });
});

describe('tree helpers', () => {
  const tree: FilterTree = {
    op: 'and',
    children: [
      { field: 'a', operator: FilterOperator.EQ, value: 1 },
      {
        op: 'or',
        children: [{ field: 'b', operator: FilterOperator.EQ, value: 2 }],
      },
    ],
  };

  it('counts leaves, lists fields and recognises node shapes', () => {
    expect(countLeaves(tree)).toBe(2);
    expect(filterFields(tree)).toEqual(['a', 'b']);
    expect(isFilterGroup(tree)).toBe(true);
    expect(isFilterLeaf(tree.children[0])).toBe(true);
    expect(isEmptyFilter(tree)).toBe(false);
    expect(isEmptyFilter(emptyFilter())).toBe(true);
    expect(clearFilter()).toEqual(emptyFilter());
  });
});

describe('value guards', () => {
  it('separate plain objects, ranges, references and date values', () => {
    expect(isPlainObject({})).toBe(true);
    expect(isPlainObject([])).toBe(false);
    expect(isPlainObject(null)).toBe(false);
    expect(isNumberRange([1, 2])).toBe(true);
    expect(isNumberRange([1])).toBe(false);
    // Shape only: whether the bounds are the right way round is the kind's
    // question, and it has its own sentence for the answer.
    expect(isNumberRange([2, 1])).toBe(true);
    expect(isReferenceFilterValue({ items: 'no' })).toBe(false);
    expect(isDateTimeFilterValue({ type: 'other' })).toBe(false);
    expect(isDateTimeFilterValue('today')).toBe(false);
    expect(isDateTimeFilterValue({ type: 'preset', preset: 'nope' })).toBe(
      false,
    );
    expect(
      isDateTimeFilterValue({ type: 'relative', amount: 1.5, unit: 'day' }),
    ).toBe(false);
    expect(readValue<number>(1)).toBe(1);
  });
});

describe('registry helpers', () => {
  it('reports the operators a field actually offers', () => {
    expect(operatorsOf(field(), stringFieldKind)).toEqual(
      stringFieldKind.operators,
    );
    expect(
      operatorsOf(
        field({ operators: [FilterOperator.EQ, FilterOperator.BETWEEN] }),
        stringFieldKind,
      ),
    ).toEqual([FilterOperator.EQ]);
  });

  it('flags a malformed group operator', () => {
    const broken = { op: 'xor', children: [] } as unknown as FilterTree;
    expect(
      validateFilter([], broken, builtinFieldKinds).map(i => i.code),
    ).toEqual(['filter.group.unknown-operator']);
  });

  it('answers whether a tree is executable as it stands', () => {
    const fields = [field()];
    expect(
      isExecutableFilter(
        fields,
        {
          op: 'and',
          children: [
            { field: 'value', operator: FilterOperator.EQ, value: 'A' },
          ],
        },
        builtinFieldKinds,
      ),
    ).toBe(true);
    expect(
      isExecutableFilter(
        fields,
        {
          op: 'and',
          children: [
            { field: 'gone', operator: FilterOperator.EQ, value: 'A' },
          ],
        },
        builtinFieldKinds,
      ),
    ).toBe(false);
  });
});

/**
 * A summary line says what is in force. A value the kind cannot read is not
 * in force, and dressing it up — "Qty o ~ o" from a string spread into two
 * bounds, `[object Object]` from an object — puts a sentence on screen that
 * is worse than no sentence, in the one place a user checks what is applied.
 */
describe('describing a value a kind cannot read', () => {
  const junk = { nope: true } as unknown;
  const cases: [string, FilterOperatorName, unknown][] = [
    ['string', FilterOperator.EQ, junk],
    ['number', FilterOperator.EQ, junk],
    ['number', FilterOperator.BETWEEN, 'not a range'],
    ['boolean', FilterOperator.EQ, junk],
    ['enum', FilterOperator.IN, junk],
    ['array', FilterOperator.IN, junk],
    ['reference', FilterOperator.IN, junk],
    ['date', FilterOperator.BETWEEN, junk],
    ['datetime', FilterOperator.GTE, junk],
    ['search', FilterOperator.SEARCH, junk],
    ['elementMatch', FilterOperator.ELEMENT_MATCH, junk],
  ];

  it.each(cases)('falls back to the label for %s %s', (id, operator, value) => {
    const kind = builtinFieldKinds.get(id)!;

    // The line is the label alone, and the parts say why: the value is
    // blank, so the bar names the field and says nothing after it.
    expect(
      kind.describe({
        leaf: leaf(operator, value),
        field: field({ kind: id }),
        kinds: builtinFieldKinds,
      }),
    ).toEqual({ text: 'Value', value: { kind: 'blank' } });
  });
});
