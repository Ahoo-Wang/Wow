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
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  builtinFieldKinds,
  MemoryViewStore,
  ViewEngine,
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
  ContentPanel,
  DashboardGrid,
  DashboardWorkbench,
  ImagePanel,
  LinksPanel,
  MarkdownPanel,
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

afterEach(cleanup);

/** Enough of `ResizeObserver` for the grid's width to be driven by hand. */
class ResizeObserverStub {
  constructor(private readonly callback: ResizeObserverCallback) {}
  observe(): void {}
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
      screen.getByRole('img', {
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
      screen.getByRole('img', {
        name: 'These conditions need the advanced editor to be shown in full.',
      }),
    ).toBeTruthy();
  });

  it('offers a grip only when the layout may be edited', async () => {
    const { controller } = await openDashboard(
      dashboardConfig({ panels: [panel()] }),
    );
    const grip = () => document.querySelector('[data-slot="panel-grip"]');

    const { rerender } = render(<DashboardGrid dashboard={controller()} />);
    expect(grip()).toBeNull();

    rerender(<DashboardGrid dashboard={controller()} editable />);
    // Hidden from assistive technology: the drag it starts is pointer-only,
    // so announcing it would offer an affordance nobody can take up.
    expect(grip()?.getAttribute('aria-hidden')).toBe('true');
    expect(grip()?.getAttribute('title')).toBe('Move panel');
    expect(screen.queryByLabelText('Move panel')).toBeNull();
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
      // starts from (0, 0) whatever the panel's stored position.
      vi.spyOn(HTMLElement.prototype, 'offsetParent', 'get').mockImplementation(
        function (this: HTMLElement) {
          return this.parentElement;
        },
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

    act(() => observers[0].resize(600));
    // A measurement of zero is what a hidden container reports; it is ignored.
    act(() => observers[0].resize(0));

    expect(item.style.width).not.toBe(initial);
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

describe('content panels', () => {
  /**
   * These components are exported on their own, so a host can render one from
   * a config that no `validateDashboard` ever saw. The scheme guard therefore
   * runs here too, and not only during admission.
   */
  it.each(['javascript:alert(1)', 'data:text/html,<script>', 'vbscript:x'])(
    'refuses to load an image from %s',
    src => {
      render(<ImagePanel src={src} alt="Chart" />);

      expect(screen.queryByRole('img')).toBeNull();
      expect(screen.getByText('Chart')).toBeTruthy();
    },
  );

  it('keeps an unsafe destination out of the document', () => {
    render(
      <LinksPanel items={[{ label: 'Payroll', href: 'vbscript:msgbox(1)' }]} />,
    );

    // The label stays — the reader still sees what was meant to be there.
    expect(screen.getByText('Payroll')).toBeTruthy();
    expect(screen.getByText('Payroll').closest('a')).toBeNull();
  });

  it('shows an image whose href is unsafe, without the link', () => {
    render(
      <ImagePanel src="/chart.png" alt="Chart" href="javascript:alert(1)" />,
    );

    expect(screen.getByRole('img')).toBeTruthy();
    expect(screen.getByRole('img').closest('a')).toBeNull();
  });

  it('renders markdown without raw HTML', () => {
    render(<MarkdownPanel content={'# Title\n\n<b>bold</b>'} />);

    expect(screen.getByRole('heading', { name: 'Title' })).toBeTruthy();
    // react-markdown is used without `rehype-raw`, so the tag stays text.
    expect(document.querySelector('b')).toBeNull();
  });

  it('shows a placeholder when an image fails to load', () => {
    render(<ImagePanel src="/missing.png" alt="Sales trend" />);

    fireEvent.error(screen.getByRole('img'));

    expect(screen.getByText('Sales trend')).toBeTruthy();
    expect(screen.queryByRole('img')).toBeNull();
  });

  it('falls back to a message when a failed image has no alt text', () => {
    render(<ImagePanel src="/missing.png" fit="cover" />);

    fireEvent.error(
      document.querySelector('[data-slot="image-panel"]') as HTMLImageElement,
    );

    expect(screen.getByText('This image could not be loaded')).toBeTruthy();
  });

  it('wraps a linked image in an anchor that cannot reach the opener', () => {
    render(<ImagePanel src="/a.png" href="https://example.com" alt="A" />);

    const link = screen.getByRole('link');
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    expect(link.getAttribute('target')).toBe('_blank');
  });

  it('opens every link with noopener', () => {
    render(
      <LinksPanel
        items={[
          { label: 'Runbook', href: '/runbook', description: 'What to do' },
          { label: 'Mail ops', href: 'mailto:ops@example.com' },
        ]}
      />,
    );

    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(2);
    for (const link of links)
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    expect(screen.getByText('What to do')).toBeTruthy();
  });

  it('dispatches by panel kind', () => {
    const { rerender } = render(
      <ContentPanel
        panel={{
          id: 'a',
          kind: 'markdown',
          content: 'note',
          layout: { x: 0, y: 0, w: 1, h: 1 },
        }}
      />,
    );
    expect(screen.getByText('note')).toBeTruthy();

    rerender(
      <ContentPanel
        panel={{
          id: 'a',
          kind: 'image',
          src: '/a.png',
          layout: { x: 0, y: 0, w: 1, h: 1 },
        }}
      />,
    );
    expect(document.querySelector('[data-slot="image-panel"]')).toBeTruthy();

    rerender(
      <ContentPanel
        panel={{
          id: 'a',
          kind: 'links',
          items: [{ label: 'Docs', href: '/docs' }],
          layout: { x: 0, y: 0, w: 1, h: 1 },
        }}
      />,
    );
    expect(screen.getByRole('link', { name: 'Docs' })).toBeTruthy();
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
    const store = new MemoryViewStore({ instances: [pending, instance] });
    const engine = new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store,
      resolveSource: () => source,
    });
    return { engine, source, store };
  }

  it('opens a dashboard, shows its panels and its global filter', async () => {
    const { engine } = setup();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );

    await waitFor(() => expect(screen.getByText('Pending')).toBeTruthy());
    expect(screen.getByRole('button', { name: /Apply/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeTruthy();
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
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

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'not a field is not a usable field name.',
      ),
    );
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
    const notice = document.querySelector('[data-slot="view-warnings"]');
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
      describe: ({ leaf, field }) => `${field.label} = ${String(leaf.value)}`,
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
      store: new MemoryViewStore({
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
    const notice = () => document.querySelector('[data-slot="view-warnings"]');
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
