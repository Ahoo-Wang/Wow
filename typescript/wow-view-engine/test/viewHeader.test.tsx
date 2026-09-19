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
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState, type ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  systemInstanceId,
  type AnyViewRuntime,
  type ViewInstance,
  type ViewKind,
} from '../src/index.js';
import {
  useSaveCommands,
  useViewRuntime,
  type SaveCommands,
} from '../src/react/index.js';
import {
  useLeaveGuard,
  type LeaveGuardOptions,
  type LeaveGuardState,
} from '../src/ui/LeaveGuard.js';
import { ViewHeader } from '../src/ui/ViewHeader.js';
import { ViewSurface } from '../src/ui/ViewSurface.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';

afterEach(cleanup);

const mine: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

const ours: ViewInstance = {
  ...mine,
  id: 'orders-2',
  title: 'Ours',
  scope: 'shared',
};

function setup() {
  const store = new MemoryViewStore({ instances: [mine, ours] });
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store,
    resolveSource: () => testSource(),
  });
  return { engine, store };
}

function Harness({
  engine,
  runtime,
  kind = 'record',
  actions,
  leading,
}: {
  engine: ViewEngine;
  runtime: AnyViewRuntime;
  kind?: ViewKind;
  actions?: ReactNode;
  leading?: ReactNode;
}) {
  const state = useViewRuntime(runtime);
  const commands = useSaveCommands(engine, runtime);
  return (
    <ViewSurface>
      <ViewHeader
        state={state}
        kind={kind}
        commands={commands}
        actions={actions}
        leading={leading}
      />
    </ViewSurface>
  );
}

/** Commands for a header with no view behind it: nothing is allowed. */
const NOTHING: SaveCommands = {
  save: () => Promise.resolve(null),
  saveAs: () => Promise.resolve(null),
  rename: () => Promise.resolve(null),
  delete: () => Promise.resolve(false),
  revert: () => undefined,
  retry: () =>
    Promise.resolve({ landed: false, written: false, instance: null }),
  abandon: () => undefined,
  resolveConflict: () =>
    Promise.resolve({ landed: false, written: false, instance: null }),
  can: {
    save: false,
    saveAs: false,
    rename: false,
    delete: false,
    revert: false,
    createPersonal: false,
    createShared: false,
  },
  state: {
    pending: false,
    error: null,
    write: null,
    dirty: false,
    blocked: false,
    hasErrors: false,
    lastSavedAt: null,
  },
};

function header(): HTMLElement {
  const found = document.querySelector('[data-slot="view-header"]');
  if (!found) throw new Error('no header');
  return found as HTMLElement;
}

function title(): HTMLElement {
  const found = document.querySelector('[data-slot="view-title"]');
  if (!found) throw new Error('no title');
  return found as HTMLElement;
}

describe('ViewHeader', () => {
  it('renders nothing while no view is open', () => {
    render(
      <ViewSurface>
        <ViewHeader state={null} kind="record" commands={NOTHING} />
      </ViewSurface>,
    );
    expect(document.querySelector('[data-slot="view-header"]')).toBeNull();
  });

  it('says what the view is called and who it is for', async () => {
    const { engine } = setup();
    const runtime = await engine.open('orders-1');
    render(<Harness engine={engine} runtime={runtime} />);

    expect(title().textContent).toBe('Mine');
    expect(header().textContent).toContain('personal');
    // Nothing has been edited, so neither mark is on the title.
    expect(title().dataset.dirty).toBeUndefined();
    expect(header().textContent).not.toContain('Edited');
    expect(header().textContent).not.toContain('Not saved yet');
  });

  it('says a shared view is shared', async () => {
    const { engine } = setup();
    const runtime = await engine.open('orders-2');
    render(<Harness engine={engine} runtime={runtime} />);

    expect(header().textContent).toContain('shared');
  });

  /**
   * A system view is a shared view — that is `audienceOf`'s answer — so the
   * tag is what says it came with the definition rather than from a user.
   */
  it('says where a system view came from', async () => {
    const { engine } = setup();
    const runtime = await engine.open(systemInstanceId('orders', 'all'));
    render(<Harness engine={engine} runtime={runtime} />);

    expect(header().textContent).toContain('system');
  });

  it('marks a view that has never been saved', () => {
    const { engine } = setup();
    const runtime = engine.create('orders', {
      title: 'Untitled',
      scope: 'personal',
      config: recordConfig(),
    });
    render(<Harness engine={engine} runtime={runtime} />);

    expect(header().textContent).toContain('Not saved yet');
    expect(header().textContent).not.toContain('Edited');
  });

  it('marks a saved view that has been edited since', async () => {
    const { engine } = setup();
    const runtime = await engine.open('orders-1');
    render(<Harness engine={engine} runtime={runtime} />);

    act(() => runtime.edit({ pageSize: 50 }));

    expect(header().textContent).toContain('Edited');
    expect(title().dataset.dirty).toBe('true');
  });

  it('names the kind it was told it is showing', async () => {
    const user = userEvent.setup();
    const { engine } = setup();
    const runtime = await engine.open('orders-1');
    render(<Harness engine={engine} runtime={runtime} kind="analysis" />);

    // One data definition holds record and analysis views together, so the
    // icon has to be nameable rather than merely recognisable.
    const trigger = header().querySelector('[data-slot="tooltip-trigger"]');
    await user.hover(trigger as Element);
    expect(await screen.findByText('Analysis view')).toBeDefined();
  });

  it("puts the host's own actions before the save commands", async () => {
    const { engine } = setup();
    const runtime = await engine.open('orders-1');
    render(
      <Harness
        engine={engine}
        runtime={runtime}
        actions={<button type="button">Export</button>}
      />,
    );

    expect(screen.getByRole('button', { name: 'Export' })).toBeDefined();
    // A separator earns its place only when there are two sides to divide.
    expect(header().querySelectorAll('[data-slot="separator"]')).toHaveLength(
      1,
    );
  });

  /**
   * The start of the line is the surface's, for whatever it has to put back
   * there — what a collapsed sidebar leaves behind, for instance. Nothing is
   * reserved for it, so a header without one reads exactly as before.
   */
  it('starts the line with whatever the surface puts there', async () => {
    const { engine } = setup();
    const runtime = await engine.open('orders-1');
    render(
      <Harness
        engine={engine}
        runtime={runtime}
        leading={<button type="button">Views</button>}
      />,
    );

    const first = header().querySelector('div')?.firstElementChild;
    expect(first?.textContent).toBe('Views');
  });

  it('draws no separator when the host adds nothing', async () => {
    const { engine } = setup();
    const runtime = await engine.open('orders-1');
    render(<Harness engine={engine} runtime={runtime} />);

    expect(header().querySelectorAll('[data-slot="separator"]')).toHaveLength(
      0,
    );
  });
});

function Guarded({
  state,
  options,
}: {
  state: LeaveGuardState | null;
  options?: LeaveGuardOptions;
}) {
  const guard = useLeaveGuard(state, options);
  const [left, setLeft] = useState(false);
  return (
    <ViewSurface>
      <button type="button" onClick={() => guard.request(() => setLeft(true))}>
        Open another
      </button>
      <span data-testid="where">{left ? 'gone' : 'here'}</span>
      {guard.dialog}
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
    expect(screen.queryByRole('dialog')).toBeNull();
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

    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain('Leave this view?');
    expect(where()).toBe('here');

    fireEvent.click(within(dialog).getByRole('button', { name: 'Leave' }));
    expect(where()).toBe('gone');
  });

  it('stays put when that is the answer', async () => {
    render(<Guarded state={{ dirty: true, write: null }} />);

    leave();
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Stay' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
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
            payload: { action: 'delete', id: 'orders-1', revision: '1' },
          },
        }}
      />,
    );

    leave();

    expect(await screen.findByRole('dialog')).toBeDefined();
  });

  /**
   * The hook runs in the workbench, which sits outside the surface that
   * carries the wording — so the labels it resolves are the ones in force
   * *there*. Handed the workbench's own `messages`, the one dialog that
   * interrupts everything else says what the rest of the surface says.
   */
  it('says it in the wording it was handed', async () => {
    render(
      <Guarded
        state={{ dirty: true, write: null }}
        options={{ messages: { 'label.leave.heading': '离开这个视图？' } }}
      />,
    );

    leave();

    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain('离开这个视图？');
  });

  /**
   * Leaving disposes the runtime, and an unsettled write outlives it inside
   * the engine: the handle would address a runtime nobody can reach again.
   */
  it('settles the outcome on its way out', async () => {
    const settled = vi.fn();
    render(
      <Guarded
        state={{ dirty: true, write: null }}
        options={{ onLeave: settled }}
      />,
    );

    leave();
    const dialog = await screen.findByRole('dialog');
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
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Stay' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
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
            payload: { action: 'delete', id: 'orders-1', revision: '1' },
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
