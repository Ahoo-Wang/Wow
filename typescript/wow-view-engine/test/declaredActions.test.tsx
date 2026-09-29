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
 * Declared actions on a record workbench (host-integration.md 5): the host
 * says what, the engine places it — the row's button and menu, the
 * selection's bar, the detail — asks first, splits a selection by what
 * each record takes, runs it a few at a time and says how it went.
 */

import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  actions,
  text,
  type RecordAction,
  type RecordActions,
} from '../src/index.js';
import type { RecordActionSlots } from '../src/react/index.js';
import { DataWorkbench, MessagesProvider } from '../src/ui/index.js';
import { deferred } from './fixtures.js';
import { setup } from './fixtures/ui.js';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

/** Words for the keys these actions are written in. */
const WORDS = {
  'orders.ship': 'Ship',
  'orders.shipped': 'Already shipped.',
  'orders.cancel': 'Cancel order',
  'orders.cancelTitle': 'Cancel {count} orders?',
  'orders.cancelTitle-one': 'Cancel {count} order?',
  'orders.cancelBody': 'The customer is refunded.',
  'orders.priority': 'Set priority',
  'orders.priorityField': 'Priority',
  'orders.priorityTitle': 'Set {count} orders to {value}?',
  'orders.priorityTitle-one': 'Set {count} order to {value}?',
  'orders.priorityAnswer': 'Set to {value}',
  'orders.high': 'High',
  'orders.low': 'Low',
  'orders.same': 'Already that priority.',
  'orders.note': 'Add a note',
  'orders.noteField': 'Note',
};

type Order = { status?: string; priority?: string };
const statusOf = (data: Record<string, unknown>) => (data as Order).status;

function ship(
  run: RecordAction['run'] = vi.fn(() => Promise.resolve()),
): RecordAction {
  return {
    id: 'ship',
    label: text('orders.ship'),
    primary: true,
    available: row =>
      statusOf(row.data) === 'SHIPPED' ? text('orders.shipped') : true,
    run,
  };
}

function cancel(
  run: RecordAction['run'] = vi.fn(() => Promise.resolve()),
): RecordAction {
  return {
    id: 'cancel',
    label: text('orders.cancel'),
    tone: 'danger',
    confirm: {
      title: text('orders.cancelTitle'),
      body: text('orders.cancelBody'),
    },
    run,
  };
}

function priority(
  run: RecordAction['run'] = vi.fn(() => Promise.resolve()),
): RecordAction {
  return {
    id: 'priority',
    label: text('orders.priority'),
    form: {
      priority: {
        label: text('orders.priorityField'),
        options: [
          { value: 'HIGH', label: text('orders.high') },
          { value: 'LOW', label: text('orders.low') },
        ],
      },
    },
    available: (row, { input }) =>
      input && input.priority === ((row.data as Order).priority ?? 'LOW')
        ? text('orders.same')
        : true,
    confirm: {
      title: text('orders.priorityTitle'),
      action: text('orders.priorityAnswer'),
    },
    run,
  };
}

async function open(
  declared: RecordActions,
  { slots }: { slots?: RecordActionSlots } = {},
) {
  const harness = setup();
  render(
    <MessagesProvider messages={WORDS}>
      <DataWorkbench
        engine={harness.engine}
        definitionId="orders"
        instanceId="orders-1"
        record={{ actions: declared, ...(slots ? { slots } : {}) }}
      />
    </MessagesProvider>,
  );
  await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
  return harness;
}

function rowOf(key: string) {
  return screen
    .getAllByRole('row')
    .find(row => within(row).queryByLabelText(`Select ${key}`))!;
}

function status() {
  return document.querySelector<HTMLElement>('[data-slot="bulk-status"]');
}

describe('a record’s declared actions', () => {
  it('puts the primary one in the row and the rest behind the record’s menu, saying why one is off', async () => {
    await open(actions([ship(), cancel(), priority()]));

    const shipped = rowOf('o-2');
    const button = within(shipped).getByRole('button', { name: 'Ship' });
    expect(button).toHaveProperty('disabled', true);
    expect(button.getAttribute('aria-describedby')).toBeTruthy();
    expect(
      document.getElementById(button.getAttribute('aria-describedby')!)
        ?.textContent,
    ).toBe('Already shipped.');

    await userEvent.click(
      within(shipped).getByRole('button', { name: 'Actions for o-2' }),
    );
    const menu = await screen.findByRole('menu');
    // The reason once, atop the menu, where a keyboard reaches it.
    expect(within(menu).getAllByText('Already shipped.')).toHaveLength(1);
    expect(
      within(menu).getByRole('menuitem', { name: 'Cancel order' }),
    ).toBeTruthy();
    // A choice is its options, under its field's name.
    expect(within(menu).getByText('Priority')).toBeTruthy();
    // The value the record already holds is not offered again.
    expect(
      within(menu)
        .getByRole('menuitem', { name: 'Low' })
        .getAttribute('aria-disabled'),
    ).toBe('true');
    expect(
      within(menu)
        .getByRole('menuitem', { name: 'High' })
        .getAttribute('aria-disabled'),
    ).toBeNull();
  });

  it('leaves out an action a record hides, and one not offered in rows', async () => {
    await open(
      actions([
        ship(),
        {
          ...cancel(),
          hidden: row => statusOf(row.data) === 'SHIPPED',
        },
        { ...priority(), on: ['bulk'] },
      ]),
    );
    // Nothing is left for the menu of a record that hides the rest.
    expect(
      within(rowOf('o-2')).queryByRole('button', { name: 'Actions for o-2' }),
    ).toBeNull();
    await userEvent.click(
      within(rowOf('o-1')).getByRole('button', { name: 'Actions for o-1' }),
    );
    const menu = await screen.findByRole('menu');
    expect(
      within(menu).getByRole('menuitem', { name: 'Cancel order' }),
    ).toBeTruthy();
    expect(within(menu).queryByText('Priority')).toBeNull();
  });

  it('runs a row’s primary action at once, says so above the rows and reads the view again', async () => {
    const run = vi.fn(() => Promise.resolve());
    const { source } = await open(actions([ship(run)]));
    const before = vi.mocked(source.paged).mock.calls.length;
    // What the surface's voice says, in order: the view read again after
    // the command says its own count too, after it.
    const said: string[] = [];
    const region = document.querySelector('[data-slot="record-announcement"]')!;
    const observer = new MutationObserver(() =>
      said.push(region.textContent ?? ''),
    );
    observer.observe(region, { childList: true, subtree: true });

    await userEvent.click(
      within(rowOf('o-1')).getByRole('button', { name: 'Ship' }),
    );

    await waitFor(() =>
      expect(status()?.textContent).toContain('Ship · 1 done'),
    );
    expect(run).toHaveBeenCalledWith(
      expect.objectContaining({ key: 'o-1' }),
      {},
    );
    await waitFor(() =>
      expect(vi.mocked(source.paged).mock.calls.length).toBeGreaterThan(before),
    );
    // Said aloud, too: the reader's focus is on the row, not on the line.
    await waitFor(() => expect(said).toContain('Ship · 1 done'));
    observer.disconnect();
  });

  it('asks before a dangerous action, in the host’s words, and sends nothing on cancel', async () => {
    const run = vi.fn(() => Promise.resolve());
    await open(actions([ship(), cancel(run)]));

    await userEvent.click(
      within(rowOf('o-1')).getByRole('button', { name: 'Actions for o-1' }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', { name: 'Cancel order' }),
    );
    const dialog = await screen.findByRole('alertdialog', {
      name: 'Cancel 1 order?',
    });
    expect(within(dialog).getByText('The customer is refunded.')).toBeTruthy();
    const confirm = within(dialog).getByRole('button', {
      name: 'Cancel order',
    });
    expect(confirm.getAttribute('data-tone')).toBe('danger');
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Cancel' }),
    );
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    expect(run).not.toHaveBeenCalled();

    await userEvent.click(
      within(rowOf('o-1')).getByRole('button', { name: 'Actions for o-1' }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', { name: 'Cancel order' }),
    );
    await userEvent.click(
      within(
        await screen.findByRole('alertdialog', { name: 'Cancel 1 order?' }),
      ).getByRole('button', { name: 'Cancel order' }),
    );
    await waitFor(() => expect(run).toHaveBeenCalledTimes(1));
  });

  it('takes a choice’s option as the input, and names it in the question and on the line', async () => {
    const run = vi.fn(() => Promise.resolve());
    await open(actions([ship(), priority(run)]));

    await userEvent.click(
      within(rowOf('o-1')).getByRole('button', { name: 'Actions for o-1' }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', { name: 'High' }),
    );
    const dialog = await screen.findByRole('alertdialog', {
      name: 'Set 1 order to High?',
    });
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Set to High' }),
    );
    await waitFor(() =>
      expect(run).toHaveBeenCalledWith(
        expect.objectContaining({ key: 'o-1' }),
        { priority: 'HIGH' },
      ),
    );
    await waitFor(() =>
      expect(status()?.textContent).toContain('Set to High · 1 done'),
    );
  });

  it('asks for a form’s fields with the condition editor’s controls, and waits until the required ones are filled', async () => {
    const run = vi.fn(() => Promise.resolve());
    await open(
      actions([
        ship(),
        {
          id: 'note',
          label: text('orders.note'),
          form: { note: { label: text('orders.noteField') } },
          run,
        },
      ]),
    );
    await userEvent.click(
      within(rowOf('o-1')).getByRole('button', { name: 'Actions for o-1' }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', { name: 'Add a note' }),
    );
    const dialog = await screen.findByRole('alertdialog', {
      name: 'Add a note',
    });
    const submit = within(dialog).getByRole('button', { name: 'Add a note' });
    expect(submit).toHaveProperty('disabled', true);
    expect(within(dialog).getByText('Required')).toBeTruthy();

    fireEvent.change(within(dialog).getByLabelText('Note'), {
      target: { value: 'Call first' },
    });
    await waitFor(() => expect(submit).toHaveProperty('disabled', false));
    await userEvent.click(submit);
    await waitFor(() =>
      expect(run).toHaveBeenCalledWith(
        expect.objectContaining({ key: 'o-1' }),
        { note: 'Call first' },
      ),
    );
  });

  it('draws the host’s slot after the declared actions, and runs its command on the same line', async () => {
    const each = vi.fn(() => Promise.resolve());
    await open(actions([ship()]), {
      slots: {
        row: ({ row, run }) => (
          <button type="button" onClick={() => run({ title: 'Nudge', each })}>
            Nudge {String(row.key)}
          </button>
        ),
      },
    });
    const row = rowOf('o-1');
    const names = within(row)
      .getAllByRole('button')
      .map(button => button.textContent);
    expect(names.indexOf('Ship')).toBeLessThan(names.indexOf('Nudge o-1'));
    await userEvent.click(
      within(row).getByRole('button', { name: 'Nudge o-1' }),
    );
    await waitFor(() =>
      expect(status()?.textContent).toContain('Nudge · 1 done'),
    );
    expect(each).toHaveBeenCalledWith('o-1');
  });
});

describe('a selection’s declared actions', () => {
  it('counts what the primary one is for, lists the records that will not take it, and picks only the ones that can', async () => {
    const run = vi.fn(() => Promise.resolve());
    await open(actions([ship(run), cancel()]));

    fireEvent.click(screen.getByLabelText('Select all rows'));
    await userEvent.click(
      await screen.findByRole('button', { name: 'Ship 2' }),
    );
    // No question of the host's own: the engine's, with the count.
    const dialog = await screen.findByRole('alertdialog', {
      name: 'Run “Ship” on 2 records?',
    });
    expect(within(dialog).getByText('1 of 2 can take it.')).toBeTruthy();
    expect(within(dialog).getByText('Already shipped. (1)')).toBeTruthy();
    expect(within(dialog).getByText('o-2')).toBeTruthy();

    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Only the one that can' }),
    );
    await screen.findByRole('alertdialog', { name: 'Run “Ship” on 1 record?' });
    expect(screen.getByText('1 selected')).toBeTruthy();
    await userEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', {
        name: 'Ship',
      }),
    );
    await waitFor(() =>
      expect(status()?.textContent).toContain('Ship · 1 done'),
    );
    expect(run).toHaveBeenCalledTimes(1);
    expect(run).toHaveBeenCalledWith(
      expect.objectContaining({ key: 'o-1' }),
      {},
    );
  });

  it('sends the able ones and reports the refused, left selected, when all are confirmed', async () => {
    const run = vi.fn(() => Promise.resolve());
    await open(actions([ship(run)]));

    fireEvent.click(screen.getByLabelText('Select all rows'));
    await userEvent.click(
      await screen.findByRole('button', { name: 'Ship 2' }),
    );
    const dialog = await screen.findByRole('alertdialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Ship' }));

    await waitFor(() =>
      expect(status()?.textContent).toContain(
        'Ship · 1 done, 1 failed · Already shipped. (1) · the rest stay selected',
      ),
    );
    expect(run).toHaveBeenCalledTimes(1);
    expect(
      screen.getByLabelText('Select o-2').getAttribute('aria-checked'),
    ).toBe('true');
    expect(
      screen.getByLabelText('Select o-1').getAttribute('aria-checked'),
    ).toBe('false');
  });

  it('says how far a run has come, stops it, and sums up a partial failure', async () => {
    const gates = new Map<string, ReturnType<typeof deferred<void>>>();
    const run = vi.fn((row: { key: unknown }) => {
      const gate = deferred<void>();
      gates.set(String(row.key), gate);
      return gate.promise;
    });
    await open(
      actions([{ ...cancel(run), primary: true, confirm: undefined }]),
    );
    fireEvent.click(screen.getByLabelText('Select all rows'));
    await userEvent.click(
      await screen.findByRole('button', { name: 'Cancel order 2' }),
    );
    await userEvent.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', {
        name: 'Cancel order',
      }),
    );
    await waitFor(() =>
      expect(status()?.getAttribute('data-state')).toBe('running'),
    );
    expect(status()?.textContent).toContain('Cancel order · Running 0 of 2');
    // One command at a time.
    expect(
      screen.getByRole('button', { name: 'Cancel order 2' }),
    ).toHaveProperty('disabled', true);
    await userEvent.click(screen.getByRole('button', { name: 'Stop' }));
    await act(async () => {
      gates.get('o-1')?.reject(new Error('Locked.'));
      gates.get('o-2')?.resolve();
    });
    await waitFor(() =>
      expect(status()?.textContent).toContain(
        'Cancel order · 1 done, 1 failed · Locked. (1) · the rest stay selected',
      ),
    );
  });
});

describe('the detail’s declared actions', () => {
  it('draws the actions offered in the detail, and not the ones for rows alone', async () => {
    await open(actions([ship(), { ...cancel(), on: ['row'] }]));
    fireEvent.keyDown(rowOf('o-1'), { key: 'Enter' });
    const panel = await screen.findByRole('dialog');
    await within(panel).findByRole('button', { name: 'Ship' });
    expect(
      within(panel).queryByRole('button', { name: 'Actions for o-1' }),
    ).toBeNull();
  });
});

describe('availability that changes on its own', () => {
  it('asks again when a record’s rule says it will change, with no timer of the host’s', async () => {
    vi.useFakeTimers({
      shouldAdvanceTime: true,
      toFake: ['Date', 'setTimeout', 'clearTimeout'],
    });
    const opensAt = Date.now() + 60_000;
    await open(
      actions([
        {
          id: 'ship',
          label: text('orders.ship'),
          primary: true,
          available: (_row, { now }) => (now > opensAt ? true : 'Not yet.'),
          changesAt: (_row, { now }) => (now > opensAt ? null : opensAt + 1),
          run: () => Promise.resolve(),
        },
      ]),
    );
    const button = within(rowOf('o-1')).getByRole('button', { name: 'Ship' });
    expect(button).toHaveProperty('disabled', true);
    await act(() => vi.advanceTimersByTimeAsync(opensAt - Date.now() + 2));
    expect(
      within(rowOf('o-1')).getByRole('button', { name: 'Ship' }),
    ).toHaveProperty('disabled', false);
  });
});
