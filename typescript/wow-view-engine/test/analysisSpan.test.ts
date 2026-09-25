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
import { drillSpan } from '../src/analysis/drill.js';
import { builtinFieldKinds } from '../src/filter/index.js';
import type {
  AnalysisGroup,
  FieldDefinition,
  FilterLeaf,
  RecordData,
} from '../src/model/index.js';
import { analysisConfig } from './fixtures.js';

/**
 * A stretch of buckets read back as one condition (D33 batch C, Q52): what
 * a brush along a time axis and a range of rows picked from the table both
 * ask the follow-up menu about.
 */

const SHANGHAI = 'Asia/Shanghai';
const NEW_YORK = 'America/New_York';
const HOUR = 3_600_000;
const DAY = 24 * HOUR;

const FIELDS: FieldDefinition[] = [
  { name: 'warehouse', label: 'Warehouse', kind: 'string' },
  { name: 'amount', label: 'Amount', kind: 'number' },
  { name: 'createdAt', label: 'Created', kind: 'datetime' },
];

const DAYS: AnalysisGroup = {
  alias: 'day',
  field: 'createdAt',
  type: 'DATE_HISTOGRAM',
  unit: 'DAY',
};
const WAREHOUSE: AnalysisGroup = {
  alias: 'warehouse',
  field: 'warehouse',
  type: 'TERMS',
};

/** The instant a wall-clock time in a zone names, found by stepping hours. */
function at(wall: string, timeZone: string): number {
  const guess = Date.parse(`${wall}Z`);
  const read = new Intl.DateTimeFormat('sv-SE', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
  const target = wall.replace('T', ' ');
  for (let offset = -14; offset <= 14; offset += 1) {
    const instant = guess - offset * HOUR;
    if (read.format(new Date(instant)) === target) return instant;
  }
  throw new Error(`${wall} names no instant in ${timeZone}`);
}

function span(
  first: RecordData,
  last: RecordData,
  groups: AnalysisGroup[] = [DAYS],
  timeZone = SHANGHAI,
) {
  return drillSpan(
    analysisConfig({ groups }),
    FIELDS,
    builtinFieldKinds,
    first,
    last,
    { timeZone },
  );
}

/** The one date condition a span carries, as its two instants. */
function bounds(conditions: readonly FilterLeaf[] | undefined) {
  const value = conditions?.[0]?.value as { from: string; to: string };
  return { from: Date.parse(value.from), to: Date.parse(value.to) };
}

describe('drillSpan', () => {
  it('reads three days back as [first start, last end), one condition', () => {
    const first = at('2026-09-01T00:00:00', SHANGHAI);
    const last = at('2026-09-03T00:00:00', SHANGHAI);
    const drilled = span({ day: first }, { day: last });
    expect(drilled).toHaveLength(1);
    expect(drilled?.[0].group).toBe(DAYS);
    expect(drilled?.[0].value).toBe(first);
    expect(drilled?.[0].conditions).toEqual([
      {
        field: 'createdAt',
        operator: 'BETWEEN',
        value: {
          type: 'absolute',
          from: new Date(first).toISOString(),
          // Closed a millisecond before the fourth day starts (K1).
          to: new Date(at('2026-09-04T00:00:00', SHANGHAI) - 1).toISOString(),
          timeZone: SHANGHAI,
        },
      },
    ]);
  });

  it('is as long as the calendar says across a daylight-saving change', () => {
    // New York springs forward on 2026-03-08: three days of 71 hours.
    const spring = span(
      { day: at('2026-03-07T00:00:00', NEW_YORK) },
      { day: at('2026-03-09T00:00:00', NEW_YORK) },
      [DAYS],
      NEW_YORK,
    );
    const forward = bounds(spring?.[0].conditions);
    expect(forward.from).toBe(at('2026-03-07T00:00:00', NEW_YORK));
    expect(forward.to + 1).toBe(at('2026-03-10T00:00:00', NEW_YORK));
    expect(forward.to + 1 - forward.from).toBe(3 * DAY - HOUR);
    // And falls back on 2026-11-01: three days of 73 hours.
    const fall = bounds(
      span(
        { day: at('2026-10-31T00:00:00', NEW_YORK) },
        { day: at('2026-11-02T00:00:00', NEW_YORK) },
        [DAYS],
        NEW_YORK,
      )?.[0].conditions,
    );
    expect(fall.to + 1 - fall.from).toBe(3 * DAY + HOUR);
    expect(fall.to + 1).toBe(at('2026-11-03T00:00:00', NEW_YORK));
  });

  it('takes the histogram’s own zone over the engine’s', () => {
    const drilled = span(
      { day: at('2026-03-07T00:00:00', NEW_YORK) },
      { day: at('2026-03-08T00:00:00', NEW_YORK) },
      [{ ...DAYS, timeZone: NEW_YORK }],
      SHANGHAI,
    );
    const { from, to } = bounds(drilled?.[0].conditions);
    expect(to + 1 - from).toBe(2 * DAY - HOUR);
    expect(
      (drilled?.[0].conditions[0].value as { timeZone: string }).timeZone,
    ).toBe(NEW_YORK);
  });

  it('runs from the earlier bucket whichever end was pressed first', () => {
    const early = at('2026-09-01T00:00:00', SHANGHAI);
    const late = at('2026-09-03T00:00:00', SHANGHAI);
    expect(span({ day: late }, { day: early })).toEqual(
      span({ day: early }, { day: late }),
    );
  });

  it('ends a stretch of months where the last month does', () => {
    const drilled = span(
      { month: '2026-01-01T00:00:00+08:00' },
      { month: '2026-03-01T00:00:00+08:00' },
      [{ ...DAYS, alias: 'month', unit: 'MONTH' }],
    );
    const { from, to } = bounds(drilled?.[0].conditions);
    expect(from).toBe(at('2026-01-01T00:00:00', SHANGHAI));
    expect(to + 1).toBe(at('2026-04-01T00:00:00', SHANGHAI));
  });

  it('narrows the dimensions the two agree on and lets the others go', () => {
    const first = at('2026-09-01T00:00:00', SHANGHAI);
    const last = at('2026-09-02T00:00:00', SHANGHAI);
    // A brush names no series: the split is not narrowed.
    expect(
      span({ day: first }, { day: last }, [DAYS, WAREHOUSE])?.map(
        entry => entry.group.alias,
      ),
    ).toEqual(['day']);
    // Two rows of one warehouse: the warehouse too.
    expect(
      span({ day: first, warehouse: 'CN' }, { day: last, warehouse: 'CN' }, [
        DAYS,
        WAREHOUSE,
      ])?.[1],
    ).toEqual({
      group: WAREHOUSE,
      value: 'CN',
      conditions: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
    });
    // Two warehouses: every warehouse.
    expect(
      span({ day: first, warehouse: 'CN' }, { day: last, warehouse: 'US' }, [
        DAYS,
        WAREHOUSE,
      ]),
    ).toHaveLength(1);
  });

  it('is nothing where no condition can say it, or nothing spans time', () => {
    const day = at('2026-09-01T00:00:00', SHANGHAI);
    // Two warehouses are two groups, not a stretch.
    expect(
      span({ warehouse: 'CN' }, { warehouse: 'US' }, [WAREHOUSE]),
    ).toBeNull();
    expect(
      span({ warehouse: 'CN' }, { warehouse: 'CN' }, [WAREHOUSE]),
    ).toBeNull();
    // A bound that reads as no instant: the sentinel bucket.
    expect(span({ day }, { day: '(none)' })).toBeNull();
    // A field the definition does not have.
    expect(span({ day }, { day }, [{ ...DAYS, field: 'nowhere' }])).toBeNull();
    // A value on which the two agree that no condition can say.
    expect(
      span({ day, band: 'wide' }, { day, band: 'wide' }, [
        DAYS,
        { alias: 'band', field: 'amount', type: 'HISTOGRAM', interval: 10 },
      ]),
    ).toBeNull();
    // Expanded elements: rows of elements, not of records.
    expect(
      drillSpan(
        analysisConfig({
          groups: [DAYS],
          elements: [{ path: 'items' }],
        }),
        FIELDS,
        builtinFieldKinds,
        { day },
        { day },
        { timeZone: SHANGHAI },
      ),
    ).toBeNull();
  });
});
