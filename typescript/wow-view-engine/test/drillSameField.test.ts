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
 * A drill whose row is on the field the analysis is already scoped by: the
 * day of 「2025 双 11 前后」, a brushed stretch of 「截至昨日」, a status
 * among the statuses asked about. The drilled filter must be one a view
 * admits — one condition per field in a group — and still say exactly the
 * records behind the row.
 */

import { describe, expect, it } from 'vitest';
import {
  drillConditions,
  drillFilter,
  drillGroups,
  focusOn,
  narrowsTo,
} from '../src/analysis/index.js';
import { drillRecordConditions, drillSpan } from '../src/analysis/drill.js';
import {
  builtinFieldKinds,
  isSimpleTree,
  validateFilter,
} from '../src/filter/index.js';
import type {
  AnalysisGroup,
  FieldDefinition,
  FilterLeaf,
  FilterNode,
  FilterTree,
} from '../src/model/index.js';
import { analysisConfig } from './fixtures.js';

const SHANGHAI = 'Asia/Shanghai';
const context = { timeZone: SHANGHAI };

const FIELDS: FieldDefinition[] = [
  {
    name: 'status',
    label: 'Status',
    kind: 'enum',
    options: [
      { value: 'PENDING', label: 'Pending' },
      { value: 'SHIPPED', label: 'Shipped' },
    ],
  },
  { name: 'amount', label: 'Amount', kind: 'number' },
  { name: 'createdAt', label: 'Created', kind: 'datetime' },
];

const DAYS: AnalysisGroup = {
  alias: 'day',
  field: 'createdAt',
  type: 'DATE_HISTOGRAM',
  unit: 'DAY',
};

/** 2025-10-15 through 2025-11-20 in Shanghai, as A-11 scopes itself. */
const AUTUMN: FilterLeaf = {
  field: 'createdAt',
  operator: 'BETWEEN',
  value: {
    type: 'absolute',
    from: '2025-10-15T00:00:00+08:00',
    to: '2025-11-20T23:59:59.999+08:00',
  },
};

/** Up to yesterday, as A-02 scopes itself: a bound that moves with the clock. */
const TO_YESTERDAY: FilterLeaf = {
  field: 'createdAt',
  operator: 'LTE',
  value: { type: 'preset', preset: 'yesterday' },
};

const PENDING: FilterLeaf = {
  field: 'status',
  operator: 'IN',
  value: ['PENDING'],
};

function scoped(...children: FilterNode[]): FilterTree {
  return { op: 'and', children };
}

/** A day's key: its Shanghai midnight. */
function day(date: string): string {
  return new Date(`${date}T00:00:00+08:00`).toISOString();
}

function rowOf(filter: FilterTree, groups: AnalysisGroup[], row: object) {
  const conditions = drillConditions(
    analysisConfig({ filter, groups }),
    FIELDS,
    builtinFieldKinds,
    row,
    context,
  );
  expect(conditions).not.toBeNull();
  return conditions!;
}

function spanOf(filter: FilterTree, first: object, last: object) {
  const config = analysisConfig({ filter, groups: [DAYS] });
  const drilled = drillSpan(
    config,
    FIELDS,
    builtinFieldKinds,
    first,
    last,
    context,
  );
  expect(drilled).not.toBeNull();
  return drillRecordConditions(config, FIELDS, builtinFieldKinds, drilled!)!;
}

/** What a view would say of the tree: nothing, for a tree it admits. */
function admitted(tree: FilterTree) {
  return validateFilter(FIELDS, tree, builtinFieldKinds);
}

describe('drilling into a group on the field the analysis is scoped by', () => {
  it('a day inside an absolute stretch takes the stretch’s place (查看这些记录)', () => {
    const scope = scoped(PENDING, AUTUMN);
    const row = rowOf(scope, [DAYS], { day: day('2025-11-11') });

    const filter = drillFilter(scope, row);

    expect(admitted(filter)).toEqual([]);
    // One condition on the field, the day's: it already says every record
    // the stretch would have allowed of it.
    expect(filter).toEqual(scoped(PENDING, row[0]));
    expect(isSimpleTree(filter)).toBe(true);
    expect(narrowsTo(filter, row)).toBe(true);
  });

  it('a brushed stretch inside an absolute stretch takes its place (只看这段时间)', () => {
    const scope = scoped(AUTUMN);
    const row = spanOf(
      scope,
      { day: day('2025-11-09') },
      { day: day('2025-11-12') },
    );

    const filter = drillFilter(scope, row);

    expect(admitted(filter)).toEqual([]);
    expect(filter).toEqual(scoped(row[0]));
    const value = (row[0] as FilterLeaf).value as { from: string; to: string };
    expect(Date.parse(value.from)).toBe(
      Date.parse('2025-11-09T00:00:00+08:00'),
    );
    expect(Date.parse(value.to)).toBe(
      Date.parse('2025-11-13T00:00:00+08:00') - 1,
    );
  });

  it('a stretch that moves with the clock stays beside the row, nested', () => {
    const scope = scoped(PENDING, TO_YESTERDAY);
    const row = spanOf(
      scope,
      { day: day('2025-11-09') },
      { day: day('2025-11-12') },
    );

    const filter = drillFilter(scope, row);

    expect(admitted(filter)).toEqual([]);
    // Both hold: the scope where it stood, the row in an "all of" of its own.
    expect(filter).toEqual(scoped(PENDING, TO_YESTERDAY, scoped(...row)));
    expect(isSimpleTree(filter)).toBe(false);
    expect(narrowsTo(filter, row)).toBe(true);
  });

  it('a day only partly inside the stretch keeps both, so no record outside either', () => {
    const scope = scoped({
      ...AUTUMN,
      value: {
        type: 'absolute',
        from: '2025-11-11T12:00:00+08:00',
        to: '2025-11-20T23:59:59.999+08:00',
      },
    });
    const row = rowOf(scope, [DAYS], { day: day('2025-11-11') });

    const filter = drillFilter(scope, row);

    expect(admitted(filter)).toEqual([]);
    expect(filter).toEqual(scoped(scope.children[0], scoped(...row)));
  });

  it('a band of a number histogram beside a bound on the same number', () => {
    const scope = scoped({ field: 'amount', operator: 'GTE', value: 50 });
    const row = rowOf(
      scope,
      [{ alias: 'band', field: 'amount', type: 'HISTOGRAM', interval: 100 }],
      { band: 100 },
    );

    const filter = drillFilter(scope, row);

    expect(admitted(filter)).toEqual([]);
    // The band's pair stays one pair, in one group of its own.
    expect(filter).toEqual(scoped(scope.children[0], scoped(...row)));
  });

  it('a status among the statuses asked about (只看这一组)', () => {
    const scope = scoped({
      field: 'status',
      operator: 'IN',
      value: ['PENDING', 'SHIPPED'],
    });
    const status: AnalysisGroup = {
      alias: 'status',
      field: 'status',
      type: 'TERMS',
    };
    const config = analysisConfig({ filter: scope, groups: [status] });
    const row = drillGroups(
      config,
      FIELDS,
      builtinFieldKinds,
      {
        status: 'SHIPPED',
      },
      context,
    )!.flatMap(group => group.conditions);

    const focused = focusOn(config, row);

    expect(admitted(focused.filter)).toEqual([]);
    expect(focused.filter).toEqual(scoped(scope.children[0], scoped(...row)));
    // Nested, so the analysis reads it in the advanced editor.
    expect(focused.filterMode).toBe('advanced');
  });

  it('只看这一组 on a day inside the stretch stays simple', () => {
    const config = analysisConfig({ filter: scoped(AUTUMN), groups: [DAYS] });
    const row = rowOf(config.filter, [DAYS], { day: day('2025-11-11') });

    const focused = focusOn(config, row);

    expect(admitted(focused.filter)).toEqual([]);
    expect(focused.filter).toEqual(scoped(row[0]));
    expect(focused.filterMode).toBe('simple');
  });
});
