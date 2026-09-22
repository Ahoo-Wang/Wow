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
import userEvent from '@testing-library/user-event';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  builtinFieldKinds,
  MemoryViewStore,
  ViewEngine,
  defaultRuntimeEnvironment,
  withFieldKinds,
  type DashboardRuntime,
  type FieldKind,
  type DashboardPanel,
  type DashboardViewConfig,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { useDashboard, type DashboardController } from '../src/react/index.js';
import {
  arrangeLayout,
  DashboardGrid,
  DashboardWorkbench,
  PanelResizeHandle,
  ViewSurface,
} from '../src/ui/index.js';
import {
  INSTANT,
  ZONE,
  analysisConfig,
  dashboardConfig,
  deferred,
  inZone,
  namedOrdersDefinition,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { landed, tracked } from './fixtures/writes.js';

afterEach(cleanup);

/** Enough of `ResizeObserver` for the grid's width to be driven by hand. */
class ResizeObserverStub {
  /** What it was asked to watch: a panel's table also watches its own. */
  node: Element | null = null;
  constructor(private readonly callback: ResizeObserverCallback) {}
  observe(node?: Element): void {
    this.node = node ?? null;
  }
  disconnect(): void {}
  unobserve(): void {}
  resize(width: number): void {
    this.callback(
      [{ contentRect: { width } } as ResizeObserverEntry],
      this as unknown as ResizeObserver,
    );
  }
}

const pending: ViewInstance = {
  id: 'pending',
  definitionId: 'orders',
  title: 'Pending orders',
  scope: 'shared',
  revision: 'r1',
  config: recordConfig(),
};

function panel(overrides: Partial<DashboardPanel> = {}): DashboardPanel {
  return {
    id: 'orders',
    kind: 'view',
    instanceId: 'pending',
    bindings: [],
    layout: { x: 0, y: 0, w: 6, h: 4 },
    ...overrides,
  } as DashboardPanel;
}

/** Opens a dashboard and returns its controller, re-rendered as it changes. */
async function openDashboard(
  config: DashboardViewConfig,
  instances: ViewInstance[] = [pending],
  source: ViewSource = testSource(),
): Promise<{
  controller: () => DashboardController;
  runtime: DashboardRuntime;
}> {
  const store = tracked(new MemoryViewStore({ instances }));
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
  await act(async () => {
    await Promise.resolve();
  });
  return { controller: () => view.result.current, runtime };
}

describe('useDashboard', () => {
  it('reports the applied panels with their geometry', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
    );

    expect(controller().panels).toHaveLength(1);
    expect(controller().panels[0].layout).toEqual({ x: 0, y: 0, w: 6, h: 4 });
    expect(controller().panels[0].broken).toBe(false);
    expect(controller().columns).toBe(12);
  });

  it('marks a panel that cannot run as broken', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel({ instanceId: 'deleted' })] }),
    );

    expect(controller().panels[0].broken).toBe(true);
    expect(controller().panels[0].issues[0].code).toBe(
      'dashboard.panel.unavailable',
    );
  });

  it('keeps panel issues out of the dashboard own list', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel({ instanceId: 'deleted' })] }),
    );

    expect(controller().issues).toEqual([]);
  });

  it('places panels with one edit and one apply', async () => {
    const { controller, runtime } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
    );

    act(() => {
      controller().place([{ id: 'orders', x: 6, y: 2, w: 4, h: 3 }]);
    });

    expect(controller().panels[0].layout).toEqual({ x: 6, y: 2, w: 4, h: 3 });
    expect(runtime.getSnapshot().dirty).toBe(true);
  });

  it('ignores a placement that changes nothing', async () => {
    const { controller, runtime } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
    );

    act(() => {
      controller().place([{ id: 'orders', x: 0, y: 0, w: 6, h: 4 }]);
      controller().place([{ id: 'other', x: 3, y: 3, w: 3, h: 3 }]);
    });

    expect(runtime.getSnapshot().dirty).toBe(false);
  });

  it('refreshes every panel', async () => {
    const { controller, runtime } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
    );
    const child = runtime.panelRuntime('orders');
    const before = child?.getSnapshot().result;

    act(() => controller().refresh());
    await act(async () => {
      await new Promise(resolve => setTimeout(resolve, 0));
    });

    expect(child?.getSnapshot().result).not.toBe(before);
  });

  it('answers with an empty dashboard when there is no runtime', () => {
    const view = renderHook(() => useDashboard(null));

    expect(view.result.current.panels).toEqual([]);
    expect(view.result.current.resolving).toBe(false);
    expect(view.result.current.dirty).toBe(false);
    view.result.current.place([{ id: 'x', x: 0, y: 0, w: 1, h: 1 }]);
    view.result.current.refresh();
  });
});

describe('DashboardGrid', () => {
  it('renders a framed panel per panel, with the record table inside', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel({ title: 'Waiting to ship' })] }),
    );

    render(
      <ViewSurface>
        <DashboardGrid dashboard={controller()} />
      </ViewSurface>,
    );

    expect(screen.getByText('Waiting to ship')).toBeTruthy();
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
  });

  /**
   * A dashboard shows rows and offers nothing to do with a pick — no toolbar,
   * no row action, nothing anywhere that reads the selection. A checkbox
   * column here is a control that leads nowhere, and it costs a column of
   * width the panel does not have to spare.
   */
  it('renders the panel table without a selection column', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel({ title: 'Waiting to ship' })] }),
    );

    render(
      <ViewSurface>
        <DashboardGrid dashboard={controller()} />
      </ViewSurface>,
    );

    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    expect(screen.queryAllByRole('checkbox')).toEqual([]);
    // The two configured columns, and no third one holding checkboxes.
    expect(screen.getAllByRole('columnheader')).toHaveLength(2);
  });

  /**
   * A panel scrolls itself (`CardContent` is the scroll area), and the table
   * inside it must not be a second scrollport: it would be a box nothing
   * ever scrolls, and the sticky header would hold against *it* while the
   * panel moved the header off the top.
   */
  it('leaves the scrolling to the panel, so the header holds against it', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
    );

    const { container } = render(
      <ViewSurface>
        <DashboardGrid dashboard={controller()} />
      </ViewSurface>,
    );

    const area = await waitFor(() => {
      const found = container.querySelector<HTMLElement>(
        '[data-slot="record-table"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    // Not its own scrollport — the panel is what scrolls — and it says so,
    // so the workbench around it does not hand it a height.
    expect(area.hasAttribute('data-scrolls')).toBe(false);
    // The header still holds: against whatever really scrolls.
    expect(container.querySelector('thead')!.dataset.sticky).toBe('top');
  });

  /**
   * The scope label rides in the selection column when there is one. Without
   * it the row has no spare cell, so the label must land above the first
   * column rather than take a column's place — a summary that silently lost
   * its `total` / `page` word is the one mistake this row could make.
   */
  it('keeps the summary scope readable without the selection column', async () => {
    const summarised: ViewInstance = {
      ...pending,
      config: recordConfig({ summaries: [{ field: 'amount', fn: 'SUM' }] }),
    };
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
      [summarised],
    );

    const { container } = render(
      <ViewSurface>
        <DashboardGrid dashboard={controller()} />
      </ViewSurface>,
    );

    const footer = await waitFor(() => {
      const found = container.querySelector('tfoot');
      expect(found).not.toBeNull();
      return found as HTMLElement;
    });
    const rows = [...footer.querySelectorAll('tr')];
    expect(rows.map(row => row.dataset.scope)).toEqual(['page', 'total']);
    expect(footer.textContent).toContain('This page');
    expect(footer.textContent).toContain('All records');
    expect(footer.textContent).toContain('30');
    // One cell per column on each row plus the filler that takes the
    // leftover width: the footer stays aligned with the header, and the
    // scope label rides above the first column's own number rather than
    // taking a cell the row does not have.
    for (const row of rows) expect(row.querySelectorAll('td')).toHaveLength(3);
    expect(rows[0].cells[0].textContent).toContain('This page');
  });

  /**
   * The panels come from `applied`, which for a dashboard read out of a store
   * is whatever that store held — including a config admission refused. One
   * bad panel must be the only thing that goes missing, and it must go missing
   * rather than render.
   */
  it('keeps a panel admission refused off the screen', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({
        panels: [
          panel({ title: 'Waiting to ship' }),
          {
            id: 'links',
            kind: 'links',
            title: 'Elsewhere',
            layout: { x: 6, y: 0, w: 6, h: 4 },
            items: [{ label: 'Payroll', href: 'javascript:alert(1)' }],
          } as DashboardPanel,
        ],
      }),
    );

    render(
      <ViewSurface>
        <DashboardGrid dashboard={controller()} />
      </ViewSurface>,
    );

    // The healthy panel still runs.
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    // The refused one reports itself instead of rendering its link.
    expect(screen.getByText('Elsewhere')).toBeTruthy();
    expect(screen.queryByText('Payroll')).toBeNull();
    expect(
      screen.getByText(/Only http, https, mailto and relative links/i),
    ).toBeTruthy();
  });

  it('falls back to the panel id when it has no title', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
    );

    render(<DashboardGrid dashboard={controller()} />);

    expect(screen.getByText('orders')).toBeTruthy();
  });

  it('shows an analysis panel as a chart', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
      [
        {
          ...pending,
          config: analysisConfig({ layout: 'chart' }),
        },
      ],
    );

    render(<DashboardGrid dashboard={controller()} />);

    await waitFor(() =>
      expect(document.querySelector('[data-slot="chart"]')).toBeTruthy(),
    );
  });

  it('says so when a panel is unavailable and leaves the rest alone', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({
        panels: [
          panel({ id: 'gone', instanceId: 'deleted' }),
          panel({ id: 'here', layout: { x: 6, y: 0, w: 6, h: 4 } }),
        ],
      }),
    );

    render(<DashboardGrid dashboard={controller()} />);

    expect(screen.getByText('This panel is unavailable')).toBeTruthy();
    // The finding is said once, in the body where the view would have been;
    // the header marker is for a panel that runs with a caveat.
    expect(document.querySelector('[data-slot="panel-warning"]')).toBeNull();
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
  });

  /**
   * The saved view a panel shows carries a warning of its own — a simple-mode
   * config holding an OR tree — and the runtime hands it to the panel. What
   * the grid owes it: the view shows as it would anyway, the caveat sits in
   * the header, and the body does not pretend the panel is out.
   */
  it('shows a panel that runs with a warning, and wears it in the header', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel({ title: 'Pending' })] }),
      [
        {
          ...pending,
          config: recordConfig({
            filterMode: 'simple',
            filter: {
              op: 'or',
              children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
            },
          }),
        },
      ],
    );

    render(<DashboardGrid dashboard={controller()} />);

    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    const card = document.querySelector('[data-slot="dashboard-panel"]');
    expect(card?.hasAttribute('data-warning')).toBe(true);
    expect(
      screen.getByRole('button', {
        name: 'These conditions need the advanced editor to be shown in full.',
      }),
    ).toBeTruthy();
    expect(screen.queryByText('This panel is unavailable')).toBeNull();
  });

  /**
   * A saved view can carry both. The body shows why the panel is out, and
   * the warning beside that error was dropped with it; it belongs in the
   * header, where a running panel would wear it.
   */
  it('keeps a broken panel warning in the header beside the reason in the body', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel({ title: 'Pending' })] }),
      [
        {
          ...pending,
          config: recordConfig({
            filterMode: 'simple',
            filter: {
              op: 'or',
              children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
            },
            pageSize: 0,
          }),
        },
      ],
    );

    render(<DashboardGrid dashboard={controller()} />);

    expect(screen.getByText('This panel is unavailable')).toBeTruthy();
    expect(screen.getByText(/page size must be a positive/)).toBeTruthy();
    expect(
      screen.getByRole('button', {
        name: 'These conditions need the advanced editor to be shown in full.',
      }),
    ).toBeTruthy();
  });

  it('offers the placing controls only when the layout may be edited', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
    );
    const slot = (name: string) =>
      document.querySelector(`[data-slot="${name}"]`);

    const { rerender } = render(<DashboardGrid dashboard={controller()} />);
    expect(slot('panel-grip')).toBeNull();
    expect(slot('panel-arrange')).toBeNull();
    // The library draws its corner whatever `enabled` says, so a read-only
    // dashboard gets the ornament rather than a control nothing answers.
    expect(slot('panel-resize')).toBeNull();
    expect(
      document
        .querySelector('.react-resizable-handle')
        ?.getAttribute('aria-hidden'),
    ).toBe('true');

    rerender(<DashboardGrid dashboard={controller()} editable />);
    // All three are named now: each one answers the arrows or says the
    // commands in words, so announcing them offers something reachable.
    expect(screen.getByLabelText('Move orders with the arrow keys')).toBe(
      slot('panel-grip'),
    );
    expect(screen.getByLabelText('Place orders')).toBe(slot('panel-arrange'));
    expect(screen.getByLabelText('Resize this panel with the arrow keys')).toBe(
      slot('panel-resize'),
    );
  });

  /**
   * `react-grid-layout` 2.2 has no keyboard sensor — the drag is
   * `react-draggable`'s and the corner is `react-resizable`'s, and neither
   * listens for a key. So the keyboard equivalent is commands of our own,
   * and they land in the same place a gesture does: one `place`, which is
   * one edit and one apply (D17.7).
   *
   * The grid is rendered over a live controller here rather than the one
   * `openDashboard` captured, so the second press sees what the first did.
   */
  describe('placed by keyboard', () => {
    function LiveGrid({ runtime }: { runtime: DashboardRuntime }) {
      return <DashboardGrid dashboard={useDashboard(runtime)} editable />;
    }

    async function press(element: Element, key: string) {
      await act(async () => {
        fireEvent.keyDown(element, { key });
        await Promise.resolve();
      });
    }

    it('moves a panel with the arrows on its grip', async () => {
      const { controller, runtime } = await openDashboard(
        dashboardConfig({ panels: [panel()] }),
      );
      render(<LiveGrid runtime={runtime} />);
      const grip = screen.getByLabelText('Move orders with the arrow keys');

      // Nothing to the left of the first column, so the press is swallowed
      // rather than writing a layout the kernel would refuse.
      await press(grip, 'ArrowLeft');
      expect(controller().panels[0].layout).toEqual({ x: 0, y: 0, w: 6, h: 4 });
      expect(runtime.getSnapshot().dirty).toBe(false);

      // Every other key is the page's: a grip that swallowed Tab or Enter
      // would be a trap in the middle of the header.
      await press(grip, 'Enter');
      expect(runtime.getSnapshot().dirty).toBe(false);

      await press(grip, 'ArrowRight');
      await press(grip, 'ArrowDown');

      expect(controller().panels[0].layout).toEqual({ x: 1, y: 1, w: 6, h: 4 });
      expect(runtime.getSnapshot().dirty).toBe(true);
      // A pointer watches the panel move; a keyboard is told where it went.
      expect(
        screen.getByText('orders is at column 2, row 2, 6 columns by 4 rows'),
      ).toBeTruthy();
    });

    it('resizes a panel with the arrows on its corner', async () => {
      const { controller, runtime } = await openDashboard(
        dashboardConfig({ panels: [panel()] }),
      );
      render(<LiveGrid runtime={runtime} />);
      const corner = screen.getByLabelText(
        'Resize this panel with the arrow keys',
      );

      await press(corner, 'Enter');
      expect(runtime.getSnapshot().dirty).toBe(false);

      await press(corner, 'ArrowRight');
      await press(corner, 'ArrowDown');
      await press(corner, 'ArrowUp');

      expect(controller().panels[0].layout).toEqual({ x: 0, y: 0, w: 7, h: 4 });
      expect(runtime.getSnapshot().dirty).toBe(true);
    });

    /**
     * The corner is appended by the library inside the grid item, out of
     * reach of any context of ours, so it reads the panel back off the
     * item's `data-panel-id`. Rendered anywhere else there is no panel to
     * name, and it says nothing rather than guessing at the first one.
     */
    it('does nothing when the corner is not inside a grid item', async () => {
      const onStep = vi.fn();
      render(<PanelResizeHandle axis="se" ref={null} onStep={onStep} />);

      await press(
        screen.getByLabelText('Resize this panel with the arrow keys'),
        'ArrowRight',
      );

      expect(onStep).not.toHaveBeenCalled();
    });

    it('says the same commands in words, and greys out the ones with no room', async () => {
      const { controller, runtime } = await openDashboard(
        dashboardConfig({ panels: [panel()] }),
      );
      render(<LiveGrid runtime={runtime} />);

      await userEvent.click(screen.getByLabelText('Place orders'));
      // At the top left corner of the grid there is nowhere to go but away
      // from it, and the entries that would leave it are disabled rather
      // than missing: where a panel can go is this moment, not a permission.
      expect(
        screen.getByRole('menuitem', { name: 'Move up' }).ariaDisabled,
      ).toBe('true');
      expect(
        screen.getByRole('menuitem', { name: 'Move left' }).ariaDisabled,
      ).toBe('true');
      expect(
        screen.getByRole('menuitem', { name: 'Wider' }).ariaDisabled,
      ).toBeNull();

      await userEvent.click(screen.getByRole('menuitem', { name: 'Taller' }));

      expect(controller().panels[0].layout).toEqual({ x: 0, y: 0, w: 6, h: 5 });
    });

    /**
     * One step is one cell, and the bounds are the kernel's, so no command
     * can produce a layout `validateDashboard` would refuse. Down and
     * taller have no far edge — a dashboard grows downwards.
     */
    describe('arrangeLayout', () => {
      const here = { x: 1, y: 1, w: 2, h: 2 };

      it.each([
        ['left', { ...here, x: 0 }],
        ['right', { ...here, x: 2 }],
        ['up', { ...here, y: 0 }],
        ['down', { ...here, y: 2 }],
        ['wider', { ...here, w: 3 }],
        ['narrower', { ...here, w: 1 }],
        ['taller', { ...here, h: 3 }],
        ['shorter', { ...here, h: 1 }],
      ] as const)('takes one cell %s', (step, landed) => {
        expect(arrangeLayout(here, step, 12)).toEqual(landed);
      });

      it.each([
        ['left', { x: 0, y: 0, w: 1, h: 1 }],
        ['up', { x: 0, y: 0, w: 1, h: 1 }],
        ['right', { x: 11, y: 0, w: 1, h: 1 }],
        ['wider', { x: 11, y: 0, w: 1, h: 1 }],
        ['narrower', { x: 0, y: 0, w: 1, h: 1 }],
        ['shorter', { x: 0, y: 0, w: 1, h: 1 }],
      ] as const)('has no room to go %s', (step, cornered) => {
        expect(arrangeLayout(cornered, step, 12)).toBeNull();
      });
    });
  });

  /**
   * The grid library computes a layout of its own on mount and on every prop
   * change; a stored layout with a gap above a panel is exactly the kind it
   * would rewrite. What the store holds is what is shown, and nothing writes
   * geometry back until a hand has moved a panel.
   */
  describe('with a stored layout the grid would have compacted', () => {
    const gapped = dashboardConfig({
      panels: [panel({ layout: { x: 0, y: 3, w: 6, h: 4 } })],
    });

    async function settle() {
      await act(async () => {
        await new Promise(resolve => setTimeout(resolve, 0));
      });
    }

    it('leaves a read-only dashboard clean and its panels unrun again', async () => {
      const source = testSource();
      const { controller, runtime } = await openDashboard(
        gapped,
        [pending],
        source,
      );

      render(<DashboardGrid dashboard={controller()} editable={false} />);
      await settle();

      expect(runtime.getSnapshot().dirty).toBe(false);
      expect(controller().panels[0].layout).toEqual({
        x: 0,
        y: 3,
        w: 6,
        h: 4,
      });
      // Opening ran each panel once; nothing re-applied the dashboard.
      expect(source.paged).toHaveBeenCalledTimes(1);
    });

    it('leaves an editable dashboard clean until a panel is moved', async () => {
      const source = testSource();
      const { controller, runtime } = await openDashboard(
        gapped,
        [pending],
        source,
      );

      render(<DashboardGrid dashboard={controller()} editable />);
      await settle();

      expect(runtime.getSnapshot().dirty).toBe(false);
      expect(source.paged).toHaveBeenCalledTimes(1);
    });

    it('writes the geometry back once a drag ends', async () => {
      // jsdom lays nothing out, so `offsetParent` is null and the grid would
      // refuse to start a drag; every rect is at the origin, so the drag
      // starts from (0, 0) whatever the panel's stored position. It also
      // reports no width, and a column of no width is one a drag can never
      // cross, so the container is given the 1280px the sums below assume.
      vi.spyOn(HTMLElement.prototype, 'offsetParent', 'get').mockImplementation(
        function (this: HTMLElement) {
          return this.parentElement;
        },
      );
      vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(
        1280,
      );
      const { controller, runtime } = await openDashboard(gapped);
      render(<DashboardGrid dashboard={controller()} editable />);
      await settle();
      const grip = document.querySelector(
        '[data-slot="panel-grip"]',
      ) as HTMLElement;

      // A drag as the pointer makes one: down on the grip, movement on the
      // document, up. The grid is 1280px over 12 columns of 80px rows, so
      // 300px right and 180px down is three columns and two rows.
      fireEvent.mouseDown(grip, { clientX: 10, clientY: 10, button: 0 });
      fireEvent.mouseMove(document, { clientX: 100, clientY: 100 });
      fireEvent.mouseMove(document, { clientX: 310, clientY: 190 });
      fireEvent.mouseUp(document, { clientX: 310, clientY: 190 });
      await settle();

      expect(runtime.getSnapshot().dirty).toBe(true);
      expect(controller().panels[0].layout).toEqual({
        x: 3,
        y: 2,
        w: 6,
        h: 4,
      });
    });
  });

  it('shows a skeleton until a panel has data', async () => {
    const waiting = deferred<{ total: number; list: [] }>();
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
      [pending],
      testSource({ paged: () => waiting.promise }),
    );

    render(<DashboardGrid dashboard={controller()} />);

    expect(screen.queryByRole('table')).toBeNull();
    expect(document.querySelector('[data-slot="skeleton"]')).toBeTruthy();
  });

  it('says so when a panel query fails, instead of loading forever', async () => {
    const broken = testSource({
      paged: () => Promise.reject(new Error('boom')),
    });
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
      [pending],
      broken,
    );

    render(<DashboardGrid dashboard={controller()} />);

    await waitFor(() =>
      expect(screen.getByText('The query failed')).toBeTruthy(),
    );
    // The failure replaced the loading skeleton rather than joining it.
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('says so when an analysis panel query fails', async () => {
    const broken = testSource({
      aggregate: () => Promise.reject(new Error('boom')),
    });
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
      [{ ...pending, config: analysisConfig({ layout: 'chart' }) }],
      broken,
    );

    render(<DashboardGrid dashboard={controller()} />);

    await waitFor(() =>
      expect(screen.getByText('The query failed')).toBeTruthy(),
    );
    expect(document.querySelector('[data-slot="skeleton"]')).toBeNull();
  });

  it('follows the container width once it can be measured', async () => {
    const observers: ResizeObserverStub[] = [];
    vi.stubGlobal(
      'ResizeObserver',
      class extends ResizeObserverStub {
        constructor(callback: ResizeObserverCallback) {
          super(callback);
          observers.push(this);
        }
      },
    );
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
    );

    render(<DashboardGrid dashboard={controller()} />);
    const item = document.querySelector('.react-grid-item') as HTMLElement;
    const initial = item.style.width;

    // A record panel's table watches cells of its own — it measures where a
    // pinned column ends — so the grid's observer is named by the element it
    // actually watches rather than by the order it was made in, or by which
    // tags some other component happens to use this week.
    const grid = observers.find(
      observer => observer.node !== null && !observer.node.closest('table'),
    );
    act(() => grid!.resize(600));

    // The measuring is `react-grid-layout`'s own `useContainerWidth`, which
    // coalesces a burst of measurements into one animation frame, so the new
    // width lands on the next paint rather than on the call itself.
    await waitFor(() => expect(item.style.width).not.toBe(initial));
    const measured = item.style.width;

    // A measurement of zero is what a hidden container reports; the panels
    // keep the last width they could be drawn at. The old hand-written hook
    // dropped the zero before it reached the grid, the library's passes it
    // on and the grid declines it — the panels are the same either way.
    act(() => grid!.resize(0));
    await act(() => new Promise(resolve => setTimeout(resolve, 20)));

    expect(item.style.width).toBe(measured);
    vi.unstubAllGlobals();
  });

  it('invites the user to add a panel when there are none', async () => {
    const { controller } = await openDashboard(dashboardConfig());

    render(<DashboardGrid dashboard={controller()} />);

    expect(screen.getByText('No panels yet')).toBeTruthy();
  });

  it('renders a content panel without a query', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({
        panels: [panel({ kind: 'markdown', content: '# Weekly review' })],
      }),
    );

    render(<DashboardGrid dashboard={controller()} />);

    expect(screen.getByRole('heading', { name: 'Weekly review' })).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });
});

describe('DashboardWorkbench', () => {
  const overview: ViewInstance = {
    id: 'overview-1',
    definitionId: 'overview',
    title: 'Operations',
    scope: 'personal',
    revision: '1',
    config: dashboardConfig({
      fields: [{ name: 'region', label: 'Region', kind: 'string' }],
      panels: [
        panel({
          title: 'Pending',
          bindings: [{ globalField: 'region', panelField: 'warehouse' }],
        }),
      ],
    }),
  };

  function setup(instance: ViewInstance = overview) {
    const source = testSource();
    const store = tracked(
      new MemoryViewStore({ instances: [pending, instance] }),
    );
    const engine = new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store,
      resolveSource: () => source,
    });
    return { engine, source, store };
  }

  it('opens a dashboard, shows its panels and its global filter', async () => {
    const user = userEvent.setup();
    const { engine } = setup();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );

    await waitFor(() => expect(screen.getByText('Pending')).toBeTruthy());
    // Refresh moved into the title bar with the save commands; the row of
    // buttons between the editor and the panels is gone.
    const header = document.querySelector(
      '[data-slot="view-header"]',
    ) as HTMLElement;
    expect(
      within(header).getByRole('button', { name: 'Refresh' }),
    ).toBeTruthy();
    expect(header.textContent).toContain('Operations');
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());

    // The global filter lives in the title bar's fold now, and a saved view
    // opens folded: the panels are what the dashboard is for, so nothing of
    // the editor is on the page until the handle beside the name asks for
    // it.
    expect(screen.queryByRole('button', { name: /Apply/ })).toBeNull();

    await user.click(
      within(header).getByRole('button', { name: 'Filter · Simple' }),
    );

    expect(screen.getByRole('button', { name: /Apply/ })).toBeTruthy();
  });

  /**
   * Nothing reloads a pin: once the manager deletes the pinned view, the
   * engine disposes the runtime and reopening the id answers "no such view"
   * for as long as the page is open, so the workbench has to let go itself.
   */
  it('moves on to the view that is still there once the open one is deleted', async () => {
    const other: ViewInstance = {
      ...overview,
      id: 'overview-2',
      title: 'Night shift',
    };
    const store = tracked(
      new MemoryViewStore({
        instances: [pending, overview, other],
      }),
    );
    const engine = new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store,
      resolveSource: () => testSource(),
    });

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Operations' }).ariaCurrent,
      ).toBe('true'),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Manage views' }));
    const manager = await screen.findByRole('dialog');
    const row = Array.from(
      manager.querySelectorAll('[data-slot="view-manager-row"]'),
    ).find(candidate =>
      candidate.textContent?.includes('Operations'),
    ) as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: 'Delete' }));
    const confirm = await screen.findByRole('alertdialog');
    fireEvent.click(within(confirm).getByRole('button', { name: 'Delete' }));
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });

    await waitFor(
      () =>
        expect(screen.queryByRole('button', { name: 'Operations' })).toBeNull(),
      { timeout: 3000 },
    );
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Night shift' }).ariaCurrent,
      ).toBe('true'),
    );
  });

  /**
   * A dashboard has no result of its own — every panel runs its own query —
   * so the bar over the panels reads the applied config rather than a
   * result's, and shows once any panel has been asked. Reading a result that
   * is always null, it never showed at all.
   */
  it('says what the panels were asked under', async () => {
    const { engine } = setup({
      ...overview,
      config: dashboardConfig({
        fields: [{ name: 'region', label: 'Region', kind: 'string' }],
        filter: {
          op: 'and',
          children: [{ field: 'region', operator: 'EQ', value: 'north' }],
        },
        panels: [
          panel({
            title: 'Pending',
            bindings: [{ globalField: 'region', panelField: 'warehouse' }],
          }),
        ],
      }),
    });

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());

    const bar = screen.getByRole('region', { name: 'Showing' });
    expect(bar.textContent).toContain('Region');
    expect(bar.textContent).toContain('north');
  });

  it('says so plainly when the panels were asked under nothing', async () => {
    const { engine } = setup();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());

    expect(
      screen.getByRole('region', { name: 'Showing' }).textContent,
    ).toContain('All records');
  });

  /**
   * The title bar is the one place a dashboard is saved from now, and what
   * lands there has to reach the sidebar and the open view alike.
   */
  it('saves a copy from the title bar and opens it', async () => {
    const { engine, store } = setup();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'More view actions' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Save as' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'Night shift' },
    });
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create view' }),
    );

    await landed(store);
    expect((await store.list('overview')).map(item => item.title)).toContain(
      'Night shift',
    );
    await waitFor(() =>
      expect(
        within(screen.getByRole('navigation')).getByRole('button', {
          name: 'Night shift',
        }).ariaCurrent,
      ).toBe('true'),
    );
  });

  /**
   * A copy is judged for the audience it is headed for, not the one the open
   * view sits in: `engine.saveAs` validates at the target scope. Gating the
   * dialog on the open draft's own validity therefore answered the wrong
   * question in both directions — it let through a copy the engine would
   * refuse, and it barred a copy that is the way out of an invalid view.
   */
  describe('copying a dashboard to another audience', () => {
    /** A personal record view: a shared dashboard may not show it. */
    const personalPanel: ViewInstance = {
      ...pending,
      id: 'mine',
      title: 'My orders',
      scope: 'personal',
    };

    function board(scope: ViewInstance['scope']): ViewInstance {
      return {
        ...overview,
        scope,
        config: dashboardConfig({
          panels: [panel({ title: 'Mine', instanceId: 'mine' })],
        }),
      };
    }

    function workbench(instance: ViewInstance) {
      const store = tracked(
        new MemoryViewStore({
          instances: [personalPanel, instance],
        }),
      );
      const engine = new ViewEngine({
        definitions: [ordersDefinition(), overviewDefinition()],
        store,
        resolveSource: () => testSource(),
      });
      render(
        <DashboardWorkbench
          engine={engine}
          definitionId="overview"
          instanceId="overview-1"
        />,
      );
      return { store };
    }

    /** The save-as dialog, opened from the title bar's menu. */
    async function openCopy() {
      fireEvent.click(
        await screen.findByRole('button', { name: 'More view actions' }),
      );
      fireEvent.click(await screen.findByRole('menuitem', { name: 'Save as' }));
      return screen.findByRole('dialog');
    }

    it('shows the refusal inside the dialog and stays open', async () => {
      // Valid where it is: a personal dashboard may show a personal view.
      const { store } = workbench(board('personal'));
      await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());

      const dialog = await openCopy();
      fireEvent.change(within(dialog).getByLabelText('Title'), {
        target: { value: 'For everyone' },
      });
      fireEvent.click(within(dialog).getByRole('radio', { name: 'Everyone' }));
      const create = within(dialog).getByRole('button', {
        name: 'Create view',
      });
      // The draft is valid, so nothing here can tell it will be refused —
      // only the engine knows, at the scope the copy is headed for.
      expect(create).toHaveProperty('disabled', false);
      fireEvent.click(create);

      expect(
        await within(dialog).findByText(
          'Fix what this view reports before saving it.',
        ),
      ).toBeDefined();
      // Said where the user is still looking, with the answer still theirs.
      expect(screen.getByRole('dialog')).toBe(dialog);
      expect(
        (await store.list('overview')).map(item => item.title),
      ).not.toContain('For everyone');
    });

    it('takes a copy that is valid where the open view is not', async () => {
      // Saved shared over a personal panel: the view on screen reports an
      // error, and a personal copy of it is exactly the way out.
      const { store } = workbench(board('shared'));
      await screen.findByRole('button', { name: 'More view actions' });
      // One finding, so the strip says it outright rather than heading it.
      expect(
        (await screen.findAllByRole('alert')).some(alert =>
          (alert.textContent ?? '').includes(
            'A shared dashboard cannot show a personal view',
          ),
        ),
      ).toBe(true);

      const dialog = await openCopy();
      fireEvent.change(within(dialog).getByLabelText('Title'), {
        target: { value: 'Mine after all' },
      });
      fireEvent.click(within(dialog).getByRole('radio', { name: 'Only me' }));
      fireEvent.click(
        within(dialog).getByRole('button', { name: 'Create view' }),
      );

      await landed(store);
      expect((await store.list('overview')).map(item => item.title)).toContain(
        'Mine after all',
      );
    });
  });

  // Its own alerts render above the provider of the surface it draws, yet
  // must read the wording it was handed, as everything inside that surface does.
  it("takes the host's wording, for its own alerts and everything inside", async () => {
    const { engine } = setup();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="missing"
        messages={{
          'label.view.unopenable': '打不开这个视图',
          'label.scope.group.personal': '仅自己',
        }}
      />,
    );

    expect(await screen.findByText('打不开这个视图')).toBeDefined();
    // The sidebar is named by the definition rather than by the catalogue;
    // what it says about itself still comes from the wording handed in.
    expect(screen.getByRole('navigation', { name: 'Overview' })).toBeDefined();
    expect(screen.getByText('仅自己')).toBeDefined();
  });

  /** A dashboard whose one panel shows `config` over the named orders. */
  function named(config: ViewInstance['config']) {
    return new ViewEngine({
      definitions: [namedOrdersDefinition(), overviewDefinition()],
      store: tracked(
        new MemoryViewStore({
          instances: [
            { ...pending, config },
            {
              ...overview,
              config: dashboardConfig({
                panels: [panel({ title: 'Pending' })],
              }),
            },
          ],
        }),
      ),
      resolveSource: () =>
        testSource({
          paged: () =>
            Promise.resolve({
              total: 1,
              list: [{ id: 'o-1', createdAt: INSTANT }],
            }),
        }),
      environment: defaultRuntimeEnvironment({ timeZone: ZONE }),
    });
  }

  it("shows a panel's times on the clock of the engine's zone, in the language given", async () => {
    const engine = named(
      recordConfig({
        table: { columns: [{ field: 'id' }, { field: 'createdAt' }] },
      }),
    );

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
        locale="en-GB"
      />,
    );

    expect(await screen.findByText(inZone(INSTANT))).toBeTruthy();
  });

  it("names a chart panel's categories as their field names its values", async () => {
    // The table shows only the count; the chart still groups by warehouse,
    // and names its bars through the schema rather than the table's columns.
    const engine = named(
      analysisConfig({
        layout: 'chart',
        table: { columns: [{ alias: 'orders' }] },
      }),
    );

    // Recharts measures text in a span of its own on the body.
    const { container } = render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );

    // The drawing alone: the `sr-only` reading beside every chart says the
    // same category again, and what it says is pinned in
    // `test/analysisChartA11y.test.tsx`.
    expect(
      await within(container).findByText('China', {
        ignore: 'script, style, [data-slot="chart-reading"] *',
      }),
    ).toBeTruthy();
  });

  it('runs the panels again on demand', async () => {
    const { engine, source } = setup();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );
    await waitFor(() => expect(source.paged).toHaveBeenCalledTimes(1));

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));

    await waitFor(() => expect(source.paged).toHaveBeenCalledTimes(2));
  });

  it('leaves the filter out when the dashboard declares no global fields', async () => {
    const { engine } = setup({
      ...overview,
      config: dashboardConfig({ panels: [panel({ title: 'Pending' })] }),
    });

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );

    await waitFor(() => expect(screen.getByText('Pending')).toBeTruthy());
    expect(screen.queryByRole('button', { name: /Apply/ })).toBeNull();
  });

  it('says what needs fixing before the dashboard can run', async () => {
    const { engine, source } = setup({
      ...overview,
      config: dashboardConfig({
        fields: [{ name: 'not a field', label: 'Broken', kind: 'string' }],
        panels: [panel({ title: 'Pending' })],
      }),
    });

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );

    // The strip is a line, and one finding is that line: said outright
    // rather than headed and folded away behind a count of one (F-14).
    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'not a field is not a usable field name.',
      ),
    );
    expect(screen.queryByRole('button', { name: '1 more' })).toBeNull();
    // Nothing ran: an error blocks the apply that would have created panels.
    expect(source.paged).not.toHaveBeenCalled();
  });

  it('reports a dashboard it cannot open', async () => {
    const { engine } = setup();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="missing"
      />,
    );

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'This view no longer exists.',
      ),
    );
  });

  /**
   * A warning about the dashboard itself is the workbench's to say; a
   * warning about one panel is that panel's, in its own frame, and saying it
   * twice would only make the notice longer than the caveat. Neither blocks:
   * the healthy panel still runs, and nothing asks for a fix.
   */
  it('notes what is worth noting about the dashboard, and leaves panel findings to the panels', async () => {
    const { engine } = setup({
      ...overview,
      config: dashboardConfig({
        fields: [{ name: 'region', label: 'Region', kind: 'string' }],
        // A simple-mode config holding an OR tree: the advanced editor opens
        // and the kernel warns.
        filterMode: 'simple',
        filter: {
          op: 'or',
          children: [{ field: 'region', operator: 'EQ', value: 'CN' }],
        },
        panels: [
          panel({
            title: 'Pending',
            bindings: [{ globalField: 'region', panelField: 'warehouse' }],
          }),
          panel({
            id: 'gone',
            title: 'Gone',
            instanceId: 'deleted',
            layout: { x: 6, y: 0, w: 6, h: 4 },
          }),
        ],
      }),
    });

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );

    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    const notice = document.querySelector(
      '[data-slot="status-strip"][data-tone="warning"]',
    );
    expect(notice?.textContent).toContain('advanced editor');
    expect(notice?.textContent).not.toContain('unavailable');
    expect(screen.getByText('This panel is unavailable')).toBeTruthy();
    expect(screen.queryByText(/needs fixing/)).toBeNull();
  });

  /**
   * A global condition mapped onto a panel field that warns is, before
   * Apply, a finding under `['panels', …]` that the applied panels do not
   * carry and the dashboard's own list leaves out, so nothing showed it —
   * and Save would have persisted it unseen. Until Apply hands it to the
   * panel, the notice says it; once the panel wears it, the notice lets go.
   */
  it('says a draft panel warning no panel carries yet, until apply hands it over', async () => {
    // Warns on the panel's field only, so the finding exists at panel level
    // and nowhere else: the dashboard's own validation of the global
    // condition has nothing to say about it.
    const rounded: FieldKind = {
      id: 'rounded',
      operators: ['EQ'],
      defaultOperator: 'EQ',
      emptyValue: () => null,
      validate: ({ value, field, path }) =>
        field.name === 'mass' &&
        typeof value === 'number' &&
        !Number.isInteger(value)
          ? [{ code: 'filter.value.rounded', severity: 'warning', path }]
          : [],
      compile: ({ leaf, field }) => ({
        op: FilterOperator.EQ,
        field: field.name,
        value: Math.round(leaf.value as number),
      }),
      editor: () => ({ input: 'number' }),
      describe: ({ leaf, field }) => ({
        text: `${field.label} = ${String(leaf.value)}`,
        value: { kind: 'text', value: String(leaf.value) },
      }),
    };
    const orders = ordersDefinition();
    const engine = new ViewEngine({
      definitions: [
        {
          ...orders,
          fields: [
            ...orders.fields,
            { name: 'mass', label: 'Mass', kind: 'rounded' },
          ],
        },
        overviewDefinition(),
      ],
      store: tracked(
        new MemoryViewStore({
          instances: [
            pending,
            {
              ...overview,
              config: dashboardConfig({
                fields: [{ name: 'weight', label: 'Weight', kind: 'rounded' }],
                panels: [
                  panel({
                    title: 'Pending',
                    bindings: [{ globalField: 'weight', panelField: 'mass' }],
                  }),
                ],
              }),
            },
          ],
        }),
      ),
      resolveSource: () => testSource(),
      kinds: withFieldKinds(builtinFieldKinds, [rounded]),
    });

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    const notice = () =>
      document.querySelector('[data-slot="status-strip"][data-tone="warning"]');
    const marker = () => document.querySelector('[data-slot="panel-warning"]');
    expect(notice()).toBeNull();
    const runtime = engine
      .openRuntimes()
      .find(opened => opened.kind === 'dashboard') as DashboardRuntime;

    act(() =>
      runtime.edit({
        filter: {
          op: 'and',
          children: [{ field: 'weight', operator: 'EQ', value: 2.5 }],
        },
      }),
    );

    await waitFor(() =>
      expect(notice()?.textContent).toContain('filter.value.rounded'),
    );
    expect(marker()).toBeNull();

    act(() => runtime.apply());

    await waitFor(() => expect(marker()).toBeTruthy());
    expect(notice()).toBeNull();
  });
});
