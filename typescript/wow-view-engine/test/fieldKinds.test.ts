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
  booleanFieldKind,
  clearFilter,
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
  isPlainObject,
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

const now = new Date('2026-09-16T10:30:00.000Z');
const timeZone = 'UTC';

function field(overrides: Partial<FieldDefinition> = {}): FieldDefinition {
  return { name: 'value', label: 'Value', kind: 'string', ...overrides };
}

function leaf(operator: FilterOperatorName, value: unknown): FilterLeaf {
  return { field: 'value', operator, value: value as FilterLeaf['value'] };
}

function compile(kind: FieldKind, one: FilterLeaf, def = field()) {
  return kind.compile({ leaf: one, field: def, now, timeZone });
}

function codes(
  kind: FieldKind,
  value: unknown,
  operator: FilterOperatorName,
  def = field(),
) {
  return kind
    .validate({ value, operator, field: def, path: [] })
    .map(i => i.code);
}

describe('presence operators', () => {
  it('compile and describe without reading a value', () => {
    for (const operator of [
      'IS_NULL',
      'IS_NOT_NULL',
      'EXISTS',
      'NOT_EXISTS',
    ] as FilterOperatorName[]) {
      expect(compilePresence('value', operator)).toMatchObject({
        field: 'value',
      });
      expect(describePresence(operator)).toBeTruthy();
    }
    expect(compilePresence('value', 'EQ')).toBeNull();
    expect(describePresence('EQ')).toBeNull();
  });

  it('are offered by every built-in kind', () => {
    for (const kind of builtinFieldKinds.values()) {
      expect(kind.operators).toContain('IS_NULL');
      expect(
        kind.validate({
          value: null,
          operator: 'IS_NULL',
          field: field(),
          path: [],
        }),
      ).toEqual([]);
      expect(kind.editor('IS_NULL', field())).toEqual({ input: 'none' });
      expect(
        kind.describe({ leaf: leaf('IS_NULL', null), field: field() }),
      ).toContain('Value');
    }
  });
});

describe('string kind', () => {
  const kind = stringFieldKind;

  it('starts from an empty value that suits the operator', () => {
    expect(kind.emptyValue('EQ', field())).toBe('');
    expect(kind.emptyValue('IN', field())).toEqual([]);
  });

  it('accepts a string, a list, and the emptiness operators', () => {
    expect(codes(kind, 'A', 'EQ')).toEqual([]);
    expect(codes(kind, ['A', 'B'], 'IN')).toEqual([]);
    expect(codes(kind, null, 'IS_EMPTY_STRING')).toEqual([]);
    expect(codes(kind, 7, 'EQ')).toEqual(['filter.value.expected-string']);
    expect(codes(kind, [7], 'IN')).toEqual([
      'filter.value.expected-string-list',
    ]);
    expect(codes(kind, [], 'IN')).toEqual(['filter.value.required']);
  });

  it('maps every operator onto Wow', () => {
    const expected: [FilterOperatorName, FilterOperator, unknown][] = [
      ['EQ', FilterOperator.EQ, 'A'],
      ['NE', FilterOperator.NE, 'A'],
      ['CONTAINS', FilterOperator.CONTAINS, 'A'],
      ['STARTS_WITH', FilterOperator.STARTS_WITH, 'A'],
      ['ENDS_WITH', FilterOperator.ENDS_WITH, 'A'],
      ['IN', FilterOperator.IN, ['A']],
      ['NOT_IN', FilterOperator.NOT_IN, ['A']],
      ['IS_EMPTY_STRING', FilterOperator.IS_EMPTY_STRING, null],
      ['IS_NOT_EMPTY_STRING', FilterOperator.IS_NOT_EMPTY_STRING, null],
      ['IS_NULL', FilterOperator.IS_NULL, null],
    ];
    for (const [operator, op, value] of expected) {
      expect(compile(kind, leaf(operator, value))).toMatchObject({ op });
    }
  });

  it('describes text and list inputs', () => {
    expect(kind.editor('EQ', field())).toEqual({
      input: 'text',
      multiple: false,
    });
    expect(kind.editor('IN', field())).toEqual({
      input: 'text',
      multiple: true,
    });
    expect(kind.editor('IS_EMPTY_STRING', field())).toEqual({ input: 'none' });
    expect(
      kind.describe({ leaf: leaf('IN', ['A', 'B']), field: field() }),
    ).toBe('Value IN A, B');
    expect(kind.describe({ leaf: leaf('EQ', 'A'), field: field() })).toBe(
      'Value EQ A',
    );
  });
});

describe('number kind', () => {
  const kind = numberFieldKind;
  const def = field({ kind: 'number' });

  it('starts from an empty value that suits the operator', () => {
    expect(kind.emptyValue('EQ', def)).toBe(0);
    expect(kind.emptyValue('IN', def)).toEqual([]);
    expect(kind.emptyValue('BETWEEN', def)).toEqual([0, 0]);
  });

  it('rejects an inverted range, a non-number and an empty list', () => {
    expect(codes(kind, [1, 2], 'BETWEEN', def)).toEqual([]);
    expect(codes(kind, [2, 1], 'BETWEEN', def)).toEqual([
      'filter.value.expected-number-range',
    ]);
    expect(codes(kind, 'x', 'EQ', def)).toEqual([
      'filter.value.expected-number',
    ]);
    expect(codes(kind, ['x'], 'IN', def)).toEqual([
      'filter.value.expected-number-list',
    ]);
    expect(codes(kind, [], 'IN', def)).toEqual(['filter.value.required']);
  });

  it('maps every comparison onto Wow', () => {
    const expected: [FilterOperatorName, FilterOperator, unknown][] = [
      ['EQ', FilterOperator.EQ, 1],
      ['NE', FilterOperator.NE, 1],
      ['GT', FilterOperator.GT, 1],
      ['GTE', FilterOperator.GTE, 1],
      ['LT', FilterOperator.LT, 1],
      ['LTE', FilterOperator.LTE, 1],
      ['BETWEEN', FilterOperator.BETWEEN, [1, 2]],
      ['IN', FilterOperator.IN, [1]],
      ['NOT_IN', FilterOperator.NOT_IN, [1]],
      ['EXISTS', FilterOperator.EXISTS, null],
    ];
    for (const [operator, op, value] of expected) {
      expect(compile(kind, leaf(operator, value), def)).toMatchObject({ op });
    }
  });

  it('describes a range, a list and a single value', () => {
    expect(kind.editor('BETWEEN', def)).toEqual({
      input: 'number',
      range: true,
    });
    expect(kind.editor('IN', def)).toEqual({ input: 'number', multiple: true });
    expect(kind.describe({ leaf: leaf('BETWEEN', [1, 2]), field: def })).toBe(
      'Value 1 ~ 2',
    );
    expect(kind.describe({ leaf: leaf('IN', [1, 2]), field: def })).toBe(
      'Value IN 1, 2',
    );
    expect(kind.describe({ leaf: leaf('GT', 1), field: def })).toBe(
      'Value GT 1',
    );
  });
});

describe('boolean kind', () => {
  const kind = booleanFieldKind;
  const def = field({ kind: 'boolean' });

  it('accepts only a boolean', () => {
    expect(kind.emptyValue('EQ', def)).toBe(true);
    expect(codes(kind, true, 'EQ', def)).toEqual([]);
    expect(codes(kind, 'true', 'EQ', def)).toEqual([
      'filter.value.expected-boolean',
    ]);
  });

  it('compiles, describes and asks for a boolean input', () => {
    expect(compile(kind, leaf('EQ', true), def)).toMatchObject({
      op: FilterOperator.EQ,
      value: true,
    });
    expect(compile(kind, leaf('NE', false), def)).toMatchObject({
      op: FilterOperator.NE,
    });
    expect(kind.editor('EQ', def)).toEqual({ input: 'boolean' });
    expect(kind.describe({ leaf: leaf('EQ', true), field: def })).toBe(
      'Value EQ true',
    );
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
    expect(kind.emptyValue('IN', def)).toEqual([]);
    expect(codes(kind, ['A', 2], 'IN', def)).toEqual([]);
    expect(codes(kind, ['Z'], 'IN', def)).toEqual([
      'filter.value.unknown-option',
    ]);
    expect(codes(kind, [], 'IN', def)).toEqual(['filter.value.required']);
    expect(codes(kind, 'A', 'IN', def)).toEqual([
      'filter.value.expected-option-list',
    ]);
    // A field without declared options accepts any scalar.
    expect(codes(kind, ['Z'], 'IN', field({ kind: 'enum' }))).toEqual([]);
  });

  it('compiles both directions and labels the description', () => {
    expect(compile(kind, leaf('IN', ['A']), def)).toMatchObject({
      op: FilterOperator.IN,
    });
    expect(compile(kind, leaf('NOT_IN', ['A']), def)).toMatchObject({
      op: FilterOperator.NOT_IN,
    });
    expect(kind.editor('IN', def)).toMatchObject({
      input: 'select',
      multiple: true,
      options: def.options,
    });
    expect(kind.describe({ leaf: leaf('IN', ['A', 2, 'Z']), field: def })).toBe(
      'Value IN Alpha, Two, Z',
    );
  });
});

describe('reference kind', () => {
  const kind = referenceFieldKind;
  const def = field({ kind: 'reference', remote: 'customers' });
  const value = { items: [{ id: 7, label: 'ACME' }] };

  it('requires well-formed items and a candidate source', () => {
    expect(kind.emptyValue('IN', def)).toEqual({ items: [] });
    expect(codes(kind, value, 'IN', def)).toEqual([]);
    expect(codes(kind, { items: [] }, 'IN', def)).toEqual([
      'filter.value.required',
    ]);
    expect(codes(kind, { items: [{ id: 7 }] }, 'IN', def)).toEqual([
      'filter.value.expected-reference-list',
    ]);
    expect(codes(kind, value, 'IN', field({ kind: 'reference' }))).toEqual([
      'filter.field.reference-without-source',
    ]);
  });

  it('compiles to the ids and describes with the snapshot labels', () => {
    expect(compile(kind, leaf('IN', value), def)).toMatchObject({
      op: FilterOperator.IN,
      values: [7],
    });
    expect(compile(kind, leaf('NOT_IN', value), def)).toMatchObject({
      op: FilterOperator.NOT_IN,
    });
    expect(kind.editor('IN', def)).toMatchObject({
      input: 'remote',
      remote: 'customers',
    });
    expect(kind.describe({ leaf: leaf('IN', value), field: def })).toBe(
      'Value IN ACME',
    );
  });
});

describe('date kinds', () => {
  const def = field({ kind: 'datetime' });

  it('default to a preset, which is what a saved view should reopen with', () => {
    expect(dateTimeFieldKind.emptyValue('BETWEEN', def)).toEqual({
      type: 'preset',
      preset: 'today',
    });
  });

  it('rejects an unparsable or inverted absolute range', () => {
    expect(
      codes(dateTimeFieldKind, { type: 'absolute', from: 'x' }, 'BETWEEN', def),
    ).toEqual(['filter.value.unparsable-date']);
    expect(
      codes(
        dateTimeFieldKind,
        { type: 'absolute', from: '2026-01-01', to: 'x' },
        'BETWEEN',
        def,
      ),
    ).toEqual(['filter.value.unparsable-date']);
    expect(
      codes(
        dateTimeFieldKind,
        { type: 'absolute', from: '2026-02-01', to: '2026-01-01' },
        'BETWEEN',
        def,
      ),
    ).toEqual(['filter.value.inverted-range']);
    expect(
      codes(
        dateTimeFieldKind,
        { type: 'relative', amount: 0, unit: 'day' },
        'BETWEEN',
        def,
      ),
    ).toEqual(['filter.value.expected-date']);
  });

  it('compiles GTE and LTE from the resolved window', () => {
    const preset = leaf('GTE', { type: 'preset', preset: 'today' });
    expect(compile(dateTimeFieldKind, preset, def)).toMatchObject({
      op: FilterOperator.GTE,
      value: '2026-09-16T00:00:00.000Z',
    });
    const upper = leaf('LTE', { type: 'preset', preset: 'today' });
    expect(compile(dateTimeFieldKind, upper, def)).toMatchObject({
      op: FilterOperator.LTE,
      value: '2026-09-16T23:59:59.999Z',
    });
    const open = leaf('LTE', {
      type: 'absolute',
      from: '2026-01-01T00:00:00.000Z',
    });
    expect(compile(dateTimeFieldKind, open, def)).toMatchObject({
      op: FilterOperator.LTE,
      value: '2026-01-01T00:00:00.000Z',
    });
  });

  it('lets a condition override the runtime zone', () => {
    const shanghai = leaf('BETWEEN', {
      type: 'absolute',
      from: '2026-01-01T00:00:00.000Z',
      to: '2026-01-02T00:00:00.000Z',
      timeZone: 'Asia/Shanghai',
    });
    expect(compile(dateTimeFieldKind, shanghai, def)).toMatchObject({
      op: FilterOperator.BETWEEN,
    });
  });

  it('picks the editor from the operator and the value variant', () => {
    expect(dateTimeFieldKind.editor('BETWEEN', def)).toEqual({
      input: 'dateRange',
      range: true,
      withTime: true,
    });
    expect(dateFieldKind.editor('GTE', def)).toEqual({
      input: 'date',
      withTime: false,
    });
    expect(
      dateTimeFieldKind.editor('BETWEEN', def, {
        type: 'relative',
        amount: 7,
        unit: 'day',
      }),
    ).toEqual({ input: 'relativeDate', withTime: true });
  });

  it('describes each value variant', () => {
    const describe_ = (value: unknown) =>
      dateTimeFieldKind.describe({ leaf: leaf('BETWEEN', value), field: def });
    expect(describe_({ type: 'preset', preset: 'today' })).toBe('Value today');
    expect(describe_({ type: 'relative', amount: 7, unit: 'day' })).toBe(
      'Value last 7 day',
    );
    expect(describe_({ type: 'absolute', from: 'a', to: 'b' })).toBe(
      'Value a ~ b',
    );
    expect(describe_({ type: 'absolute', from: 'a' })).toBe('Value from a');
  });
});

describe('tree helpers', () => {
  const tree: FilterTree = {
    op: 'and',
    children: [
      { field: 'a', operator: 'EQ', value: 1 },
      { op: 'or', children: [{ field: 'b', operator: 'EQ', value: 2 }] },
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
      operatorsOf(field({ operators: ['EQ', 'BETWEEN'] }), stringFieldKind),
    ).toEqual(['EQ']);
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
          children: [{ field: 'value', operator: 'EQ', value: 'A' }],
        },
        builtinFieldKinds,
      ),
    ).toBe(true);
    expect(
      isExecutableFilter(
        fields,
        {
          op: 'and',
          children: [{ field: 'gone', operator: 'EQ', value: 'A' }],
        },
        builtinFieldKinds,
      ),
    ).toBe(false);
  });
});
