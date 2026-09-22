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
  useBulkCommand,
  type BulkOutcome,
  type BulkSelection,
} from '../src/react/index.js';
import { BulkOutcomeStrip } from '../src/ui/index.js';

afterEach(cleanup);

/** The slot's context, narrowed to what a command is given. */
function selection(keys: RecordKey[] = ['a', 'b']) {
  const clearSelection = vi.fn<() => void>();
  const refresh = vi.fn<() => void>();
  return { keys, clearSelection, refresh } satisfies BulkSelection;
}

/** A command whose settling this test decides. */
function deferred(): {
  command: (keys: readonly RecordKey[]) => Promise<BulkOutcome>;
  seen: RecordKey[][];
  resolve(outcome: BulkOutcome): Promise<void>;
  reject(error: unknown): Promise<void>;
} {
  const seen: RecordKey[][] = [];
  let settle: (outcome: BulkOutcome) => void = () => undefined;
  let fail: (error: unknown) => void = () => undefined;
  return {
    seen,
    command: keys => {
      seen.push([...keys]);
      return new Promise<BulkOutcome>((resolveWith, rejectWith) => {
        settle = resolveWith;
        fail = rejectWith;
      });
    },
    resolve: outcome =>
      act(async () => {
        settle(outcome);
        await Promise.resolve();
      }),
    reject: error =>
      act(async () => {
        fail(error);
        await Promise.resolve();
      }),
  };
}

describe('useBulkCommand', () => {
  it('is pending from the press until the command settles', async () => {
    const held = deferred();
    const { result } = renderHook(() => useBulkCommand(held.command));

    expect(result.current.pending).toBe(false);
    act(() => result.current.run(selection()));
    expect(result.current.pending).toBe(true);
    expect(held.seen).toEqual([['a', 'b']]);

    await held.resolve({ succeeded: ['a', 'b'], failed: [] });

    expect(result.current.pending).toBe(false);
  });

  it('hands the command the keys the selection was on', async () => {
    const held = deferred();
    const { result } = renderHook(() => useBulkCommand(held.command));

    act(() => result.current.run(selection(['x', 7])));
    await held.resolve({ succeeded: ['x', 7], failed: [] });

    expect(held.seen).toEqual([['x', 7]]);
  });

  it('refuses a second run while one is in flight', async () => {
    const held = deferred();
    const { result } = renderHook(() => useBulkCommand(held.command));

    act(() => result.current.run(selection()));
    act(() => result.current.run(selection(['c'])));

    expect(held.seen).toEqual([['a', 'b']]);
    await held.resolve({ succeeded: ['a', 'b'], failed: [] });
  });

  it('runs nothing at all over an empty selection', () => {
    const held = deferred();
    const { result } = renderHook(() => useBulkCommand(held.command));

    act(() => result.current.run(selection([])));

    expect(held.seen).toEqual([]);
    expect(result.current.pending).toBe(false);
  });

  it('refreshes and clears the selection when nothing failed', async () => {
    const held = deferred();
    const picked = selection();
    const { result } = renderHook(() => useBulkCommand(held.command));

    act(() => result.current.run(picked));
    await held.resolve({ succeeded: ['a', 'b'], failed: [] });

    expect(result.current.outcome).toEqual({
      succeeded: ['a', 'b'],
      failed: [],
    });
    expect(picked.refresh).toHaveBeenCalledTimes(1);
    expect(picked.clearSelection).toHaveBeenCalledTimes(1);
  });

  it('keeps the selection when anything failed, and still refreshes', async () => {
    const held = deferred();
    const picked = selection();
    const { result } = renderHook(() => useBulkCommand(held.command));

    act(() => result.current.run(picked));
    await held.resolve({ succeeded: ['a'], failed: ['b'], reason: 'locked' });

    expect(result.current.outcome).toEqual({
      succeeded: ['a'],
      failed: ['b'],
      reason: 'locked',
    });
    expect(picked.refresh).toHaveBeenCalledTimes(1);
    expect(picked.clearSelection).not.toHaveBeenCalled();
  });

  it('reads a thrown command as every record failing, in its own words', async () => {
    const held = deferred();
    const picked = selection();
    const { result } = renderHook(() => useBulkCommand(held.command));

    act(() => result.current.run(picked));
    await held.reject(new Error('the service is down'));

    expect(result.current.outcome).toEqual({
      succeeded: [],
      failed: ['a', 'b'],
      reason: 'the service is down',
    });
    expect(picked.clearSelection).not.toHaveBeenCalled();
  });

  it('reads a thrown non-error as its own text', async () => {
    const held = deferred();
    const { result } = renderHook(() => useBulkCommand(held.command));

    act(() => result.current.run(selection(['a'])));
    await held.reject('refused');

    expect(result.current.outcome?.reason).toBe('refused');
  });

  it('takes the outcome down when it is dismissed', async () => {
    const held = deferred();
    const { result } = renderHook(() => useBulkCommand(held.command));

    act(() => result.current.run(selection()));
    await held.resolve({ succeeded: ['a', 'b'], failed: [] });
    act(() => result.current.dismiss());

    expect(result.current.outcome).toBeNull();
  });

  it('drops the outcome the moment the next run starts', async () => {
    const held = deferred();
    const { result } = renderHook(() => useBulkCommand(held.command));

    act(() => result.current.run(selection()));
    await held.resolve({ succeeded: ['a'], failed: ['b'] });
    act(() => result.current.run(selection(['c'])));

    expect(result.current.outcome).toBeNull();
  });

  it('reports nothing once the host that asked has gone', async () => {
    const held = deferred();
    const picked = selection();
    const { result, unmount } = renderHook(() => useBulkCommand(held.command));

    act(() => result.current.run(picked));
    unmount();
    await held.resolve({ succeeded: ['a', 'b'], failed: [] });

    expect(picked.refresh).not.toHaveBeenCalled();
    expect(picked.clearSelection).not.toHaveBeenCalled();
  });
});

describe('BulkOutcomeStrip', () => {
  it('draws nothing before a command has settled', () => {
    const { container } = render(
      <BulkOutcomeStrip outcome={null} onDismiss={vi.fn()} />,
    );

    expect(container.innerHTML).toBe('');
  });

  it('counts a run that everything took, as a note', () => {
    render(
      <BulkOutcomeStrip
        outcome={{ succeeded: ['a', 'b', 'c'], failed: [] }}
        onDismiss={vi.fn()}
      />,
    );

    const line = screen.getByRole('status');
    expect(line.textContent).toContain('3 done');
    expect(line.getAttribute('data-tone')).toBe('info');
  });

  it('counts both sides of a partly refused run, as a warning', () => {
    render(
      <BulkOutcomeStrip
        outcome={{ succeeded: ['a'], failed: ['b', 'c'], reason: 'Locked.' }}
        onDismiss={vi.fn()}
      />,
    );

    const line = screen.getByRole('status');
    expect(line.textContent).toContain('1 done, 2 failed');
    // The service's own words, after the counts and on the same line.
    expect(line.textContent).toContain('Locked.');
    expect(line.getAttribute('data-tone')).toBe('warning');
  });

  it('interrupts a reader when nothing took', () => {
    render(
      <BulkOutcomeStrip
        outcome={{ succeeded: [], failed: ['a', 'b'] }}
        onDismiss={vi.fn()}
      />,
    );

    const line = screen.getByRole('alert');
    expect(line.textContent).toContain('2 failed');
    expect(line.getAttribute('data-tone')).toBe('error');
  });

  it('says which command it is about when the host names one', () => {
    render(
      <BulkOutcomeStrip
        outcome={{ succeeded: ['a'], failed: [] }}
        onDismiss={vi.fn()}
        title="Export"
      />,
    );

    expect(screen.getByRole('status').textContent).toContain('Export · 1 done');
  });

  it('offers the one way out, and nothing expires on its own', async () => {
    const onDismiss = vi.fn();
    render(
      <BulkOutcomeStrip
        outcome={{ succeeded: ['a'], failed: [] }}
        onDismiss={onDismiss}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Dismiss' }));

    expect(onDismiss).toHaveBeenCalledTimes(1);
  });
});
