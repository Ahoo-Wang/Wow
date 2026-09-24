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
  cardField,
  epochUnitOf,
  projectRecord,
  readInstant,
  type FieldDefinition,
} from '../src/index.js';
import { displayValue } from '../src/ui/display.js';
import { ordersDefinition, recordConfig } from './fixtures.js';

const MOMENT_S = 1_790_115_665;
const MOMENT_MS = MOMENT_S * 1000;

const seconds: FieldDefinition = {
  name: 'retryAt',
  label: 'Retry at',
  kind: 'datetime',
  temporal: { type: 'epoch', timeUnit: 'SECONDS' },
};

describe('a time kept in epoch seconds', () => {
  it('is read in its unit, as a number or as digits', () => {
    expect(readInstant(MOMENT_S, 'SECONDS')?.ms).toBe(MOMENT_MS);
    expect(readInstant(String(MOMENT_S), 'SECONDS')?.ms).toBe(MOMENT_MS);
    // Milliseconds stay the default, and a wall-clock text has no unit.
    expect(readInstant(MOMENT_MS)?.ms).toBe(MOMENT_MS);
    expect(readInstant('2026-09-18', 'SECONDS')?.dayOnly).toBe(true);
  });

  it('is said only where it is not the default', () => {
    expect(epochUnitOf(seconds)).toBe('SECONDS');
    expect(
      epochUnitOf({ ...seconds, temporal: { type: 'epoch' } }),
    ).toBeUndefined();
    expect(epochUnitOf({ ...seconds, temporal: undefined })).toBeUndefined();
    expect(
      epochUnitOf({ ...seconds, temporal: { type: 'date' } }),
    ).toBeUndefined();
  });

  it('shows the moment it names, not a day in January 1970', () => {
    const context = { locale: 'en-US', timeZone: 'UTC' };
    expect(
      displayValue(
        MOMENT_S,
        { cell: 'datetime', timeUnit: 'SECONDS' },
        context,
      ),
    ).toBe(displayValue(MOMENT_MS, { cell: 'datetime' }, context));
    expect(
      displayValue(
        MOMENT_S,
        { cell: 'datetime', timeUnit: 'SECONDS' },
        context,
      ),
    ).toContain('2026');
  });

  it('carries its unit to the column, the card and the detail', () => {
    const definition = ordersDefinition({
      fields: [...ordersDefinition().fields, seconds],
    });
    const view = projectRecord(
      definition,
      recordConfig({
        table: { columns: [{ field: 'id' }, { field: 'retryAt' }] },
      }),
      { total: 1, list: [{ id: 'o-1', retryAt: MOMENT_S }] },
    );
    expect(
      view.columns.find(column => column.field === 'retryAt')?.timeUnit,
    ).toBe('SECONDS');
    expect(
      view.columns.find(column => column.field === 'id')?.timeUnit,
    ).toBeUndefined();
    expect(cardField(seconds).timeUnit).toBe('SECONDS');
  });
});
