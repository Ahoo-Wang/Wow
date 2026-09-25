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
  bucketRange,
  drillConditions,
  drillFilter,
  drillGroups,
  focusOn,
  groupFor,
  narrowsTo,
  splitBy,
} from '../src/analysis/index.js';
import { builtinFieldKinds } from '../src/filter/index.js';
import type {
  AnalysisDateUnit,
  AnalysisGroup,
  AnalysisGroupType,
  FieldDefinition,
  FilterLeaf,
  FilterNode,
  FilterTree,
} from '../src/model/index.js';
import { analysisConfig } from './fixtures.js';

const SHANGHAI = 'Asia/Shanghai';
const NEW_YORK = 'America/New_York';

/** Midnight of a wall-clock day in a zone, as an epoch instant. */
function midnight(day: string, timeZone: string): number {
  const utc = Date.parse(`${day}T00:00:00Z`);
  // The zone's offset at that moment, read the way the kernel reads it.
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    hour: '2-digit',
    minute: '2-digit',
  }).formatToParts(new Date(utc));
  const hour = Number(parts.find(part => part.type === 'hour')?.value);
  const minute = Number(parts.find(part => part.type === 'minute')?.value);
  // At UTC midnight the zone reads `hour:minute` past (or before) its own
  // midnight; step back by that much.
  const ahead = hour * 60 + minute;
  const offset = ahead >= 12 * 60 ? ahead - 24 * 60 : ahead;
  return utc - offset * 60_000;
}

const DAY = 24 * 3_600_000;

describe('bucketRange', () => {
  it('advances clock units by their length', () => {
    const start = Date.parse('2026-09-18T09:00:00Z');
    expect(bucketRange('HOUR', start, SHANGHAI)).toEqual({
      from: start,
      to: start + 3_600_000,
    });
    expect(bucketRange('MINUTE', start, SHANGHAI).to).toBe(start + 60_000);
    expect(bucketRange('SECOND', start, SHANGHAI).to).toBe(start + 1000);
  });

  it('advances a day on the wall clock of the zone the bucket was cut in', () => {
    const start = midnight('2026-09-18', SHANGHAI);
    expect(bucketRange('DAY', start, SHANGHAI)).toEqual({
      from: start,
      to: midnight('2026-09-19', SHANGHAI),
    });
    expect(bucketRange('WEEK', start, SHANGHAI).to).toBe(
      midnight('2026-09-25', SHANGHAI),
    );
  });

  it('advances a month, a quarter and a year on the calendar', () => {
    const start = midnight('2026-01-31', SHANGHAI);
    // `Date.UTC` carries the 31st of a 28-day month into March, which is
    // one month later by the calendar's own arithmetic.
    expect(bucketRange('MONTH', start, SHANGHAI).to).toBe(
      midnight('2026-03-03', SHANGHAI),
    );
    const quarter = midnight('2026-04-01', SHANGHAI);
    expect(bucketRange('QUARTER', quarter, SHANGHAI).to).toBe(
      midnight('2026-07-01', SHANGHAI),
    );
    expect(bucketRange('YEAR', quarter, SHANGHAI).to).toBe(
      midnight('2027-04-01', SHANGHAI),
    );
  });

  it('keeps a day that crosses a daylight-saving change as long as the calendar says', () => {
    // New York falls back on 2026-11-01: that day has 25 hours.
    const start = midnight('2026-11-01', NEW_YORK);
    const range = bucketRange('DAY', start, NEW_YORK);
    expect(range.to - range.from).toBe(DAY + 3_600_000);
    // And springs forward on 2026-03-08: 23 hours.
    const spring = midnight('2026-03-08', NEW_YORK);
    expect(bucketRange('DAY', spring, NEW_YORK).to - spring).toBe(
      DAY - 3_600_000,
    );
  });
});

const FIELDS: FieldDefinition[] = [
  { name: 'warehouse', label: 'Warehouse', kind: 'string' },
  {
    name: 'status',
    label: 'Status',
    kind: 'enum',
    options: [
      { value: 'PENDING', label: 'Pending' },
      { value: 'SHIPPED', label: 'Shipped' },
    ],
  },
  { name: 'customer', label: 'Customer', kind: 'reference', remote: 'c' },
  { name: 'amount', label: 'Amount', kind: 'number' },
  { name: 'createdAt', label: 'Created', kind: 'datetime' },
];
const context = { timeZone: SHANGHAI };

describe('drillConditions', () => {
  it('turns a TERMS group into the operator its kind understands', () => {
    const config = analysisConfig({
      groups: [
        { alias: 'warehouse', field: 'warehouse', type: 'TERMS' },
        { alias: 'status', field: 'status', type: 'TERMS' },
        { alias: 'customer', field: 'customer', type: 'TERMS' },
      ],
    });
    expect(
      drillConditions(
        config,
        FIELDS,
        builtinFieldKinds,
        { warehouse: 'CN', status: 'PENDING', customer: 'c-1' },
        context,
      ),
    ).toEqual([
      { field: 'warehouse', operator: 'EQ', value: 'CN' },
      { field: 'status', operator: 'IN', value: ['PENDING'] },
      {
        field: 'customer',
        operator: 'IN',
        value: { items: [{ id: 'c-1', label: 'c-1' }] },
      },
    ]);
  });

  it('reads the sentinel bucket, and a missing value, as no value', () => {
    const config = analysisConfig({
      groups: [
        {
          alias: 'warehouse',
          field: 'warehouse',
          type: 'TERMS',
          missingKey: '(empty)',
        },
      ],
    });
    const asked = (warehouse: unknown) =>
      drillConditions(
        config,
        FIELDS,
        builtinFieldKinds,
        { warehouse },
        context,
      );
    expect(asked('(empty)')).toEqual([
      { field: 'warehouse', operator: 'IS_NULL', value: null },
    ]);
    expect(asked(null)).toEqual([
      { field: 'warehouse', operator: 'IS_NULL', value: null },
    ]);
  });

  it('turns a HISTOGRAM group into its half-open interval', () => {
    const config = analysisConfig({
      groups: [
        { alias: 'band', field: 'amount', type: 'HISTOGRAM', interval: 500 },
      ],
    });
    expect(
      drillConditions(
        config,
        FIELDS,
        builtinFieldKinds,
        { band: 1000 },
        context,
      ),
    ).toEqual([
      { field: 'amount', operator: 'GTE', value: 1000 },
      { field: 'amount', operator: 'LT', value: 1500 },
    ]);
  });

  it('turns a DATE_HISTOGRAM group into the bucket, closed a millisecond short (K1)', () => {
    const config = analysisConfig({
      groups: [
        {
          alias: 'month',
          field: 'createdAt',
          type: 'DATE_HISTOGRAM',
          unit: 'MONTH',
          timeZone: SHANGHAI,
        },
      ],
    });
    const start = midnight('2026-09-01', SHANGHAI);
    expect(
      drillConditions(
        config,
        FIELDS,
        builtinFieldKinds,
        { month: start },
        { timeZone: NEW_YORK },
      ),
    ).toEqual([
      {
        field: 'createdAt',
        operator: 'BETWEEN',
        value: {
          type: 'absolute',
          from: new Date(start).toISOString(),
          to: new Date(midnight('2026-10-01', SHANGHAI) - 1).toISOString(),
          timeZone: SHANGHAI,
        },
      },
    ]);
  });

  it("cuts in the engine's zone when the group names none", () => {
    const config = analysisConfig({
      groups: [
        {
          alias: 'day',
          field: 'createdAt',
          type: 'DATE_HISTOGRAM',
          unit: 'DAY',
        },
      ],
    });
    const start = midnight('2026-09-18', NEW_YORK);
    const [leaf] = drillConditions(
      config,
      FIELDS,
      builtinFieldKinds,
      { day: String(start) },
      { timeZone: NEW_YORK },
    )!;
    expect(leaf).toMatchObject({
      operator: 'BETWEEN',
      value: {
        to: new Date(midnight('2026-09-19', NEW_YORK) - 1).toISOString(),
        timeZone: NEW_YORK,
      },
    });
  });

  it('says nothing for a bucket key it cannot read back', () => {
    // The key comes off the result with the row. A band key that is not a
    // finite number, or a date bucket that is not an instant, cannot be
    // turned back into the interval it stood for — and a drill that guessed
    // would open a records view under conditions nobody asked for. One
    // unreadable group takes the whole row's conditions with it: a
    // half-written drill is narrower than the row it came from.
    const band = analysisConfig({
      groups: [
        { alias: 'band', field: 'amount', type: 'HISTOGRAM', interval: 500 },
        { alias: 'warehouse', field: 'warehouse', type: 'TERMS' },
      ],
    });
    for (const key of ['1000', Number.NaN, Number.POSITIVE_INFINITY, null])
      expect(
        drillConditions(
          band,
          FIELDS,
          builtinFieldKinds,
          { band: key, warehouse: 'CN' },
          context,
        ),
      ).toBeNull();

    const month = analysisConfig({
      groups: [
        {
          alias: 'month',
          field: 'createdAt',
          type: 'DATE_HISTOGRAM',
          unit: 'MONTH',
        },
      ],
    });
    for (const key of ['last month', null, {}])
      expect(
        drillConditions(
          month,
          FIELDS,
          builtinFieldKinds,
          { month: key },
          context,
        ),
      ).toBeNull();
  });

  // Over an array the definition holds, see elementDrill.test.ts (D38).
  it('says nothing over an array the definition does not hold, or an unknown field', () => {
    expect(
      drillConditions(
        analysisConfig({ elements: [{ path: 'items' }] }),
        FIELDS,
        builtinFieldKinds,
        { warehouse: 'CN' },
        context,
      ),
    ).toBeNull();
    expect(
      drillConditions(
        analysisConfig({
          groups: [{ alias: 'ghost', field: 'ghost', type: 'TERMS' }],
        }),
        FIELDS,
        builtinFieldKinds,
        { ghost: 'x' },
        context,
      ),
    ).toBeNull();
  });
});

describe('drillGroups', () => {
  /**
   * The same conditions, kept by the dimension they came from: a menu names
   * the group pressed one dimension at a time, and a bucket reads better as
   * its value than as the two instants that bound it.
   */
  it('keeps each dimension with its value and its own conditions', () => {
    const config = analysisConfig({
      groups: [
        { alias: 'warehouse', field: 'warehouse', type: 'TERMS' },
        { alias: 'band', field: 'amount', type: 'HISTOGRAM', interval: 500 },
      ],
    });
    const row = { warehouse: 'CN', band: 1000 };
    const drilled = drillGroups(
      config,
      FIELDS,
      builtinFieldKinds,
      row,
      context,
    );
    expect(
      drilled?.map(entry => [entry.group.alias, entry.value, entry.conditions]),
    ).toEqual([
      [
        'warehouse',
        'CN',
        [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
      ],
      [
        'band',
        1000,
        [
          { field: 'amount', operator: 'GTE', value: 1000 },
          { field: 'amount', operator: 'LT', value: 1500 },
        ],
      ],
    ]);
    // Flattened, they are `drillConditions` exactly.
    expect(drilled?.flatMap(entry => entry.conditions)).toEqual(
      drillConditions(config, FIELDS, builtinFieldKinds, row, context),
    );
    expect(
      drillGroups(
        analysisConfig({
          groups: [{ alias: 'gone', field: 'nowhere', type: 'TERMS' }],
        }),
        FIELDS,
        builtinFieldKinds,
        row,
        context,
      ),
    ).toBeNull();
  });
});

describe('drillFilter', () => {
  const added = [{ field: 'warehouse', operator: 'EQ', value: 'CN' } as const];

  it('flattens the row into a simple analysis filter', () => {
    const applied: FilterTree = {
      op: 'and',
      children: [{ field: 'status', operator: 'IN', value: ['PENDING'] }],
    };
    expect(drillFilter(applied, added)).toEqual({
      op: 'and',
      children: [...applied.children, ...added],
    });
  });

  it('nests an advanced analysis filter under the row', () => {
    const applied: FilterTree = {
      op: 'or',
      children: [
        { field: 'status', operator: 'IN', value: ['PENDING'] },
        { field: 'status', operator: 'IN', value: ['SHIPPED'] },
      ],
    };
    expect(drillFilter(applied, added)).toEqual({
      op: 'and',
      children: [applied, ...added],
    });
  });
});

describe('narrowsTo', () => {
  const group: FilterNode[] = [
    { field: 'warehouse', operator: 'EQ', value: 'CN' },
    { field: 'status', operator: 'IN', value: ['PENDING'] },
  ];
  const own: FilterNode = { field: 'amount', operator: 'GT', value: 10 };

  it('holds while every condition of the group is still a conjunct', () => {
    const simple = drillFilter({ op: 'and', children: [own] }, group);
    expect(narrowsTo(simple, group)).toBe(true);
    // Nested by an advanced range, then again by a board's filters: still
    // all of them, however deep the "all of" goes.
    const advanced: FilterTree = {
      op: 'or',
      children: [own, { field: 'amount', operator: 'LT', value: 0 }],
    };
    const twice = drillFilter(drillFilter(advanced, group), [own]);
    expect(narrowsTo(twice, group)).toBe(true);
    expect(narrowsTo({ op: 'and', children: [] }, [])).toBe(true);
  });

  it('stops holding once a condition is taken off, edited or negated', () => {
    const [warehouse, status] = group;
    expect(narrowsTo({ op: 'and', children: [own, status] }, group)).toBe(
      false,
    );
    expect(
      narrowsTo(
        {
          op: 'and',
          children: [
            { field: 'warehouse', operator: 'EQ', value: 'US' },
            status,
          ],
        },
        group,
      ),
    ).toBe(false);
    expect(
      narrowsTo(
        { op: 'and', children: [{ op: 'nor', children: [warehouse] }, status] },
        group,
      ),
    ).toBe(false);
  });

  it('does not count a condition under "any of": that no longer narrows', () => {
    const [warehouse, status] = group;
    expect(
      narrowsTo(
        {
          op: 'and',
          children: [{ op: 'or', children: [warehouse, own] }, status],
        },
        group,
      ),
    ).toBe(false);
  });
});

const ROW = [{ field: 'warehouse', operator: 'EQ', value: 'CN' } as const];

describe('focusOn', () => {
  it('adds the row to the range and leaves everything else alone', () => {
    const config = analysisConfig({
      filter: {
        op: 'and',
        children: [{ field: 'status', operator: 'IN', value: ['PENDING'] }],
      },
    });

    expect(focusOn(config, ROW)).toEqual({
      filter: {
        op: 'and',
        children: [
          { field: 'status', operator: 'IN', value: ['PENDING'] },
          ...ROW,
        ],
      },
      // A row's conditions flatten into the simple tree they were added to.
      filterMode: 'simple',
    });
  });

  it('keeps an advanced range advanced, because the row nests under it', () => {
    const config = analysisConfig({
      filterMode: 'advanced',
      filter: {
        op: 'or',
        children: [
          { field: 'status', operator: 'IN', value: ['PENDING'] },
          { field: 'status', operator: 'IN', value: ['SHIPPED'] },
        ],
      },
    });

    expect(focusOn(config, ROW).filterMode).toBe('advanced');
  });
});

describe('splitBy', () => {
  const status: AnalysisGroup = {
    type: 'TERMS',
    field: 'status',
    alias: 'status_group',
  };

  /**
   * The same question, of this one group, by another dimension: the range
   * narrows to the row and the dimension replaces the one the row is of —
   * "warehouse CN, by status", never "warehouse and status".
   */
  it('replaces the dimensions and narrows the range to the row', () => {
    const patch = splitBy(analysisConfig(), ROW, status);

    expect(patch.groups).toEqual([status]);
    expect(patch.filter).toEqual({ op: 'and', children: [...ROW] });
  });

  /**
   * Sort entries name aliases, table columns name aliases, and a chart's
   * slots name them too: the alias the old dimension carried is gone, so a
   * patch that kept any of the three would be refused by `validateAnalysis`
   * the moment it ran.
   */
  it('lets go of what named the dimension that is gone', () => {
    const config = analysisConfig({
      sort: [{ alias: 'warehouse', direction: 'DESC' }],
      table: { columns: [{ alias: 'warehouse' }], totals: true },
    });

    const patch = splitBy(config, ROW, status);

    expect(patch.sort).toEqual([]);
    // The table's own settings stay; only its columns are re-chosen.
    expect(patch.table).toEqual({ columns: [], totals: true });
    // The chart's family is re-fitted to the dimension now in force.
    expect(patch.chart).toEqual({
      type: 'bar',
      cartesian: { x: 'status_group', series: [{ metric: 'orders' }] },
    });
  });
});

describe('groupFor', () => {
  const warehouse: FieldDefinition = {
    name: 'warehouse',
    label: 'Warehouse',
    kind: 'string',
  };
  const createdAt: FieldDefinition = {
    name: 'createdAt',
    label: 'Created',
    kind: 'datetime',
  };
  const amount: FieldDefinition = {
    name: 'amount',
    label: 'Amount',
    kind: 'number',
  };

  /**
   * The dimension a field becomes when a group is split by it, under the
   * alias the editor's own picker would have given it: a split reads as if
   * the user had chosen the field there, which is what makes it savable.
   */
  it('groups by value where the capability offers it, sentinel and all', () => {
    expect(
      groupFor(
        warehouse,
        { groups: ['TERMS', 'DATE_HISTOGRAM'], dateUnits: [] },
        builtinFieldKinds.get('string'),
      ),
    ).toEqual({
      type: 'TERMS',
      field: 'warehouse',
      alias: 'warehouse_group',
      // One string per record, so the records with none have a bucket.
      missingKey: '(empty)',
    });
  });

  it('buckets a date by the finest unit the capability offers', () => {
    expect(
      groupFor(createdAt, { groups: ['DATE_HISTOGRAM'], dateUnits: ['MONTH'] }),
    ).toEqual({
      type: 'DATE_HISTOGRAM',
      field: 'createdAt',
      alias: 'createdAt_group',
      unit: 'MONTH',
    });
    // A capability that names the group but no unit still has to bucket.
    expect(
      groupFor(createdAt, { groups: ['DATE_HISTOGRAM'], dateUnits: [] }),
    ).toMatchObject({ unit: 'DAY' });
  });

  it('falls back to a band of values', () => {
    expect(groupFor(amount, { groups: ['HISTOGRAM'], dateUnits: [] })).toEqual({
      type: 'HISTOGRAM',
      field: 'amount',
      alias: 'amount_group',
      interval: 1,
    });
  });
});

/**
 * Whether a record satisfies every leaf, reading only the operators a drill
 * writes. An operator it does not know fails the test rather than passing
 * a record it cannot judge.
 */
function selects(
  conditions: readonly FilterNode[],
  record: Record<string, unknown>,
): boolean {
  return conditions.every(node => {
    const { field, operator, value } = node as FilterLeaf;
    const actual = record[field];
    switch (operator) {
      case 'EQ':
        return actual === value;
      case 'IN':
        return Array.isArray(value)
          ? value.includes(actual as never)
          : (value as { items: { id: unknown }[] }).items.some(
              item => item.id === actual,
            );
      case 'IS_NULL':
        return actual === null || actual === undefined;
      case 'GTE':
        return typeof actual === 'number' && actual >= (value as number);
      case 'LT':
        return typeof actual === 'number' && actual < (value as number);
      case 'BETWEEN': {
        const { from, to } = value as { from: string; to: string };
        return (
          typeof actual === 'number' &&
          actual >= Date.parse(from) &&
          actual <= Date.parse(to)
        );
      }
      default:
        throw new Error(`the drill wrote ${operator}, which this cannot read`);
    }
  });
}

describe('a dimension groupFor builds, drilled back', () => {
  const byName = new Map(FIELDS.map(field => [field.name, field]));
  const hour = Date.parse('2026-09-18T09:00:00Z');

  /**
   * One case per group type a field can take, with the bucket key a result
   * row carries for it and records on both sides of that bucket's edges:
   * `inside` are the records Wow counted into the row, `outside` the ones it
   * did not — a neighbouring bucket, or the missing value when the key names
   * a value. The dimension is built by `groupFor`, so the default each type
   * starts with (the sentinel bucket, the unit band, the unit) is the one
   * under test.
   */
  const cases: {
    name: string;
    field: string;
    type: AnalysisGroupType;
    unit?: AnalysisDateUnit;
    key: unknown;
    inside: unknown[];
    outside: unknown[];
  }[] = [
    {
      name: 'a string by value',
      field: 'warehouse',
      type: 'TERMS',
      key: 'CN',
      inside: ['CN'],
      outside: ['US', null, undefined],
    },
    {
      name: "a string's sentinel bucket",
      field: 'warehouse',
      type: 'TERMS',
      key: '(empty)',
      inside: [null, undefined],
      outside: ['CN'],
    },
    {
      name: 'an enum by value',
      field: 'status',
      type: 'TERMS',
      key: 'PENDING',
      inside: ['PENDING'],
      outside: ['SHIPPED', null],
    },
    {
      name: 'a reference by value',
      field: 'customer',
      type: 'TERMS',
      key: 'c-1',
      inside: ['c-1'],
      outside: ['c-2', null],
    },
    {
      // A reference carries no sentinel, so Wow hands the missing group
      // back under a null key.
      name: "a reference's missing values",
      field: 'customer',
      type: 'TERMS',
      key: null,
      inside: [null, undefined],
      outside: ['c-1'],
    },
    {
      name: 'a number by value',
      field: 'amount',
      type: 'TERMS',
      key: 42,
      inside: [42],
      outside: [41, 43, null],
    },
    {
      name: 'a number by band',
      field: 'amount',
      type: 'HISTOGRAM',
      key: 3,
      inside: [3, 3.5, 3.999],
      outside: [2.999, 4, null],
    },
    {
      name: 'a date by hour',
      field: 'createdAt',
      type: 'DATE_HISTOGRAM',
      unit: 'HOUR',
      key: hour,
      inside: [hour, hour + 3_599_999],
      outside: [hour - 1, hour + 3_600_000, null],
    },
    ...(
      [
        ['DAY', '2026-09-18', '2026-09-19'],
        ['WEEK', '2026-09-14', '2026-09-21'],
        ['MONTH', '2026-09-01', '2026-10-01'],
        ['QUARTER', '2026-07-01', '2026-10-01'],
        ['YEAR', '2026-01-01', '2027-01-01'],
      ] as const
    ).map(([unit, start, next]) => {
      const from = midnight(start, SHANGHAI);
      const to = midnight(next, SHANGHAI);
      return {
        name: `a date by ${unit.toLowerCase()}`,
        field: 'createdAt',
        type: 'DATE_HISTOGRAM' as const,
        unit,
        key: from,
        inside: [from, to - 1],
        outside: [from - 1, to, null],
      };
    }),
  ];

  it.each(cases)(
    'selects exactly the bucket of $name',
    ({ field: name, type, unit, key, inside, outside }) => {
      const field = byName.get(name)!;
      const group = groupFor(
        field,
        { groups: [type], dateUnits: unit ? [unit] : [] },
        builtinFieldKinds.get(field.kind),
      );
      const conditions = drillConditions(
        analysisConfig({ groups: [group] }),
        FIELDS,
        builtinFieldKinds,
        { [group.alias]: key },
        context,
      );

      expect(conditions).not.toBeNull();
      for (const value of inside)
        expect(selects(conditions!, { [name]: value })).toBe(true);
      for (const value of outside)
        expect(selects(conditions!, { [name]: value })).toBe(false);
    },
  );
});
