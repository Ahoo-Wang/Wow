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
 * The time of day beside a date's calendar (用户 2026-09-25): a whole day
 * until 「+ 指定时刻」, then an hour box and a minute box to the minute,
 * each cleared back to the whole day by its ×, and 「移除时刻」 folding it
 * again — the segments' parsing, stepping, holding in range and clearing.
 */

import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import type { EditorDescriptor, FilterValue } from '../src/index.js';
import { FilterValueEditor, ViewSurface, zhCN } from '../src/ui/index.js';
import {
  clampSegment,
  hourIsComplete,
  parseTime,
  writeTime,
} from '../src/ui/filter/inputs/timeOfDay.js';

afterEach(cleanup);

describe('a time of day as two segments', () => {
  it('reads a stored time to the minute, and nothing else as a time', () => {
    expect(parseTime('09:05')).toEqual({ hour: 9, minute: 5 });
    // What the native box used to write keeps its hour and minute.
    expect(parseTime('18:45:30')).toEqual({ hour: 18, minute: 45 });
    expect(parseTime('')).toEqual({ hour: null, minute: null });
    expect(parseTime('24:00')).toEqual({ hour: null, minute: null });
    expect(parseTime('9:5')).toEqual({ hour: null, minute: null });
  });

  it('holds a segment inside 00–23 and 00–59, whole', () => {
    expect(clampSegment(25, 'hour')).toBe(23);
    expect(clampSegment(75, 'minute')).toBe(59);
    expect(clampSegment(-1, 'hour')).toBe(0);
    expect(clampSegment(7.5, 'minute')).toBe(7);
    expect(clampSegment(null, 'hour')).toBeNull();
    expect(clampSegment(Number.NaN, 'hour')).toBeNull();
  });

  it('writes HH:mm, a blank segment beside a typed one at 00, none as the day', () => {
    expect(writeTime({ hour: 9, minute: 5 })).toBe('09:05');
    expect(writeTime({ hour: 9, minute: null })).toBe('09:00');
    expect(writeTime({ hour: null, minute: 30 })).toBe('00:30');
    expect(writeTime({ hour: null, minute: null })).toBe('');
  });

  it('moves on to the minutes once a whole hour is typed', () => {
    expect(hourIsComplete('09')).toBe(true);
    expect(hourIsComplete('9')).toBe(true);
    expect(hourIsComplete('1')).toBe(false);
    expect(hourIsComplete('2')).toBe(false);
    expect(hourIsComplete('')).toBe(false);
  });
});

/** A controlled date editor, and what it reported. */
function editor(descriptor: EditorDescriptor, initial: FilterValue = null) {
  const changes: FilterValue[] = [];
  function Host() {
    const [current, setCurrent] = useState<FilterValue>(initial);
    return (
      <ViewSurface>
        <FilterValueEditor
          kind="datetime"
          editor={descriptor}
          value={current}
          label="Signed"
          onChange={next => {
            changes.push(next);
            setCurrent(next);
          }}
        />
      </ViewSurface>
    );
  }
  render(<Host />);
  return { changes, last: () => changes[changes.length - 1] };
}

const box = (name: string) =>
  screen.getByRole('textbox', { name }) as HTMLInputElement;

describe('the time beside the calendar', () => {
  it('stands at the whole day until a time is asked for', async () => {
    const user = userEvent.setup();
    const day = editor({ input: 'dateRange', range: true, withTime: true }, {
      type: 'absolute',
      from: '2026-09-16',
      to: '2026-09-20',
    } as never);
    await user.click(screen.getByRole('button', { name: 'Signed' }));

    expect(await screen.findByText('Whole day')).toBeTruthy();
    expect(
      screen.queryByRole('textbox', { name: 'From time hour' }),
    ).toBeNull();
    // The hint says what the whole day is.
    expect(screen.getByText(/Left empty, a day runs/)).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Set a time' }));
    expect(document.activeElement).toBe(box('From time hour'));
    expect(box('To time hour')).toBeTruthy();
    expect(day.changes).toEqual([]);
  });

  it('types 09:05 to the minute, moving on from the hour by itself', async () => {
    const user = userEvent.setup();
    const day = editor({ input: 'date', withTime: true }, {
      type: 'absolute',
      from: '2026-09-20',
    } as never);
    await user.click(screen.getByRole('button', { name: 'Signed' }));
    await user.click(await screen.findByRole('button', { name: 'Set a time' }));

    await user.keyboard('09');
    expect(document.activeElement).toBe(box('Time minute'));
    await user.keyboard('05');
    expect(day.last()).toEqual({ type: 'absolute', from: '2026-09-20T09:05' });
    await user.tab();
    expect(box('Time hour').value).toBe('09');
    expect(box('Time minute').value).toBe('05');
  });

  it('steps a segment with the arrow keys and holds it in range', async () => {
    const user = userEvent.setup();
    const day = editor({ input: 'date', withTime: true }, {
      type: 'absolute',
      from: '2026-09-20T23:58',
    } as never);
    await user.click(screen.getByRole('button', { name: 'Signed' }));

    await user.click(await screen.findByRole('textbox', { name: 'Time hour' }));
    // 23 is the last hour there is.
    await user.keyboard('{ArrowUp}');
    expect(box('Time hour').value).toBe('23');
    await user.keyboard('{ArrowDown}');
    expect(day.last()).toEqual({ type: 'absolute', from: '2026-09-20T22:58' });

    await user.click(box('Time minute'));
    await user.keyboard('{ArrowUp}{ArrowUp}{ArrowUp}');
    expect(day.last()).toEqual({ type: 'absolute', from: '2026-09-20T22:59' });
  });

  it('keeps the day and the time apart, with no offset, when the day moves', async () => {
    const user = userEvent.setup();
    const day = editor({ input: 'date', withTime: true }, {
      type: 'absolute',
      from: '2026-09-16T09:30:00',
    } as never);
    await user.click(screen.getByRole('button', { name: 'Signed' }));
    expect(box('Time hour').value).toBe('09');
    expect(box('Time minute').value).toBe('30');

    await user.click(
      await screen.findByRole('button', { name: /September 20/ }),
    );
    expect(day.last()).toEqual({ type: 'absolute', from: '2026-09-20T09:30' });
  });

  it('clears one bound back to the whole day, and removes the time from both', async () => {
    const user = userEvent.setup();
    const day = editor({ input: 'dateRange', range: true, withTime: true }, {
      type: 'absolute',
      from: '2026-09-16T09:00',
      to: '2026-09-20T18:45',
    } as never);
    await user.click(screen.getByRole('button', { name: 'Signed' }));

    await user.click(
      await screen.findByRole('button', { name: 'Clear To time' }),
    );
    expect(day.last()).toEqual({
      type: 'absolute',
      from: '2026-09-16T09:00',
      to: '2026-09-20',
    });
    expect(document.activeElement).toBe(box('To time hour'));
    expect(box('To time hour').value).toBe('');
    expect(
      document.querySelector('[data-slot="date-time-status"]')?.textContent,
    ).toBe('To time cleared: the whole day');
    // Nothing left to clear there, so no ×.
    expect(screen.queryByRole('button', { name: 'Clear To time' })).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Remove time' }));
    expect(day.last()).toEqual({
      type: 'absolute',
      from: '2026-09-16',
      to: '2026-09-20',
    });
    expect(screen.getByText('Whole day')).toBeTruthy();
    expect(document.activeElement).toBe(
      screen.getByRole('button', { name: 'Set a time' }),
    );
    expect(
      document.querySelector('[data-slot="date-time-status"]')?.textContent,
    ).toBe('Time removed: whole days');
  });

  it('waits for a day before a time of day can be given', async () => {
    const user = userEvent.setup();
    editor({ input: 'dateRange', range: true, withTime: true });
    await user.click(screen.getByRole('button', { name: 'Signed' }));
    expect(
      (
        (await screen.findByRole('button', {
          name: 'Set a time',
        })) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
  });

  it('says it all in the surface language', async () => {
    const user = userEvent.setup();
    const changes: FilterValue[] = [];
    function Host() {
      const [current, setCurrent] = useState<FilterValue>({
        type: 'absolute',
        from: '2026-09-16T09:00',
        to: '2026-09-20',
      } as never);
      return (
        <ViewSurface locale="zh-CN" messages={zhCN}>
          <FilterValueEditor
            kind="datetime"
            editor={{ input: 'dateRange', range: true, withTime: true }}
            value={current}
            label="签收时间"
            onChange={next => {
              changes.push(next);
              setCurrent(next);
            }}
          />
        </ViewSurface>
      );
    }
    render(<Host />);
    await user.click(screen.getByRole('button', { name: '签收时间' }));
    expect(
      await screen.findByRole('textbox', {
        name: `${zhCN['label.date.time-from']} 小时`,
      }),
    ).toBeTruthy();
    expect(
      screen.getByRole('button', { name: zhCN['label.date.time-remove'] }),
    ).toBeTruthy();
    await user.click(
      screen.getByRole('button', {
        name: `清除${zhCN['label.date.time-from']}`,
      }),
    );
    expect(
      document.querySelector('[data-slot="date-time-status"]')?.textContent,
    ).toBe('已清除起始时刻，按整天');
  });
});
