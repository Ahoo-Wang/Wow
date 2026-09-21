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
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  systemInstanceId,
  type ViewInstance,
} from '../src/index.js';
import {
  useLeaveGuard,
  useWorkbench,
  type LeaveGuard,
  type LeaveGuardOptions,
  type LeaveGuardState,
} from '../src/react/index.js';
import { LeaveDialog } from '../src/ui/LeaveGuard.js';
import type { ViewMessages } from '../src/ui/messages.js';
import { ViewSurface } from '../src/ui/ViewSurface.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';
import { mine } from './fixtures/ui.js';

afterEach(cleanup);

/**
 * The shell of a workbench, tested without one.
 *
 * `useWorkbench` is what the three default workbenches and any hand-built
 * one have in common, so the rules it carries — the kind it refuses, the pin
 * it releases, the switch it asks about — are asserted here once rather than
 * three times through three different sets of markup.
 */

const chart: ViewInstance = {
  id: 'orders-chart',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'personal',
  revision: '1',
  config: analysisConfig(),
};

const second: ViewInstance = {
  ...mine,
  id: 'orders-2',
  title: 'Theirs',
};

function engineWith(instances: ViewInstance[]): ViewEngine {
  return new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances }),
    resolveSource: () => testSource(),
  });
}

/** The controller under test, driven straight rather than through markup. */
function open(engine: ViewEngine, instanceId: string | null = null) {
  return renderHook(() =>
    useWorkbench(engine, 'orders', { kind: 'record', instanceId }),
  );
}

describe('useWorkbench', () => {
  it('opens the effective default and lists only its own kind', async () => {
    const { result } = open(engineWith([mine, chart]));

    // Nothing was named, so the definition's own default view opened.
    await waitFor(() => expect(result.current.state?.title).toBe('All orders'));
    expect(result.current.openId).toBe(systemInstanceId('orders', 'all'));
    expect(result.current.unopenable).toBeNull();
    // The analysis view of the same definition is not on offer: the sidebar
    // must not name a view this page has no body for, and the default is
    // resolved among the views it does list.
    const listed = result.current.list.items.map(item => item.title);
    expect(listed).toContain('Mine');
    expect(listed).not.toContain('By warehouse');
  });

  it('opens another view when one is chosen', async () => {
    const { result } = open(engineWith([mine, second]), 'orders-1');

    await waitFor(() => expect(result.current.state?.title).toBe('Mine'));
    act(() => {
      result.current.choose('orders-2');
    });

    await waitFor(() => expect(result.current.state?.title).toBe('Theirs'));
    expect(result.current.openId).toBe('orders-2');
  });

  /**
   * A host names the id itself, so the list cannot keep this one out. It
   * opens, and the controller says why it is not this page's to draw rather
   * than handing over a runtime with no body for it.
   */
  it('refuses a view of another kind, with a reason', async () => {
    const { result } = open(engineWith([mine, chart]), 'orders-chart');

    await waitFor(() =>
      expect(result.current.unopenable?.code).toBe('view.open.wrong-kind'),
    );
    expect(result.current.unopenable?.params).toMatchObject({
      kind: 'analysis',
    });
    expect(result.current.runtime).toBeNull();
    expect(result.current.state).toBeNull();
  });

  it('reports a view that would not open at all', async () => {
    const { result } = open(engineWith([mine]), 'missing');

    await waitFor(() =>
      expect(result.current.unopenable?.code).toBe(
        'view.open.failed.not_found',
      ),
    );
  });

  /**
   * Nothing reloads a pin: once the view is deleted the engine disposes the
   * runtime and reopening the id answers "no such view" for as long as the
   * page is open, so the workbench has to let go itself and move on to what
   * is still there.
   */
  it('moves on once the open view is deleted', async () => {
    const { result } = open(engineWith([mine, second]), 'orders-1');

    await waitFor(() => expect(result.current.state?.title).toBe('Mine'));
    await act(async () => {
      await result.current.commands.delete();
      // What the title bar does with the outcome.
      result.current.onDeleted();
    });

    await waitFor(() => expect(result.current.openId).not.toBe('orders-1'));
    await waitFor(() =>
      expect(result.current.list.items.map(item => item.id)).not.toContain(
        'orders-1',
      ),
    );
    expect(result.current.unopenable).toBeNull();
    expect(result.current.state?.title).toBeDefined();
  });

  /** A saved copy is what the workbench then shows. */
  it('opens the copy a save-as produced', async () => {
    const { result } = open(engineWith([mine]), 'orders-1');

    await waitFor(() => expect(result.current.state?.title).toBe('Mine'));
    await act(async () => {
      const saved = await result.current.commands.saveAs({
        title: 'A copy',
        scope: 'personal',
      });
      expect(saved).not.toBeNull();
      result.current.onSaved(saved!);
    });

    await waitFor(() => expect(result.current.state?.title).toBe('A copy'));
    // And the list was read again, so the copy is on it.
    await waitFor(() =>
      expect(result.current.list.items.map(item => item.title)).toContain(
        'A copy',
      ),
    );
  });

  describe('the leave guard', () => {
    it('switches straight over when nothing would be lost', async () => {
      const { result } = open(engineWith([mine, second]), 'orders-1');

      await waitFor(() => expect(result.current.state?.title).toBe('Mine'));
      act(() => {
        result.current.choose('orders-2');
      });

      expect(result.current.leave.asking).toBe(false);
      await waitFor(() => expect(result.current.state?.title).toBe('Theirs'));
    });

    it('asks before an unsaved draft is lost, and stays on a no', async () => {
      const { result } = open(engineWith([mine, second]), 'orders-1');
      await waitFor(() => expect(result.current.state?.title).toBe('Mine'));
      act(() => {
        result.current.runtime?.edit({ pageSize: 50 });
      });
      await waitFor(() => expect(result.current.state?.dirty).toBe(true));

      act(() => {
        result.current.choose('orders-2');
      });
      expect(result.current.leave.asking).toBe(true);
      expect(result.current.state?.title).toBe('Mine');

      act(() => {
        result.current.leave.cancel();
      });
      expect(result.current.leave.asking).toBe(false);
      expect(result.current.state?.title).toBe('Mine');
      expect(result.current.openId).toBe('orders-1');
    });

    it('switches on a yes', async () => {
      const { result } = open(engineWith([mine, second]), 'orders-1');
      await waitFor(() => expect(result.current.state?.title).toBe('Mine'));
      act(() => {
        result.current.runtime?.edit({ pageSize: 50 });
      });
      await waitFor(() => expect(result.current.state?.dirty).toBe(true));

      act(() => {
        result.current.choose('orders-2');
      });
      act(() => {
        result.current.leave.confirm();
      });

      await waitFor(() => expect(result.current.state?.title).toBe('Theirs'));
    });

    /**
     * Leaving disposes the runtime, and an unsettled write outlives it inside
     * the engine: the handle would address a runtime nobody can reach again,
     * and `engine.pendingWrites()` would hold it for the rest of the session.
     */
    it('settles a write that never came back before it goes', async () => {
      const store = new MemoryViewStore({ instances: [mine, second] });
      // A save that leaves and never answers is the one loss walking away
      // cannot undo, so the guard asks about it even with no draft.
      vi.spyOn(store, 'save').mockRejectedValue(
        new ViewStoreError('UNAVAILABLE', 'timeout'),
      );
      const engine = new ViewEngine({
        definitions: [ordersDefinition()],
        store,
        resolveSource: () => testSource(),
      });
      const { result } = open(engine, 'orders-1');
      await waitFor(() => expect(result.current.state?.title).toBe('Mine'));

      act(() => {
        result.current.runtime?.edit({ pageSize: 50 });
      });
      await act(async () => {
        await result.current.commands.save();
      });
      await waitFor(() =>
        expect(result.current.state?.write?.kind).toBe('unknown'),
      );
      expect(engine.pendingWrites().size).toBeGreaterThan(0);

      act(() => {
        result.current.choose('orders-2');
      });
      expect(result.current.leave.asking).toBe(true);
      act(() => {
        result.current.leave.confirm();
      });

      await waitFor(() => expect(result.current.state?.title).toBe('Theirs'));
      expect(engine.pendingWrites().size).toBe(0);
    });
  });
});

/**
 * The guard on its own, without a view behind it: the two facts it reads and
 * the three answers it gives. `LeaveDialog` is the only thing `/ui` still
 * has of it, and it decides nothing.
 */
function Guarded({
  state,
  options,
  messages,
}: {
  state: LeaveGuardState | null;
  options?: LeaveGuardOptions;
  /** The wording the dialog is handed; the guard itself resolves none. */
  messages?: ViewMessages;
}) {
  const guard: LeaveGuard = useLeaveGuard(state, options);
  const [left, setLeft] = useState(false);
  return (
    <ViewSurface>
      <button type="button" onClick={() => guard.request(() => setLeft(true))}>
        Open another
      </button>
      <span data-testid="where">{left ? 'gone' : 'here'}</span>
      <span data-testid="asking">{guard.asking ? 'asking' : 'quiet'}</span>
      <LeaveDialog leave={guard} messages={messages} />
    </ViewSurface>
  );
}

function leave() {
  fireEvent.click(screen.getByRole('button', { name: 'Open another' }));
}

function where(): string {
  return screen.getByTestId('where').textContent ?? '';
}

describe('useLeaveGuard', () => {
  it('goes straight there when nothing would be lost', () => {
    render(<Guarded state={{ dirty: false, write: null }} />);

    leave();

    expect(where()).toBe('gone');
    expect(screen.getByTestId('asking').textContent).toBe('quiet');
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  it('goes straight there when no view is open at all', () => {
    render(<Guarded state={null} />);

    leave();

    expect(where()).toBe('gone');
  });

  /**
   * Opening another view releases this one's runtime, and the draft lives
   * nowhere else — so leaving is the deletion of work, asked about once.
   */
  it('asks before unsaved edits are lost', async () => {
    render(<Guarded state={{ dirty: true, write: null }} />);

    leave();

    const dialog = await screen.findByRole('alertdialog');
    expect(dialog.textContent).toContain('Leave this view?');
    expect(where()).toBe('here');
    expect(screen.getByTestId('asking').textContent).toBe('asking');

    fireEvent.click(within(dialog).getByRole('button', { name: 'Leave' }));
    expect(where()).toBe('gone');
  });

  it('stays put when that is the answer', async () => {
    render(<Guarded state={{ dirty: true, write: null }} />);

    leave();
    const dialog = await screen.findByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Stay' }));

    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    expect(where()).toBe('here');
  });

  it('asks when a write never came back', async () => {
    // Leaving takes away the chance to settle it, which is its own loss.
    render(
      <Guarded
        state={{
          dirty: false,
          write: {
            kind: 'unknown',
            requestId: 'r1',
            payload: {
              action: 'delete',
              id: 'orders-1',
              definitionId: 'orders',
              revision: '1',
            },
          },
        }}
      />,
    );

    leave();

    expect(await screen.findByRole('alertdialog')).toBeDefined();
  });

  /**
   * The dialog is rendered by the shell, which sits outside the surface that
   * carries the wording — so the labels it resolves are the ones in force
   * *there*. Handed the workbench's own `messages`, the one dialog that
   * interrupts everything else says what the rest of the surface says.
   */
  it('says it in the wording it was handed', async () => {
    render(
      <Guarded
        state={{ dirty: true, write: null }}
        messages={{ 'label.leave.heading': '离开这个视图？' }}
      />,
    );

    leave();

    const dialog = await screen.findByRole('alertdialog');
    expect(dialog.textContent).toContain('离开这个视图？');
  });

  it('settles the outcome on its way out', async () => {
    const settled = vi.fn();
    render(
      <Guarded
        state={{ dirty: true, write: null }}
        options={{ onLeave: settled }}
      />,
    );

    leave();
    const dialog = await screen.findByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Leave' }));

    expect(settled).toHaveBeenCalledTimes(1);
    expect(where()).toBe('gone');
  });

  it('settles nothing when the answer is to stay', async () => {
    const settled = vi.fn();
    render(
      <Guarded
        state={{ dirty: true, write: null }}
        options={{ onLeave: settled }}
      />,
    );

    leave();
    const dialog = await screen.findByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Stay' }));

    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    expect(settled).not.toHaveBeenCalled();
  });

  it('does not ask about an outcome that can be settled later', () => {
    // A conflict keeps the edits and stays answerable from wherever the view
    // is next opened; only an unknown result is lost by walking away.
    render(
      <Guarded
        state={{
          dirty: false,
          write: {
            kind: 'rejected',
            requestId: 'r1',
            payload: {
              action: 'delete',
              id: 'orders-1',
              definitionId: 'orders',
              revision: '1',
            },
            issue: {
              code: 'view.delete.forbidden',
              path: [],
              severity: 'error',
            },
          },
        }}
      />,
    );

    leave();

    expect(where()).toBe('gone');
  });
});
