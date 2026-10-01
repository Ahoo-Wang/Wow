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

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { projectRecord } from '../src/record/index.js';
import { builtinFieldKinds } from '../src/filter/index.js';
import { validateDefinition } from '../src/runtime/index.js';
import type { FieldDefinition } from '../src/index.js';
import {
  ViewSurface,
  cellValue,
  tableTime,
  useSurfaceDisplay,
  useViewMessages,
  zhCN,
  type CellField,
  type CellSurface,
} from '../src/ui/index.js';
import { ordersDefinition, recordConfig } from './fixtures.js';

afterEach(cleanup);

/**
 * A table cell's time (second review R2-23): to the minute, the year only
 * outside the current one, the whole time in its title and in the detail.
 */

/** 21:19:08 on 17 Sep 2026 in Shanghai. */
const AT = Date.parse('2026-09-17T13:19:08Z');
/** The same moment a year earlier. */
const LAST_YEAR = Date.parse('2025-09-17T13:19:08Z');
const SHANGHAI = 'Asia/Shanghai';
const clock = (iso: string) => ({ now: () => new Date(iso) });
const IN_2026 = clock('2026-10-01T00:00:00Z');

const field: CellField = { kind: 'datetime' };

describe('tableTime', () => {
  const zh = { locale: 'zh-CN', timeZone: SHANGHAI, clock: IN_2026 };
  const en = { locale: 'en-US', timeZone: SHANGHAI, clock: IN_2026 };

  it('writes Chinese in numbers, to the minute, the year left out this year', () => {
    expect(tableTime(AT, field, zh)).toBe('09-17 21:19');
    expect(tableTime(LAST_YEAR, field, zh)).toBe('2025-09-17 21:19');
  });

  it('writes English with the month by name', () => {
    expect(tableTime(AT, field, en)).toMatch(/^Sep 17, 9:19\sPM$/);
    expect(tableTime(LAST_YEAR, field, en)).toMatch(/^Sep 17, 2025, 9:19\sPM$/);
  });

  it('keeps the seconds where the column asks for them', () => {
    const seconds: CellField = { ...field, timePrecision: 'second' };
    expect(tableTime(AT, seconds, zh)).toBe('09-17 21:19:08');
    expect(tableTime(AT, seconds, en)).toMatch(/^Sep 17, 9:19:08\sPM$/);
  });

  it("reads the year against the surface's clock, not the machine's", () => {
    expect(
      tableTime(AT, field, { ...zh, clock: clock('2027-01-01T00:00:00Z') }),
    ).toBe('2026-09-17 21:19');
    // New Year's Eve in UTC is already 2027 in Shanghai.
    expect(
      tableTime(AT, field, { ...zh, clock: clock('2026-12-31T17:00:00Z') }),
    ).toBe('2026-09-17 21:19');
  });

  it('leaves alone what is not a moment read as a datetime', () => {
    expect(tableTime(AT, { kind: 'date' }, zh)).toBeUndefined();
    expect(tableTime(AT, { kind: 'number' }, zh)).toBeUndefined();
    expect(tableTime('2026-09-17', field, zh)).toBeUndefined();
    expect(tableTime('soon', field, zh)).toBeUndefined();
    expect(tableTime(null, field, zh)).toBeUndefined();
  });
});

function Read({
  value,
  surface,
  of,
}: {
  value: unknown;
  surface: CellSurface;
  of: CellField;
}) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  return (
    <div data-testid="read">
      {cellValue(value, of, messages, display, surface)}
    </div>
  );
}

function read(surface: CellSurface, of: CellField = field) {
  render(
    <ViewSurface
      messages={zhCN}
      locale="zh-CN"
      timeZone={SHANGHAI}
      clock={IN_2026}
    >
      <Read value={AT} surface={surface} of={of} />
    </ViewSurface>,
  );
  return screen.getByTestId('read');
}

describe('a time in a record', () => {
  it('is short in a table cell, whole in its title', () => {
    const cell = read('table');
    expect(cell.textContent).toBe('09-17 21:19');
    expect(cell.querySelector('[data-slot="cell-time"]')).toHaveProperty(
      'title',
      '2026年9月17日 21:19:08',
    );
  });

  it('is whole in the detail and on a card', () => {
    expect(read('detail').textContent).toBe('2026年9月17日 21:19:08');
    cleanup();
    expect(read('card').textContent).toBe('2026年9月17日 21:19:08');
  });
});

describe('timePrecision on a field', () => {
  const definition = (extra: Partial<FieldDefinition>) =>
    ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        { name: 'at', label: 'At', kind: 'datetime', ...extra },
      ],
    });
  const column = (extra: Partial<FieldDefinition>) =>
    projectRecord(
      definition(extra),
      recordConfig({ table: { columns: [{ field: 'at' }] } }),
      { total: 0, list: [] },
    ).columns[0];
  const codes = (extra: Partial<FieldDefinition>) =>
    validateDefinition(definition(extra), builtinFieldKinds).map(
      found => found.code,
    );

  it('reaches the column only when it asks for the seconds', () => {
    expect(column({ timePrecision: 'second' }).timePrecision).toBe('second');
    expect(column({ timePrecision: 'minute' })).not.toHaveProperty(
      'timePrecision',
    );
    expect(column({})).not.toHaveProperty('timePrecision');
  });

  it('is admitted on a datetime reading, by kind or by cell', () => {
    expect(codes({ timePrecision: 'second' })).toEqual([]);
    expect(
      codes({ kind: 'number', cell: 'datetime', timePrecision: 'minute' }),
    ).toEqual([]);
  });

  it('is refused where nothing reads a time of day, or spelled unknown', () => {
    expect(codes({ kind: 'date', timePrecision: 'second' })).toEqual([
      'definition.field.time-precision-misplaced',
    ]);
    expect(codes({ timePrecision: 'millisecond' as never })).toEqual([
      'definition.field.time-precision-invalid',
    ]);
  });
});
