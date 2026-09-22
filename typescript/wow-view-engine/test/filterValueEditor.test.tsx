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
import { useState } from 'react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import type {
  EditorDescriptor,
  FilterValue,
  OptionSource,
} from '../src/index.js';
import { SurfaceCalendar } from '../src/ui/filter/inputs/calendar.js';
import {
  en,
  FilterValueEditor,
  formatMessage,
  ViewSurface,
  zhCN,
} from '../src/ui/index.js';

afterEach(cleanup);

/**
 * A bound as the trigger reads it back: through the surface's own formatter,
 * and a wall-clock string as written — which is the rule `displayValue`
 * follows for a table cell and an applied badge alike.
 */
function onSurface(utc: number, withTime: boolean): string {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    ...(withTime ? { timeStyle: 'medium' as const } : {}),
    timeZone: 'UTC',
  }).format(utc);
}

/** The clock beside the calendar, by the name its own label gives it. */
function timeBox(label: string): HTMLInputElement {
  return screen.getByLabelText(label) as HTMLInputElement;
}

/** The last value a controlled editor reported. */
function last(changes: FilterValue[]): FilterValue {
  return changes[changes.length - 1];
}

describe('FilterValueEditor', () => {
  const CANDIDATES = [
    { value: 'CN', label: 'China' },
    { value: 'JP', label: 'Japan' },
  ];

  function editor(
    descriptor: EditorDescriptor,
    initial: FilterValue = null,
    options: typeof CANDIDATES | null = CANDIDATES,
    source: OptionSource | null = null,
  ): { changes: FilterValue[]; replace(next: FilterValue): void } {
    const changes: FilterValue[] = [];
    // A host like the filter panel feeds the editor the value it emitted —
    // the same reference — and may later replace the value wholesale.
    function Host({ forced }: { forced: FilterValue | null }) {
      const [current, setCurrent] = useState<FilterValue>(initial);
      if (forced !== null && forced !== current) {
        // A replacement wins over whatever was being typed.
        setCurrent(forced);
      }
      return (
        <ViewSurface>
          <FilterValueEditor
            kind="number"
            editor={descriptor}
            value={current}
            label="amount"
            options={options ?? undefined}
            source={source}
            onChange={next => {
              changes.push(next);
              setCurrent(next);
            }}
          />
        </ViewSurface>
      );
    }
    const view = render(<Host forced={null} />);
    return {
      changes,
      replace: (next: FilterValue) => view.rerender(<Host forced={next} />),
    };
  }

  it('renders nothing for an operator that takes no value', () => {
    const { container } = render(
      <FilterValueEditor
        kind="number"
        editor={{ input: 'none' }}
        value={null}
        label="amount"
        onChange={() => {}}
      />,
    );
    expect(container.textContent).toBe('');
  });

  /**
   * A text list used to be one box of comma-separated values, parsed on the
   * way out. It is chips now, like the number list: one value at a time,
   * each with a remove button of its own, so a value with a comma in it is
   * possible and a value already in the list is refused rather than
   * silently doubled.
   */
  it('collects a list of typed text values, one chip at a time', async () => {
    const user = userEvent.setup();
    const { changes } = editor({ input: 'text', multiple: true }, ['a']);

    await user.type(screen.getByLabelText('New amount'), 'b{Enter}');

    expect(changes).toEqual([['a', 'b']]);
    expect(screen.getByRole('button', { name: 'Remove b' })).toBeTruthy();
    // What was committed left the entry field, so the next value starts blank.
    expect(
      (screen.getByLabelText('New amount') as HTMLInputElement).value,
    ).toBe('');
  });

  it('trims a text value, and refuses a blank or a repeated one', async () => {
    const user = userEvent.setup();
    const { changes } = editor({ input: 'text', multiple: true }, ['a']);
    const entry = screen.getByLabelText('New amount');

    await user.type(entry, '  b  {Enter}');
    expect(last(changes)).toEqual(['a', 'b']);

    // Blank is not a value: Enter and leaving the field both commit nothing.
    await user.type(entry, '   ');
    await user.keyboard('{Enter}');
    await user.tab();
    // A value already in the list is refused, and the popup says so instead
    // of standing empty.
    await user.clear(entry);
    await user.type(entry, 'a');
    expect(await screen.findByText('Already in the list')).toBeTruthy();
    await user.keyboard('{Enter}');

    expect(changes).toEqual([['a', 'b']]);
  });

  it('adopts a value the host replaced with an equal list', async () => {
    const user = userEvent.setup();
    const { replace } = editor({ input: 'text', multiple: true }, ['a']);
    const entry = () => screen.getByLabelText('New amount') as HTMLInputElement;

    await user.type(entry(), 'b');
    expect(entry().value).toBe('b');

    // A host that rebuilds its config — a conflict reload, a reset — supplies
    // a fresh list with the same items; the half-typed draft must not survive.
    replace(['a']);
    expect(entry().value).toBe('');
  });

  it("offers the three deletion readings in the catalogue's words", async () => {
    const user = userEvent.setup();
    const { changes } = editor({ input: 'deletion' }, null);

    await user.click(screen.getByLabelText('amount'));
    const options = await screen.findAllByRole('option');
    expect(options.map(option => option.textContent)).toEqual([
      'Not deleted',
      'Deleted only',
      'Deleted included',
    ]);
    await user.click(screen.getByRole('option', { name: 'Deleted only' }));

    expect(changes).toEqual(['DELETED']);
  });

  it('collects one number and a range of two', () => {
    const single = editor({ input: 'number' }, 3);
    fireEvent.change(screen.getByLabelText('amount'), {
      target: { value: '7' },
    });
    expect(single.changes).toEqual([7]);
    cleanup();

    const range = editor({ input: 'number', range: true }, [1, 2]);
    fireEvent.change(screen.getByLabelText('amount to'), {
      target: { value: '9' },
    });
    expect(range.changes).toEqual([[1, 9]]);
  });

  /**
   * Two boxes side by side are two answers; a range is one. Nothing stood
   * between them, so «amount between 100 ———— 5,000» read as a pair of
   * numbers asked for separately — and the summary above the rows had been
   * printing a `~` between the same two ends all along. One catalogue entry
   * now, drawn `aria-hidden` because each end is already named.
   */
  it('joins the two ends of a range with the separator the summary uses', () => {
    editor({ input: 'number', range: true }, [1, 2]);

    const from = screen.getByLabelText('amount from');
    const row = from.closest('div[class*="flex"]') as HTMLElement;
    const join = row.querySelector(':scope > span[aria-hidden="true"]');

    expect(join).not.toBeNull();
    expect(join?.textContent).toBe(en['label.filter.range-join']);
    // It sits between them rather than after both, and neither end is the
    // one that gives when the pill runs out of room.
    expect(
      (join as Element).compareDocumentPosition(from) &
        Node.DOCUMENT_POSITION_PRECEDING,
    ).toBeTruthy();
    for (const end of ['amount from', 'amount to'])
      // A **surviving class assertion**: the two bounds share the row
      // evenly, which is a flex basis and not a state.
      expect(screen.getByLabelText(end).className).toContain('flex-1');
  });

  /**
   * The value select asked for a 160px floor, which a flex item reports
   * upwards whatever its container has: inside a condition pill it grew past
   * the pill's own border and under the ✕ beside it. It fills what the pill
   * has left instead, and clamps.
   */
  it('lets the value select shrink to whatever room the pill has', () => {
    editor({ input: 'select', options: CANDIDATES }, 'CN');

    const trigger = screen.getByLabelText('amount');
    expect(trigger.className).toContain('min-w-0');
    expect(trigger.className).not.toMatch(/(^|\s)min-w-(?!0)/);
  });

  it('offers true and false for a boolean', async () => {
    const user = userEvent.setup();
    const { changes } = editor({ input: 'boolean' }, true);

    await user.click(screen.getByLabelText('amount'));
    await user.click(await screen.findByRole('option', { name: 'False' }));

    expect(changes).toEqual([false]);
  });

  /**
   * A row the user has only just added read as "is False" — a condition
   * already narrowing the list — and picking the False it appeared to hold
   * changed nothing, so the value it showed could not even be confirmed.
   */
  it('shows no choice for a blank boolean, and fires on the first pick', async () => {
    const user = userEvent.setup();
    const { changes } = editor({ input: 'boolean' }, null);

    const trigger = screen.getByLabelText('amount');
    expect(trigger.textContent).not.toContain('False');

    await user.click(trigger);
    await user.click(await screen.findByRole('option', { name: 'False' }));

    expect(changes).toEqual([false]);
  });

  /**
   * `Number('')` is 0, so emptying a number field used to ask for "equals
   * zero", and a half-typed one for `NaN`, which no kind admits.
   */
  it('leaves an emptied number blank rather than asking for zero', () => {
    const { changes } = editor({ input: 'number' }, 3);
    const input = screen.getByLabelText('amount') as HTMLInputElement;

    fireEvent.change(input, { target: { value: '1e' } });
    fireEvent.change(input, { target: { value: '' } });

    expect(last(changes)).toBeNull();
    expect(input.value).toBe('');
    expect(
      changes.some(value => typeof value === 'number' && Number.isNaN(value)),
    ).toBe(false);
  });

  it('blanks a range once both of its ends are emptied', () => {
    const { changes } = editor({ input: 'number', range: true }, [1, 2]);

    fireEvent.change(screen.getByLabelText('amount from'), {
      target: { value: '' },
    });
    expect(last(changes)).toEqual([null, 2]);

    fireEvent.change(screen.getByLabelText('amount to'), {
      target: { value: '' },
    });
    expect(last(changes)).toBeNull();
  });

  /** Type a number into the entry field and hand it to the list. */
  async function addValue(
    user: ReturnType<typeof userEvent.setup>,
    typed: string,
    by: 'enter' | 'option' | 'blur' = 'enter',
  ): Promise<void> {
    const entry = screen.getByLabelText('New amount');
    await user.click(entry);
    if (typed.length > 0) await user.type(entry, typed);
    if (by === 'enter') await user.keyboard('{Enter}');
    else if (by === 'blur') await user.tab();
    else
      await user.click(
        await screen.findByRole('option', { name: `Add ${typed}` }),
      );
  }

  /**
   * `IN` and `NOT_IN` compile an array of any length, and this editor used to
   * borrow the range's pair of boxes — so a third value had nowhere to go.
   * What is typed is offered back as the one item of a popup, so Enter and a
   * click on it are the same commit.
   */
  it('takes as many values into a number list as are entered', async () => {
    const user = userEvent.setup();
    const { changes } = editor({ input: 'number', multiple: true }, []);

    await addValue(user, '1');
    await addValue(user, '2');
    await addValue(user, '3', 'option');

    expect(last(changes)).toEqual([1, 2, 3]);
    // What was committed left the entry field, so the next value starts blank.
    expect(
      (screen.getByLabelText('New amount') as HTMLInputElement).value,
    ).toBe('');
  });

  it('removes the value its own remove button names', () => {
    const { changes } = editor({ input: 'number', multiple: true }, [1, 2, 3]);

    fireEvent.click(screen.getByRole('button', { name: 'Remove 2' }));

    expect(last(changes)).toEqual([1, 3]);
    // The last one out leaves the kind's blank value, not a list of nothing.
    for (const value of [1, 3])
      fireEvent.click(screen.getByRole('button', { name: `Remove ${value}` }));
    expect(last(changes)).toEqual([]);
  });

  /**
   * Apply is a button elsewhere on the panel, and reaching for it blurs this
   * field first — so a number typed and not yet added was dropped by the very
   * click meant to run the query with it.
   */
  it('takes a number left in the entry field when it is left', async () => {
    const user = userEvent.setup();
    const { changes } = editor({ input: 'number', multiple: true }, [1]);

    await addValue(user, '7', 'blur');

    expect(last(changes)).toEqual([1, 7]);
    expect(
      (screen.getByLabelText('New amount') as HTMLInputElement).value,
    ).toBe('');
  });

  /**
   * An empty entry is a normal editing state, not a value: committing it
   * would ask for `Number('')`, which is zero, and half a number is `NaN`.
   * Neither is offered; the popup says why instead of standing empty.
   */
  it('commits nothing from an empty or half-typed number entry', async () => {
    const user = userEvent.setup();
    const { changes } = editor({ input: 'number', multiple: true }, [1]);
    const entry = screen.getByLabelText('New amount');

    await addValue(user, '');
    await addValue(user, '', 'blur');
    await user.type(entry, '1e');
    expect(await screen.findByText('Not a number')).toBeTruthy();
    await user.keyboard('{Enter}');
    // Leaving the field is the same rule as Enter, not a laxer one.
    await user.tab();
    // The same number twice asks nothing more, and would name two remove
    // buttons alike.
    await user.clear(entry);
    await user.type(entry, '1');
    expect(await screen.findByText('Already in the list')).toBeTruthy();
    await user.keyboard('{Enter}');
    await user.tab();

    expect(changes).toEqual([]);
  });

  /**
   * What is in the entry field belongs to the list it is being typed into.
   * The panel's discard puts the applied list back, and the half-typed number
   * used to survive it — the next Add or blur wrote it into the very list the
   * user had just restored, so the discard had not discarded.
   */
  it('forgets what was being typed once the list is replaced from outside', async () => {
    const user = userEvent.setup();
    const { changes, replace } = editor(
      { input: 'number', multiple: true },
      [1],
    );
    const entry = () => screen.getByLabelText('New amount') as HTMLInputElement;

    await user.type(entry(), '3');
    expect(entry().value).toBe('3');

    // A discard, a config reload, another view opened: a fresh list arrives,
    // equal or not to the one on screen.
    replace([1]);

    expect(entry().value).toBe('');
    // Reaching for Apply blurs the field, and that blur commits what is in
    // it. There is nothing in it.
    await user.tab();
    expect(changes).toEqual([]);
  });

  /**
   * The entry field answers Enter itself, so it takes the keystroke out of
   * the panel's reach. Only its own: Enter with a modifier held is some
   * other shortcut, very likely the host page's, and `FilterPanel` lets it
   * by for that reason — a field that swallowed it would be the one place in
   * the panel where the host's shortcut stops working.
   */
  it('leaves a modified Enter to whatever else wants it', async () => {
    const user = userEvent.setup();
    const { changes } = editor({ input: 'number', multiple: true }, [1]);
    const entry = screen.getByLabelText('New amount');
    let reached = 0;
    const listen = () => {
      reached += 1;
    };
    document.addEventListener('keydown', listen);

    try {
      await user.type(entry, '3');
      reached = 0;
      fireEvent.keyDown(entry, { key: 'Enter', shiftKey: true });

      // Neither added, nor stopped on its way up.
      expect(changes).toEqual([]);
      expect(reached).toBe(1);

      // A plain Enter is still this field's own answer, and still nobody
      // else's to hear.
      fireEvent.keyDown(entry, { key: 'Enter' });
      expect(changes).toEqual([[1, 3]]);
      expect(reached).toBe(1);
    } finally {
      document.removeEventListener('keydown', listen);
    }
  });

  it('keeps a relative window blank rather than asking for zero units', () => {
    const { changes } = editor({ input: 'relativeDate' }, {
      type: 'relative',
      amount: 7,
      unit: 'day',
    } as unknown as FilterValue);
    const amount = screen.getByLabelText('amount amount') as HTMLInputElement;

    fireEvent.change(amount, { target: { value: '' } });

    expect(last(changes)).toBeNull();
    // Blank, and still the row the user was writing rather than a calendar.
    expect(amount.value).toBe('');
    expect(screen.getByLabelText('amount kind').textContent).toContain(
      'Relative',
    );
  });

  it('offers the options a kind declared', async () => {
    const { changes } = editor(
      {
        input: 'select',
        options: [
          { value: 'CN', label: 'China' },
          { value: 'JP', label: 'Japan' },
        ],
      },
      'CN',
    );

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    await user.click(await screen.findByRole('option', { name: 'Japan' }));

    expect(changes).toEqual(['JP']);
  });

  /**
   * A text and a number field both say `Not set` while they are blank; the
   * enum said nothing at all — `placeholder` never reached `SelectValue`, so
   * the trigger was an empty box, and the only ✕ beside it removes the
   * condition rather than the value it does not have.
   */
  it('says a blank enum is not set, and offers nothing to clear', () => {
    editor({
      input: 'select',
      multiple: true,
      options: [{ value: 'CN', label: 'China' }],
    });

    const shown = screen
      .getByLabelText('amount')
      .querySelector('[data-slot="select-value"]');
    expect(shown?.textContent).toBe('Not set');
    // The chevron is the only other control in the box: nothing offers to
    // clear a value that is not there.
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('says a blank yes-or-no is not set, like every other blank value', () => {
    editor({ input: 'boolean' });

    const shown = screen
      .getByLabelText('amount')
      .querySelector('[data-slot="select-value"]');
    expect(shown?.textContent).toBe('Not set');
  });

  it('uses the candidates a remote editor was given', async () => {
    const user = userEvent.setup();
    const { changes } = editor(
      { input: 'remote', remote: 'warehouses', multiple: true },
      [],
    );

    await user.click(screen.getByLabelText('amount'));
    await user.click(await screen.findByRole('option', { name: 'China' }));

    expect(changes).toEqual([['CN']]);
  });

  /**
   * A searchable source (F-04). What it is asked, and when, is the contract:
   * nothing until the list opens, one search per pause in typing, every
   * request carrying a signal that the next query aborts.
   */
  function fakeSource(
    pages: Record<string, { items: typeof CANDIDATES; nextCursor?: string }>,
    failing: string[] = [],
  ) {
    const calls: { query: string; cursor?: string; signal?: AbortSignal }[] =
      [];
    const source: OptionSource = {
      search: ({ query, cursor }, signal) => {
        calls.push({ query, cursor, signal });
        if (failing.includes(query)) return Promise.reject(new Error('down'));
        const page = pages[cursor ? `${query}@${cursor}` : query] ?? {
          items: [],
        };
        return Promise.resolve({
          items: page.items,
          nextCursor: page.nextCursor ?? null,
        });
      },
      resolve: () => Promise.resolve([]),
    };
    return { source, calls };
  }

  it("searches a source once typing pauses, and writes what was picked in the value's own shape", async () => {
    const user = userEvent.setup();
    const { source, calls } = fakeSource({
      '': { items: CANDIDATES },
      ja: { items: [CANDIDATES[1]] },
    });
    const { changes } = editor(
      { input: 'remote', remote: 'warehouses', multiple: true },
      { items: [] },
      null,
      source,
    );
    // Nothing asked for a pill that is merely on screen.
    expect(calls).toHaveLength(0);

    const input = screen.getByLabelText('amount');
    await user.click(input);
    await screen.findByRole('option', { name: 'China' });
    expect(calls.map(call => call.query)).toEqual(['']);

    await user.type(input, 'ja');
    await waitFor(() => expect(calls[calls.length - 1]?.query).toBe('ja'));
    // One search for the pause, not one per keystroke; the first was aborted.
    expect(calls).toHaveLength(2);
    expect(calls[0].signal?.aborted).toBe(true);
    await user.click(await screen.findByRole('option', { name: 'Japan' }));

    // A reference value keeps the label beside the id.
    expect(changes).toEqual([{ items: [{ id: 'JP', label: 'Japan' }] }]);
    expect(screen.getByRole('button', { name: 'Remove Japan' })).toBeDefined();
  });

  it('writes plain ids for a value that held plain ids', async () => {
    const user = userEvent.setup();
    const { source } = fakeSource({ '': { items: CANDIDATES } });
    const { changes } = editor(
      { input: 'remote', remote: 'warehouses', multiple: true },
      ['CN'],
      null,
      source,
    );

    await user.click(screen.getByLabelText('amount'));
    await user.click(await screen.findByRole('option', { name: 'Japan' }));

    expect(changes).toEqual([['CN', 'JP']]);
  });

  it('offers the next page while the source has one, and says when a page failed', async () => {
    const user = userEvent.setup();
    const { source, calls } = fakeSource(
      {
        '': { items: [CANDIDATES[0]], nextCursor: '1' },
        '@1': { items: [CANDIDATES[1]] },
      },
      ['x'],
    );
    editor(
      { input: 'remote', remote: 'warehouses', multiple: true },
      { items: [] },
      null,
      source,
    );

    await user.click(screen.getByLabelText('amount'));
    await screen.findByRole('option', { name: 'China' });
    expect(screen.queryByRole('option', { name: 'Japan' })).toBeNull();

    await user.click(screen.getByRole('button', { name: 'More' }));
    await screen.findByRole('option', { name: 'Japan' });
    expect(calls[calls.length - 1]).toMatchObject({ query: '', cursor: '1' });
    expect(screen.queryByRole('button', { name: 'More' })).toBeNull();

    // A failed search keeps the page that was listed and offers a retry.
    await user.type(screen.getByLabelText('amount'), 'x');
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('The candidates could not be loaded');
    expect(screen.getByRole('option', { name: 'China' })).toBeDefined();
    expect(screen.queryByText('No match')).toBeNull();

    await user.click(within(alert).getByRole('button', { name: 'Try again' }));
    await waitFor(() =>
      expect(calls.filter(call => call.query === 'x')).toHaveLength(2),
    );
  });

  it('falls back to typed entry when no remote candidates are given', () => {
    const { changes } = editor(
      { input: 'remote', remote: 'warehouses' },
      '',
      null,
    );

    fireEvent.change(screen.getByLabelText('amount'), {
      target: { value: 'w-1' },
    });

    expect(changes).toEqual(['w-1']);
  });

  it('switches a date between absolute, relative and a period', async () => {
    const { changes } = editor({ input: 'date' }, {
      type: 'absolute',
      from: '2026-09-16T00:00:00.000Z',
    } as unknown as FilterValue);

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount kind'));
    // The shape is neutral now; the direction beside it carries the wording.
    await user.click(await screen.findByRole('option', { name: 'Relative' }));
    expect(last(changes)).toMatchObject({ type: 'relative', unit: 'day' });

    cleanup();
    const relative = editor({ input: 'relativeDate' }, {
      type: 'relative',
      amount: 7,
      unit: 'day',
    } as unknown as FilterValue);
    fireEvent.change(screen.getByLabelText('amount amount'), {
      target: { value: '30' },
    });
    expect(last(relative.changes)).toMatchObject({ amount: 30 });

    await user.click(screen.getByLabelText('amount kind'));
    await user.click(await screen.findByRole('option', { name: 'A period' }));
    expect(last(relative.changes)).toMatchObject({ preset: 'today' });
  });

  it('asks a relative window which way it runs', async () => {
    // The kernel could express "the next 7 days" while this editor could
    // only ever write a backwards window, which left the feature unreachable.
    const { changes } = editor({ input: 'relativeDate' }, {
      type: 'relative',
      amount: 7,
      unit: 'day',
    } as unknown as FilterValue);

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount direction'));
    await user.click(
      await screen.findByRole('option', { name: 'In the next' }),
    );

    expect(last(changes)).toMatchObject({
      type: 'relative',
      amount: 7,
      unit: 'day',
      direction: 'future',
    });
  });

  it('opens a window with no direction as the past one', () => {
    editor({ input: 'relativeDate' }, {
      type: 'relative',
      amount: 7,
      unit: 'day',
    } as unknown as FilterValue);

    expect(screen.getByLabelText('amount direction').textContent).toContain(
      'In the last',
    );
  });

  /**
   * A day picked without a time of day is a calendar day, stored as
   * `YYYY-MM-DD`. Storing `toISOString()` pinned local midnight to UTC with a
   * `Z`, which the kernel treats as one fixed moment: no runtime or condition
   * zone was ever applied, and a range lost its last day.
   */
  it('picks a day from the calendar and stores the day, not an instant', async () => {
    const { changes } = editor({ input: 'date', withTime: false }, {
      type: 'absolute',
      from: '2026-09-16',
    } as unknown as FilterValue);

    // Shown as the day it names, whatever zone the browser is in — and in
    // the surface's own wording for a date, the one the applied bar and the
    // cells beside it use, rather than the browser's `toLocaleDateString`.
    expect(screen.getByLabelText('amount').textContent).toContain(
      'Sep 16, 2026',
    );

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    const day = await screen.findByRole('button', { name: /September 20/ });
    await user.click(day);

    expect(last(changes)).toEqual({ type: 'absolute', from: '2026-09-20' });
  });

  /**
   * The picker used to be the one control on the surface that did not speak
   * its language: `react-day-picker` reads its words out of a date-fns
   * locale object and falls back to `en-US`, so a 简体中文 surface whose
   * trigger said «2026年9月15日» opened `September 2026` over
   * `Su Mo Tu We Th Fr Sa`, and told a screen reader
   * `Sunday, August 30th, 2026` and `Go to the Next Month`.
   */
  it('draws the calendar in the surface language, its chrome included', async () => {
    const user = userEvent.setup();
    // A day that is neither the selected one nor today, so its name is the
    // bare date; the selected one is checked below, wording and all.
    const day = new Date(2026, 8, 18);
    render(
      <ViewSurface locale="zh-CN" messages={zhCN}>
        <FilterValueEditor
          kind="datetime"
          editor={{ input: 'date', withTime: false }}
          value={
            { type: 'absolute', from: '2026-09-16' } as unknown as FilterValue
          }
          label="创建时间"
          onChange={() => {}}
        />
      </ViewSurface>,
    );
    await user.click(screen.getByLabelText('创建时间'));

    // Everything that is a date comes from `Intl` in the surface's language,
    // so the expectations are built the same way rather than typed out: what
    // is pinned is that the picker and the rest of the package agree, not
    // which words one release of ICU chose.
    const inWords = (options: Intl.DateTimeFormatOptions) =>
      new Intl.DateTimeFormat('zh-CN', options).format(day);
    expect(
      await screen.findByText(inWords({ year: 'numeric', month: 'long' })),
    ).toBeTruthy();
    expect(
      screen.getByRole('button', { name: inWords({ dateStyle: 'full' }) }),
    ).toBeTruthy();
    // What the grid says with colour alone, said in words for a reader.
    const chosen = new Intl.DateTimeFormat('zh-CN', {
      dateStyle: 'full',
    }).format(new Date(2026, 8, 16));
    expect(
      screen.getByRole('button', {
        name: formatMessage(zhCN, 'label.date.calendar-selected', {
          date: chosen,
        }),
      }),
    ).toBeTruthy();
    // The heads are short on screen and long in the name a reader hears;
    // both used to be `Su Mo Tu We Th Fr Sa` / `Sunday`.
    const heads = [...document.querySelectorAll('th[aria-label]')];
    expect(heads.map(head => head.getAttribute('aria-label'))).toContain(
      inWords({ weekday: 'long' }),
    );
    expect(heads.map(head => head.textContent)).toContain(
      inWords({ weekday: 'short' }),
    );

    // And the words around them, which no formatter produces, come from the
    // catalogue in force.
    for (const key of [
      'label.date.calendar-previous',
      'label.date.calendar-next',
    ] as const)
      expect(screen.getByRole('button', { name: zhCN[key] })).toBeTruthy();
  });

  it('stores both ends of a day range as days', async () => {
    const { changes } = editor(
      { input: 'dateRange', range: true, withTime: false },
      { type: 'absolute', from: '2026-09-16' } as unknown as FilterValue,
    );

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    await user.click(
      await screen.findByRole('button', { name: /September 20/ }),
    );

    expect(last(changes)).toEqual({
      type: 'absolute',
      from: '2026-09-16',
      to: '2026-09-20',
    });
  });

  /**
   * A `withTime` field's bound is a day and a time of day, both written on a
   * clock and neither carrying an offset: it is the kernel that reads them,
   * in the runtime's or the condition's zone. The calendar keeps the time
   * beside it, so moving the day does not silently drop the hour.
   *
   * Storing `toISOString()` pinned local midnight to UTC with a `Z`, which
   * the kernel rightly takes as one fixed moment — no zone applied, and no
   * interval reading left for an empty time (D17-1).
   */
  it('keeps the day and the time apart, with no offset', async () => {
    const { changes } = editor({ input: 'date', withTime: true }, {
      type: 'absolute',
      from: '2026-09-16T09:30:00',
    } as unknown as FilterValue);

    // The trigger reads the time back, because one was given.
    expect(screen.getByLabelText('amount').textContent).toContain(
      onSurface(Date.UTC(2026, 8, 16, 9, 30), true),
    );

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    expect(timeBox('Time').value).toBe('09:30:00');
    await user.click(
      await screen.findByRole('button', { name: /September 20/ }),
    );

    expect(last(changes)).toEqual({
      type: 'absolute',
      from: '2026-09-20T09:30:00',
    });
  });

  /**
   * An empty time is not midnight: the bound stays the day itself, which the
   * kernel reads as an interval — `00:00:00.000` as a start, `23:59:59.999`
   * as an end. So the control writes the day alone until a time is typed,
   * and emptying the box takes the bound back to the day.
   */
  it('adds a time of day to a bound, and takes it off again', async () => {
    const { changes } = editor({ input: 'date', withTime: true }, {
      type: 'absolute',
      from: '2026-09-20',
    } as unknown as FilterValue);

    // A day alone reads as a day: no 12:00:00 AM nobody chose.
    expect(screen.getByLabelText('amount').textContent).toContain(
      onSurface(Date.UTC(2026, 8, 20), false),
    );

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    const time = timeBox('Time');
    expect(time.value).toBe('');

    fireEvent.change(time, { target: { value: '15:30:00' } });
    expect(last(changes)).toEqual({
      type: 'absolute',
      from: '2026-09-20T15:30:00',
    });

    fireEvent.change(timeBox('Time'), { target: { value: '' } });
    expect(last(changes)).toEqual({ type: 'absolute', from: '2026-09-20' });
  });

  /** Seconds are optional in the box; a time without them is on the second. */
  it('stores a time given without seconds on the whole second', async () => {
    const { changes } = editor({ input: 'date', withTime: true }, {
      type: 'absolute',
      from: '2026-09-20',
    } as unknown as FilterValue);

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    fireEvent.change(timeBox('Time'), { target: { value: '15:30' } });

    expect(last(changes)).toEqual({
      type: 'absolute',
      from: '2026-09-20T15:30:00',
    });
  });

  /** Both ends of a range get their own clock, and one control submits. */
  it('gives each end of a range its own time of day', async () => {
    const { changes } = editor(
      { input: 'dateRange', range: true, withTime: true },
      {
        type: 'absolute',
        from: '2026-09-16',
        to: '2026-09-20',
      } as unknown as FilterValue,
    );

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    fireEvent.change(timeBox('From time'), { target: { value: '09:00:00' } });
    expect(last(changes)).toEqual({
      type: 'absolute',
      from: '2026-09-16T09:00:00',
      to: '2026-09-20',
    });

    fireEvent.change(timeBox('To time'), { target: { value: '18:45:00' } });
    expect(last(changes)).toEqual({
      type: 'absolute',
      from: '2026-09-16T09:00:00',
      to: '2026-09-20T18:45:00',
    });
  });

  /**
   * A time of day is not a moment until something says which day it is on,
   * and a blank date condition must not read the clock to invent one.
   */
  it('waits for a day before a time of day can be given', async () => {
    editor({ input: 'dateRange', range: true, withTime: true });

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    expect(timeBox('From time').disabled).toBe(true);
    expect(timeBox('To time').disabled).toBe(true);
  });

  /**
   * A field without a time of day gets no clock — and keeps none. A bound an
   * older editor stored as an instant still shows the day it names, but the
   * time inside it has no control and must not be written back invisibly:
   * that would leave a plain date field asking for midnight where the day
   * itself is the condition.
   */
  it('offers no time of day on a plain date field, and keeps none', async () => {
    const { changes } = editor({ input: 'date', withTime: false }, {
      type: 'absolute',
      from: new Date(2026, 8, 16, 9, 30).toISOString(),
    } as unknown as FilterValue);

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    await user.click(
      await screen.findByRole('button', { name: /September 20/ }),
    );

    expect(screen.queryByLabelText('Time')).toBeNull();
    expect(last(changes)).toEqual({ type: 'absolute', from: '2026-09-20' });
  });

  /**
   * A date condition that has only just been added asks nothing yet, and an
   * editor that seeded the calendar with `new Date()` said otherwise: the
   * pill read `between · On a date · 9/20/2026, 9:12:55 PM – Pick a date`
   * while it was blank and dashed. The kernel refused to compile it, so
   * applying changed no row — and the screen had claimed the list was
   * already narrowed to everything from this moment on.
   */
  it('does not read the clock for a date nobody has picked', async () => {
    const day = editor({ input: 'dateRange', range: true, withTime: false });

    const trigger = screen.getByLabelText('amount');
    expect(trigger.textContent).toContain('Pick a date – Pick a date');
    expect(trigger.textContent).not.toMatch(/\d/);
    expect(day.changes).toEqual([]);

    const user = userEvent.setup();
    await user.click(trigger);
    await user.click(
      await screen.findByRole('button', { name: /September 20/ }),
    );

    expect(last(day.changes)).toEqual({
      type: 'absolute',
      from: '2026-09-20',
      to: '2026-09-20',
    });
  });

  /**
   * The two shapes that are an answer on their own keep their defaults — "the
   * last 7 days" and "today" are whole conditions. A calendar date is not
   * one until a day is picked, so choosing that shape blanks the leaf and the
   * editor remembers the shape itself.
   */
  it('blanks the leaf when the calendar shape is chosen', async () => {
    const user = userEvent.setup();
    const day = editor({ input: 'date', withTime: true }, {
      type: 'relative',
      amount: 7,
      unit: 'day',
    } as unknown as FilterValue);

    await user.click(screen.getByLabelText('amount kind'));
    await user.click(await screen.findByRole('option', { name: 'On a date' }));

    expect(last(day.changes)).toBeNull();
    expect(screen.getByLabelText('amount kind').textContent).toContain(
      'On a date',
    );
    expect(screen.getByLabelText('amount').textContent).toContain(
      'Pick a date',
    );
  });

  /** Taking the last day back off the calendar is blank, not half a range. */
  it('blanks the leaf when the calendar is emptied', async () => {
    const { changes } = editor({ input: 'date', withTime: false }, {
      type: 'absolute',
      from: '2026-09-20',
    } as unknown as FilterValue);

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    await user.click(
      await screen.findByRole('button', { name: /September 20/ }),
    );

    expect(last(changes)).toBeNull();
  });
});

/**
 * The wrapper takes every prop the registry's calendar does, so the strings
 * it draws only under some of them are in the surface's language too — and a
 * locale tag `Intl` refuses is a host's string, not a crash.
 */
describe('SurfaceCalendar', () => {
  it('names the month, the year and the week in the surface language', () => {
    render(
      <ViewSurface locale="zh-CN">
        <SurfaceCalendar
          mode="single"
          captionLayout="dropdown"
          showWeekNumber
          month={new Date(2026, 8, 1)}
          startMonth={new Date(2026, 0, 1)}
          endMonth={new Date(2026, 11, 31)}
        />
      </ViewSurface>,
    );

    const said = (options: Intl.DateTimeFormatOptions) =>
      new Intl.DateTimeFormat('zh-CN', options).format(new Date(2026, 8, 1));
    const options = [...document.querySelectorAll('option')].map(
      option => option.textContent,
    );
    expect(options).toContain(said({ month: 'short' }));
    expect(options).toContain(said({ year: 'numeric' }));
    // The week numbers down the side are digits in the locale's own
    // numbering system, which is all a number is. The registry draws them in
    // a `td` rather than the library's `th`, so they are found by the role
    // the library gives them.
    expect(
      [...document.querySelectorAll('[role="rowheader"]')].map(cell =>
        cell.textContent?.trim(),
      ),
    ).toContain(new Intl.NumberFormat('zh-CN').format(36));
  });

  it('falls back to the environment for a tag Intl refuses', () => {
    // `Intl` throws on construction for a malformed tag, and a surface's
    // `locale` is whatever a host passed. One bad tag must not take the
    // calendar — or the condition it is inside — down with it.
    render(
      <ViewSurface locale="not a locale">
        <SurfaceCalendar mode="single" month={new Date(2026, 8, 1)} />
      </ViewSurface>,
    );

    expect(document.querySelectorAll('th[aria-label]').length).toBe(7);
  });
});
