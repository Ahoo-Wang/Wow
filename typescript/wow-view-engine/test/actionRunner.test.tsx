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
  act,
  cleanup,
  render,
  renderHook,
  screen,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RecordKey } from '../src/index.js';
import {
  failureReasons,
  type ActionRunner,
  type BulkOutcome,
  type BulkSelection,
} from '../src/react/index.js';
import { useActionRunner } from '../src/react/actionRunner.js';
import { ActionRefusedError } from '../src/runtime/actions.js';
import { text } from '../src/index.js';
import { BulkStatus } from '../src/ui/actions/BulkStatus.js';
import { MessagesProvider } from '../src/ui/index.js';
import { nextTask } from './fixtures.js';

afterEach(cleanup);

/** The slot's context, narrowed to what a command is given. */
function selection(keys: RecordKey[] = ['a', 'b']) {
  const select = vi.fn<(keys: readonly RecordKey[]) => void>();
  const refresh = vi.fn<() => void>();
  return { keys, select, refresh } satisfies BulkSelection;
}

/** A command over records whose every settling this test decides. */
function gated() {
  const started: RecordKey[] = [];
  const gates = new Map<
    RecordKey,
    { resolve(): void; reject(error: unknown): void }
  >();
  const each = (key: RecordKey) => {
    started.push(key);
    return new Promise<void>((resolve, reject) => {
      gates.set(key, { resolve, reject });
    });
  };
  const settle = (key: RecordKey, error?: unknown) =>
    act(async () => {
      const gate = gates.get(key)!;
      if (error === undefined) gate.resolve();
      else gate.reject(error);
      // The worker reads the reason, reports, and takes the next record.
      await nextTask();
    });
  return { started, each, settle, command: { title: 'Retry', each } };
}

function refusal(errorMsg: string) {
  return Object.assign(new Error('Request failed with status code 400'), {
    exchange: {
      response: { status: 400 },
      extractResult: () => Promise.resolve({ errorCode: 'Refused', errorMsg }),
    },
  });
}

describe('useActionRunner', () => {
  it('runs a handful of records at a time, and the next as one lands', async () => {
    const { result } = renderHook(() => useActionRunner({ concurrency: 2 }));
    const run = gated();

    act(() => result.current.run(selection(['a', 'b', 'c']), run.command));

    // Two in flight, not three: a selection is not sent in one burst.
    expect(run.started).toEqual(['a', 'b']);
    expect(result.current.running?.progress).toEqual({
      total: 3,
      done: 0,
      failed: 0,
    });
    await run.settle('a');
    expect(run.started).toEqual(['a', 'b', 'c']);
    expect(result.current.running?.progress.done).toBe(1);
  });

  it('refuses a second run while one is in flight, and runs nothing over no rows', () => {
    const { result } = renderHook(() => useActionRunner());
    const run = gated();

    act(() => result.current.run(selection([]), run.command));
    expect(result.current.running).toBeNull();

    act(() => result.current.run(selection(['a']), run.command));
    act(() => result.current.run(selection(['b']), run.command));
    expect(run.started).toEqual(['a']);
  });

  it('lets go of the selection and reads the page again when every record took it', async () => {
    const { result } = renderHook(() => useActionRunner());
    const run = gated();
    const picked = selection(['a', 'b']);

    act(() => result.current.run(picked, run.command));
    await run.settle('a');
    await run.settle('b');

    expect(result.current.running).toBeNull();
    expect(result.current.outcome).toEqual({
      title: 'Retry',
      succeeded: ['a', 'b'],
      failed: [],
      refused: [],
      unknown: [],
      skipped: [],
      kept: [],
    });
    expect(picked.select).toHaveBeenCalledWith([]);
    expect(picked.refresh).toHaveBeenCalledTimes(1);
  });

  /**
   * The records that refused are the ones still to be dealt with, so they
   * stay picked — each with the source's own reason, read off what the
   * command threw, rather than one reason said for all of them.
   */
  it('keeps the refused records selected, each with the source’s reason', async () => {
    const { result } = renderHook(() => useActionRunner());
    const run = gated();
    const picked = selection(['a', 'b', 'c']);

    act(() => result.current.run(picked, run.command));
    await run.settle('a', refusal('Retry limit reached.'));
    await run.settle('b');
    await run.settle('c', 'Not a thing an Error is');

    expect(result.current.outcome?.failed).toEqual([
      { key: 'a', reason: 'Retry limit reached.' },
      { key: 'c', reason: 'Not a thing an Error is' },
    ]);
    expect(picked.select).toHaveBeenCalledWith(['a', 'c']);
    expect(picked.refresh).toHaveBeenCalledTimes(1);
  });

  it('keeps an action’s own refusal as written — a key stays a key', async () => {
    const { result } = renderHook(() => useActionRunner());
    const run = gated();
    act(() => result.current.run(selection(['a']), run.command));
    await run.settle('a', new ActionRefusedError(text('orders.shipped')));
    // Not sent, so not a failure: it is counted apart, and stays picked.
    expect(result.current.outcome).toMatchObject({
      failed: [],
      refused: [{ key: 'a', reason: text('orders.shipped') }],
      kept: ['a'],
    });
  });

  it('starts nothing more once stopped, and leaves what never ran selected', async () => {
    const { result } = renderHook(() => useActionRunner({ concurrency: 1 }));
    const run = gated();
    const picked = selection(['a', 'b', 'c']);

    act(() => result.current.run(picked, run.command));
    act(() => result.current.stop());
    expect(result.current.running?.stopping).toBe(true);
    // What is in flight still lands: a write already sent is not taken back.
    await run.settle('a');

    expect(run.started).toEqual(['a']);
    expect(result.current.outcome).toMatchObject({
      succeeded: ['a'],
      skipped: ['b', 'c'],
      kept: ['b', 'c'],
    });
    expect(picked.select).toHaveBeenCalledWith(['b', 'c']);
  });

  it('takes the outcome down when dismissed, and when the next run starts', async () => {
    const { result } = renderHook(() => useActionRunner());
    const run = gated();

    act(() => result.current.run(selection(['a']), run.command));
    await run.settle('a');
    act(() => result.current.dismiss());
    expect(result.current.outcome).toBeNull();

    act(() => result.current.run(selection(['b']), run.command));
    await run.settle('b');
    act(() => result.current.run(selection(['c']), run.command));
    expect(result.current.outcome).toBeNull();
  });

  it('reports nothing once the host that asked has gone', async () => {
    const { result, unmount } = renderHook(() => useActionRunner());
    const run = gated();
    const picked = selection(['a']);

    act(() => result.current.run(picked, run.command));
    unmount();
    await run.settle('a');

    expect(picked.select).not.toHaveBeenCalled();
    expect(picked.refresh).not.toHaveBeenCalled();
  });

  it('starts nothing from a run asked for after its host has gone', () => {
    const { result, unmount } = renderHook(() => useActionRunner());
    // Held by a handler that outlives the page: a late click, a timer.
    const run = result.current.run;
    unmount();
    const each = vi.fn(() => Promise.resolve());
    run(selection(['EF-0', 'EF-1']), { title: 'Retry', each });
    expect(each).not.toHaveBeenCalled();
  });

  it('starts no record once the host that asked has gone, and lets those under way land', async () => {
    const { result, unmount } = renderHook(() =>
      useActionRunner({ concurrency: 2 }),
    );
    const sent: RecordKey[] = [];
    const landed: RecordKey[] = [];
    // Each record waits for the test to let it land.
    const releases: (() => void)[] = [];
    const keys = Array.from({ length: 12 }, (_, at) => `EF-${at}`);
    act(() =>
      result.current.run(selection(keys), {
        title: 'Retry',
        each: (key: RecordKey) => {
          sent.push(key);
          return new Promise<void>(resolve =>
            releases.push(() => {
              landed.push(key);
              resolve();
            }),
          );
        },
      }),
    );
    expect(sent).toEqual(['EF-0', 'EF-1']);
    unmount();
    for (const release of releases.splice(0)) release();
    for (let turn = 0; turn < 5; turn += 1) await nextTask();
    // Nobody can see the run or stop it now: what was under way lands, and
    // nothing more is sent (review of #3761).
    expect(landed).toEqual(['EF-0', 'EF-1']);
    expect(sent).toEqual(['EF-0', 'EF-1']);
    expect(releases).toEqual([]);
  });
});

/**
 * A command whose outcome nobody knows — sent, and no answer came back —
 * is not a failure to run again: it is let go of and checked (R2-05).
 */
describe('an outcome nobody knows', () => {
  afterEach(() => vi.useRealTimers());

  it('lets go of a record the network dropped or a timeout cut off, and tells the host what was thrown', async () => {
    const onError = vi.fn();
    const { result } = renderHook(() => useActionRunner({ onError }));
    const run = gated();
    const picked = selection(['a', 'b', 'c', 'd']);
    const dropped = new TypeError('Failed to fetch');
    const timedOut = Object.assign(new Error('Request timed out'), {
      name: 'FetchTimeoutError',
    });

    act(() =>
      result.current.run(picked, { ...run.command, operation: 'refund' }),
    );
    await run.settle('a', dropped);
    await run.settle('b', timedOut);
    await run.settle('c', refusal('Already refunded.'));
    await run.settle('d');

    expect(result.current.outcome).toMatchObject({
      succeeded: ['d'],
      failed: [{ key: 'c', reason: 'Already refunded.' }],
      unknown: [
        { key: 'a', reason: 'Failed to fetch' },
        { key: 'b', reason: 'Request timed out' },
      ],
      kept: ['c'],
    });
    // The refused one stays picked; the two that may have taken do not.
    expect(picked.select).toHaveBeenCalledWith(['c']);
    expect(onError.mock.calls).toEqual([
      [dropped, { key: 'a', operation: 'refund' }],
      [timedOut, { key: 'b', operation: 'refund' }],
      [
        expect.objectContaining({ exchange: expect.anything() }),
        { key: 'c', operation: 'refund' },
      ],
    ]);
  });

  it('reads an abort and a gateway timeout as unknown, and tells nobody of the abort', async () => {
    const onError = vi.fn();
    const { result } = renderHook(() => useActionRunner({ onError }));
    const run = gated();
    const gateway = Object.assign(new Error('Request failed'), {
      exchange: { response: { status: 504 } },
    });
    act(() => result.current.run(selection(['a', 'b']), run.command));
    await run.settle('a', new DOMException('Aborted', 'AbortError'));
    await run.settle('b', gateway);
    expect(result.current.outcome?.unknown.map(each => each.key)).toEqual([
      'a',
      'b',
    ]);
    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError).toHaveBeenCalledWith(gateway, {
      key: 'b',
      operation: 'Retry',
    });
  });

  it('tells the host nothing of an action’s own refusal', async () => {
    const onError = vi.fn();
    const { result } = renderHook(() => useActionRunner({ onError }));
    const run = gated();
    act(() => result.current.run(selection(['a']), run.command));
    await run.settle('a', new ActionRefusedError(text('orders.shipped')));
    expect(onError).not.toHaveBeenCalled();
  });

  it('stops waiting for a record past the command’s deadline, and frees the surface', async () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useActionRunner({ concurrency: 1 }));
    const picked = selection(['a', 'b']);
    const each = vi.fn((key: RecordKey) =>
      key === 'a' ? new Promise<void>(() => {}) : Promise.resolve(),
    );
    act(() =>
      result.current.run(picked, { title: 'Refund', each, timeout: 5_000 }),
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
    });
    expect(result.current.running).toBeNull();
    expect(result.current.outcome).toMatchObject({
      succeeded: ['b'],
      unknown: [{ key: 'a', reason: text('label.action.timed-out') }],
      kept: [],
    });
    expect(picked.select).toHaveBeenCalledWith([]);
  });

  it('settles at a second Stop though a record never answers, and runs again after', async () => {
    const { result } = renderHook(() => useActionRunner({ concurrency: 2 }));
    const picked = selection(['a', 'b', 'c']);
    const each = vi.fn(() => new Promise<void>(() => {}));
    act(() => result.current.run(picked, { title: 'Cancel', each }));
    act(() => result.current.stop());
    expect(result.current.running?.stopping).toBe(true);
    await act(async () => {
      result.current.stop();
      await nextTask();
    });
    expect(result.current.running).toBeNull();
    expect(result.current.outcome).toMatchObject({
      unknown: [
        { key: 'a', reason: text('label.action.abandoned') },
        { key: 'b', reason: text('label.action.abandoned') },
      ],
      skipped: ['c'],
      kept: ['c'],
    });
    const again = gated();
    act(() => result.current.run(selection(['d']), again.command));
    expect(again.started).toEqual(['d']);
  });
});

describe('a failure read badly', () => {
  it('gives a generic reason for an error with no words, and one whose reading throws', async () => {
    const { result } = renderHook(() => useActionRunner());
    const run = gated();
    const unreadable = {
      get exchange(): never {
        throw new Error('no exchange here');
      },
    };
    act(() => result.current.run(selection(['a', 'b']), run.command));
    await run.settle('a', new Error('   '));
    await run.settle('b', unreadable);
    expect(result.current.running).toBeNull();
    expect(result.current.outcome?.failed).toEqual([
      { key: 'a', reason: text('label.action.failed') },
      { key: 'b', reason: text('label.action.failed') },
    ]);
  });
});

describe('failureReasons', () => {
  it('counts each reason, the commonest first', () => {
    expect(
      failureReasons([
        { key: 'a', reason: 'Locked.' },
        { key: 'b', reason: 'Gone.' },
        { key: 'c', reason: 'Locked.' },
      ]),
    ).toEqual([
      { reason: 'Locked.', count: 2 },
      { reason: 'Gone.', count: 1 },
    ]);
  });
});

/** A command as the line reads it, in the state a test puts it in. */
function command(state: Partial<ActionRunner>): ActionRunner {
  return {
    run: vi.fn(),
    stop: vi.fn(),
    dismiss: vi.fn(),
    running: null,
    outcome: null,
    ...state,
  };
}

describe('BulkStatus', () => {
  it('draws nothing while no command has run', () => {
    const { container } = render(<BulkStatus command={command({})} />);
    expect(container.innerHTML).toBe('');
  });

  it('says how far a command has come, with the way to stop it', async () => {
    const stop = vi.fn();
    render(
      <BulkStatus
        command={command({
          stop,
          running: {
            title: 'Retry',
            progress: { total: 40, done: 12, failed: 2 },
            stopping: false,
          },
        })}
      />,
    );

    const line = screen.getByRole('status');
    expect(line.textContent).toContain('Retry · Running 12 of 40, 2 failed');
    await userEvent.click(screen.getByRole('button', { name: 'Stop' }));
    expect(stop).toHaveBeenCalledTimes(1);
  });

  it('counts a run that everything took, as a note', () => {
    render(
      <BulkStatus
        command={command({
          outcome: {
            title: 'Retry',
            succeeded: ['a', 'b', 'c'],
            failed: [],
            refused: [],
            unknown: [],
            skipped: [],
            kept: [],
          },
        })}
      />,
    );

    const line = screen.getByRole('status');
    expect(line.textContent).toContain('Retry · 3 done');
    expect(line.textContent).not.toContain('selected');
    expect(line.getAttribute('data-tone')).toBe('info');
  });

  it('gives the commonest reasons with their counts, and says the rest stay selected', () => {
    render(
      <BulkStatus
        command={command({
          outcome: {
            title: 'Retry',
            succeeded: ['a'],
            failed: [
              { key: 'b', reason: 'Locked.' },
              { key: 'c', reason: 'Locked.' },
              { key: 'd', reason: 'Gone.' },
              { key: 'e', reason: 'Late.' },
            ],
            refused: [],
            unknown: [],
            skipped: ['f'],
            kept: ['b', 'c', 'd', 'e', 'f'],
          },
        })}
      />,
    );

    const line = screen.getByRole('status');
    expect(line.getAttribute('data-tone')).toBe('warning');
    expect(line.textContent).toContain(
      'Retry · 1 done · 4 failed · 1 not run · Locked. (2) · Gone. (1) · one more reason · the failed and the not run stay selected',
    );
  });

  it('interrupts a reader when nothing took', () => {
    render(
      <BulkStatus
        command={command({
          outcome: {
            title: 'Retry',
            succeeded: [],
            failed: [
              { key: 'a', reason: 'Locked.' },
              { key: 'b', reason: 'Locked.' },
            ],
            refused: [],
            unknown: [],
            skipped: [],
            kept: ['a', 'b'],
          },
        })}
      />,
    );

    expect(screen.getByRole('alert').getAttribute('data-tone')).toBe('error');
  });

  it('offers the one way out, and nothing expires on its own', async () => {
    const dismiss = vi.fn();
    render(
      <BulkStatus
        command={command({
          dismiss,
          outcome: {
            title: 'Retry',
            succeeded: ['a'],
            failed: [],
            refused: [],
            unknown: [],
            skipped: [],
            kept: [],
          },
        })}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Dismiss' }));
    expect(dismiss).toHaveBeenCalledTimes(1);
  });

  it('says a run’s name and an action’s refusal in the words in force', () => {
    render(
      <MessagesProvider
        messages={{
          'orders.remind': 'Remind as {value}',
          'orders.urgent': 'urgent',
          'orders.shipped': 'Already shipped.',
        }}
      >
        <BulkStatus
          command={command({
            outcome: {
              title: text('orders.remind'),
              values: { value: text('orders.urgent') },
              succeeded: ['a'],
              failed: [],
              refused: [{ key: 'b', reason: text('orders.shipped') }],
              unknown: [],
              skipped: [],
              kept: ['b'],
            },
          })}
        />
      </MessagesProvider>,
    );
    expect(screen.getByRole('status').textContent).toContain(
      'Remind as urgent · 1 done · 1 not run · Already shipped. (1)',
    );
  });
});

describe('BulkStatus, one record and a stop', () => {
  const settled = (outcome: Partial<BulkOutcome>): BulkOutcome => ({
    title: 'Ship',
    succeeded: [],
    failed: [],
    refused: [],
    unknown: [],
    skipped: [],
    kept: [],
    ...outcome,
  });

  it.each([
    [{ succeeded: ['SO-1002'] }, 'Ship · SO-1002 done', 'info'],
    [
      { failed: [{ key: 'SO-1002', reason: 'Locked.' }] },
      'Ship · SO-1002 failed: Locked.',
      'error',
    ],
    [
      { refused: [{ key: 'SO-1002', reason: 'Already shipped.' }] },
      'Ship · SO-1002 not run: Already shipped.',
      'warning',
    ],
    [
      { unknown: [{ key: 'SO-1002', reason: 'Failed to fetch' }] },
      'Ship · SO-1002: outcome unknown, refresh to check first',
      'warning',
    ],
  ] as const)(
    'names the record a command ran on: %j',
    (outcome, said, tone) => {
      const { container } = render(
        <BulkStatus command={command({ outcome: settled(outcome) })} />,
      );
      const line = container.querySelector('[data-slot="bulk-status"]')!;
      expect(line.textContent).toContain(said);
      expect(line.textContent).not.toContain('selected');
      expect(line.getAttribute('data-tone')).toBe(tone);
    },
  );

  it('says nothing of failures a stopped run did not have', () => {
    render(
      <BulkStatus
        command={command({
          outcome: settled({
            succeeded: ['a', 'b'],
            skipped: ['c', 'd'],
            kept: ['c', 'd'],
          }),
        })}
      />,
    );
    const line = screen.getByRole('status');
    expect(line.textContent).toContain(
      'Ship · 2 done · 2 not run · the failed and the not run stay selected',
    );
    expect(line.textContent).not.toContain('failed,');
    expect(line.textContent).not.toMatch(/\b0 failed/);
  });

  it('counts the unknown apart, and says to check before anything is sent again', () => {
    render(
      <BulkStatus
        command={command({
          outcome: settled({
            succeeded: ['a'],
            unknown: [
              { key: 'b', reason: 'Failed to fetch' },
              { key: 'c', reason: 'Failed to fetch' },
            ],
          }),
        })}
      />,
    );
    expect(screen.getByRole('status').textContent).toContain(
      'Ship · 1 done · 2 with outcome unknown, refresh to check first',
    );
  });

  it('keeps Stop pressable while stopping, to stop waiting', async () => {
    const stop = vi.fn();
    render(
      <BulkStatus
        command={command({
          stop,
          running: {
            title: 'Retry',
            progress: { total: 4, done: 1, failed: 0 },
            stopping: true,
          },
        })}
      />,
    );
    expect(screen.getByRole('status').textContent).toContain(
      'Retry · Running 1 of 4 · Stopping…',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Stop waiting' }));
    expect(stop).toHaveBeenCalledTimes(1);
  });
});
