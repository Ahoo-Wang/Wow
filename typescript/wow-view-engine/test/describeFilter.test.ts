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

import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  describeFilter,
  withFieldKinds,
  type FieldDefinition,
  type FilterTree,
} from '../src/index.js';

/**
 * `describeFilter` — the applied-condition summary, which is two things at
 * once: the structured parts `/ui` builds a badge from, and the English line
 * a host consuming `FilterSummaryItem.text` has always been given. Its own
 * file because it is its own subject; `filter.test.ts` covers admission,
 * compilation, time and tree editing.
 */

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
  // A number carrying a millisecond instant, shown as a date wherever it is
  // shown: the display rule is `cell ?? kind`, not `kind`.
  { name: 'shippedAt', label: 'Shipped', kind: 'number', cell: 'date' },
  // An open-ended array - no candidate set, so no entry has a label - whose
  // entries are money.
  {
    name: 'charges',
    label: 'Charges',
    kind: 'array',
    numberFormat: { style: 'currency', currency: 'CNY' },
  },
];

function tree(...children: FilterTree['children']): FilterTree {
  return { op: 'and', children };
}

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

  it('reads a group out as one item joined by its own operator', () => {
    const items = describeFilter(
      fields,
      {
        op: 'and',
        children: [
          { field: 'id', operator: 'EQ', value: 'o-1' },
          {
            op: 'or',
            children: [
              { field: 'status', operator: 'IN', value: ['PENDING'] },
              { field: 'amount', operator: 'BETWEEN', value: [1, 9] },
              {
                op: 'nor',
                children: [{ field: 'paid', operator: 'EQ', value: true }],
              },
            ],
          },
        ],
      },
      builtinFieldKinds,
    );

    // Side by side reads as "all of"; the group keeps its own logic inside.
    expect(items.map(item => item.text)).toEqual([
      'Order EQ o-1',
      'Status IN Pending or Amount 1 ~ 9 or (not Paid EQ true)',
    ]);
    expect(items[1]).toMatchObject({ group: 'or', path: ['children', 1] });
  });

  it('folds a root that is not "all of" into one item that says so', () => {
    const items = describeFilter(
      fields,
      {
        op: 'or',
        children: [
          { field: 'id', operator: 'EQ', value: 'o-1' },
          { field: 'amount', operator: 'GT', value: 9 },
        ],
      },
      builtinFieldKinds,
    );
    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ group: 'or', path: [] });
    expect(items[0].text).toBe('Order EQ o-1 or Amount GT 9');
  });

  it('keeps the negation of a "none of" root even over one condition', () => {
    const items = describeFilter(
      fields,
      {
        op: 'nor',
        children: [
          { field: 'paid', operator: 'EQ', value: true },
          // Blank, so left out; the root still negates what remains.
          { field: 'amount', operator: 'GT', value: null as never },
        ],
      },
      builtinFieldKinds,
    );
    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ group: 'nor', path: [] });
    expect(items[0].text).toBe('not Paid EQ true');
  });

  it('marks a condition whose field disappeared instead of hiding it', () => {
    const items = describeFilter(
      fields,
      tree({ field: 'gone', operator: 'EQ', value: 'x' }),
      builtinFieldKinds,
    );
    expect(items[0]).toMatchObject({ field: 'gone', unresolved: true });
  });

  it('names the field alone for a value the kind cannot read', () => {
    // A stored value the field no longer admits: `status` is an enum, whose
    // operators take a list. Summarising it must not take the view down, and
    // must not invent a reading of it either — the label is all that is true.
    const items = describeFilter(
      fields,
      tree({ field: 'status', operator: 'EQ', value: 'PENDING' }),
      builtinFieldKinds,
    );

    expect(items[0].text).toBe('Status');
  });

  it('marks a leaf whose kind threw rather than taking the view down', () => {
    // A kind is an extension point: what a custom one does with a value it
    // cannot read is not this layer's to predict, only to survive.
    const exploding = withFieldKinds(builtinFieldKinds, [
      {
        ...builtinFieldKinds.get('enum')!,
        id: 'exploding',
        describe() {
          throw new Error('boom');
        },
      },
    ]);
    const items = describeFilter(
      [{ name: 'status', label: 'Status', kind: 'exploding' }],
      tree({ field: 'status', operator: 'IN', value: ['PENDING'] }),
      exploding,
    );

    expect(items[0]).toMatchObject({ field: 'status', unresolved: true });
    expect(items[0].text).toContain('IN');
  });
});

/**
 * The parts beside `text`.
 *
 * The summary used to be one English sentence per condition, concatenated
 * inside each kind — raw operator names, "is empty", "on or before" — so the
 * most visible line of the result area was the one line no catalogue could
 * reach. Every kind now hands over the field, the operator and a value in a
 * closed union, and `/ui` turns those into words; `text` stays the English
 * reading, because a host may consume it as it stands.
 */
describe('describeFilter parts', () => {
  const partsOf = (leaf: FilterTree['children'][number]) =>
    describeFilter(fields, tree(leaf), builtinFieldKinds)[0];

  it('names the field and its kind beside the operator', () => {
    expect(
      partsOf({ field: 'amount', operator: 'GT', value: 9 }),
    ).toMatchObject({
      field: 'amount',
      label: 'Amount',
      kind: 'number',
      operator: 'GT',
      value: { kind: 'text', value: 9 },
    });
  });

  it('carries a number range as two bounds', () => {
    expect(
      partsOf({ field: 'amount', operator: 'BETWEEN', value: [1, 9] }).value,
    ).toEqual({ kind: 'range', from: 1, to: 9 });
  });

  it('carries a range that is one period of its zone as the period', () => {
    const day = {
      type: 'absolute',
      from: '2026-09-21T16:00:00.000Z',
      to: '2026-09-22T15:59:59.999Z',
      timeZone: 'Asia/Shanghai',
    };
    expect(
      partsOf({ field: 'createdAt', operator: 'BETWEEN', value: day }).value,
    ).toEqual({
      kind: 'period',
      unit: 'DAY',
      from: day.from,
      timeZone: 'Asia/Shanghai',
    });
    // Without a zone of its own, whose calendar it is on is the engine's to
    // say: two bounds, as ever.
    expect(
      partsOf({
        field: 'createdAt',
        operator: 'BETWEEN',
        value: { type: 'absolute', from: day.from, to: day.to },
      }).value,
    ).toEqual({ kind: 'range', from: day.from, to: day.to });
  });

  /**
   * A number band opens its records under `GTE` its key and `LT` the key
   * plus the interval; the bar says that pair as the one segment, so the
   * menu that named the band and the bar under the view opened from it
   * read alike (2026-09-23 review P2).
   */
  describe('a segment of a number line', () => {
    const gte = { field: 'amount', operator: 'GTE', value: 0 } as const;
    const lt = { field: 'amount', operator: 'LT', value: 500 } as const;
    const status: FilterTree['children'][number] = {
      field: 'status',
      operator: 'IN',
      value: ['PENDING'],
    };

    it('reads a field’s GTE and LT under "all of" as one segment', () => {
      const items = describeFilter(
        fields,
        tree(status, gte, lt),
        builtinFieldKinds,
      );
      expect(items).toHaveLength(2);
      expect(items[1]).toEqual({
        path: ['children', 1],
        paths: [
          ['children', 1],
          ['children', 2],
        ],
        text: 'Amount in [0, 500)',
        unresolved: false,
        field: 'amount',
        label: 'Amount',
        kind: 'number',
        value: { kind: 'segment', from: 0, to: 500 },
      });
    });

    it('joins the pair wherever it stands, in the place of the first', () => {
      const items = describeFilter(
        fields,
        tree(lt, status, gte),
        builtinFieldKinds,
      );
      expect(items.map(item => item.value?.kind)).toEqual(['segment', 'list']);
      expect(items[0].paths).toEqual([
        ['children', 0],
        ['children', 2],
      ]);
      // Inside a nested "all of", the pair is that group's one condition.
      const [group] = describeFilter(
        fields,
        tree({ op: 'and', children: [gte, lt] }),
        builtinFieldKinds,
      );
      const [inner] = group.items ?? [];
      expect(group.items).toHaveLength(1);
      expect(inner.value).toEqual({ kind: 'segment', from: 0, to: 500 });
      expect(inner.paths).toEqual([
        ['children', 0, 'children', 0],
        ['children', 0, 'children', 1],
      ]);
    });

    it('leaves the comparisons as they are where they are not one segment', () => {
      const kinds = (...children: FilterTree['children']) =>
        describeFilter(fields, tree(...children), builtinFieldKinds).map(
          item => item.value?.kind,
        );
      // Two lower bounds, an empty span, the edge included: no one segment.
      expect(
        kinds(gte, { ...gte, value: 100 }, lt).filter(
          kind => kind === 'segment',
        ),
      ).toEqual([]);
      expect(kinds({ ...gte, value: 500 }, lt)).toEqual(['text', 'text']);
      expect(kinds(gte, { ...lt, operator: 'LTE' })).toEqual(['text', 'text']);
      // Two fields, one bound each.
      expect(
        kinds(gte, { field: 'shippedAt', operator: 'LT', value: 500 }),
      ).toEqual(['text', 'text']);
      // Under "any of" the pair asks something else.
      const [either] = describeFilter(
        fields,
        { op: 'or', children: [gte, lt] },
        builtinFieldKinds,
      );
      expect(either.items?.map(item => item.value?.kind)).toEqual([
        'text',
        'text',
      ]);
    });

    it('carries how the field shows its numbers', () => {
      const [item] = describeFilter(
        fields,
        tree(
          { field: 'shippedAt', operator: 'GTE', value: 1 },
          { field: 'shippedAt', operator: 'LT', value: 2 },
        ),
        builtinFieldKinds,
      );
      expect(item).toMatchObject({
        cell: 'date',
        value: { kind: 'segment', from: 1, to: 2 },
      });
    });
  });

  it('carries the raw codes an enum holds beside the labels it resolved', () => {
    expect(
      partsOf({ field: 'status', operator: 'IN', value: ['PENDING'] }).value,
    ).toEqual({ kind: 'list', values: ['PENDING'], labels: ['Pending'] });
  });

  it('carries a reference as its ids and the labels stored with them', () => {
    expect(
      partsOf({
        field: 'customer',
        operator: 'IN',
        value: { items: [{ id: 'c-1', label: 'Acme' }] } as never,
      }).value,
    ).toEqual({ kind: 'list', values: ['c-1'], labels: ['Acme'] });
  });

  it('says a presence question has no value beside its operator', () => {
    expect(
      partsOf({ field: 'amount', operator: 'IS_NULL', value: null as never }),
    ).toMatchObject({ operator: 'IS_NULL', value: { kind: 'none' } });
  });

  /**
   * The same stored value is two different conditions. `BETWEEN` asks for
   * the span between now and seven days ago; `GTE` and `LTE` compare against
   * the moment at the far end of it, which is what `resolveDateTimeBound`
   * returns. A summary that called the second one "the last 7 days" would
   * name a span the query never ran over, so the value says which it is.
   */
  it('says whether a distance from now is a window or the moment at its end', () => {
    const last = { type: 'relative', amount: 7, unit: 'day' };
    const at = (operator: string, value: unknown = last) =>
      partsOf({ field: 'createdAt', operator, value } as never).value;

    expect(at('BETWEEN')).toEqual({
      kind: 'relative',
      amount: 7,
      unit: 'day',
      direction: 'past',
      bound: 'window',
    });
    expect(at('GTE')).toMatchObject({ bound: 'instant', direction: 'past' });
    expect(at('LTE')).toMatchObject({ bound: 'instant', direction: 'past' });
    expect(at('LTE', { ...last, direction: 'future' })).toMatchObject({
      bound: 'instant',
      direction: 'future',
    });
    expect(at('BETWEEN', { ...last, direction: 'future' })).toMatchObject({
      bound: 'window',
      direction: 'future',
    });
  });

  /**
   * An absolute `BETWEEN` with no upper edge compiles to `filter.gte(from)`,
   * and the old line said "from <date>" rather than naming a range. The
   * parts said `BETWEEN` over a range with a hole in it, so the badge read
   * "between <date>" — neither the range nor the condition that ran.
   */
  it('calls an open-ended window the GTE it compiles to', () => {
    const item = partsOf({
      field: 'createdAt',
      operator: 'BETWEEN',
      value: { type: 'absolute', from: '2026-01-01' } as never,
    });

    expect(item.operator).toBe('GTE');
    expect(item.value).toEqual({ kind: 'text', value: '2026-01-01' });
    // And the line a host reads is the one it always was.
    expect(item.text).toBe('Created from 2026-01-01');
  });

  it('keeps a named period a key, for the catalogue to name', () => {
    expect(
      partsOf({
        field: 'createdAt',
        operator: 'LTE',
        value: { type: 'preset', preset: 'nextQuarter' } as never,
      }).value,
    ).toEqual({ kind: 'preset', preset: 'nextQuarter' });
  });

  it('gives a single bound the one date the operator asks for', () => {
    const range = { type: 'absolute', from: '2026-01-01', to: '2026-01-31' };
    const at = (operator: string) =>
      partsOf({ field: 'createdAt', operator, value: range } as never).value;

    expect(at('GTE')).toEqual({ kind: 'text', value: '2026-01-01' });
    expect(at('LTE')).toEqual({ kind: 'text', value: '2026-01-31' });
    expect(at('BETWEEN')).toEqual({
      kind: 'range',
      from: '2026-01-01',
      to: '2026-01-31',
    });
  });

  /**
   * The bar shows a value the way its field shows it, and "the way its field
   * shows it" is `cell ?? kind` - the rule the table follows. A field that
   * overrides its kind showed as a date in the table and fell back to a raw
   * thirteen-digit timestamp in the bar, because only `kind` travelled.
   */
  it('carries the renderer key that overrides the field kind', () => {
    expect(
      partsOf({ field: 'shippedAt', operator: 'GT', value: 1_760_000_000_000 }),
    ).toMatchObject({ kind: 'number', cell: 'date' });
    // Nothing is invented for a field that declares none.
    expect(partsOf({ field: 'amount', operator: 'GT', value: 9 }).cell).toBe(
      undefined,
    );
  });

  /**
   * A label is what the definition calls a value, never a stand-in for the
   * value itself. `labelOf` falls back to the value stringified, and handing
   * that back as a label made the bar prefer it to the field's own
   * formatting - so an open-ended array of money read as bare numbers beside
   * a column showing the currency.
   */
  it('labels only the entries the definition actually named', () => {
    // No candidate set at all: raw values, and nothing claiming to be a label.
    expect(
      partsOf({ field: 'charges', operator: 'IN', value: [100, 5000] }).value,
    ).toEqual({ kind: 'list', values: [100, 5000] });

    // A code the definition dropped leaves a hole where its label would be,
    // so the ones it still names keep theirs.
    expect(
      partsOf({
        field: 'status',
        operator: 'IN',
        value: ['PENDING', 'RETIRED'],
      }).value,
    ).toEqual({
      kind: 'list',
      values: ['PENDING', 'RETIRED'],
      labels: ['Pending', undefined],
    });
  });

  /** And the English line reads exactly as it always did. */
  it('still reads an unnamed entry as the entry itself', () => {
    expect(
      partsOf({ field: 'charges', operator: 'IN', value: [100, 5000] }).text,
    ).toBe('Charges has any of 100, 5000');
    expect(
      partsOf({
        field: 'status',
        operator: 'IN',
        value: ['PENDING', 'RETIRED'],
      }).text,
    ).toBe('Status IN Pending, RETIRED');
  });

  it('says a value the kind cannot read is blank, not a reading of it', () => {
    // `status` is an enum, whose operators take a list.
    expect(
      partsOf({ field: 'status', operator: 'EQ', value: 'PENDING' }).value,
    ).toEqual({ kind: 'blank' });
  });

  it('keeps the question a vanished field was asked under', () => {
    // Nothing is known about the value; the operator still is, and a bar
    // that dropped it would say less than the one line it replaced.
    expect(
      partsOf({ field: 'gone', operator: 'EQ', value: 'x' }),
    ).toMatchObject({
      operator: 'EQ',
      value: { kind: 'blank' },
      unresolved: true,
    });
  });

  it('gives a group its own conditions, under its own operator', () => {
    const items = describeFilter(
      fields,
      {
        op: 'or',
        children: [
          { field: 'id', operator: 'EQ', value: 'o-1' },
          { field: 'amount', operator: 'GT', value: 9 },
        ],
      },
      builtinFieldKinds,
    );

    expect(items[0].group).toBe('or');
    expect(items[0].items?.map(item => item.field)).toEqual(['id', 'amount']);
    // A group is not a condition: it names no field and has no value.
    expect(items[0].field).toBeUndefined();
    expect(items[0].value).toBeUndefined();
  });

  it('gives a predicate the conditions inside it and the operator joining them', () => {
    const withItems: FieldDefinition[] = [
      {
        name: 'items',
        label: 'Items',
        kind: 'elementMatch',
        elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
      },
    ];
    const item = describeFilter(
      withItems,
      tree({
        field: 'items',
        operator: 'ELEMENT_MATCH',
        value: {
          op: 'or',
          children: [{ field: 'items.sku', operator: 'EQ', value: 'A' }],
        } as never,
      }),
      builtinFieldKinds,
    )[0];

    expect(item).toMatchObject({
      field: 'items',
      operator: 'ELEMENT_MATCH',
      value: { kind: 'none' },
      group: 'or',
    });
    expect(item.items?.map(inner => inner.text)).toEqual(['SKU EQ A']);
    // And the English line is what it always was.
    expect(item.text).toBe('Items has an entry where SKU EQ A');
  });

  /**
   * A predicate holds conditions of its own, and the element definition can
   * lose a field the same way the root one can. The bar draws one badge for
   * the outer condition, so a field that is gone inside has to mark it.
   */
  it('marks a predicate whose own condition names a field that is gone', () => {
    const withItems: FieldDefinition[] = [
      {
        name: 'items',
        label: 'Items',
        kind: 'elementMatch',
        elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
      },
    ];
    const item = describeFilter(
      withItems,
      tree({
        field: 'items',
        operator: 'ELEMENT_MATCH',
        value: {
          op: 'and',
          children: [{ field: 'items.retired', operator: 'EQ', value: 'A' }],
        } as never,
      }),
      builtinFieldKinds,
    )[0];

    expect(item.items?.[0]).toMatchObject({ unresolved: true });
    expect(item.unresolved).toBe(true);
  });

  /**
   * `IN` over an array asks whether the array holds any of the candidates.
   * The generic word for that operator says a value is one of them, which is
   * a different question, so the kind names the relation and the bar words
   * that instead.
   */
  it('gives an array condition the relation its operator stands for', () => {
    const withTags: FieldDefinition[] = [
      { name: 'tags', label: 'Tags', kind: 'array' },
    ];
    const [any, none] = ['IN', 'NOT_IN'].map(
      operator =>
        describeFilter(
          withTags,
          tree({ field: 'tags', operator: operator as 'IN', value: ['a'] }),
          builtinFieldKinds,
        )[0],
    );

    expect(any).toMatchObject({ operator: 'IN', relation: 'has-any' });
    expect(none).toMatchObject({ operator: 'NOT_IN', relation: 'has-none' });
    // The English line is what it always was.
    expect(any?.text).toBe('Tags has any of a');
  });

  /**
   * `describeFilter` folds an `or` root with several children, and any
   * non-empty `nor` root, into one group item carrying that operator —
   * at the top of a bar the items sit side by side and nothing else would
   * say how they combine. A predicate states the operator beside them
   * anyway, so handing the fold on read it twice, and read a one-condition
   * `nor` as its own negation negated.
   */
  it('does not hand a predicate the fold that already states its operator', () => {
    const withItems: FieldDefinition[] = [
      {
        name: 'items',
        label: 'Items',
        kind: 'elementMatch',
        elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
      },
    ];
    const predicate = (op: string, ...skus: string[]) =>
      describeFilter(
        withItems,
        tree({
          field: 'items',
          operator: 'ELEMENT_MATCH',
          value: {
            op,
            children: skus.map(sku => ({
              field: 'items.sku',
              operator: 'EQ',
              value: sku,
            })),
          },
        } as never),
        builtinFieldKinds,
      )[0];

    const anyOf = predicate('or', 'A', 'B');
    expect(anyOf.group).toBe('or');
    // The conditions themselves, not one group item wrapping them.
    expect(anyOf.items?.map(inner => inner.field)).toEqual([
      'items.sku',
      'items.sku',
    ]);
    expect(anyOf.items?.every(inner => inner.items === undefined)).toBe(true);

    const noneOf = predicate('nor', 'A');
    expect(noneOf.group).toBe('nor');
    expect(noneOf.items?.map(inner => inner.text)).toEqual(['SKU EQ A']);

    // A group somebody actually wrote inside the predicate still nests.
    const nested = describeFilter(
      withItems,
      tree({
        field: 'items',
        operator: 'ELEMENT_MATCH',
        value: {
          op: 'and',
          children: [
            { field: 'items.sku', operator: 'EQ', value: 'A' },
            {
              op: 'or',
              children: [{ field: 'items.sku', operator: 'EQ', value: 'B' }],
            },
          ],
        },
      } as never),
      builtinFieldKinds,
    )[0];
    expect(nested.items?.map(inner => inner.group)).toEqual([undefined, 'or']);
  });
});
