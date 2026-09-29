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
 * A column's width without a drag (WCAG 2.2 2.5.7): a box on each row of the
 * column settings, and the header's width keys written out above them.
 */

import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FieldDefinition } from '../src/model/index.js';
import type { RecordColumnView } from '../src/record/index.js';
import { ColumnSettings } from '../src/ui/columns/ColumnSettings.js';
import { tableController } from './fixtures/columns.js';

afterEach(cleanup);

const FIELDS: FieldDefinition[] = [
  { name: 'id', label: 'Order', kind: 'string' },
  { name: 'amount', label: 'Amount', kind: 'number' },
];

const COLUMNS: RecordColumnView[] = [
  { field: 'id', label: 'Order', sortable: false, primary: true },
  { field: 'amount', label: 'Amount', sortable: false, width: 120 },
] as RecordColumnView[];

async function open() {
  const setColumnWidth = vi.fn();
  const table = tableController({
    columnFields: ['id', 'amount'],
    columns: COLUMNS,
    setColumnWidth,
  });
  render(<ColumnSettings table={table} fields={FIELDS} rowKey="id" />);
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: /Columns/ }));
  return { user, setColumnWidth };
}

function announced(): string {
  return (
    document.querySelector('[data-slot="column-announcement"]')?.textContent ??
    ''
  );
}

describe('the width box', () => {
  it('shows the width a column has, and nothing for one that sizes itself', async () => {
    await open();
    expect(
      (
        screen.getByRole('textbox', {
          name: 'Width of Amount, in pixels',
        }) as HTMLInputElement
      ).value,
    ).toBe('120');
    expect(
      (
        screen.getByRole('textbox', {
          name: 'Width of Order, in pixels',
        }) as HTMLInputElement
      ).value,
    ).toBe('');
  });

  it('sets a typed width on Enter, and says it', async () => {
    const { user, setColumnWidth } = await open();
    const box = screen.getByRole('textbox', {
      name: 'Width of Amount, in pixels',
    });
    await user.clear(box);
    await user.type(box, '200{Enter}');
    expect(setColumnWidth).toHaveBeenCalledTimes(1);
    expect(setColumnWidth).toHaveBeenCalledWith('amount', 200);
    expect(announced()).toBe('Amount is 200 px wide');
  });

  it('raises a width under the floor to it, and empties to automatic', async () => {
    const { user, setColumnWidth } = await open();
    const box = screen.getByRole('textbox', {
      name: 'Width of Amount, in pixels',
    });
    await user.clear(box);
    await user.type(box, '12{Enter}');
    expect(setColumnWidth).toHaveBeenLastCalledWith('amount', 48);
    await user.clear(box);
    await user.tab();
    expect(setColumnWidth).toHaveBeenLastCalledWith('amount', null);
    expect(announced()).toBe('Amount fits its content');
  });

  it('says what is no width, and keeps it to be fixed', async () => {
    const { user, setColumnWidth } = await open();
    const box = screen.getByRole('textbox', {
      name: 'Width of Amount, in pixels',
    }) as HTMLInputElement;
    await user.clear(box);
    await user.type(box, 'wide{Enter}');
    expect(setColumnWidth).not.toHaveBeenCalled();
    expect(box.value).toBe('wide');
    expect(box.getAttribute('aria-invalid')).toBe('true');
    const error = document.querySelector('[data-slot="column-width-error"]');
    expect(error?.textContent).toBe(
      'Type a whole number of pixels, or leave it empty to fit the content.',
    );
    expect(box.getAttribute('aria-describedby')).toContain(error!.id);

    await user.clear(box);
    await user.type(box, '150{Enter}');
    expect(setColumnWidth).toHaveBeenLastCalledWith('amount', 150);
    expect(box.hasAttribute('aria-invalid')).toBe(false);
    expect(
      document.querySelector('[data-slot="column-width-error"]'),
    ).toBeNull();
  });

  it('lowers a width past the ceiling to it', async () => {
    const { user, setColumnWidth } = await open();
    const box = screen.getByRole('textbox', {
      name: 'Width of Amount, in pixels',
    }) as HTMLInputElement;
    await user.clear(box);
    await user.type(box, '5000{Enter}');
    expect(setColumnWidth).toHaveBeenLastCalledWith('amount', 2000);
    expect(box.value).toBe('2000');
  });

  it('refuses digits too many to be a number at all', async () => {
    const { user, setColumnWidth } = await open();
    const box = screen.getByRole('textbox', {
      name: 'Width of Amount, in pixels',
    }) as HTMLInputElement;
    await user.clear(box);
    // Past `Number.MAX_VALUE`, which reads as Infinity.
    await user.type(box, `${'9'.repeat(400)}{Enter}`);
    expect(setColumnWidth).not.toHaveBeenCalled();
    expect(box.getAttribute('aria-invalid')).toBe('true');
  });
});

describe('the header’s width keys', () => {
  it('are written out in the settings', async () => {
    await open();
    expect(
      document.querySelector('[data-slot="column-resize-keys"]')?.textContent,
    ).toContain('Alt+←');
  });
});
