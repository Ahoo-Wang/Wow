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
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type DashboardRuntime,
  type DashboardViewConfig,
  type FilterTree,
  type PanelLayout,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { useDashboard } from '../src/react/index.js';
import { DashboardGrid } from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { panel, pending } from './fixtures/dashboard.js';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const REGION = { name: 'region', label: 'Region', kind: 'string' };
const BOUND = [{ globalField: 'region', panelField: 'warehouse' }];
const EU: FilterTree = {
  op: 'and',
  children: [{ field: 'region', operator: 'EQ', value: 'EU' }],
};

async function settle() {
  await act(async () => {
    await new Promise(resolve => setTimeout(resolve, 0));
  });
}

async function openDashboard(
  config: DashboardViewConfig,
  instances: ViewInstance[] = [pending],
  source: ViewSource = testSource(),
) {
  const store = new MemoryViewStore({ instances });
  const engine = new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store,
    resolveSource: () => source,
    environment: testEnvironment().environment,
  });
  const instance = await store.create(
    { definitionId: 'overview', title: 'Overview', scope: 'personal', config },
    { requestId: 'r' },
  );
  const runtime = (await engine.open(instance.id)) as DashboardRuntime;
  const view = renderHook(() => useDashboard(runtime));
  await settle();
  return { controller: () => view.result.current, runtime };
}

function LiveGrid({
  runtime,
  editable = true,
}: {
  runtime: DashboardRuntime;
  editable?: boolean;
}) {
  return (
    <DashboardGrid dashboard={useDashboard(runtime)} editable={editable} />
  );
}

function layouts(runtime: DashboardRuntime): Record<string, PanelLayout> {
  return Object.fromEntries(
    runtime.getSnapshot().applied.panels.map(entry => [entry.id, entry.layout]),
  );
}

async function press(element: Element, key: string) {
  await act(async () => {
    fireEvent.keyDown(element, { key });
    await Promise.resolve();
  });
}

/** Two panels in one column, the second right under the first. */
const column = dashboardConfig({
  panels: [
    panel({ id: 'top', title: 'Top' }),
    panel({ id: 'below', title: 'Below', layout: { x: 0, y: 4, w: 6, h: 4 } }),
  ],
});

describe('placing a panel', () => {
  /**
   * R2. A placement used to go through `edit` + `apply`, which promoted the
   * whole draft — so a global filter still being composed ran the moment a
   * panel was nudged.
   */
  it('applies the placement and leaves a global filter being edited unapplied', async () => {
    const { controller, runtime } = await openDashboard(
      dashboardConfig({
        fields: [REGION],
        panels: [panel({ bindings: BOUND })],
      }),
    );

    act(() => {
      runtime.edit({ filter: EU });
      controller().place('orders', { x: 6, y: 0, w: 6, h: 4 });
    });

    expect(controller().panels[0].layout).toEqual({ x: 6, y: 0, w: 6, h: 4 });
    expect(runtime.getSnapshot().applied.filter).not.toEqual(EU);
    expect(runtime.getSnapshot().draft.filter).toEqual(EU);
  });

  /**
   * R6. A keyboard step placed one panel and nothing else, so stepping into
   * a neighbour left the two on top of each other. It goes through the
   * kernel now, which pushes the neighbour out of the way.
   */
  it('pushes a neighbour out of the way of a keyboard step', async () => {
    const { runtime } = await openDashboard(column);
    render(<LiveGrid runtime={runtime} />);

    await press(screen.getByLabelText('Move “Below”'), 'ArrowUp');

    expect(layouts(runtime)).toEqual({
      top: { x: 0, y: 7, w: 6, h: 4 },
      below: { x: 0, y: 3, w: 6, h: 4 },
    });
    // What is said is where the stepped panel went; the pushed one is on
    // screen in the order the grid reads.
    expect(
      screen.getByText('Below is at column 1, row 4, 6 columns by 4 rows'),
    ).toBeTruthy();
  });

  it('pushes a neighbour out of the way of a keyboard resize', async () => {
    const { runtime } = await openDashboard(column);
    render(<LiveGrid runtime={runtime} />);
    const corner = document.querySelector(
      '[data-panel-id="top"] [data-slot="panel-resize"]',
    ) as HTMLElement;

    await press(corner, 'ArrowDown');

    expect(layouts(runtime)).toEqual({
      top: { x: 0, y: 0, w: 6, h: 5 },
      below: { x: 0, y: 5, w: 6, h: 4 },
    });
  });

  /**
   * The pointer goes through the same rule. Left to itself the grid would
   * have let the dropped panel sit on its neighbour or traded their rows;
   * it is told panels may overlap and handed the kernel as its compactor,
   * so the drop lands on the layout the keyboard would have made.
   */
  it('pushes a neighbour out of the way of a drag, by the same rule', async () => {
    // jsdom lays nothing out, so `offsetParent` is null and the grid would
    // refuse to start a drag; every rect is at the origin, so the drag
    // starts from (0, 0) whatever the panel's stored position. The 1280px
    // container makes the sums below: 12 columns of ~107px, rows of 80px.
    vi.spyOn(HTMLElement.prototype, 'offsetParent', 'get').mockImplementation(
      function (this: HTMLElement) {
        return this.parentElement;
      },
    );
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(1280);
    const { runtime } = await openDashboard(
      dashboardConfig({
        panels: [
          panel({ id: 'left', title: 'Left' }),
          panel({
            id: 'right',
            title: 'Right',
            layout: { x: 6, y: 0, w: 6, h: 4 },
          }),
        ],
      }),
    );
    render(<LiveGrid runtime={runtime} />);
    await settle();
    const grip = document.querySelector(
      '[data-panel-id="right"] [data-slot="panel-grip"]',
    ) as HTMLElement;

    const left = document.querySelector(
      '.react-grid-item[data-panel-id="left"]',
    ) as HTMLElement;
    const resting = left.style.transform;

    // Three columns right and two rows down from the origin: onto the left
    // panel's lower half.
    fireEvent.mouseDown(grip, { clientX: 10, clientY: 10, button: 0 });
    fireEvent.mouseMove(document, { clientX: 100, clientY: 100 });
    fireEvent.mouseMove(document, { clientX: 310, clientY: 190 });
    await settle();
    // Already out of the way while the pointer is still down: the preview
    // is the kernel's placement, not the library's own collision handling.
    expect(left.style.transform).not.toBe(resting);
    expect(runtime.getSnapshot().applied.panels[0].layout.y).toBe(0);

    fireEvent.mouseUp(document, { clientX: 310, clientY: 190 });
    await settle();

    expect(layouts(runtime)).toEqual({
      left: { x: 0, y: 6, w: 6, h: 4 },
      right: { x: 3, y: 2, w: 6, h: 4 },
    });
    // Nothing overlaps on screen either: the grid shows what was applied.
    const tops = [...document.querySelectorAll('.react-grid-item')].map(
      item => (item as HTMLElement).style.transform,
    );
    expect(new Set(tops).size).toBe(2);
  });

  /**
   * R1. A panel's own error used to stop the board's apply, so a panel
   * dragged beside a broken one snapped straight back.
   */
  it('lands beside a panel in error', async () => {
    const { runtime } = await openDashboard(
      dashboardConfig({
        fields: [REGION],
        panels: [
          panel({ id: 'fine', title: 'Fine', bindings: BOUND }),
          panel({
            id: 'misbound',
            bindings: [{ globalField: 'region', panelField: 'nope' }],
            layout: { x: 6, y: 0, w: 6, h: 4 },
          }),
        ],
      }),
    );
    render(<LiveGrid runtime={runtime} />);

    await press(screen.getByLabelText('Move “Fine”'), 'ArrowDown');

    expect(layouts(runtime).fine).toEqual({ x: 0, y: 1, w: 6, h: 4 });
  });
});

/**
 * A source whose answers can be turned off and on again, for a panel that
 * had data, lost the connection, and got it back.
 */
function switchable() {
  const healthy = testSource();
  const state = { down: false };
  const answer =
    <A extends unknown[], R>(call: (...args: A) => Promise<R>) =>
    (...args: A): Promise<R> =>
      state.down ? Promise.reject(new Error('boom')) : call(...args);
  const source: ViewSource = {
    paged: vi.fn(answer(healthy.paged)),
    cursor: vi.fn(answer(healthy.cursor)),
    aggregate: vi.fn(answer(healthy.aggregate)),
  };
  return { source, state };
}

describe('a panel whose query failed', () => {
  /**
   * R5. The panel used to trade its rows for the failure, where the
   * workbenches and `EmbeddedView` keep the last result and say it is the
   * last one. It says so now in the same words, over the same rows.
   */
  it('keeps its last rows when a refresh fails, and says they are the last ones', async () => {
    const { source, state } = switchable();
    const { runtime } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
      [pending],
      source,
    );
    render(<LiveGrid runtime={runtime} editable={false} />);
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());

    state.down = true;
    await act(async () => {
      runtime.refresh();
      await Promise.resolve();
    });
    await settle();

    expect(screen.getByRole('table')).toBeTruthy();
    expect(
      screen.getByText(/boom · Showing the last successful result$/),
    ).toBeTruthy();
    expect(document.querySelector('[data-slot="panel-failed"]')).toBeNull();

    // U4: the line ends in a retry of this panel alone.
    state.down = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await settle();

    expect(screen.queryByText(/Showing the last successful result/)).toBeNull();
    expect(screen.getByRole('table')).toBeTruthy();
  });

  it('keeps its last chart when a refresh fails', async () => {
    const { source, state } = switchable();
    const { runtime } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
      [{ ...pending, config: analysisConfig({ layout: 'chart' }) }],
      source,
    );
    render(<LiveGrid runtime={runtime} editable={false} />);
    await waitFor(() =>
      expect(document.querySelector('[data-slot="skeleton"]')).toBeNull(),
    );
    const chart = document.querySelector('[role="img"]');
    expect(chart).toBeTruthy();

    state.down = true;
    await act(async () => {
      runtime.refresh();
      await Promise.resolve();
    });
    await settle();

    expect(
      screen.getByText(/boom · Showing the last successful result$/),
    ).toBeTruthy();
    expect(document.querySelector('[role="img"]')).toBeTruthy();
    expect(document.querySelector('[data-slot="panel-failed"]')).toBeNull();
  });

  /**
   * U4. With nothing behind the failure the panel says so in its body, and
   * offers to run again — that panel, not the whole board.
   */
  it('offers to retry that panel alone when there was nothing to keep', async () => {
    const { source, state } = switchable();
    state.down = true;
    const { runtime } = await openDashboard(
      dashboardConfig({
        panels: [
          panel({ id: 'first' }),
          panel({ id: 'second', layout: { x: 6, y: 0, w: 6, h: 4 } }),
        ],
      }),
      [pending],
      source,
    );
    render(<LiveGrid runtime={runtime} editable={false} />);
    await waitFor(() =>
      expect(screen.getAllByText('The query failed')).toHaveLength(2),
    );
    const calls = vi.mocked(source.paged).mock.calls.length;

    state.down = false;
    const [first] = screen.getAllByRole('button', { name: 'Try again' });
    await userEvent.click(first);
    await settle();

    expect(vi.mocked(source.paged).mock.calls.length).toBe(calls + 1);
    expect(screen.getAllByRole('table')).toHaveLength(1);
    expect(screen.getAllByText('The query failed')).toHaveLength(1);
  });

  it('offers no retry when shown without a board to retry through', async () => {
    const { source, state } = switchable();
    state.down = true;
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
      [pending],
      source,
    );
    const { DashboardPanel } = await import('../src/ui/index.js');
    render(<DashboardPanel panel={controller().panels[0]} />);

    await waitFor(() =>
      expect(screen.getByText('The query failed')).toBeTruthy(),
    );
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
  });
});
