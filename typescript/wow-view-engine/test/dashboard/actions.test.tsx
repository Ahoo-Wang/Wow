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

import { afterEach, expect, it, vi } from 'vitest';
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { useSyncExternalStore } from 'react';
import { createPortal } from 'react-dom';
import { RecordActionGuard } from '../../src/record/RecordActionGuard.js';
import { filter } from '@ahoo-wang/fetcher-wow';
import { RecordContent } from '../../src/record/RecordContent.js';
import type {
  RowActionsRendererProps,
  ToolbarActionsRendererProps,
} from '../../src/record/recordReactTypes.js';
import { setup, definition, instance, deferred } from '../engine/fixtures.js';
afterEach(cleanup);
it.each([true, false])(
  'keeps row and toolbar guards current with live snapshot=%s',
  async live => {
    const { engine, paged } = setup({ limits: { maxConcurrentQueries: 1 } });
    await engine.load();
    const saved = instance();
    saved.config.presentation.table!.columns.push({
      id: 'actions',
      kind: 'actions',
    });
    const scopedDefinition = {
      ...definition,
      record: {
        ...definition.record!,
        recordActions: {
          row: { name: 'guarded' },
          toolbar: { name: 'guarded' },
        },
      },
    };
    const position = engine.openPosition(saved, scopedDefinition, {
      queryPolicy: 'queue',
      source: { paged },
    });
    if (position.kind !== 'record') throw new Error('record');
    engine.setPositionScope(
      position.identity.id,
      filter.eq('state.amount', 10),
    );
    await position.commands.refresh();
    let row!: RowActionsRendererProps;
    let bulk!: ToolbarActionsRendererProps;
    const write = vi.fn();
    const extensions = {
      rowActions: {
        guarded: (props: RowActionsRendererProps) => {
          row = props;
          return <button onClick={write}>Write row</button>;
        },
      },
      toolbarActions: {
        guarded: (props: ToolbarActionsRendererProps) => {
          bulk = props;
          return <button onClick={write}>Write scope</button>;
        },
      },
    };
    function View() {
      const state = useSyncExternalStore(
        engine.subscribe,
        engine.getSnapshot,
        engine.getSnapshot,
      );
      const session = state.sessions[position.identity.id];
      return session?.kind === 'record' ? (
        <RecordContent
          selectable
          session={session}
          definition={scopedDefinition}
          commands={position.commands}
          getSnapshot={
            live
              ? () => {
                  const current =
                    engine.getSnapshot().sessions[position.identity.id];
                  return current?.kind === 'record' ? current : undefined;
                }
              : undefined
          }
          extensions={extensions}
        />
      ) : null;
    }
    render(<View />);
    const compact = screen.queryByRole('button', { name: /记录 .* 操作/ });
    if (compact) fireEvent.click(compact);
    await screen.findByRole('button', { name: 'Write row' });
    fireEvent.click(screen.getByRole('button', { name: 'Write row' }));
    expect(write).toHaveBeenCalledOnce();
    const old = row;
    const held = deferred<{ total: number; list: never[] }>();
    const blocker = engine.openPosition(instance(), definition, {
      queryPolicy: 'queue',
      source: { paged },
    });
    paged.mockImplementationOnce(() => held.promise);
    let waiting!: Promise<void>;
    let blocking!: Promise<void>;
    act(() => {
      blocking = blocker.commands.refresh();
      engine.setPositionScope(
        position.identity.id,
        filter.eq('state.amount', 20),
      );
      waiting = position.commands.refresh();
    });
    expect(position.getSnapshot().queryStatus).toBe('waiting');
    expect(bulk.isCurrent?.()).toBe(false);
    expect(JSON.stringify(row.filter)).toContain('10');
    expect(old.isCurrent?.()).toBe(false);
    expect(screen.queryByRole('button', { name: 'Write row' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Write scope' })).toBeNull();
    expect(write).toHaveBeenCalledOnce();
    await act(async () => {
      held.resolve({ total: 0, list: [] });
      await Promise.all([blocking, waiting]);
    });
    await waitFor(() =>
      expect(position.getSnapshot().queryStatus).toBe('success'),
    );
    expect(bulk.querying).toBe(false);
    expect(JSON.stringify(row.filter)).toContain('20');
    fireEvent.click(screen.getByRole('button', { name: 'Write row' }));
    expect(write).toHaveBeenCalledTimes(2);
    expect(old.isCurrent?.()).toBe(false);
    await act(async () => {
      engine.setPositionScope(
        position.identity.id,
        filter.eq('state.amount', 10),
      );
      await position.commands.refresh();
    });
    expect(old.isCurrent?.()).toBe(false);
    const previousResult = row;
    await act(() => position.commands.refresh());
    expect(previousResult.isCurrent?.()).toBe(false);
    expect(row.isCurrent?.()).toBe(true);
    const oldSelection = bulk;
    act(() => position.commands.setSelection(['a']));
    expect(oldSelection.isCurrent?.()).toBe(false);
    const finalRow = row;
    cleanup();
    expect(finalRow.isCurrent?.()).toBe(false);
    engine.dispose();
  },
);

it('unmounts an expired extension portal instead of trapping its cancel controls', () => {
  const view = render(
    <RecordActionGuard disabled={false}>
      {createPortal(
        <div role="dialog">
          <button>Cancel</button>
        </div>,
        document.body,
      )}
    </RecordActionGuard>,
  );
  expect(screen.getByRole('dialog')).toBeTruthy();
  view.rerender(
    <RecordActionGuard disabled>
      {createPortal(
        <div role="dialog">
          <button>Cancel</button>
        </div>,
        document.body,
      )}
    </RecordActionGuard>,
  );
  expect(screen.queryByRole('dialog')).toBeNull();
});

it('does not revive a delayed action when a props-only host restores an older result snapshot', async () => {
  const { engine } = setup();
  await engine.load();
  const original = engine.getSnapshot().sessions.mine;
  if (original.kind !== 'record') throw new Error('record');
  const scopedDefinition = {
    ...definition,
    record: {
      ...definition.record!,
      recordActions: { toolbar: { name: 'guarded' } },
    },
  };
  let action!: ToolbarActionsRendererProps;
  const extensions = {
    toolbarActions: {
      guarded: (props: ToolbarActionsRendererProps) => {
        action = props;
        return <span>Action</span>;
      },
    },
  };
  const commands = engine.record('mine');
  const view = render(
    <RecordContent
      selectable
      session={original}
      definition={scopedDefinition}
      commands={commands}
      extensions={extensions}
    />,
  );
  const old = action;
  expect(old.isCurrent?.()).toBe(true);
  const newer = { ...original, result: { ...original.result! } };
  view.rerender(
    <RecordContent
      selectable
      session={newer}
      definition={scopedDefinition}
      commands={commands}
      extensions={extensions}
    />,
  );
  expect(old.isCurrent?.()).toBe(false);
  view.rerender(
    <RecordContent
      selectable
      session={original}
      definition={scopedDefinition}
      commands={commands}
      extensions={extensions}
    />,
  );
  expect(old.isCurrent?.()).toBe(false);
  expect(action.isCurrent?.()).toBe(true);
  cleanup();
  engine.dispose();
});
