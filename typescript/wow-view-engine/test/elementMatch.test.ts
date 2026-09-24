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
  describeFilter,
  elementFields,
  searchFieldKind,
  validateFilter,
  withFieldKinds,
} from '../src/filter/index.js';
import type {
  FieldDefinition,
  FilterOperatorName,
  FilterTree,
  FilterValue,
} from '../src/model/index.js';

/**
 * A condition whose value is a condition.
 *
 * The distinction it exists for: `items.sku` and `items.qty` written side by
 * side at the top level are satisfied by any entries, one matching each.
 * Inside an element match they must be satisfied by the same entry.
 */
const fields: FieldDefinition[] = [
  { name: 'warehouse', label: 'Warehouse', kind: 'string' },
  {
    name: 'items',
    label: 'Items',
    kind: 'elementMatch',
    elements: [
      { name: 'sku', label: 'SKU', kind: 'string' },
      { name: 'qty', label: 'Qty', kind: 'number' },
      {
        name: 'tags',
        label: 'Tags',
        kind: 'elementMatch',
        elements: [{ name: 'name', label: 'Name', kind: 'string' }],
      },
    ],
  },
  { name: 'bare', label: 'Bare', kind: 'elementMatch' },
];

const context = { now: new Date('2026-09-17T00:00:00Z'), timeZone: 'UTC' };

function outer(
  operator: FilterOperatorName,
  value: FilterValue,
  field = 'items',
): FilterTree {
  return { op: 'and', children: [{ field, operator, value }] };
}

const predicate = (...children: FilterTree['children']): FilterTree => ({
  op: 'and',
  children,
});

function errors(issues: { severity: string; code: string }[]): string[] {
  return issues.filter(i => i.severity === 'error').map(i => i.code);
}

function compile(tree: FilterTree) {
  return compileFilter(fields, tree, builtinFieldKinds, context);
}

describe('element fields', () => {
  it('are named as a condition names them', () => {
    // A declaration writes the relative name; a condition points at one from
    // outside, so it writes the full path.
    expect(elementFields(fields[1]).map(field => field.name)).toEqual([
      'items.sku',
      'items.qty',
      'items.tags',
    ]);
  });

  it('are empty for an array that declares none', () => {
    expect(elementFields(fields[2])).toEqual([]);
  });
});

describe('the elementMatch kind', () => {
  /**
   * Wow reads a predicate's fields relative to the element — `sku`, not
   * `items.sku`, which it would look up as `items.items.sku` and never find
   * — while the config names them from the root. An element of an element
   * is re-addressed by its own compile in turn.
   */
  it('compiles a predicate onto the array, its fields named from inside it', () => {
    expect(
      compile(
        outer(
          'ELEMENT_MATCH',
          predicate(
            { field: 'items.sku', operator: 'EQ', value: 'A' },
            { field: 'items.qty', operator: 'GT', value: 2 },
            {
              field: 'items.tags',
              operator: 'ELEMENT_MATCH',
              value: predicate({
                field: 'items.tags.name',
                operator: 'EQ',
                value: 'gift',
              }) as never,
            },
          ) as never,
        ),
      ),
    ).toEqual({
      op: FilterOperator.ELEMENT_MATCH,
      field: 'items',
      predicate: {
        op: FilterOperator.AND,
        operands: [
          { op: FilterOperator.EQ, field: 'sku', value: 'A' },
          { op: FilterOperator.GT, field: 'qty', value: 2 },
          {
            op: FilterOperator.ELEMENT_MATCH,
            field: 'tags',
            predicate: { op: FilterOperator.EQ, field: 'name', value: 'gift' },
          },
        ],
      },
    });
  });

  it('asks whether there are entries at all', () => {
    expect(compile(outer('IS_EMPTY', null))).toEqual({
      op: FilterOperator.IS_EMPTY,
      field: 'items',
    });
  });

  it('answers the presence questions, which name a real path', () => {
    // Unlike a metadata kind, this field's name is the array's own path.
    expect(compile(outer('IS_NULL', null))).toEqual({
      op: FilterOperator.IS_NULL,
      field: 'items',
    });
  });

  it('admits a predicate against the entry fields', () => {
    expect(
      errors(
        validateFilter(
          fields,
          outer(
            'ELEMENT_MATCH',
            predicate({
              field: 'items.sku',
              operator: 'EQ',
              value: 'A',
            }) as never,
          ),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
  });

  it('refuses a condition on a field the entries do not hold', () => {
    // `warehouse` belongs to the order, not to a line.
    expect(
      errors(
        validateFilter(
          fields,
          outer(
            'ELEMENT_MATCH',
            predicate({
              field: 'warehouse',
              operator: 'EQ',
              value: 'CN',
            }) as never,
          ),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.field.unknown']);
  });

  it('refuses a value that is not a condition', () => {
    expect(
      errors(
        validateFilter(
          fields,
          outer('ELEMENT_MATCH', 'nonsense'),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.value.expected-predicate']);
  });

  it('refuses an array that declares no entry fields', () => {
    expect(
      errors(
        validateFilter(
          fields,
          outer(
            'ELEMENT_MATCH',
            predicate({ field: 'x', operator: 'EQ', value: 1 }) as never,
            'bare',
          ),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.field.holds-no-elements']);
  });

  it('treats an empty predicate as a question not yet asked', () => {
    const empty = outer('ELEMENT_MATCH', predicate() as never);

    expect(errors(validateFilter(fields, empty, builtinFieldKinds))).toEqual(
      [],
    );
    expect(compile(empty)).toEqual({ op: FilterOperator.MATCH_ALL });
    // It never reached the query, so it is not a condition in force.
    expect(describeFilter(fields, empty, builtinFieldKinds)).toEqual([]);
  });

  it('treats a predicate whose only condition is unfilled the same way', () => {
    // A row was added inside the match and no value typed yet. Counting the
    // leaves would call that a question; `compileFilter` would then drop the
    // blank leaf and answer MATCH_ALL, and `ELEMENT_MATCH` over MATCH_ALL
    // narrows the result to "the array is non-empty", which nobody asked.
    const unfilled = predicate(
      { field: 'items.sku', operator: 'EQ', value: '' },
      {
        op: 'or',
        children: [{ field: 'items.qty', operator: 'GT', value: null }],
      },
    );
    const alone = outer('ELEMENT_MATCH', unfilled as never);
    expect(errors(validateFilter(fields, alone, builtinFieldKinds))).toEqual(
      [],
    );
    expect(compile(alone)).toEqual({ op: FilterOperator.MATCH_ALL });
    expect(describeFilter(fields, alone, builtinFieldKinds)).toEqual([]);

    // Beside a real condition, the tree compiles and reads as if the match
    // were not there at all.
    const beside: FilterTree = {
      op: 'and',
      children: [
        { field: 'warehouse', operator: 'EQ', value: 'SH' },
        { field: 'items', operator: 'ELEMENT_MATCH', value: unfilled as never },
      ],
    };
    const without: FilterTree = { op: 'and', children: [beside.children[0]] };
    expect(errors(validateFilter(fields, beside, builtinFieldKinds))).toEqual(
      [],
    );
    expect(compile(beside)).toEqual(compile(without));
    expect(compile(beside)).toEqual({
      op: FilterOperator.EQ,
      field: 'warehouse',
      value: 'SH',
    });
    expect(
      describeFilter(fields, beside, builtinFieldKinds).map(item => item.text),
    ).toEqual(
      describeFilter(fields, without, builtinFieldKinds).map(i => i.text),
    );
  });

  it('forgives only an unfilled condition, never a wrong one', () => {
    // A blank value on a field the entries do not hold, on an operator the
    // field does not offer, or on a kind nobody registered is a finding, and
    // calling the predicate blank would hide it.
    const cases: [FilterTree['children'][number], string][] = [
      [
        { field: 'items.gone', operator: 'EQ', value: '' },
        'filter.field.unknown',
      ],
      [
        { field: 'items.qty', operator: 'CONTAINS', value: '' },
        'filter.operator.unsupported',
      ],
    ];
    for (const [leaf, code] of cases) {
      expect(
        errors(
          validateFilter(
            fields,
            outer('ELEMENT_MATCH', predicate(leaf) as never),
            builtinFieldKinds,
          ),
        ),
      ).toEqual([code]);
    }

    const mystery: FieldDefinition[] = [
      {
        name: 'items',
        label: 'Items',
        kind: 'elementMatch',
        elements: [{ name: 'tag', label: 'Tag', kind: 'mystery' }],
      },
    ];
    expect(
      errors(
        validateFilter(
          mystery,
          outer(
            'ELEMENT_MATCH',
            predicate({
              field: 'items.tag',
              operator: 'EQ',
              value: '',
            }) as never,
          ),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.kind.unregistered']);
  });

  it('starts from an empty condition', () => {
    expect(
      builtinFieldKinds
        .get('elementMatch')!
        .emptyValue('ELEMENT_MATCH', fields[1]),
    ).toEqual({ op: 'and', children: [] });
  });

  it('asks the editor for a condition rather than a value', () => {
    const kind = builtinFieldKinds.get('elementMatch')!;

    expect(kind.editor('ELEMENT_MATCH', fields[1])).toEqual({
      input: 'predicate',
    });
    expect(kind.editor('IS_EMPTY', fields[1])).toEqual({ input: 'none' });
    expect(kind.editor('IS_NULL', fields[1])).toEqual({ input: 'none' });
  });

  it("summarises a predicate with the predicate's own operator", () => {
    // Joining an `or` with "and" states the opposite of what is in force.
    const either: FilterTree = {
      op: 'or',
      children: [
        { field: 'items.sku', operator: 'EQ', value: 'A' },
        { field: 'items.qty', operator: 'GT', value: 2 },
      ],
    };

    expect(
      describeFilter(
        fields,
        outer('ELEMENT_MATCH', either as never),
        builtinFieldKinds,
      ).map(item => item.text),
    ).toEqual(['Items has an entry where SKU EQ A or Qty GT 2']);
  });

  it('names the field alone for a value that is not a condition', () => {
    // Reading a non-tree as an empty predicate announced "has any entry" —
    // a condition nobody wrote and the query does not carry.
    expect(
      describeFilter(
        fields,
        outer('ELEMENT_MATCH', 'nonsense'),
        builtinFieldKinds,
      ).map(item => item.text),
    ).toEqual(['Items']);
  });

  it('refuses a custom kind that compiles to a root filter', () => {
    // The rule is Wow's, and it is about what the kind compiles to, not about
    // which ids this package happens to ship.
    const kinds = withFieldKinds(builtinFieldKinds, [
      { ...searchFieldKind, id: 'fullText' },
    ]);
    const custom: FieldDefinition[] = [
      {
        name: 'items',
        label: 'Items',
        kind: 'elementMatch',
        elements: [{ name: 'text', label: 'Text', kind: 'fullText' }],
      },
    ];

    expect(
      errors(
        validateFilter(
          custom,
          outer(
            'ELEMENT_MATCH',
            predicate({
              field: 'items.text',
              operator: 'SEARCH',
              value: 'widget',
            }) as never,
          ),
          kinds,
        ),
      ),
    ).toEqual(['filter.element.root-filter']);
  });

  it.each([
    [
      'ELEMENT_MATCH',
      predicate({ field: 'items.sku', operator: 'EQ', value: 'A' }),
      'Items has an entry where SKU EQ A',
    ],
    ['IS_EMPTY', null, 'Items has no entries'],
    ['IS_NULL', null, 'Items is empty'],
  ] as [FilterOperatorName, FilterValue, string][])(
    'summarises %s',
    (operator, value, want) => {
      expect(
        describeFilter(fields, outer(operator, value), builtinFieldKinds).map(
          item => item.text,
        ),
      ).toEqual([want]);
    },
  );
});

/**
 * One budget covers the nesting. A tree hidden inside a value would be a
 * second dimension nobody counted, which is the exact thing the limits exist
 * to prevent.
 */
describe('the budget reaches into a predicate', () => {
  it('counts nested depth', () => {
    let deep: FilterTree = predicate({
      field: 'items.sku',
      operator: 'EQ',
      value: 'A',
    });
    for (let index = 0; index < 10; index += 1)
      deep = { op: 'and', children: [deep] };

    expect(
      errors(
        validateFilter(
          fields,
          outer('ELEMENT_MATCH', deep as never),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.tree.too-deep']);
  });

  it('counts nested nodes against the same total', () => {
    const many = predicate(
      ...Array.from({ length: 300 }, () => ({
        field: 'items.sku',
        operator: 'EQ' as FilterOperatorName,
        value: 'A',
      })),
    );

    expect(
      errors(
        validateFilter(
          fields,
          outer('ELEMENT_MATCH', many as never),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.tree.too-many-nodes']);
  });

  it("counts it against the caller's budget, not the default one", () => {
    // The nested pass used to run with `DEFAULT_RUNTIME_LIMITS`, so a filter
    // its caller had given room for was refused for a size it did not have.
    // One field per group, so each condition sits in a group of its own.
    const many = predicate(
      ...Array.from({ length: 300 }, () => ({
        op: 'and' as const,
        children: [
          {
            field: 'items.sku',
            operator: 'EQ' as FilterOperatorName,
            value: 'A',
          },
        ],
      })),
    );

    expect(
      errors(
        validateFilter(
          fields,
          outer('ELEMENT_MATCH', many as never),
          builtinFieldKinds,
          { limits: { maxFilterDepth: 8, maxFilterNodes: 1000 } },
        ),
      ),
    ).toEqual([]);
  });

  it('leaves a tree the operator never asks anything with uncounted', () => {
    // A leaf keeps its value when the operator changes, so a predicate left
    // behind under `IS_EMPTY` is not a question. It used to be charged to the
    // budget all the same, and refused for a depth nothing would validate.
    let deep: FilterTree = predicate({
      field: 'items.sku',
      operator: 'EQ',
      value: 'A',
    });
    for (let index = 0; index < 10; index += 1)
      deep = { op: 'and', children: [deep] };

    expect(
      errors(
        validateFilter(
          fields,
          outer('IS_EMPTY', deep as never),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
  });

  it('leaves a predicate within budget alone', () => {
    expect(
      errors(
        validateFilter(
          fields,
          outer(
            'ELEMENT_MATCH',
            predicate({
              field: 'items.qty',
              operator: 'GT',
              value: 1,
            }) as never,
          ),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
  });
});
