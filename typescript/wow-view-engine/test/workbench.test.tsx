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
import { afterEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  systemInstanceId,
  type ViewInstance,
  type ViewPermissions,
} from '../src/index.js';
import {
  useLeaveGuard,
  useWorkbench,
  type LeaveGuard,
  type LeaveGuardOptions,
  type LeaveGuardState,
  type WorkbenchOptions,
} from '../src/react/index.js';
import { EmbeddedView } from '../src/ui/EmbeddedView.js';
import { LeaveDialog } from '../src/ui/LeaveGuard.js';
import type { ViewMessages } from '../src/ui/messages.js';
import { ViewSurface } from '../src/ui/ViewSurface.js';
import {
  analysisConfig,
  ordersDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';
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

function engineWith(
  instances: ViewInstance[],
  permissions?: () => ViewPermissions,
): ViewEngine {
  return new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances, permissions }),
    resolveSource: () => testSource(),
  });
}

/** The controller under test, driven straight rather than through markup. */
function open(
  engine: ViewEngine,
  instanceId: string | null = null,
  options: Partial<WorkbenchOptions> = {},
) {
  return renderHook(() =>
    useWorkbench(engine, 'orders', { kind: 'record', instanceId, ...options }),
  );
}

/** A workbench that may make a view from nothing. */
const NEW_VIEW = { newView: { title: 'New view' } };

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

  describe('a view made from nothing', () => {
    it('opens unsaved, of its own kind, in place of the view that was open', async () => {
      const engine = engineWith([mine]);
      const { result } = open(engine, 'orders-1', NEW_VIEW);
      await waitFor(() => expect(result.current.state?.title).toBe('Mine'));
      const before = result.current.runtime;

      expect(result.current.canCreate).toBe(true);
      act(() => {
        result.current.create();
      });

      // Made and shown at once — no id to open, nothing to wait for — and
      // the view it replaced is closed, since it is no longer on screen.
      expect(result.current.opened.loading).toBe(false);
      expect(result.current.state?.title).toBe('New view');
      expect(result.current.state?.saved).toBeNull();
      expect(result.current.runtime?.kind).toBe('record');
      expect(result.current.unopenable).toBeNull();
      expect(before?.disposed).toBe(true);
      // Unsaved, so it is in no list: the sidebar marks no row as open.
      expect(result.current.list.items.map(item => item.title)).toEqual([
        'All orders',
        'Mine',
      ]);
    });

    it('starts from the template the host gave', async () => {
      const { result } = open(engineWith([mine]), 'orders-1', {
        newView: { title: 'New view', config: recordConfig({ pageSize: 7 }) },
      });
      await waitFor(() => expect(result.current.state?.title).toBe('Mine'));

      act(() => {
        result.current.create();
      });
      expect(result.current.state?.draft).toMatchObject({ pageSize: 7 });
    });

    it('lets one nobody touched go without asking', async () => {
      const { result } = open(engineWith([mine]), 'orders-1', NEW_VIEW);
      await waitFor(() => expect(result.current.state?.title).toBe('Mine'));
      act(() => {
        result.current.create();
      });
      const fresh = result.current.runtime;

      // Dirty by the runtime's own account — losing it loses everything —
      // but there is nothing in it yet that the user made.
      expect(result.current.state?.dirty).toBe(true);
      act(() => {
        result.current.choose('orders-1');
      });
      expect(result.current.leave.asking).toBe(false);
      await waitFor(() => expect(result.current.state?.title).toBe('Mine'));
      expect(fresh?.disposed).toBe(true);
    });

    it('asks before one the user shaped is lost', async () => {
      const { result } = open(engineWith([mine]), 'orders-1', NEW_VIEW);
      await waitFor(() => expect(result.current.state?.title).toBe('Mine'));
      act(() => {
        result.current.create();
      });
      act(() => {
        result.current.runtime?.edit({ pageSize: 50 });
      });
      await waitFor(() =>
        expect(result.current.state?.draft).toMatchObject({ pageSize: 50 }),
      );

      act(() => {
        result.current.choose('orders-1');
      });
      expect(result.current.leave.asking).toBe(true);
      expect(result.current.state?.title).toBe('New view');

      act(() => {
        result.current.leave.confirm();
      });
      await waitFor(() => expect(result.current.state?.title).toBe('Mine'));
    });

    it('opens what the store took once it is saved', async () => {
      const engine = engineWith([mine]);
      const { result } = open(engine, 'orders-1', NEW_VIEW);
      await waitFor(() => expect(result.current.state?.title).toBe('Mine'));
      act(() => {
        result.current.create();
      });
      const fresh = result.current.runtime;
      if (!fresh) throw new Error('no new view');

      // The first save is a create: the UI asks for a title and an audience
      // and calls `saveAs`, which is what lands here.
      const saved = await engine.saveAs(fresh, {
        title: 'Fresh',
        scope: 'personal',
      });
      act(() => {
        result.current.onSaved(saved);
      });

      await waitFor(() =>
        expect(result.current.state?.saved?.id).toBe(saved.id),
      );
      expect(result.current.openId).toBe(saved.id);
      expect(result.current.state?.title).toBe('Fresh');
      expect(fresh.disposed).toBe(true);
      await waitFor(() =>
        expect(result.current.list.items.map(item => item.title)).toContain(
          'Fresh',
        ),
      );
    });

    it('is not on offer without a name, a permission, or the kind', async () => {
      // No title to open it under: this layer has no wording of its own.
      const unnamed = open(engineWith([mine]), 'orders-1');
      await waitFor(() => expect(unnamed.result.current.state).not.toBeNull());
      expect(unnamed.result.current.canCreate).toBe(false);
      act(() => {
        unnamed.result.current.create();
      });
      expect(unnamed.result.current.state?.title).toBe('Mine');

      // Nowhere to create in.
      const forbidden = open(
        engineWith([mine], () => ({
          createPersonal: false,
          createShared: false,
          reorder: true,
          setDefault: true,
          instance: () => ({ save: true, rename: true, delete: true }),
        })),
        'orders-1',
        NEW_VIEW,
      );
      await waitFor(() =>
        expect(forbidden.result.current.state).not.toBeNull(),
      );
      expect(forbidden.result.current.canCreate).toBe(false);

      // A template of another kind is no template for this page.
      const mismatched = open(engineWith([mine]), 'orders-1', {
        newView: { title: 'New view', config: analysisConfig() },
      });
      await waitFor(() =>
        expect(mismatched.result.current.state).not.toBeNull(),
      );
      expect(mismatched.result.current.canCreate).toBe(false);
    });
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
 * Which view is open is the one piece of workbench state a host may also
 * hold — a route, a link somebody shares — so `instanceId` is controlled and
 * `onInstanceChange` reports. It converges rather than renders: a view holds
 * an unsaved draft, so a pushed value goes through the same leave guard a
 * click goes through, and whichever side moved last is the one that speaks.
 */
describe('a workbench a host routes', () => {
  /** The controller with a host holding the value, and able to change it. */
  function routed(
    engine: ViewEngine,
    instanceId: string | null,
    onInstanceChange?: (id: string | null) => void,
  ) {
    return renderHook(
      ({ id }: { id: string | null }) =>
        useWorkbench(engine, 'orders', {
          kind: 'record',
          instanceId: id,
          onInstanceChange,
        }),
      { initialProps: { id: instanceId } },
    );
  }

  it('opens what the host names when the host names another', async () => {
    const { result, rerender } = routed(engineWith([mine, second]), 'orders-1');
    await waitFor(() => expect(result.current.state?.title).toBe('Mine'));

    rerender({ id: 'orders-2' });

    await waitFor(() => expect(result.current.state?.title).toBe('Theirs'));
    expect(result.current.openId).toBe('orders-2');
  });

  /** `null` means the effective default in the prop as in the report. */
  it('falls back to the effective default when the host names none', async () => {
    const { result, rerender } = routed(engineWith([mine, second]), 'orders-2');
    await waitFor(() => expect(result.current.state?.title).toBe('Theirs'));

    rerender({ id: null });

    await waitFor(() =>
      expect(result.current.openId).toBe(systemInstanceId('orders', 'all')),
    );
  });

  it('reports the view the user switched to', async () => {
    const told = vi.fn();
    const { result } = routed(engineWith([mine, second]), 'orders-1', told);
    await waitFor(() => expect(result.current.state?.title).toBe('Mine'));

    act(() => {
      result.current.choose('orders-2');
    });

    await waitFor(() => expect(told).toHaveBeenCalledWith('orders-2'));
    // Said once: a host that does not follow is not told again.
    expect(told).toHaveBeenCalledTimes(1);
  });

  /** A push the host made is its own news; it does not come back to it. */
  it('says nothing back about a move the host itself made', async () => {
    const told = vi.fn();
    const { result, rerender } = routed(
      engineWith([mine, second]),
      'orders-1',
      told,
    );
    await waitFor(() => expect(result.current.state?.title).toBe('Mine'));

    rerender({ id: 'orders-2' });
    await waitFor(() => expect(result.current.state?.title).toBe('Theirs'));

    expect(told).not.toHaveBeenCalled();
  });

  /**
   * The pin goes with the deleted view, and `null` is exactly what a host
   * puts back into `instanceId` to mean "whatever my default is now".
   */
  it('reports null once the open view is deleted', async () => {
    const told = vi.fn();
    const { result } = routed(engineWith([mine, second]), 'orders-1', told);
    await waitFor(() => expect(result.current.state?.title).toBe('Mine'));

    await act(async () => {
      await result.current.commands.delete();
      result.current.onDeleted();
    });

    await waitFor(() => expect(told).toHaveBeenLastCalledWith(null));
  });

  /**
   * A view that vanished from the store after having been open answers
   * not_found for the rest of the session, so the pin is released — and the
   * host hears it, rather than being left with a route to nothing.
   */
  it('reports null when the open view is gone from the store', async () => {
    const store = new MemoryViewStore({ instances: [mine, second] });
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    const told = vi.fn();
    const { result } = routed(engine, 'orders-1', told);
    await waitFor(() => expect(result.current.state?.title).toBe('Mine'));

    // Deleted by somebody else: the engine lets the runtime go, and the next
    // open of the same id is answered "no such view".
    await act(async () => {
      await engine.delete('orders-1');
    });

    await waitFor(() => expect(told).toHaveBeenLastCalledWith(null));
    expect(result.current.unopenable).toBeNull();
  });

  /**
   * A route change is a switch like any other, so it is asked about — and a
   * "no" leaves the host's route naming a view that is not on screen. The
   * workbench says what stayed, so the route can go back where it was.
   */
  it('asks before a pushed view takes a draft away, and reports the one that stayed', async () => {
    const told = vi.fn();
    const { result, rerender } = routed(
      engineWith([mine, second]),
      'orders-1',
      told,
    );
    await waitFor(() => expect(result.current.state?.title).toBe('Mine'));
    act(() => {
      result.current.runtime?.edit({ pageSize: 50 });
    });
    await waitFor(() => expect(result.current.state?.dirty).toBe(true));

    rerender({ id: 'orders-2' });
    expect(result.current.leave.asking).toBe(true);
    expect(result.current.state?.title).toBe('Mine');
    // Nothing is reported while the question is still on screen: it has not
    // been settled either way yet.
    expect(told).not.toHaveBeenCalled();

    act(() => {
      result.current.leave.cancel();
    });

    await waitFor(() => expect(told).toHaveBeenCalledWith('orders-1'));
    expect(result.current.openId).toBe('orders-1');
  });

  it('switches on a yes, and says nothing the host did not already know', async () => {
    const told = vi.fn();
    const { result, rerender } = routed(
      engineWith([mine, second]),
      'orders-1',
      told,
    );
    await waitFor(() => expect(result.current.state?.title).toBe('Mine'));
    act(() => {
      result.current.runtime?.edit({ pageSize: 50 });
    });
    await waitFor(() => expect(result.current.state?.dirty).toBe(true));

    rerender({ id: 'orders-2' });
    act(() => {
      result.current.leave.confirm();
    });

    await waitFor(() => expect(result.current.state?.title).toBe('Theirs'));
    expect(told).not.toHaveBeenCalled();
  });

  /**
   * The uncontrolled form is `instanceId` left out entirely, and `null` is
   * not it: a host that passes `null` is holding a value. Left out, the
   * workbench owns the open view and still reports every move.
   */
  it('owns the open view when the host holds no value, and still reports', async () => {
    const told = vi.fn();
    const engine = engineWith([mine, second]);
    const { result } = renderHook(() =>
      useWorkbench(engine, 'orders', {
        kind: 'record',
        onInstanceChange: told,
      }),
    );
    await waitFor(() =>
      expect(result.current.openId).toBe(systemInstanceId('orders', 'all')),
    );
    expect(told).not.toHaveBeenCalled();

    act(() => {
      result.current.choose('orders-2');
    });

    await waitFor(() => expect(result.current.state?.title).toBe('Theirs'));
    expect(told).toHaveBeenCalledTimes(1);
    expect(told).toHaveBeenCalledWith('orders-2');
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

/**
 * The other way out.
 *
 * Every exit inside the page goes through `request` and can be argued with.
 * Closing the tab, going back and following a link off the page do not —
 * nothing in this package is ever told they happened — so the same judgement
 * hangs on `beforeunload`, and only while there is something to lose.
 */
function closeTab(): boolean {
  const event = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(event);
  return event.defaultPrevented;
}

/** Whether a handler was hung at all — not merely whether one stopped the event. */
function hung(listen: MockInstance<typeof window.addEventListener>): boolean {
  return listen.mock.calls.some(([type]) => type === 'beforeunload');
}

describe('beforeunload', () => {
  it('stops the tab closing over an unsaved draft', () => {
    render(<Guarded state={{ dirty: true, write: null }} />);

    expect(closeTab()).toBe(true);
  });

  /**
   * The write the ledger cannot answer for: the request left and nothing came
   * back, so nobody knows whether it landed. Closing the tab takes away the
   * one screen that could still retry or abandon it.
   */
  it('stops it over a write nobody can answer for', () => {
    render(
      <Guarded
        state={{
          dirty: false,
          write: {
            kind: 'unknown',
            requestId: 'r1',
            payload: {
              action: 'rename',
              id: 'orders-1',
              revision: '1',
              title: 'Mine',
            },
          },
        }}
      />,
    );

    expect(closeTab()).toBe(true);
  });

  /**
   * Nothing hung at all, rather than a handler that lets the event through: a
   * page carrying a `beforeunload` listener is a page the browser may keep
   * out of the back/forward cache whatever that listener decides.
   */
  it('hangs nothing while there is nothing to lose', () => {
    const listen = vi.spyOn(window, 'addEventListener');
    render(<Guarded state={{ dirty: false, write: null }} />);

    expect(hung(listen)).toBe(false);
    expect(closeTab()).toBe(false);
  });

  it('hangs nothing where the host asked for none', () => {
    const listen = vi.spyOn(window, 'addEventListener');
    render(
      <Guarded
        state={{ dirty: true, write: null }}
        options={{ guardUnload: false }}
      />,
    );

    expect(hung(listen)).toBe(false);
    expect(closeTab()).toBe(false);
  });

  /**
   * An embed is somebody else's page: no editor, no draft, no save — so it
   * never reaches this guard at all, and an order page that happens to show a
   * saved view is never argued with on its way out.
   */
  it('hangs nothing on an embedded view', async () => {
    const listen = vi.spyOn(window, 'addEventListener');
    render(<EmbeddedView engine={engineWith([mine])} instanceId="orders-1" />);

    await waitFor(() => expect(screen.queryByRole('table')).not.toBeNull());
    expect(hung(listen)).toBe(false);
    expect(closeTab()).toBe(false);
  });

  it('takes it back down once the draft is saved', () => {
    const { rerender } = render(
      <Guarded state={{ dirty: true, write: null }} />,
    );
    expect(closeTab()).toBe(true);

    rerender(<Guarded state={{ dirty: false, write: null }} />);

    expect(closeTab()).toBe(false);
  });

  it('takes it back down when the workbench goes', () => {
    const { unmount } = render(
      <Guarded state={{ dirty: true, write: null }} />,
    );
    expect(closeTab()).toBe(true);

    unmount();

    expect(closeTab()).toBe(false);
  });
});
