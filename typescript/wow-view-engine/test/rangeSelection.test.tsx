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
 * Shift ranges of records: a Shift+press on a row's checkbox — or Shift+Space
 * on it — selects or clears every row from the last plain toggle to it, in
 * result order, over a real engine; the anchor stays with the rows it was
 * set among.
 */

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RecordKey } from '../src/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import {
  standingAnchor,
  toggledSelection,
} from '../src/react/recordSelection.js';
import { testSource } from './fixtures.js';
import { setup } from './fixtures/ui.js';

afterEach(cleanup);

/** Five rows on every page, and more pages than one. */
const FIVE = [1, 2, 3, 4, 5].map(n => ({
  id: `o-${n}`,
  warehouse: 'CN',
  amount: n * 10,
  status: 'PENDING',
}));

async function open() {
  const source = testSource({
    paged: vi.fn(() => Promise.resolve({ total: 50, list: [...FIVE] })),
  });
  const harness = setup(source);
  render(
    <DataWorkbench
      engine={harness.engine}
      definitionId="orders"
      instanceId="orders-1"
    />,
  );
  await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(6));
  return harness;
}

function box(key: string): HTMLElement {
  return screen.getByRole('checkbox', { name: `Select ${key}` });
}

/** The row keys whose checkbox is ticked, in the order drawn. */
function ticked(): string[] {
  return screen
    .getAllByRole('checkbox', { name: /^Select o-/ })
    .filter(element => element.getAttribute('aria-checked') === 'true')
    .map(element => element.getAttribute('aria-label')!.slice(7));
}

function shiftPress(key: string) {
  fireEvent.click(box(key), { shiftKey: true });
}

describe('Shift ranges in the record table', () => {
  it('selects every row from the anchor down to the pressed one', async () => {
    await open();
    fireEvent.click(box('o-2'));
    shiftPress('o-4');
    expect(ticked()).toEqual(['o-2', 'o-3', 'o-4']);
    expect(screen.getByText('3 selected')).toBeDefined();
  });

  it('selects upwards just as it does downwards', async () => {
    await open();
    fireEvent.click(box('o-4'));
    shiftPress('o-1');
    expect(ticked()).toEqual(['o-1', 'o-2', 'o-3', 'o-4']);
  });

  it('keeps the anchor through a range, so a second range re-draws from it', async () => {
    await open();
    fireEvent.click(box('o-3'));
    shiftPress('o-5');
    expect(ticked()).toEqual(['o-3', 'o-4', 'o-5']);
    // Pressing o-1 now extends from o-3 — not from o-5, where the range
    // ended — and the rows already picked stay picked.
    shiftPress('o-1');
    expect(ticked()).toEqual(['o-1', 'o-2', 'o-3', 'o-4', 'o-5']);
  });

  it('clears the range when the pressed row is being cleared', async () => {
    await open();
    fireEvent.click(screen.getByLabelText('Select all rows'));
    fireEvent.click(box('o-2'));
    expect(ticked()).toEqual(['o-1', 'o-3', 'o-4', 'o-5']);
    // o-2 is the anchor and clear; o-4 is selected, so the range goes the
    // way o-4 goes — cleared — and o-1, o-5 outside it are left alone.
    shiftPress('o-4');
    expect(ticked()).toEqual(['o-1', 'o-5']);
  });

  it('is a plain toggle with nothing to extend from, and sets the anchor', async () => {
    await open();
    shiftPress('o-2');
    expect(ticked()).toEqual(['o-2']);
    shiftPress('o-4');
    expect(ticked()).toEqual(['o-2', 'o-3', 'o-4']);
  });

  it('lets the anchor go on another page, whose rows are another set', async () => {
    const { source } = await open();
    fireEvent.click(box('o-2'));
    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => {
      const calls = vi.mocked(source.paged).mock.calls;
      expect(calls[calls.length - 1][0].pagination).toMatchObject({ index: 2 });
    });
    await waitFor(() => expect(ticked()).toEqual([]));
    // The source answers every page with the same keys, so only the page
    // tells the rows apart: o-2 here is not the o-2 that was pressed.
    shiftPress('o-4');
    expect(ticked()).toEqual(['o-4']);
  });

  it('keeps the anchor through a refresh of the same page', async () => {
    const { source } = await open();
    fireEvent.click(box('o-2'));
    const before = vi.mocked(source.paged).mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: /Refresh/ }));
    await waitFor(() =>
      expect(vi.mocked(source.paged).mock.calls.length).toBe(before + 1),
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(6));
    shiftPress('o-4');
    expect(ticked()).toEqual(['o-2', 'o-3', 'o-4']);
  });

  it('lets the anchor go when another question is applied', async () => {
    const { source } = await open();
    fireEvent.click(box('o-2'));
    const before = vi.mocked(source.paged).mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: /Amount/ }));
    await waitFor(() =>
      expect(vi.mocked(source.paged).mock.calls.length).toBe(before + 1),
    );
    await waitFor(() => expect(ticked()).toEqual([]));
    shiftPress('o-4');
    expect(ticked()).toEqual(['o-4']);
  });

  it('extends with Shift+Space on a focused checkbox', async () => {
    await open();
    const user = userEvent.setup();
    await user.click(box('o-1'));
    expect(ticked()).toEqual(['o-1']);
    box('o-3').focus();
    await user.keyboard('{Shift>}[Space]{/Shift}');
    expect(ticked()).toEqual(['o-1', 'o-2', 'o-3']);
    // Space alone is still one row.
    box('o-5').focus();
    await user.keyboard('[Space]');
    expect(ticked()).toEqual(['o-1', 'o-2', 'o-3', 'o-5']);
  });

  it('tells a screen reader once what Shift does', async () => {
    await open();
    const ids = new Set(
      screen
        .getAllByRole('checkbox', { name: /^Select o-/ })
        .map(element => element.getAttribute('aria-describedby')),
    );
    expect(ids.size).toBe(1);
    const [id] = [...ids];
    const hint = document.getElementById(id!);
    expect(hint?.textContent).toBe(
      'Hold Shift to select or clear every record from the last one you picked to this one.',
    );
    // Said once, not once a row.
    expect(screen.getAllByText(/^Hold Shift to select or clear/)).toHaveLength(
      1,
    );
    // The header's checkbox selects the page; there is no range to it.
    expect(
      screen.getByLabelText('Select all rows').getAttribute('aria-describedby'),
    ).toBeNull();
  });
});

describe('Shift ranges on the cards', () => {
  it('selects and clears ranges of cards the way the table does', async () => {
    await open();
    fireEvent.click(screen.getByRole('button', { name: 'Cards' }));
    await waitFor(() => expect(screen.queryByRole('table')).toBeNull());
    fireEvent.click(box('o-5'));
    shiftPress('o-2');
    expect(ticked()).toEqual(['o-2', 'o-3', 'o-4', 'o-5']);
    fireEvent.click(box('o-4'));
    shiftPress('o-3');
    expect(ticked()).toEqual(['o-2', 'o-5']);
    const ids = new Set(
      screen
        .getAllByRole('checkbox', { name: /^Select o-/ })
        .map(element => element.getAttribute('aria-describedby')),
    );
    expect(ids.size).toBe(1);
    expect(document.getElementById([...ids][0]!)?.textContent).toMatch(
      /^Hold Shift/,
    );
  });
});

describe('the range rule', () => {
  const rows = ['a', 'b', 'c', 'd'].map(key => ({ key, data: {} }));
  const mark = { question: {}, page: 1 };

  it('takes a lone row when the anchor or the row is not on screen', () => {
    expect(toggledSelection([], rows, 'c', 'z')).toEqual(['c']);
    expect(toggledSelection(['a'], rows, 'z', 'a')).toEqual(['a', 'z']);
  });

  it('adds only the rows not yet held, in result order', () => {
    const held: RecordKey[] = ['c', 'a'];
    expect(toggledSelection(held, rows, 'd', 'b')).toEqual([
      'c',
      'a',
      'b',
      'd',
    ]);
  });

  it('stands only on the page and question it was set on, over a row still there', () => {
    const anchor = { key: 'b', rows: mark };
    expect(standingAnchor(anchor, rows, mark)).toBe('b');
    expect(standingAnchor(null, rows, mark)).toBeNull();
    expect(standingAnchor(anchor, rows, { ...mark, page: 2 })).toBeNull();
    expect(standingAnchor(anchor, rows, { ...mark, question: {} })).toBeNull();
    expect(standingAnchor(anchor, rows.slice(2), mark)).toBeNull();
  });
});
