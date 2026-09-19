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

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import type { EditorDescriptor, FilterValue } from '../src/index.js';
import { FilterValueEditor, ViewSurface } from '../src/ui/index.js';

afterEach(cleanup);

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
            editor={descriptor}
            value={current}
            label="amount"
            options={options ?? undefined}
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
        editor={{ input: 'none' }}
        value={null}
        label="amount"
        onChange={() => {}}
      />,
    );
    expect(container.textContent).toBe('');
  });

  it('collects a list from comma separated text', () => {
    const { changes } = editor({ input: 'text', multiple: true }, ['a']);

    fireEvent.change(screen.getByLabelText('amount'), {
      target: { value: 'a, b' },
    });

    expect(changes).toEqual([['a', 'b']]);
  });

  it('keeps the trailing comma while a second list value is typed', () => {
    const { changes } = editor({ input: 'text', multiple: true }, ['a']);
    const input = screen.getByLabelText('amount') as HTMLInputElement;

    fireEvent.change(input, { target: { value: 'a,' } });
    // The comma is the separator being typed; eating it re-derives the text
    // from the parsed list and makes a second value impossible to enter.
    expect(input.value).toBe('a,');
    expect(changes).toEqual([['a']]);

    fireEvent.change(input, { target: { value: 'a, b' } });
    expect(changes).toEqual([['a'], ['a', 'b']]);
  });

  it('adopts a value the host replaced with an equal list', () => {
    const { replace } = editor({ input: 'text', multiple: true }, ['a']);
    const input = screen.getByLabelText('amount') as HTMLInputElement;

    fireEvent.change(input, { target: { value: 'a,' } });
    expect(input.value).toBe('a,');

    // A host that rebuilds its config — a conflict reload, a reset — supplies
    // a fresh list with the same items; the half-typed draft must not survive.
    replace(['a']);
    expect(input.value).toBe('a');
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

    // Shown as the day it names, whatever zone the browser is in.
    expect(screen.getByLabelText('amount').textContent).toContain(
      new Date(2026, 8, 16).toLocaleDateString(),
    );

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    const day = await screen.findByRole('button', { name: /September 20/ });
    await user.click(day);

    expect(last(changes)).toEqual({ type: 'absolute', from: '2026-09-20' });
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

  it('stores a moment when the editor carries a time of day', async () => {
    const { changes } = editor({ input: 'date', withTime: true }, {
      type: 'absolute',
      from: '2026-09-16T00:00:00.000Z',
    } as unknown as FilterValue);

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    await user.click(
      await screen.findByRole('button', { name: /September 20/ }),
    );

    // A real moment the user picked: local midnight of that day, as an
    // instant the kernel will not move.
    expect(last(changes)).toEqual({
      type: 'absolute',
      from: new Date(2026, 8, 20).toISOString(),
    });
  });

  it('seeds a fresh absolute value in the shape the editor stores', async () => {
    const relative = {
      type: 'relative',
      amount: 7,
      unit: 'day',
    } as unknown as FilterValue;
    const user = userEvent.setup();

    const day = editor({ input: 'date', withTime: false }, relative);
    await user.click(screen.getByLabelText('amount kind'));
    await user.click(await screen.findByRole('option', { name: 'On a date' }));
    expect(last(day.changes)).toMatchObject({
      type: 'absolute',
      from: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
    });

    cleanup();
    const moment = editor({ input: 'date', withTime: true }, relative);
    await user.click(screen.getByLabelText('amount kind'));
    await user.click(await screen.findByRole('option', { name: 'On a date' }));
    expect(last(moment.changes)).toMatchObject({
      type: 'absolute',
      from: expect.stringMatching(/^\d{4}-\d{2}-\d{2}T[\d:.]+Z$/),
    });
  });
});
