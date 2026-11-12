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

import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { FieldOption } from '../src/index.js';
import { RecordTable } from '../src/ui/index.js';
import { twoColumnTable } from './fixtures/ui.js';

afterEach(cleanup);

/**
 * An ordinary choice is text and a status is a badge (D94): a pill around
 * every channel, region and payment method made the statuses that matter no
 * louder than the rest. What makes a field a status is the definition's:
 * a `status` or `tags` cell, or an enum one of whose options names a tone.
 * It is decided by the field, so a column is all badges or all text.
 */
const cellOf = (
  cell: 'enum' | 'status' | 'tags',
  options: FieldOption[],
  value: unknown,
) =>
  render(
    <RecordTable
      table={twoColumnTable({
        columns: [
          {
            field: 'choice',
            label: 'Choice',
            kind: 'enum',
            cell,
            sortable: false,
            options,
          },
        ],
        rows: [{ key: 'o-1', data: { choice: value } }],
      })}
    />,
  ).container;

const CHANNELS: FieldOption[] = [
  { value: 'APP', label: 'App' },
  { value: 'WEB', label: 'Web' },
];

describe('a choice in a cell', () => {
  it('is its label as text where no option names a tone', () => {
    const container = cellOf('enum', CHANNELS, 'APP');
    expect(container.querySelector('[data-slot="badge"]')).toBeNull();
    expect(
      container.querySelector('[data-slot="cell-choice"]')?.textContent,
    ).toBe('App');
  });

  it('reads a list of them as one line of labels', () => {
    const container = cellOf('enum', CHANNELS, ['APP', 'WEB']);
    expect(container.querySelector('[data-slot="badge"]')).toBeNull();
    const text = container.querySelector(
      '[data-slot="cell-choice"]',
    )!.textContent!;
    expect(text).toContain('App');
    expect(text).toContain('Web');
  });

  it('is a badge, every value of the field, once one option names a tone', () => {
    const options: FieldOption[] = [
      { value: 'PAID', label: 'Paid', tone: 'success' },
      { value: 'OPEN', label: 'Open' },
    ];
    // The value whose own option names none is a badge too: the column is
    // a status column.
    const container = cellOf('enum', options, 'OPEN');
    expect(container.querySelector('[data-slot="cell-choice"]')).toBeNull();
    expect(container.querySelector('[data-slot="badge"]')?.textContent).toBe(
      'Open',
    );
  });

  it.each(['status', 'tags'] as const)(
    'is a badge in a %s cell, which the definition asked for by name',
    cell => {
      const container = cellOf(cell, CHANNELS, 'APP');
      expect(container.querySelector('[data-slot="badge"]')?.textContent).toBe(
        'App',
      );
    },
  );
});
