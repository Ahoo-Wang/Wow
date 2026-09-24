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
import { blockHeight, gridBlocks } from '../src/ui/dashboard/gridBlocks.js';

/**
 * Every grid the library is handed, as it was handed it: the width it is
 * laid out at, how many columns and how tall a row. The library itself
 * still draws.
 */
const grids = vi.hoisted(
  () => [] as { width: number; cols: number; rowHeight: number }[],
);
vi.mock('react-grid-layout', async importOriginal => {
  const actual = await importOriginal<typeof GridModule>();
  const Grid = actual.default;
  function RecordingGrid(props: ComponentProps<typeof Grid>) {
    grids.push({
      width: props.width,
      cols: props.gridConfig?.cols ?? 0,
      rowHeight: props.gridConfig?.rowHeight ?? 0,
    });
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
    expect(grids[0]).toEqual({ width: 375, cols: 1, rowHeight: 80 });
    expect(grids.every(grid => grid.cols === 1)).toBe(true);
    expect(slot('dashboard-grid')!.dataset.narrow).toBe('true');
  });

  it('lays a wide screen’s board out at its own width from the first grid it draws', async () => {
    const controller = await openGrid(
      dashboardConfig({ panels: [panel({ title: 'Pending' })] }),
    );
    measure(1920);
    render(<DashboardGrid dashboard={controller()} />);

    expect(grids[0]).toEqual({ width: 1920, cols: 24, rowHeight: 80 });
    expect(grids.some(grid => grid.width === 1280)).toBe(false);
  });

  it('keeps the library’s width where the container measures nothing', async () => {
    const controller = await openGrid(
      dashboardConfig({ panels: [panel({ title: 'Pending' })] }),
    );
    render(<DashboardGrid dashboard={controller()} />);
    // A hidden container, or a DOM without layout: drawn, as before.
    await waitFor(() => expect(slot('panel-title')).not.toBeNull());
    expect(grids[0]).toEqual({ width: 1280, cols: 24, rowHeight: 80 });
  });
});

/**
 * The blocks while a board is built (the user's 2026-09-24 walk-throughs;
 * D34). The pixels are the browser story's to measure
 * (`GridBlocksWhileBuilding`); here is what jsdom can witness — the row the
 * grid is laid out at, whether the blocks are asked for, and the one row of
 * them the layer is masked with, which is the library's own sums over the
 * width the grid is drawn at.
 */
describe('the grid blocks while a board is built (D34)', () => {
  const layer = () => slot('dashboard-grid-blocks');
  /** The one row of blocks the layer is masked with, as SVG rectangles. */
  const blocksOf = (element: HTMLElement) => {
    const url = element.style.getPropertyValue('--grid-blocks');
    const svg = decodeURIComponent(
      /^url\("data:image\/svg\+xml,(.*)"\)$/.exec(url)![1],
    );
    return [...svg.matchAll(/<rect ([^>]*)\/>/g)].map(
      ([, attributes]) =>
        Object.fromEntries(
          [...attributes.matchAll(/(\w+)='([^']*)'/g)].map(([, k, v]) => [
            k,
            Number(v),
          ]),
        ) as Record<'x' | 'y' | 'width' | 'height' | 'rx', number>,
    );
  };
  /** The row of blocks drawn for a grid `width` wide, 24 columns, 80px rows. */
  const drawn = (width: number) => {
    const holder = document.createElement('div');
    const style = gridBlocks({ width, cols: 24, rowHeight: 80 })!;
    for (const [name, value] of Object.entries(style))
      holder.style.setProperty(name, String(value));
    return blocksOf(holder);
  };
  /** A column's width at `width`: the padding and 23 gaps taken off. */
  const column = (width: number) => (width - 250) / 24;

  it('cuts the 80px row into the blocks nearest a square', () => {
    // 1920px: a 70px column; one 80px block is nearer square than two 35px.
    expect(blockHeight(80, column(1920))).toBe(80);
    // 1200px: a 40px column; two 35px blocks.
    expect(blockHeight(80, column(1200))).toBe(35);
    // 776px: a 22px column; three 20px blocks.
    expect(blockHeight(80, column(776))).toBe(20);
    // However narrow, never a block of no height.
    expect(blockHeight(80, 1)).toBeGreaterThan(0);
  });

  it('lays the grid out at 80px rows at every width, read and built alike', async () => {
    const controller = await openGrid(
      dashboardConfig({ panels: [panel({ title: 'Pending' })] }),
    );
    for (const width of [776, 1200, 1280, 1920]) {
      measure(width);
      const view = render(<DashboardGrid dashboard={controller()} />);
      expect(grids[grids.length - 1]).toMatchObject({ width, rowHeight: 80 });
      view.rerender(<DashboardGrid dashboard={controller()} editable />);
      expect(grids[grids.length - 1]).toMatchObject({ width, rowHeight: 80 });
      cleanup();
    }
  });

  it('draws the blocks while built and nothing while read', async () => {
    const controller = await openGrid(
      dashboardConfig({ panels: [panel({ title: 'Pending' })] }),
    );
    measure(1200);
    const view = render(<DashboardGrid dashboard={controller()} />);
    expect(layer()).toBeNull();

    view.rerender(<DashboardGrid dashboard={controller()} editable />);
    const blocks = layer()!;
    // Under the panels, never pressed and never read out.
    expect(blocks.getAttribute('aria-hidden')).toBe('true');
    expect(blocks.parentElement).toBe(slot('dashboard-tab-panel'));
    // One row of the grid: two 40×35 blocks in each of the 24 columns, a
    // column's left edge and width the library's, the second one gap under
    // the first and ending on the row's bottom edge; repeated down from the
    // grid's padding, a row and a gap apart.
    const rects = blocksOf(blocks);
    expect(rects).toHaveLength(48);
    expect(rects[0]).toMatchObject({ x: 10, y: 0, width: 40, height: 35 });
    expect(rects[1]).toMatchObject({ x: 10, y: 45, height: 35 });
    expect(rects[2].x).toBe(Math.round(10 + column(1200) + 10));
    for (const rect of rects) expect(rect.rx).toBeGreaterThan(0);
    expect(blocks.style.getPropertyValue('--grid-blocks-size')).toBe(
      '1200px 90px',
    );
    expect(blocks.style.getPropertyValue('--grid-blocks-top')).toBe('10px');
  });

  it('follows the width the grid is drawn at, and draws none in one column', async () => {
    const controller = await openGrid(
      dashboardConfig({ panels: [panel({ title: 'Pending' })] }),
    );
    measure(1920);
    render(<DashboardGrid dashboard={controller()} editable />);
    const rects = blocksOf(layer()!);
    expect(rects).toHaveLength(24);
    expect(rects[0]).toMatchObject({ y: 0, width: 70, height: 80 });
    cleanup();

    measure(375);
    render(<DashboardGrid dashboard={controller()} editable />);
    expect(slot('dashboard-grid')!.dataset.narrow).toBe('true');
    expect(layer()).toBeNull();
  });

  it('draws the block sizes of the table in D34', () => {
    const size = (width: number) => {
      const rects = drawn(width);
      return {
        across: rects[0].width,
        down: rects[0].height,
        count: rects.length / 24,
      };
    };
    expect(size(1920)).toEqual({ across: 70, down: 80, count: 1 });
    expect(size(1401)).toEqual({ across: 48, down: 35, count: 2 });
    expect(size(1200)).toEqual({ across: 40, down: 35, count: 2 });
    expect(size(1000)).toEqual({ across: 31, down: 35, count: 2 });
    expect(size(776)).toEqual({ across: 22, down: 20, count: 3 });
    // Three blocks a row end on the row's bottom edge too.
    expect(
      drawn(776)
        .slice(0, 3)
        .map(rect => rect.y),
    ).toEqual([0, 30, 60]);
  });

  it('draws none over a board with no panel on it', async () => {
    const controller = await openGrid(dashboardConfig({ panels: [] }));
    measure(1280);
    render(<DashboardGrid dashboard={controller()} editable />);
    expect(layer()).toBeNull();
  });

  it('asks for nothing before the grid is measured', () => {
    expect(gridBlocks({ width: 0, cols: 24, rowHeight: 80 })).toBeUndefined();
    expect(gridBlocks({ width: 800, cols: 0, rowHeight: 80 })).toBeUndefined();
  });
});
