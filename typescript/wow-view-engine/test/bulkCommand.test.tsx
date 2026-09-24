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
  useBulkCommand,
  type BulkCommand,
  type BulkSelection,
} from '../src/react/index.js';
import { BulkStatus } from '../src/ui/index.js';
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

describe('useBulkCommand', () => {
  it('runs a handful of records at a time, and the next as one lands', async () => {
    const { result } = renderHook(() => useBulkCommand({ concurrency: 2 }));
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
    const { result } = renderHook(() => useBulkCommand());
    const run = gated();

    act(() => result.current.run(selection([]), run.command));
    expect(result.current.running).toBeNull();

    act(() => result.current.run(selection(['a']), run.command));
    act(() => result.current.run(selection(['b']), run.command));
    expect(run.started).toEqual(['a']);
  });

  it('lets go of the selection and reads the page again when every record took it', async () => {
    const { result } = renderHook(() => useBulkCommand());
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
      skipped: [],
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
    const { result } = renderHook(() => useBulkCommand());
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

  it('starts nothing more once stopped, and leaves what never ran selected', async () => {
    const { result } = renderHook(() => useBulkCommand({ concurrency: 1 }));
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
    });
    expect(picked.select).toHaveBeenCalledWith(['b', 'c']);
  });

  it('takes the outcome down when dismissed, and when the next run starts', async () => {
    const { result } = renderHook(() => useBulkCommand());
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
    const { result, unmount } = renderHook(() => useBulkCommand());
    const run = gated();
    const picked = selection(['a']);

    act(() => result.current.run(picked, run.command));
    unmount();
    await run.settle('a');

    expect(picked.select).not.toHaveBeenCalled();
    expect(picked.refresh).not.toHaveBeenCalled();
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
function command(state: Partial<BulkCommand>): BulkCommand {
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
            skipped: [],
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
            skipped: ['f'],
          },
        })}
      />,
    );

    const line = screen.getByRole('status');
    expect(line.getAttribute('data-tone')).toBe('warning');
    expect(line.textContent).toContain(
      '1 done, 4 failed · 1 not run · Locked. (2) · Gone. (1) · one more reason · the rest stay selected',
    );
  });

  it('interrupts a reader when nothing took', () => {
    render(
      <BulkStatus
        command={command({
          outcome: {
            title: 'Retry',
            succeeded: [],
            failed: [{ key: 'a', reason: 'Locked.' }],
            skipped: [],
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
            skipped: [],
          },
        })}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Dismiss' }));
    expect(dismiss).toHaveBeenCalledTimes(1);
  });
});
