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
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
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
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
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
        'dashboard.field.name-invalid',
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
        'view.open.failed',
      ),
    );
  });
});
