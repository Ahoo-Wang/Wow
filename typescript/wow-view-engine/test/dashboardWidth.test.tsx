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
 * A board's width (D31): fixed and centred, or across whatever holds it —
 * a new board fixed, a board saved before it could say full; admitted with
 * a warning for a width this release does not know; switched while the
 * board is built, one step of its history; drawn the same in the workbench
 * and in an embed. And the grid measured before its first paint, so a phone
 * never sees a frame of the wide layout.
 */

import {
  act,
  cleanup,
  render,
  renderHook,
  screen,
  waitFor,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createElement, type ComponentProps } from 'react';
import type * as GridModule from 'react-grid-layout';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  boardWidth,
  builtinFieldKinds,
  emptyDashboardConfig,
  setBoardWidth,
  validateDashboard,
  type DashboardRuntime,
  type DashboardViewConfig,
  type ViewInstance,
} from '../src/index.js';
import { useDashboard } from '../src/react/index.js';
import {
  DashboardGrid,
  DashboardWorkbench,
  EmbeddedDashboard,
} from '../src/ui/index.js';
import {
  dashboardConfig,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  panelReference,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { panel, pending } from './fixtures/dashboard.js';

/**
 * Every grid the library is handed, as it was handed it: the width it is
 * laid out at and how many columns. The library itself still draws.
 */
const grids = vi.hoisted(() => [] as { width: number; cols: number }[]);
vi.mock('react-grid-layout', async importOriginal => {
  const actual = await importOriginal<typeof GridModule>();
  const Grid = actual.default;
  function RecordingGrid(props: ComponentProps<typeof Grid>) {
    grids.push({ width: props.width, cols: props.gridConfig?.cols ?? 0 });
    return createElement(Grid, props);
  }
  return { ...actual, default: RecordingGrid };
});

afterEach(() => {
  cleanup();
  grids.length = 0;
  vi.restoreAllMocks();
});

const slot = (name: string) =>
  document.querySelector<HTMLElement>(`[data-slot="${name}"]`);

function validate(config: DashboardViewConfig) {
  return validateDashboard(
    config,
    'personal',
    new Map([['pending', panelReference()]]),
    builtinFieldKinds,
  );
}

describe('the width a board is laid out at (D31)', () => {
  it('starts a new board fixed, and reads one saved before it could say as full', () => {
    expect(emptyDashboardConfig().width).toBe('fixed');
    expect(boardWidth(emptyDashboardConfig())).toBe('fixed');
    expect(boardWidth(dashboardConfig())).toBe('full');
    expect(boardWidth(dashboardConfig({ width: 'full' }))).toBe('full');
  });

  it('is set by the kernel, which hands the same board back for no change', () => {
    const board = dashboardConfig();
    const fixed = setBoardWidth(board, 'fixed');
    expect(fixed.width).toBe('fixed');
    expect(setBoardWidth(fixed, 'fixed')).toBe(fixed);
    // Full is written, not left out: the author chose it.
    expect(setBoardWidth(fixed, 'full').width).toBe('full');
    // What no screen offers changes nothing.
    expect(setBoardWidth(fixed, 'wide' as never)).toBe(fixed);
  });

  it('admits the two widths and none, and warns of one it does not know', () => {
    for (const width of [undefined, 'fixed', 'full'] as const)
      expect(
        validate(dashboardConfig(width ? { width } : {})).filter(found =>
          found.code.startsWith('dashboard.width'),
        ),
      ).toEqual([]);

    const unknown = (width: unknown) =>
      validate({ ...dashboardConfig(), width } as DashboardViewConfig);
    expect(unknown('wide')).toEqual([
      {
        code: 'dashboard.width.unknown',
        severity: 'warning',
        path: ['width'],
        params: { width: 'wide' },
      },
    ]);
    // Not a string: named by what it is, and a long one cut short — a
    // config arrives from a store.
    expect(unknown(42)[0].params).toEqual({ width: 'number' });
    expect(String(unknown('x'.repeat(500))[0].params?.width)).toHaveLength(40);
    // A warning: the board still opens, full width.
    expect(boardWidth({ width: 'wide' })).toBe('full');
  });
});

function engineWith(board: ViewInstance) {
  const store = new MemoryViewStore({ instances: [pending, board] });
  return new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store,
    resolveSource: () => testSource(),
    environment: testEnvironment().environment,
  });
}

function boardOf(overrides: Partial<DashboardViewConfig> = {}): ViewInstance {
  return {
    id: 'board',
    definitionId: 'overview',
    title: 'Operations',
    scope: 'personal',
    revision: '1',
    config: dashboardConfig({
      panels: [panel({ title: 'Pending' })],
      ...overrides,
    }),
  };
}

describe('switching the width while the board is built', () => {
  it('is one step of the history, and refused while the board is only read', async () => {
    const engine = engineWith(boardOf());
    const runtime = (await engine.open('board')) as DashboardRuntime;
    await nextTask();

    runtime.setWidth('fixed');
    expect(runtime.getSnapshot().draft.width).toBeUndefined();

    runtime.setBuilding(true);
    runtime.setWidth('fixed');
    let state = runtime.getSnapshot();
    expect(state.draft.width).toBe('fixed');
    expect(state.applied.width).toBe('fixed');
    expect(state.dirty).toBe(true);
    expect(state.history.undo).toEqual({ command: 'setWidth', subject: null });

    expect(runtime.undo()).toEqual({ command: 'setWidth', subject: null });
    state = runtime.getSnapshot();
    // Back to a board that never said: the member goes, not `full`.
    expect('width' in state.draft).toBe(false);
    expect(state.dirty).toBe(false);
    runtime.redo();
    expect(runtime.getSnapshot().applied.width).toBe('fixed');
  });

  it('reaches the controller the grid reads', async () => {
    const engine = engineWith(boardOf({ width: 'fixed' }));
    const runtime = (await engine.open('board')) as DashboardRuntime;
    const view = renderHook(() => useDashboard(runtime));
    await act(async () => {
      await nextTask();
    });
    expect(view.result.current.width).toBe('fixed');
    act(() => {
      runtime.setBuilding(true);
      runtime.setWidth('full');
    });
    expect(view.result.current.width).toBe('full');
    expect(renderHook(() => useDashboard(null)).result.current.width).toBe(
      'full',
    );
  });
});

describe('a board drawn at its width', () => {
  it('holds a fixed board to the fixed width, centred, and a full one to nothing', async () => {
    const engine = engineWith(boardOf({ width: 'fixed' }));
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="board"
      />,
    );
    await screen.findByText('Pending', { selector: 'h3' });
    const grid = slot('dashboard-grid')!;
    expect(grid.dataset.width).toBe('fixed');
    expect(grid.style.maxWidth).toBe('1200px');
    // The filter bar and the edit bar are drawn inside it: one board.
    expect(grid.contains(slot('dashboard-filter-bar') ?? grid)).toBe(true);

    cleanup();
    render(
      <EmbeddedDashboard engine={engineWith(boardOf())} instanceId="board" />,
    );
    await waitFor(() => expect(slot('panel-title')).not.toBeNull());
    expect(slot('dashboard-grid')!.dataset.width).toBe('full');
    expect(slot('dashboard-grid')!.style.maxWidth).toBe('');
  });

  it('switches from the edit bar, in the workbench and in an embed alike, and 撤销 takes it back', async () => {
    const surfaces = [
      (engine: ViewEngine) => (
        <DashboardWorkbench
          engine={engine}
          definitionId="overview"
          instanceId="board"
        />
      ),
      (engine: ViewEngine) => (
        <EmbeddedDashboard
          engine={engine}
          instanceId="board"
          interaction="editable"
        />
      ),
    ];
    for (const surface of surfaces) {
      render(surface(engineWith(boardOf())));
      const user = userEvent.setup({ pointerEventsCheck: 0 });
      await user.click(await screen.findByRole('button', { name: /^Edit$/ }));
      const width = await screen.findByRole('group', {
        name: 'Dashboard width',
      });
      const full = screen.getByRole('button', { name: 'Full width' });
      const fixed = screen.getByRole('button', { name: 'Fixed width' });
      expect(width.contains(full)).toBe(true);
      expect(full.getAttribute('aria-pressed')).toBe('true');

      await user.click(fixed);
      expect(slot('dashboard-grid')!.dataset.width).toBe('fixed');
      expect(fixed.getAttribute('aria-pressed')).toBe('true');
      // Pressing the one in force leaves it in force.
      await user.click(fixed);
      expect(slot('dashboard-grid')!.dataset.width).toBe('fixed');

      await user.click(
        screen.getByRole('button', {
          name: 'Undo the change to the dashboard width',
        }),
      );
      expect(slot('dashboard-grid')!.dataset.width).toBe('full');
      cleanup();
    }
  });
});

/** The dashboard grid's container measures `width`; nothing else does. */
function measure(width: number) {
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(
    function (this: HTMLElement) {
      return this.dataset.slot === 'dashboard-grid' ? width : 0;
    },
  );
}

async function openGrid(config: DashboardViewConfig) {
  const engine = engineWith({ ...boardOf(), config });
  const runtime = (await engine.open('board')) as DashboardRuntime;
  const view = renderHook(() => useDashboard(runtime));
  await act(async () => {
    await nextTask();
  });
  return () => view.result.current;
}

describe('the first frame (measured before the first paint)', () => {
  it('lays a phone’s board out in one column from the first grid it draws', async () => {
    const controller = await openGrid(
      dashboardConfig({ panels: [panel({ title: 'Pending' })] }),
    );
    measure(375);
    render(<DashboardGrid dashboard={controller()} />);

    // The library's own hook starts at 1280px and measures after the
    // paint; the wide 24-column grid was handed over, and painted, first.
    expect(grids.length).toBeGreaterThan(0);
    expect(grids[0]).toEqual({ width: 375, cols: 1 });
    expect(grids.every(grid => grid.cols === 1)).toBe(true);
    expect(slot('dashboard-grid')!.dataset.narrow).toBe('true');
  });

  it('lays a wide screen’s board out at its own width from the first grid it draws', async () => {
    const controller = await openGrid(
      dashboardConfig({ panels: [panel({ title: 'Pending' })] }),
    );
    measure(1920);
    render(<DashboardGrid dashboard={controller()} />);

    expect(grids[0]).toEqual({ width: 1920, cols: 24 });
    expect(grids.some(grid => grid.width === 1280)).toBe(false);
  });

  it('keeps the library’s width where the container measures nothing', async () => {
    const controller = await openGrid(
      dashboardConfig({ panels: [panel({ title: 'Pending' })] }),
    );
    render(<DashboardGrid dashboard={controller()} />);
    // A hidden container, or a DOM without layout: drawn, as before.
    await waitFor(() => expect(slot('panel-title')).not.toBeNull());
    expect(grids[0]).toEqual({ width: 1280, cols: 24 });
  });
});
