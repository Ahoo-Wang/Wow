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
  focusOn,
  groupFor,
  splitBy,
} from '../src/analysis/index.js';
import { builtinFieldKinds } from '../src/filter/index.js';
import type {
  AnalysisGroup,
  FieldDefinition,
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

  it('says nothing for an analysis over expanded elements, or an unknown field', () => {
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
