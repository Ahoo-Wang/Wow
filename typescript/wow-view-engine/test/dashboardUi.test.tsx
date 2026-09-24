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
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  arrangePanel,
  MemoryViewStore,
  ViewEngine,
  type DashboardRuntime,
  type DashboardPanel,
  type DashboardViewConfig,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { useDashboard, type DashboardController } from '../src/react/index.js';
import {
  DashboardGrid,
  PanelResizeHandle,
  ViewSurface,
} from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  deferred,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { tracked } from './fixtures/writes.js';
import { panel, pending } from './fixtures/dashboard.js';
import { settle } from './fixtures/ui.js';

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

async function openDashboard(
  config: DashboardViewConfig,
  instances: ViewInstance[] = [pending],
  source: ViewSource = testSource(),
  scope: ViewInstance['scope'] = 'personal',
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
    { definitionId: 'overview', title: 'Overview', scope, config },
    { requestId: 'r' },
  );
  const runtime = (await engine.open(instance.id)) as DashboardRuntime;
  const view = renderHook(() => useDashboard(runtime));
  await act(async () => {
    await Promise.resolve();
  });
  return { controller: () => view.result.current, runtime };
}

/**
 * A board being built: a placement is an edit, taken only while the board
 * is (R3b), as every other one is.
 */
async function openBuilding(...args: Parameters<typeof openDashboard>) {
  const opened = await openDashboard(...args);
  act(() => opened.runtime.setBuilding(true));
  return opened;
}

describe('useDashboard', () => {
  it('reports the applied panels with their geometry', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
    );

    expect(controller().panels).toHaveLength(1);
    expect(controller().panels[0].layout).toEqual({ x: 0, y: 0, w: 6, h: 4 });
    expect(controller().panels[0].broken).toBe(false);
    expect(controller().columns).toBe(24);
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

  it('places a panel and applies the placement', async () => {
    const { controller, runtime } = await openBuilding(
      dashboardConfig({ panels: [panel()] }),
    );

    act(() => {
      controller().place('orders', { x: 6, y: 2, w: 4, h: 3 });
    });

    // Nothing above it, so it rises to the top of its column.
    expect(controller().panels[0].layout).toEqual({ x: 6, y: 0, w: 4, h: 3 });
    expect(runtime.getSnapshot().dirty).toBe(true);
  });

  it('ignores a placement that changes nothing', async () => {
    const { controller, runtime } = await openBuilding(
      dashboardConfig({ panels: [panel()] }),
    );

    act(() => {
      controller().place('orders', { x: 0, y: 0, w: 6, h: 4 });
      controller().place('other', { x: 3, y: 3, w: 3, h: 3 });
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
    await settle();

    expect(child?.getSnapshot().result).not.toBe(before);
  });

  it('answers with an empty dashboard when there is no runtime', () => {
    const view = renderHook(() => useDashboard(null));

    expect(view.result.current.panels).toEqual([]);
    expect(view.result.current.resolving).toBe(false);
    expect(view.result.current.dirty).toBe(false);
    view.result.current.place('x', { x: 0, y: 0, w: 1, h: 1 });
    view.result.current.refresh();
    view.result.current.refreshPanel('x');
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
   * A panel is read where it stands, and a narrow one overflows with only a
   * few columns — so a last column held on the right would sit over the
   * column before it at rest, which is exactly what the home page showed
   * (「已重试次数」 read as 「已重试次」), and the pin cap keeps one column
   * that is under half the port. The panel holds no end; the key keeps its
   * place on the left, which covers nothing until the rows are scrolled.
   * What the browser makes of it is measured by the home page's `Fixture`.
   */
  it('holds no end on the right, and keeps the key on the left', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
    );

    const { container } = render(
      <ViewSurface>
        <DashboardGrid dashboard={controller()} />
      </ViewSurface>,
    );

    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    const pins = [
      ...container.querySelectorAll<HTMLElement>('thead th[data-field]'),
    ].map(cell => [cell.dataset.field, cell.dataset.pin ?? null]);
    expect(pins).toEqual([
      ['id', 'left'],
      ['amount', null],
    ]);
    expect(container.querySelector('[data-pin="right"]')).toBeNull();
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
    // The two orders are one page, so the one row is 「全部」 (D26 Q40).
    expect(rows.map(row => row.dataset.scope)).toEqual(['total']);
    expect(footer.textContent).toContain('All rows');
    expect(footer.textContent).toContain('30');
    // One cell per column on each row plus the filler that takes the
    // leftover width: the footer stays aligned with the header, and the
    // scope label rides above the first column's own number rather than
    // taking a cell the row does not have.
    for (const row of rows) expect(row.querySelectorAll('td')).toHaveLength(3);
    expect(rows[0].cells[0].textContent).toContain('All rows');
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

  /**
   * `panel.id` is a key in a config, not a word anyone reading the board
   * chose, and it used to be the heading, the grip's name and the scroll
   * region's name of every untitled panel (U8). An untitled view panel is
   * named after the view it shows, a content panel after what it holds, and
   * one with nothing to name it by after where it stands.
   */
  it('names an untitled panel by what it shows, never by its id', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({
        panels: [
          panel(),
          panel({
            id: 'gone',
            instanceId: 'deleted',
            layout: { x: 6, y: 0, w: 6, h: 4 },
          }),
          panel({
            id: 'memo',
            kind: 'markdown',
            content: 'hello',
            layout: { x: 0, y: 4, w: 6, h: 2 },
          }),
        ],
      }),
    );

    render(<DashboardGrid dashboard={controller()} />);

    const headings = screen
      .getAllByRole('heading', { level: 3 })
      .map(heading => heading.textContent);
    expect(headings).toEqual(['Pending orders', 'Panel 2', 'Text']);
    expect(screen.queryByText('orders')).toBeNull();
    expect(screen.queryByText('gone')).toBeNull();
    // The scroll region a keyboard reaches is named the same.
    expect(screen.getByRole('group', { name: 'Pending orders' })).toBeTruthy();
  });

  /**
   * Batch-A walk: two untitled text panels were both 「Text」, so a handle, a
   * finding or a landing announced by name pointed at two panels. A name
   * the board makes up is numbered in reading order; an author's title is
   * theirs, and a made-up name steps around it.
   */
  it('numbers the names it makes up, in reading order, around the titles given', async () => {
    const memo = (id: string, x: number, y: number, title?: string) =>
      panel({
        id,
        kind: 'markdown',
        content: 'hello',
        layout: { x, y, w: 6, h: 2 },
        ...(title ? { title } : {}),
      });
    const { controller } = await openDashboard(
      dashboardConfig({
        panels: [
          // Listed out of order: the numbers follow the board, not the list.
          memo('third', 0, 4),
          memo('first', 0, 0),
          memo('second', 6, 0),
          memo('titled', 6, 4, 'Text 2'),
          panel({
            id: 'heading',
            kind: 'heading',
            content: 'Stock',
            layout: { x: 0, y: 6, w: 24, h: 1 },
          }),
          panel({
            id: 'blank',
            kind: 'heading',
            content: ' ',
            layout: { x: 0, y: 7, w: 24, h: 1 },
          }),
        ],
      }),
    );

    render(<DashboardGrid dashboard={controller()} />);

    expect(
      screen
        .getAllByRole('heading', { level: 3 })
        .map(heading => heading.textContent),
    ).toEqual(['Text', 'Text 3', 'Text 4', 'Text 2', 'Stock', 'Heading']);
    // A heading card says its words once: as the panel's own title.
    expect(document.querySelector('[data-slot="panel-heading"]')).toBeNull();
  });

  it('draws each panel title as a heading under the view title', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel({ title: 'Waiting to ship' })] }),
    );

    render(<DashboardGrid dashboard={controller()} />);

    expect(
      screen.getByRole('heading', { level: 3, name: 'Waiting to ship' }),
    ).toBeTruthy();
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
          panel({ id: 'gone', instanceId: 'vanished' }),
          panel({ id: 'here', layout: { x: 6, y: 0, w: 6, h: 4 } }),
        ],
      }),
    );

    const { container } = render(<DashboardGrid dashboard={controller()} />);

    // Why, once, in the reader's words — and who can bring it back. It used
    // to say "unavailable" as the title and again under it (U5).
    const body = container.querySelector(
      '[data-slot="panel-unavailable"]',
    ) as HTMLElement;
    expect(body.textContent).toContain(
      'The view this panel shows was deleted, or you do not have access to it',
    );
    expect(body.textContent).toContain("Ask the view's owner to share it");
    expect(body.textContent?.match(/unavailable/g) ?? []).toEqual([]);
    // Never the id it points at, and no button offering what nothing can do.
    expect(body.textContent).not.toContain('vanished');
    expect(within(body).queryByRole('button')).toBeNull();
    // The finding is said once, in the body where the view would have been;
    // the header marker is for a panel that runs with a caveat.
    expect(document.querySelector('[data-slot="panel-warning"]')).toBeNull();
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
  });

  /**
   * Each finding that can put a panel out, mapped to a reason a reader of
   * the board can use. The kernels' own sentences carry what a config knows
   * — a scope code, the instance id a panel points at, a field's name — and
   * none of it reaches the panel.
   */
  describe('says why a panel is out, in words its reader uses', () => {
    async function outage(
      config: DashboardViewConfig,
      instances: ViewInstance[] = [pending],
      scope: ViewInstance['scope'] = 'personal',
    ) {
      const { controller } = await openDashboard(
        config,
        instances,
        testSource(),
        scope,
      );
      const { container } = render(<DashboardGrid dashboard={controller()} />);
      return (
        container.querySelector('[data-slot="panel-unavailable"]')
          ?.textContent ?? ''
      );
    }

    /**
     * Not an outage any more (D22 B): whoever can read the personal view —
     * its author, typically — sees the panel, with the reason in its
     * header; the readers who cannot are told the view is out of reach.
     */
    it('a personal view on a shared board is shown, marked in its header', async () => {
      const { controller } = await openDashboard(
        dashboardConfig({ panels: [panel()] }),
        [{ ...pending, scope: 'personal' }],
        testSource(),
        'shared',
      );
      const { container } = render(<DashboardGrid dashboard={controller()} />);

      expect(
        container.querySelector('[data-slot="panel-unavailable"]'),
      ).toBeNull();
      expect(
        screen.getByLabelText(
          'The view this panel shows is not open to everyone who reads this dashboard.',
        ),
      ).toBeTruthy();
    });

    it('a global filter the panel cannot carry', async () => {
      const said = await outage(
        dashboardConfig({
          fields: [{ name: 'region', label: 'Region', kind: 'string' }],
          panels: [
            panel({
              bindings: [{ globalField: 'region', panelField: 'nowhere' }],
            }),
          ],
        }),
      );
      expect(said).toContain("The dashboard's filters do not fit this panel");
      expect(said).not.toContain('nowhere');
    });

    it('a view saved with settings its definition now refuses', async () => {
      const said = await outage(dashboardConfig({ panels: [panel()] }), [
        { ...pending, config: recordConfig({ pageSize: 0 }) },
      ]);
      expect(said).toContain(
        'The view this panel shows was saved with settings that no longer work',
      );
      expect(said).toContain("Ask the view's owner to open it and fix it");
      expect(said).not.toMatch(/page size/i);
    });

    it('a panel pointing at another dashboard', async () => {
      const said = await outage(
        dashboardConfig({ panels: [panel({ instanceId: 'nested' })] }),
        [
          pending,
          {
            ...pending,
            id: 'nested',
            definitionId: 'overview',
            config: dashboardConfig(),
          },
        ],
      );
      expect(said).toContain(
        'This panel points at something that is not a record or analysis view',
      );
      expect(said).not.toContain('nested');
    });
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

    expect(
      screen.getByText(
        'The view this panel shows was saved with settings that no longer work',
      ),
    ).toBeTruthy();
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
    // The library draws its corner whatever `enabled` says, so a read-only
    // dashboard gets the ornament rather than a control nothing answers.
    expect(slot('panel-resize')).toBeNull();
    expect(
      document
        .querySelector('.react-resizable-handle')
        ?.getAttribute('aria-hidden'),
    ).toBe('true');

    rerender(<DashboardGrid dashboard={controller()} editable />);
    // Both are named: one handle that moves and resizes (V-02) — there is
    // no second 「摆放」 beside it — and the corner that resizes.
    expect(screen.getByLabelText('Move or resize “Pending orders”')).toBe(
      slot('panel-grip'),
    );
    expect(slot('panel-arrange')).toBeNull();
    expect(screen.getByLabelText('Resize “Pending orders”')).toBe(
      slot('panel-resize'),
    );
    // Which keys work them is said on the element, not in the name, and
    // how, in the handle's description.
    expect(slot('panel-grip')?.getAttribute('aria-keyshortcuts')).toContain(
      'Enter Space ArrowUp',
    );
    expect(slot('panel-grip')?.getAttribute('aria-keyshortcuts')).toContain(
      'Shift+ArrowUp',
    );
    expect(slot('panel-resize')?.getAttribute('aria-keyshortcuts')).toBe(
      'ArrowUp ArrowDown ArrowLeft ArrowRight',
    );
    const hint = document.getElementById(
      slot('panel-grip')?.getAttribute('aria-describedby') ?? '',
    );
    expect(hint?.textContent).toMatch(/press Enter to arrange/);
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

    /** Enter or Space on the handle: a click with no pointer behind it. */
    async function enter(handle: Element) {
      await act(async () => {
        fireEvent.click(handle, { detail: 0 });
        await Promise.resolve();
      });
    }

    it('arranges a panel from its one handle: Enter, the arrows, Shift and the arrows, then Enter or Escape (V-02)', async () => {
      const { controller, runtime } = await openBuilding(
        dashboardConfig({ panels: [panel()] }),
      );
      render(<LiveGrid runtime={runtime} />);
      const handle = screen.getByLabelText('Move or resize “Pending orders”');
      const said = () =>
        document.querySelector('[data-slot="dashboard-grid-announcement"]')
          ?.textContent;

      // Walking the header with the arrows moves nothing: arranging is
      // started on purpose.
      await press(handle, 'ArrowRight');
      expect(runtime.getSnapshot().dirty).toBe(false);
      // A pointer's click is the start of a drag that did not happen.
      fireEvent.click(handle, { detail: 1 });
      expect(handle.getAttribute('aria-pressed')).toBe('false');

      await enter(handle);
      expect(handle.getAttribute('aria-pressed')).toBe('true');
      expect(said()).toMatch(/^Arranging “Pending orders”: the arrows move it/);

      // Nothing to the left of the first column: the press is swallowed
      // and said, rather than writing a layout the kernel would refuse.
      await press(handle, 'ArrowLeft');
      expect(runtime.getSnapshot().dirty).toBe(false);
      expect(said()).toBe('“Pending orders” cannot go that way');

      await press(handle, 'ArrowRight');
      expect(controller().panels[0].layout).toEqual({ x: 1, y: 0, w: 6, h: 4 });
      // A pointer watches the panel move; a keyboard is told where it went.
      expect(said()).toBe(
        'Pending orders is at column 2, row 1, 6 columns by 4 rows',
      );
      await act(async () => {
        fireEvent.keyDown(handle, { key: 'ArrowRight', shiftKey: true });
        await Promise.resolve();
      });
      expect(controller().panels[0].layout).toEqual({ x: 1, y: 0, w: 7, h: 4 });

      // Enter again keeps it there.
      await enter(handle);
      expect(handle.getAttribute('aria-pressed')).toBe('false');
      expect(said()).toBe('“Pending orders” stays where it is');
      expect(runtime.getSnapshot().dirty).toBe(true);

      // Escape takes every step of this arranging back, and only those.
      await enter(handle);
      await press(handle, 'ArrowRight');
      await act(async () => {
        fireEvent.keyDown(handle, { key: 'ArrowDown', shiftKey: true });
        await Promise.resolve();
      });
      expect(controller().panels[0].layout).toEqual({ x: 2, y: 0, w: 7, h: 5 });
      await press(handle, 'Escape');
      expect(controller().panels[0].layout).toEqual({ x: 1, y: 0, w: 7, h: 4 });
      expect(handle.getAttribute('aria-pressed')).toBe('false');
      expect(said()).toBe('“Pending orders” is back where it was');
    });

    it('resizes a panel with the arrows on its corner', async () => {
      const { controller, runtime } = await openBuilding(
        dashboardConfig({ panels: [panel()] }),
      );
      render(<LiveGrid runtime={runtime} />);
      const corner = screen.getByLabelText('Resize “Pending orders”');

      await press(corner, 'Enter');
      expect(runtime.getSnapshot().dirty).toBe(false);

      await press(corner, 'ArrowRight');
      await press(corner, 'ArrowDown');
      await press(corner, 'ArrowUp');

      expect(controller().panels[0].layout).toEqual({ x: 0, y: 0, w: 7, h: 4 });
      expect(runtime.getSnapshot().dirty).toBe(true);
    });

    it('says one column and one row as one, not "1 columns"', async () => {
      const { runtime } = await openBuilding(
        dashboardConfig({
          panels: [panel({ layout: { x: 0, y: 0, w: 2, h: 2 } })],
        }),
      );
      render(<LiveGrid runtime={runtime} />);
      const corner = screen.getByLabelText('Resize “Pending orders”');

      await press(corner, 'ArrowLeft');
      await press(corner, 'ArrowUp');

      expect(
        screen.getByText(
          'Pending orders is at column 1, row 1, 1 column by 1 row',
        ),
      ).toBeTruthy();
    });

    /**
     * Two panels, two corners, two names: the corner reads its panel from
     * the grid item the library appended it to.
     */
    it('names each corner after its own panel', async () => {
      const { runtime } = await openBuilding(
        dashboardConfig({
          panels: [
            panel({ title: 'North' }),
            panel({
              id: 'south',
              title: 'South',
              layout: { x: 6, y: 0, w: 6, h: 4 },
            }),
          ],
        }),
      );
      render(<LiveGrid runtime={runtime} />);

      const corners = [
        ...document.querySelectorAll('[data-slot="panel-resize"]'),
      ].map(corner => corner.getAttribute('aria-label'));
      expect(corners).toEqual(['Resize “North”', 'Resize “South”']);
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

      await press(screen.getByLabelText('Resize this panel'), 'ArrowRight');

      expect(onStep).not.toHaveBeenCalled();
    });

    /**
     * react-resizable's `DraggableCore` clones the corner with the pointer
     * handlers that drive a resize drag. The corner must hand them to its
     * button: dropping them left panels resizable by keyboard only.
     */
    it('hands the drag handlers the library gives it to its button', () => {
      const onMouseDown = vi.fn();
      const onMouseUp = vi.fn();
      const onTouchEnd = vi.fn();
      render(
        <PanelResizeHandle
          axis="se"
          ref={null}
          onStep={vi.fn()}
          onMouseDown={onMouseDown}
          onMouseUp={onMouseUp}
          onTouchEnd={onTouchEnd}
        />,
      );
      const corner = screen.getByLabelText('Resize this panel');

      fireEvent.mouseDown(corner);
      fireEvent.mouseUp(corner);
      fireEvent.touchEnd(corner);

      expect(onMouseDown).toHaveBeenCalledOnce();
      expect(onMouseUp).toHaveBeenCalledOnce();
      expect(onTouchEnd).toHaveBeenCalledOnce();
    });

    /**
     * Sideways and in size one step is one cell, and the bounds are the
     * kernel's, so no command can produce a layout `validateDashboard`
     * would refuse. Up and down are what a board that floats panels up
     * makes of them — see test/dashboardLayout.test.ts「arranging by
     * keyboard」.
     */
    describe('arrangePanel', () => {
      const here = { id: 'a', x: 1, y: 0, w: 2, h: 2 };

      it.each([
        ['left', { x: 0, y: 0, w: 2, h: 2 }],
        ['right', { x: 2, y: 0, w: 2, h: 2 }],
        ['wider', { x: 1, y: 0, w: 3, h: 2 }],
        ['narrower', { x: 1, y: 0, w: 1, h: 2 }],
        ['taller', { x: 1, y: 0, w: 2, h: 3 }],
        ['shorter', { x: 1, y: 0, w: 2, h: 1 }],
      ] as const)('takes one cell %s', (step, landed) => {
        expect(arrangePanel([here], 'a', step, 24)).toEqual(landed);
      });

      it.each([
        ['left', { id: 'a', x: 0, y: 0, w: 1, h: 1 }],
        ['up', { id: 'a', x: 0, y: 0, w: 1, h: 1 }],
        ['down', { id: 'a', x: 0, y: 0, w: 1, h: 1 }],
        ['right', { id: 'a', x: 23, y: 0, w: 1, h: 1 }],
        ['wider', { id: 'a', x: 23, y: 0, w: 1, h: 1 }],
        ['narrower', { id: 'a', x: 0, y: 0, w: 1, h: 1 }],
        ['shorter', { id: 'a', x: 0, y: 0, w: 1, h: 1 }],
      ] as const)('has nowhere to go %s', (step, cornered) => {
        expect(arrangePanel([cornered], 'a', step, 24)).toBeNull();
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
      const { controller, runtime } = await openBuilding(gapped);
      render(<DashboardGrid dashboard={controller()} editable />);
      await settle();
      const grip = document.querySelector(
        '[data-slot="panel-grip"]',
      ) as HTMLElement;

      // A drag as the pointer makes one: down on the grip, movement on the
      // document, up. The grid is 1280px over 24 columns of 80px rows, so
      // 300px right and 180px down is some columns and two rows; with
      // nothing above it, the panel then rises to the top (batch-A walk).
      fireEvent.mouseDown(grip, { clientX: 10, clientY: 10, button: 0 });
      fireEvent.mouseMove(document, { clientX: 100, clientY: 100 });
      fireEvent.mouseMove(document, { clientX: 310, clientY: 190 });
      fireEvent.mouseUp(document, { clientX: 310, clientY: 190 });
      await settle();

      expect(runtime.getSnapshot().dirty).toBe(true);
      // Which of the narrow columns it lands in depends on the width the
      // grid measured first; that it moved right and rose is the point.
      const moved = controller().panels[0].layout;
      expect(moved).toMatchObject({ y: 0, w: 6, h: 4 });
      expect(moved.x).toBeGreaterThanOrEqual(3);
    });

    it('resizes a panel by dragging its corner', async () => {
      // The same jsdom stand-ins as the move above: a parent to measure
      // against, and the 1280px the grid's columns are counted from.
      vi.spyOn(HTMLElement.prototype, 'offsetParent', 'get').mockImplementation(
        function (this: HTMLElement) {
          return this.parentElement;
        },
      );
      vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(
        1280,
      );
      const { controller, runtime } = await openBuilding(gapped);
      render(<DashboardGrid dashboard={controller()} editable />);
      await settle();
      const before = controller().panels[0].layout;
      const corner = document.querySelector(
        '[data-slot="panel-resize"]',
      ) as HTMLElement;

      // Down on the corner, movement on the document, up: 160px right is
      // three columns of ~53px, 160px down two rows of 80px.
      fireEvent.mouseDown(corner, { clientX: 10, clientY: 10, button: 0 });
      fireEvent.mouseMove(document, { clientX: 90, clientY: 90 });
      fireEvent.mouseMove(document, { clientX: 170, clientY: 170 });
      fireEvent.mouseUp(document, { clientX: 170, clientY: 170 });
      await settle();

      expect(runtime.getSnapshot().dirty).toBe(true);
      const resized = controller().panels[0].layout;
      expect(resized.w).toBeGreaterThan(before.w);
      expect(resized.h).toBeGreaterThan(before.h);
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
    // Said to a reader who reaches the body too (U-13): a word and a busy
    // mark, not a live region — a board opens a dozen panels at once, and
    // its one voice is not for a dozen 「加载中」.
    const loading = document.querySelector<HTMLElement>(
      '[data-slot="panel-loading"]',
    )!;
    expect(loading.getAttribute('aria-busy')).toBe('true');
    expect(loading.textContent).toBe('Loading');
    expect(
      loading
        .querySelector('[data-slot="skeleton"]')
        ?.getAttribute('aria-hidden'),
    ).toBe('true');
    expect(loading.closest('[aria-live]')).toBeNull();
    expect(loading.querySelector('[aria-live]')).toBeNull();
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
    act(() => grid!.resize(900));

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
    // The frame the library coalesces into: a frame callback asked for now
    // runs after every one asked for before it.
    await act(
      () =>
        new Promise<void>(resolve => requestAnimationFrame(() => resolve())),
    );

    expect(item.style.width).toBe(measured);
    vi.unstubAllGlobals();
  });

  /**
   * The empty state says what a dashboard is and that this one is empty.
   * The first things to add are offered under it only to whoever may build
   * the board (D22 A); this grid is given no way to build it, so it offers
   * nothing to press — an entry the reader cannot use would be a door that
   * is not there (U1).
   */
  it('says what an empty dashboard is, and promises nothing', async () => {
    const { controller } = await openDashboard(dashboardConfig());

    const { container } = render(<DashboardGrid dashboard={controller()} />);

    expect(screen.getByText('This dashboard has no panels yet')).toBeTruthy();
    expect(
      screen.getByText(
        'A dashboard puts saved record and analysis views side by side.',
      ),
    ).toBeTruthy();
    expect(container.textContent).not.toMatch(/\badd\b/i);
    expect(screen.queryByRole('button')).toBeNull();
  });

  /**
   * The panels are drawn in reading order — rows top to bottom, each left
   * to right — whatever order the config lists them in, so Tab walks the
   * board the way the eye does.
   */
  it('draws the panels in reading order, not config order', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({
        panels: [
          panel({
            id: 'c',
            title: 'Below',
            layout: { x: 0, y: 4, w: 6, h: 2 },
          }),
          panel({
            id: 'b',
            title: 'Right',
            layout: { x: 6, y: 0, w: 6, h: 4 },
          }),
          panel({ id: 'a', title: 'Left', layout: { x: 0, y: 0, w: 6, h: 4 } }),
        ],
      }),
    );

    render(<DashboardGrid dashboard={controller()} />);

    expect(
      screen
        .getAllByRole('heading', { level: 3 })
        .map(heading => heading.textContent),
    ).toEqual(['Left', 'Right', 'Below']);
  });

  /**
   * Below `md` a twelve-column grid squeezes a panel to a sliver — an
   * analysis panel at ~130px, its labels over each other. The grid gives up
   * its columns there: one column, the panels in reading order, each full
   * width and as tall as it was saved. It is a reading of the layout, never
   * written back, and nothing can be dragged or resized in it.
   */
  describe('in a column narrower than md', () => {
    async function narrow(width: number, editable = true) {
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
      const opened = await openDashboard(
        dashboardConfig({
          panels: [
            panel({
              id: 'b',
              title: 'Right',
              layout: { x: 6, y: 0, w: 6, h: 4 },
            }),
            panel({
              id: 'a',
              title: 'Left',
              layout: { x: 0, y: 0, w: 6, h: 3 },
            }),
          ],
        }),
      );
      const view = render(
        <DashboardGrid dashboard={opened.controller()} editable={editable} />,
      );
      const grid = observers.find(
        observer => observer.node !== null && !observer.node.closest('table'),
      );
      act(() => grid!.resize(width));
      await waitFor(() =>
        expect(
          document
            .querySelector('[data-slot="dashboard-grid"]')
            ?.hasAttribute('data-narrow'),
        ).toBe(width < 768),
      );
      vi.unstubAllGlobals();
      return { ...opened, ...view };
    }

    const item = (title: string) =>
      screen
        .getByRole('heading', { level: 3, name: title })
        .closest('.react-grid-item') as HTMLElement;

    it('stacks the panels in one column, each as tall as it was saved', async () => {
      await narrow(414);

      // Where the grid put an item: it places by `translate(x, y)`.
      const at = (element: HTMLElement) =>
        (
          element.style.transform.match(
            /translate\(([\d.]+)px,\s*([\d.]+)px\)/,
          ) ?? []
        )
          .slice(1)
          .map(Number);
      // Full width: both as wide as the column, from the same left edge.
      expect(item('Left').style.width).toBe(item('Right').style.width);
      expect(parseFloat(item('Left').style.width)).toBeGreaterThan(414 * 0.9);
      expect(at(item('Left'))[0]).toBe(at(item('Right'))[0]);
      // Reading order: the left panel first, the right one under it, the
      // height each was saved with kept (3 rows, then 4).
      expect(at(item('Left'))[1]).toBeLessThan(at(item('Right'))[1]);
      expect(parseFloat(item('Left').style.height)).toBeLessThan(
        parseFloat(item('Right').style.height),
      );
    });

    it('offers no handle to drag or resize, and writes nothing back', async () => {
      const { controller, runtime } = await narrow(414);

      expect(document.querySelector('[data-slot="panel-grip"]')).toBeNull();
      expect(document.querySelector('[data-slot="panel-arrange"]')).toBeNull();
      expect(document.querySelector('[data-slot="panel-resize"]')).toBeNull();
      // The saved layout is the wide one, untouched.
      expect(controller().panels.map(found => found.layout)).toEqual([
        { x: 6, y: 0, w: 6, h: 4 },
        { x: 0, y: 0, w: 6, h: 3 },
      ]);
      expect(runtime.getSnapshot().dirty).toBe(false);
    });

    it('keeps the grid from md up', async () => {
      await narrow(768);

      // The measurement lands a frame later, and 1280 — where it starts — is
      // wide as well, so the width itself is what is waited for.
      await waitFor(() =>
        expect(parseFloat(item('Left').style.width)).toBeLessThan(768 / 2),
      );
      expect(document.querySelector('[data-slot="panel-grip"]')).toBeTruthy();
    });
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
