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
import {
  MemoryViewStore,
  ViewEngine,
  defaultRuntimeEnvironment,
  type ViewErrorEvent,
} from '../src/index.js';
import type { RecordActionSlots } from '../src/react/index.js';
import { DataWorkbench, MessagesProvider, zhCN } from '../src/ui/index.js';
import {
  deferred,
  mine,
  ordersDefinition,
  resourcesOf,
  testSource,
} from './fixtures.js';
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

/** What an element is described by, as a reader hears it. */
function described(element: HTMLElement): string {
  return (element.getAttribute('aria-describedby') ?? '')
    .split(/\s+/)
    .filter(Boolean)
    .map(id => document.getElementById(id)?.textContent ?? '')
    .join(' ');
}

/**
 * Whether a control is held off where the keyboard still finds it
 * (`focusableWhenDisabled`): `aria-disabled`, never the native `disabled`,
 * which would drop the focus a press left on it.
 */
function held(element: HTMLElement): boolean {
  return (
    element.getAttribute('aria-disabled') === 'true' &&
    !element.hasAttribute('disabled')
  );
}

describe('a record’s declared actions', () => {
  it('puts the primary one in the row and the rest behind the record’s menu, saying why one is off', async () => {
    await open(actions([ship(), cancel(), priority()]));

    const shipped = rowOf('o-2');
    const button = within(shipped).getByRole('button', { name: 'Ship' });
    // Held off where Tab reaches it, so its reason does too (A11Y-15).
    expect(held(button)).toBe(true);
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

  it('runs a row’s primary action at once, says so under the rows and reads the view again', async () => {
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

    // One record is named, not counted.
    await waitFor(() =>
      expect(status()?.textContent).toContain('Ship · o-1 done'),
    );
    expect(run).toHaveBeenCalledWith(
      expect.objectContaining({ key: 'o-1' }),
      {},
    );
    await waitFor(() =>
      expect(vi.mocked(source.paged).mock.calls.length).toBeGreaterThan(before),
    );
    // Said aloud, too: the reader's focus is on the row, not on the line —
    // and the refresh's count after it, in one sentence, never over it.
    await waitFor(() =>
      expect(said[said.length - 1]).toMatch(/^Ship · o-1 done; .*records?/),
    );
    expect(said).toContain('Ship · o-1 done');
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
    // The host's words count; the record they are for is named beside them.
    expect(described(dialog)).toBe('The customer is refunded. Record o-1');
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
    // A routine question: a dialog, not an alert.
    const dialog = await screen.findByRole('dialog', {
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
      expect(status()?.textContent).toContain('Set to High · o-1 done'),
    );
  });

  it('asks for a form’s fields with the condition editor’s controls, and takes the keyboard to a required one left blank', async () => {
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
    // A form is a dialog, not an alert; one record is named in its title.
    const dialog = await screen.findByRole('dialog', {
      name: 'Add a note: o-1',
    });
    const note = within(dialog).getByLabelText('Note');
    // Required, said to a reader, with 「Required」 as its description; not
    // marked invalid before anything was pressed (A11Y-9).
    expect(note.getAttribute('aria-required')).toBe('true');
    expect(described(note)).toBe('Required');
    expect(note.getAttribute('aria-invalid')).not.toBe('true');
    // The answer stays pressable: pressed blank, it marks the field and
    // takes the keyboard there, and sends nothing.
    const submit = within(dialog).getByRole('button', { name: 'Add a note' });
    expect(submit.hasAttribute('disabled')).toBe(false);
    expect(submit.getAttribute('aria-disabled')).not.toBe('true');
    await userEvent.click(submit);
    await waitFor(() => expect(document.activeElement).toBe(note));
    expect(note.getAttribute('aria-invalid')).toBe('true');
    expect(run).not.toHaveBeenCalled();

    fireEvent.change(note, { target: { value: 'Call first' } });
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
      expect(status()?.textContent).toContain('Nudge · o-1 done'),
    );
    expect(each).toHaveBeenCalledWith('o-1');
  });
});

describe('a selection’s declared actions', () => {
  it('counts what the primary one is for, lists the records that will not take it, and picks only the ones that can', async () => {
    const run = vi.fn(() => Promise.resolve());
    await open(actions([ship(run), cancel()]));

    fireEvent.click(screen.getByLabelText('Select all rows'));
    // How many take it, before the press (UX-6).
    await userEvent.click(
      await screen.findByRole('button', { name: 'Ship 1/2' }),
    );
    // No question of the host's own: the engine's, with the count.
    const dialog = await screen.findByRole('dialog', {
      name: 'Run “Ship” on 2 records?',
    });
    // The answer counts what will be sent.
    expect(within(dialog).getByRole('button', { name: 'Ship 1' })).toBeTruthy();
    expect(within(dialog).getByText('1 of 2 can take it.')).toBeTruthy();
    expect(within(dialog).getByText('Already shipped. (1)')).toBeTruthy();
    expect(within(dialog).getByText('o-2')).toBeTruthy();

    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Only the one that can' }),
    );
    // One record left: named.
    const one = await screen.findByRole('dialog', {
      name: 'Run “Ship” on o-1?',
    });
    expect(screen.getByText('1 selected')).toBeTruthy();
    await userEvent.click(within(one).getByRole('button', { name: 'Ship' }));
    await waitFor(() =>
      expect(status()?.textContent).toContain('Ship · o-1 done'),
    );
    expect(run).toHaveBeenCalledTimes(1);
    expect(run).toHaveBeenCalledWith(
      expect.objectContaining({ key: 'o-1' }),
      {},
    );
  });

  it('asks outside the bar it was pressed in, every button of the question its own tab stop', async () => {
    // The question a bulk action asks is drawn beside the surface, not
    // under the result toolbar, so the bar's roving focus never reaches
    // its buttons (the export's window is detached for the same reason).
    await open(actions([ship(vi.fn(() => Promise.resolve())), cancel()]));
    fireEvent.click(screen.getByLabelText('Select all rows'));
    const pressed = await screen.findByRole('button', { name: 'Ship 1/2' });
    expect(pressed.closest('[role="toolbar"]')).not.toBeNull();
    await userEvent.click(pressed);
    const dialog = await screen.findByRole('dialog');
    expect(dialog.closest('[role="toolbar"]')).toBeNull();
    const buttons = within(dialog)
      .getAllByRole('button')
      .filter(button => !(button as HTMLButtonElement).disabled);
    expect(buttons.length).toBeGreaterThanOrEqual(2);
    for (const button of buttons)
      expect(button.getAttribute('tabindex')).toBe('0');
  });

  it('sends the able ones and reports the refused, left selected, when all are confirmed', async () => {
    const run = vi.fn(() => Promise.resolve());
    await open(actions([ship(run)]));

    fireEvent.click(screen.getByLabelText('Select all rows'));
    await userEvent.click(
      await screen.findByRole('button', { name: 'Ship 1/2' }),
    );
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Ship 1' }),
    );

    // Refused before it was sent: not run, not failed (UX-6).
    await waitFor(() =>
      expect(status()?.textContent).toContain(
        'Ship · 1 done · 1 not run · Already shipped. (1) · the failed and the not run stay selected',
      ),
    );
    // Left selected, the bar's button says none of it takes the action,
    // held off with why.
    const bar = await screen.findByRole('button', { name: 'Ship 1' });
    expect(held(bar)).toBe(true);
    expect(described(bar)).toBe('Already shipped.');
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
        name: 'Cancel order 2',
      }),
    );
    await waitFor(() =>
      expect(status()?.getAttribute('data-state')).toBe('running'),
    );
    expect(status()?.textContent).toContain('Cancel order · Running 0 of 2');
    // One command at a time.
    expect(held(screen.getByRole('button', { name: 'Cancel order 2' }))).toBe(
      true,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Stop' }));
    await act(async () => {
      gates.get('o-1')?.reject(new Error('Locked.'));
      gates.get('o-2')?.resolve();
    });
    await waitFor(() =>
      expect(status()?.textContent).toContain(
        'Cancel order · 1 done · 1 failed · Locked. (1) · the failed and the not run stay selected',
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
    expect(held(button)).toBe(true);
    await act(() => vi.advanceTimersByTimeAsync(opensAt - Date.now() + 2));
    expect(
      held(within(rowOf('o-1')).getByRole('button', { name: 'Ship' })),
    ).toBe(false);
  });
});

describe('a rule whose change keeps moving', () => {
  it('is asked again at most about once a second, not on every tick', async () => {
    vi.useFakeTimers({
      shouldAdvanceTime: true,
      toFake: ['Date', 'setTimeout', 'clearTimeout'],
    });
    const asked = vi.fn(() => true as const);
    await open(
      actions([
        {
          id: 'ship',
          label: text('orders.ship'),
          primary: true,
          available: asked,
          // Always a moment from whatever clock it is asked at.
          changesAt: (_row, { now }) => now + 1,
          run: () => Promise.resolve(),
        },
      ]),
    );
    asked.mockClear();
    // Three seconds in steps, each drawn: a timer the surface sets after a
    // render is only set once that render has been committed.
    for (let step = 0; step < 60; step += 1)
      await act(() => vi.advanceTimersByTimeAsync(50));
    // A few asks per row per second, not one per step.
    expect(asked.mock.calls.length).toBeGreaterThan(0);
    expect(asked.mock.calls.length).toBeLessThan(40);
  });
});

describe('the keyboard after a command', () => {
  it('stays on a row’s button pressed with Enter, through the run and the refresh', async () => {
    const gate = deferred<void>();
    await open(actions([ship(() => gate.promise)]));
    const button = within(rowOf('o-1')).getByRole('button', { name: 'Ship' });
    button.focus();
    await userEvent.keyboard('{Enter}');
    await waitFor(() =>
      expect(status()?.getAttribute('data-state')).toBe('running'),
    );
    // Held while it runs, but still where the keyboard is (A11Y-1).
    expect(held(button)).toBe(true);
    expect(document.activeElement).toBe(button);
    await act(async () => gate.resolve());
    await waitFor(() =>
      expect(status()?.textContent).toContain('Ship · o-1 done'),
    );
    await waitFor(() => expect(held(button)).toBe(false));
    expect(document.activeElement).toBe(button);
  });

  it('goes back to the record’s menu button when its question closes', async () => {
    await open(actions([ship(), cancel()]));
    const more = within(rowOf('o-1')).getByRole('button', {
      name: 'Actions for o-1',
    });
    await userEvent.click(more);
    await userEvent.click(
      await screen.findByRole('menuitem', { name: 'Cancel order' }),
    );
    const dialog = await screen.findByRole('alertdialog');
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Cancel order' }),
    );
    await waitFor(() =>
      expect(status()?.textContent).toContain('Cancel order · o-1 done'),
    );
    await waitFor(() => expect(document.activeElement).toBe(more));
  });

  it('lands on the line when the selection’s bar went with the selection', async () => {
    await open(actions([{ ...cancel(), confirm: undefined, primary: true }]));
    fireEvent.click(screen.getByLabelText('Select all rows'));
    await userEvent.click(
      await screen.findByRole('button', { name: 'Cancel order 2' }),
    );
    await userEvent.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', {
        name: 'Cancel order 2',
      }),
    );
    await waitFor(() =>
      expect(status()?.textContent).toContain('Cancel order · 2 done'),
    );
    // Every record took it, so nothing is selected and the bar is gone:
    // the keyboard is on the line's way out, not on the page.
    await waitFor(() =>
      expect(document.activeElement).toBe(
        within(status()!).getByRole('button', { name: 'Dismiss' }),
      ),
    );
  });

  it('keeps the selection’s actions in the toolbar’s one Tab stop, the arrows through them', async () => {
    await open(actions([ship(), cancel(), priority()]));
    fireEvent.click(screen.getByLabelText('Select all rows'));
    const ship2 = await screen.findByRole('button', { name: 'Ship 1/2' });
    const bar = ship2.closest<HTMLElement>('[role="toolbar"]')!;
    const clear = within(bar).getByRole('button', { name: 'Clear selection' });
    const cancelAll = within(bar).getByRole('button', { name: 'Cancel order' });
    const choose = within(bar).getByRole('button', { name: 'Set priority' });
    // One stop for the bar (A11Y-11).
    for (const item of [ship2, cancelAll, choose])
      expect(item.getAttribute('tabindex')).not.toBe('0');
    clear.focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(document.activeElement).toBe(ship2);
    await userEvent.keyboard('{ArrowRight}');
    expect(document.activeElement).toBe(cancelAll);
    await userEvent.keyboard('{ArrowRight}');
    expect(document.activeElement).toBe(choose);
  });
});

describe('what the surface says, and to whom', () => {
  it('says a command’s start and outcome in the host’s words passed as props, the count after it', async () => {
    const harness = setup();
    render(
      <DataWorkbench
        engine={harness.engine}
        definitionId="orders"
        instanceId="orders-1"
        messages={{ ...zhCN, 'orders.ship': '发货' }}
        locale="zh-CN"
        record={{ actions: actions([ship()]) }}
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    const region = document.querySelector('[data-slot="record-announcement"]')!;
    const said: string[] = [];
    const observer = new MutationObserver(() =>
      said.push(region.textContent ?? ''),
    );
    observer.observe(region, { childList: true, subtree: true });
    await userEvent.click(
      within(
        screen.getAllByRole('row').find(row => within(row).queryByText('o-1'))!,
      ).getByRole('button', { name: '发货' }),
    );
    await waitFor(() =>
      expect(said[said.length - 1]).toMatch(/^发货 · o-1 已完成；/),
    );
    observer.disconnect();
    expect(said.some(each => /Running|done/.test(each))).toBe(false);
    expect(said).toContain('发货 · 正在执行 0/1');
  });

  it('tells the host’s onError what a command threw, with the action and the record, and not of a refusal', async () => {
    const events: ViewErrorEvent[] = [];
    const engine = new ViewEngine({
      resources: resourcesOf([ordersDefinition()], () => testSource()),
      store: new MemoryViewStore({ instances: [mine] }),
      environment: defaultRuntimeEnvironment({
        onError: event => events.push(event),
      }),
    });
    const failure = Object.assign(new Error('Internal'), { errorCode: 'Boom' });
    render(
      <MessagesProvider messages={WORDS}>
        <DataWorkbench
          engine={engine}
          definitionId="orders"
          instanceId="orders-1"
          record={{
            actions: actions([ship(() => Promise.reject(failure))]),
          }}
        />
      </MessagesProvider>,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    fireEvent.click(screen.getByLabelText('Select all rows'));
    await userEvent.click(
      await screen.findByRole('button', { name: 'Ship 1/2' }),
    );
    await userEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: 'Ship 1',
      }),
    );
    await waitFor(() =>
      expect(status()?.textContent).toContain('1 failed · 1 not run'),
    );
    const told = events.filter(event => event.kind === 'action');
    expect(told).toHaveLength(1);
    expect(told[0]).toMatchObject({
      kind: 'action',
      error: failure,
      context: {
        operation: 'ship',
        recordKey: 'o-1',
        definitionId: 'orders',
        instanceId: 'orders-1',
      },
    });
  });
});

describe('a host’s question that is code', () => {
  it('asks by the action’s name when the question throws, rather than taking the surface down', async () => {
    const run = vi.fn(() => Promise.resolve());
    await open(
      actions([
        ship(),
        {
          ...cancel(run),
          confirm: () => {
            throw new Error('host bug');
          },
        },
      ]),
    );
    await userEvent.click(
      within(rowOf('o-1')).getByRole('button', { name: 'Actions for o-1' }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', { name: 'Cancel order' }),
    );
    const dialog = await screen.findByRole('alertdialog', {
      name: 'Cancel order',
    });
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Cancel order' }),
    );
    await waitFor(() => expect(run).toHaveBeenCalledTimes(1));
  });

  it('runs a choice at its pick when its computed question asks only a selection, the menu handing the keyboard back', async () => {
    const run = vi.fn(() => Promise.resolve());
    await open(
      actions([
        ship(),
        {
          ...priority(run),
          confirm: () => ({ title: text('orders.priorityTitle'), ask: 'bulk' }),
        },
      ]),
    );
    const more = within(rowOf('o-1')).getByRole('button', {
      name: 'Actions for o-1',
    });
    await userEvent.click(more);
    await userEvent.click(
      await screen.findByRole('menuitem', { name: 'High' }),
    );
    await waitFor(() => expect(run).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('dialog')).toBeNull();
    // The item said it opens nothing, so the menu gave the keyboard back.
    await waitFor(() => expect(document.activeElement).toBe(more));
  });
});
