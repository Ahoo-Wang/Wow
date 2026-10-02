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
 * `/testing`'s `actionHarness` (host-integration.md 6): a host's declared
 * actions read without a screen, by the rules the screen draws by — and
 * those rules themselves (`runtime/actions`).
 */

import { describe, expect, it, vi } from 'vitest';
import { actions, text, type RecordAction } from '../src/index.js';
import { actionHarness, ActionRefusedError } from '../src/testing/index.js';
import {
  actionState,
  asksFirst,
  nextChange,
  offeredAt,
  UNAVAILABLE,
} from '../src/runtime/actions.js';

const T = 1_000_000;

const ROWS = [
  {
    key: 'EF-1',
    data: { status: 'FAILED', timeoutAt: 0, recoverable: 'RECOVERABLE' },
  },
  { key: 'EF-2', data: { status: 'PREPARED', timeoutAt: T + 60_000 } },
  { key: 'EF-3', data: { status: 'SUCCEEDED', timeoutAt: 0 } },
];

type State = { status: string; timeoutAt: number; recoverable?: string };
const state = (data: Record<string, unknown>) => data as State;

/** The console's rules, as a host would write them. */
function refusal(data: State, now: number): string | undefined {
  if (data.status === 'SUCCEEDED') return text('succeeded');
  if (data.status === 'PREPARED' && now <= data.timeoutAt)
    return text('inProgress');
  return undefined;
}

const prepare: RecordAction = {
  id: 'prepare',
  label: text('prepare'),
  primary: true,
  available: (row, { now }) => refusal(state(row.data), now) ?? true,
  changesAt: (row, { now }) => {
    const data = state(row.data);
    return data.status === 'PREPARED' && data.timeoutAt >= now
      ? data.timeoutAt + 1
      : null;
  },
  confirm: { title: text('prepareTitle'), ask: 'bulk' },
  run: vi.fn(() => Promise.resolve()),
};

const forcePrepare: RecordAction = {
  id: 'forcePrepare',
  label: text('forcePrepare'),
  tone: 'danger',
  on: ['row', 'detail'],
  confirm: { title: text('forceTitle') },
  run: () => Promise.resolve(),
};

const markRecoverable: RecordAction = {
  id: 'markRecoverable',
  label: text('mark'),
  form: {
    recoverable: {
      label: text('markAs'),
      options: [
        { value: 'RECOVERABLE', label: text('recoverable') },
        { value: 'UNRECOVERABLE', label: text('unrecoverable') },
      ],
    },
  },
  available: (row, { input }) =>
    input?.recoverable !== undefined &&
    input.recoverable === state(row.data).recoverable
      ? text('already')
      : true,
  confirm: input => ({
    title: text('markTitle'),
    tone: input.recoverable === 'UNRECOVERABLE' ? 'danger' : 'default',
  }),
  run: () => Promise.resolve(),
};

const note: RecordAction = {
  id: 'note',
  label: text('note'),
  hidden: row => state(row.data).status === 'SUCCEEDED',
  form: {
    text: { label: text('text') },
    urgent: { label: text('urgent'), input: 'boolean', required: false },
    minutes: { label: text('minutes'), input: 'number', initial: 5 },
  },
  run: () => Promise.resolve(),
};

const all = actions([prepare, forcePrepare, markRecoverable, note]);

describe('actions()', () => {
  it('refuses two actions with one id, an action without one, and one that runs nothing', () => {
    expect(() => actions([prepare, prepare])).toThrow('share the id "prepare"');
    expect(() => actions([{ ...prepare, id: '' }])).toThrow('needs an id');
    expect(() =>
      actions([{ ...prepare, run: undefined } as unknown as RecordAction]),
    ).toThrow('has no run');
    expect(Object.isFrozen(all)).toBe(true);
  });
});

describe('actionHarness', () => {
  const harness = actionHarness(all, ROWS, { now: T });

  it('lists the actions, and where each is offered', () => {
    expect(harness.ids).toEqual([
      'prepare',
      'forcePrepare',
      'markRecoverable',
      'note',
    ]);
    expect(harness.at('bulk')).toEqual(['prepare', 'markRecoverable', 'note']);
    expect(harness.at('detail')).toEqual(harness.ids);
  });

  it('says whether a record takes an action now, and why not', () => {
    expect(harness.state('prepare', 'EF-1')).toEqual({
      hidden: false,
      available: true,
      reason: null,
    });
    expect(harness.state('prepare', 'EF-2').reason).toBe(text('inProgress'));
    expect(harness.state('note', 'EF-3').hidden).toBe(true);
    // A choice refuses the value the record already holds, and no other.
    expect(
      harness.state('markRecoverable', 'EF-1', { recoverable: 'RECOVERABLE' })
        .available,
    ).toBe(false);
    expect(
      harness.state('markRecoverable', 'EF-1', { recoverable: 'UNRECOVERABLE' })
        .available,
    ).toBe(true);
  });

  it('splits a selection by what each record takes, the commonest reason first', () => {
    const bulk = harness.bulk('prepare', ['EF-1', 'EF-2', 'EF-3', 'EF-9']);
    expect(bulk.able).toEqual(['EF-1']);
    expect(bulk.reasons).toEqual([
      { reason: text('inProgress'), count: 1, keys: ['EF-2'] },
      { reason: text('succeeded'), count: 1, keys: ['EF-3'] },
      // A record the engine does not hold is not sent.
      { reason: text('label.action.unseen'), count: 1, keys: ['EF-9'] },
    ]);
    expect(harness.bulk('note').refused).toEqual([
      { key: 'EF-3', reason: text('label.action.not-offered') },
    ]);
  });

  it('says what an action asks first, where', () => {
    expect(harness.asks('prepare', 'row').asks).toBe(false);
    expect(harness.asks('prepare', 'bulk')).toEqual({
      asks: true,
      confirm: { title: text('prepareTitle'), ask: 'bulk' },
    });
    expect(harness.asks('forcePrepare', 'row').asks).toBe(true);
    // A choice asks with its option in hand, in words that depend on it.
    expect(
      harness.asks('markRecoverable', 'row', { recoverable: 'UNRECOVERABLE' })
        .confirm?.tone,
    ).toBe('danger');
    // A form always asks, for its fields.
    expect(harness.asks('note', 'row').asks).toBe(true);
  });

  it('reads the form and the choice', () => {
    expect(
      harness.choice('markRecoverable')?.map(option => option.value),
    ).toEqual(['RECOVERABLE', 'UNRECOVERABLE']);
    expect(harness.choice('note')).toBeNull();
    expect(harness.form('prepare')).toBeNull();
    expect(harness.form('note')).toEqual([
      {
        name: 'text',
        label: text('text'),
        input: 'text',
        options: null,
        required: true,
      },
      {
        name: 'urgent',
        label: text('urgent'),
        input: 'boolean',
        options: null,
        required: false,
      },
      {
        name: 'minutes',
        label: text('minutes'),
        input: 'number',
        options: null,
        required: true,
      },
    ]);
    expect(harness.form('markRecoverable')?.[0].input).toBe('select');
    expect(harness.missing('note', { minutes: 5 })).toEqual(['text']);
    expect(harness.missing('note', { text: ' ', minutes: Number.NaN })).toEqual(
      ['text', 'minutes'],
    );
    expect(
      harness.missing('note', { text: 'x', minutes: 0, urgent: [] }),
    ).toEqual([]);
  });

  it('says when a record’s availability flips on its own', () => {
    expect(harness.changesAt()).toBe(T + 60_001);
    expect(harness.changesAt('EF-1')).toBeNull();
    expect(
      actionHarness(all, ROWS, { now: T + 60_001 }).state('prepare', 'EF-2')
        .available,
    ).toBe(true);
  });

  it('sends as the engine does: refused with the reason where a record does not take it', async () => {
    await expect(harness.run('prepare', 'EF-3')).rejects.toBeInstanceOf(
      ActionRefusedError,
    );
    await expect(harness.run('prepare', 'EF-3')).rejects.toMatchObject({
      reason: text('succeeded'),
    });
    await harness.run('prepare', 'EF-1');
    expect(prepare.run).toHaveBeenCalledWith(ROWS[0], {});
  });

  it('names what it cannot find', () => {
    expect(() => harness.state('nope', 'EF-1')).toThrow('No action "nope"');
    expect(() => harness.state('prepare', 'EF-0')).toThrow('No record "EF-0"');
  });
});

describe('the rules the harness and the screen share', () => {
  it('refuses where a rule throws or says nothing useful, rather than offer an unchecked command', () => {
    const row = ROWS[0];
    const broken: RecordAction = {
      ...prepare,
      available: () => {
        throw new Error('bug');
      },
    };
    expect(actionState(broken, row, { now: T }).reason).toBe(UNAVAILABLE);
    expect(
      actionState({ ...prepare, available: () => '' as unknown as true }, row, {
        now: T,
      }).reason,
    ).toBe(UNAVAILABLE);
  });

  it('offers every place when none is named, and ignores a rule that throws for the clock', () => {
    expect(offeredAt(undefined, 'row')).toEqual([]);
    expect(
      nextChange(
        [
          {
            ...prepare,
            changesAt: () => {
              throw new Error('bug');
            },
          },
        ],
        ROWS,
        T,
      ),
    ).toBeNull();
    expect(nextChange([prepare], ROWS, T + 100_000)).toBeNull();
    expect(asksFirst(markRecoverable, 'row', {})).toBe(true);
  });
});

describe('a press, as the harness and the screen both start it', () => {
  const remind: RecordAction = {
    id: 'remind',
    label: text('remind'),
    form: {
      note: { label: text('note') },
      minutes: { label: text('minutes'), input: 'number', initial: 30 },
    },
    // A question that depends on the form: only a long wait asks.
    confirm: input =>
      Number(input.minutes) > 60
        ? { title: text('remindLate') }
        : { title: text('remindSoon'), ask: 'bulk' },
    run: vi.fn(() => Promise.resolve()),
  };

  it('starts from the form’s initial values, the given input over them', async () => {
    const harness = actionHarness(actions([remind]), ROWS, { now: T });
    // The screen opens the form with 30 in it: only the note is missing.
    expect(harness.missing('remind', {})).toEqual(['note']);
    expect(harness.asks('remind', 'row').confirm).toEqual({
      title: text('remindSoon'),
      ask: 'bulk',
    });
    expect(harness.asks('remind', 'row', { minutes: 90 }).confirm).toEqual({
      title: text('remindLate'),
    });
    await harness.run('remind', 'EF-1', { note: 'call' });
    expect(remind.run).toHaveBeenCalledWith(ROWS[0], {
      minutes: 30,
      note: 'call',
    });
  });

  it('asks by the action’s name where its question throws', () => {
    const broken: RecordAction = {
      ...remind,
      id: 'broken',
      confirm: () => {
        throw new Error('host bug');
      },
    };
    const harness = actionHarness(actions([broken]), ROWS, { now: T });
    expect(harness.asks('broken', 'row')).toEqual({
      asks: true,
      confirm: { title: text('remind') },
    });
  });
});
