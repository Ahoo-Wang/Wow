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

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FieldDefinition, RecordViewRuntime } from '../src/index.js';
import { CardSettings } from '../src/ui/CardSettings.js';
import { ResultToolbar } from '../src/ui/ResultToolbar.js';
import { ViewSurface } from '../src/ui/ViewSurface.js';
import { recordTableController } from './fixtures/ui.js';

afterEach(cleanup);

/** The toolbar reads an export ceiling off it and nothing more. */
const runtime = {
  id: 'r-1',
  limits: { exportMax: 10000 },
} as unknown as RecordViewRuntime;

const FIELDS: FieldDefinition[] = [
  { name: 'id', label: 'Order', kind: 'string' },
  { name: 'amount', label: 'Amount', kind: 'number' },
  { name: 'warehouse', label: 'Warehouse', kind: 'string' },
  { name: 'photo', label: 'Photo', kind: 'string' },
];

function open(spec = { title: 'id', fields: ['amount'] }) {
  const table = recordTableController({
    layout: 'card',
    cardSpec: spec,
    setCard: vi.fn(),
  });
  render(
    <ViewSurface>
      <CardSettings table={table} fields={FIELDS} />
    </ViewSurface>,
  );
  fireEvent.click(screen.getByRole('button', { name: 'Card settings' }));
  return table;
}

/**
 * The card settings are the column settings' button under the card layout
 * (D18 VI): the same place, the same popover, the four questions a card
 * asks instead of the three a row asks.
 */
describe('CardSettings', () => {
  it('lists the body fields as checkboxes, the title field left out', async () => {
    const table = open();
    const dialog = await screen.findByRole('dialog', { name: 'Card settings' });

    const shown = within(dialog).getAllByRole('checkbox');
    // Named by the label beside each, which is the field's own word.
    expect(
      ['Amount', 'Warehouse', 'Photo'].map(
        name => within(dialog).getByRole('checkbox', { name }) === undefined,
      ),
    ).toEqual([false, false, false]);
    expect(shown[0].getAttribute('aria-checked')).toBe('true');

    // A field switched on joins the body at the end; the order a saved
    // view already holds stays.
    fireEvent.click(shown[1]);
    expect(table.setCard).toHaveBeenLastCalledWith({
      fields: ['amount', 'warehouse'],
    });
    fireEvent.click(shown[0]);
    expect(table.setCard).toHaveBeenLastCalledWith({ fields: [] });
  });

  it('chooses how many cards stand in a row', async () => {
    const table = open();
    const dialog = await screen.findByRole('dialog', { name: 'Card settings' });

    const three = within(dialog).getByRole('button', { name: '3 per row' });
    expect(three.getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(within(dialog).getByRole('button', { name: '2 per row' }));
    expect(table.setCard).toHaveBeenLastCalledWith({ perRow: 2 });
  });

  it('names the title and image selects by their labels', async () => {
    open({ title: 'id', fields: [] });
    const dialog = await screen.findByRole('dialog', { name: 'Card settings' });
    expect(
      within(dialog).getByRole('combobox', { name: 'Title' }).textContent,
    ).toContain('Order');
    expect(
      within(dialog).getByRole('combobox', { name: 'Image' }).textContent,
    ).toContain('No image');
  });
});

describe('the arrangement button follows the layout', () => {
  it('is the card settings under cards and the column settings under the table', async () => {
    const { rerender } = render(
      <ViewSurface>
        <ResultToolbar
          table={recordTableController({ layout: 'card' })}
          fields={FIELDS}
          runtime={runtime}
        />
      </ViewSurface>,
    );
    expect(screen.getByRole('button', { name: 'Card settings' })).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Columns' })).toBeNull();

    rerender(
      <ViewSurface>
        <ResultToolbar
          table={recordTableController({ layout: 'table' })}
          fields={FIELDS}
          runtime={runtime}
        />
      </ViewSurface>,
    );
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Columns' })).toBeDefined(),
    );
    expect(screen.queryByRole('button', { name: 'Card settings' })).toBeNull();
  });
});
