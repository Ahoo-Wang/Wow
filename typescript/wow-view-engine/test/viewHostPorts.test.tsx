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
 * `ViewHost`'s ports (host-integration.md 4.1–4.3): the router — through
 * `/react-router`'s adapter, as a host writes it — keeping the address
 * (`?view=`, `?id=`, the history entry's state) and taking every way off;
 * light and dark on `<html>`, painted by the engine or left to the host;
 * the preset and the brand; and the navigation as data.
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
import { createContext, useContext, type ReactNode } from 'react';
import {
  createMemoryRouter,
  RouterProvider,
  type InitialEntry,
} from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  systemInstanceId,
  text,
  type ViewNavigation,
} from '../src/index.js';
import { useReactRouter } from '../src/react-router/index.js';
import {
  bind,
  DashboardWorkbench,
  DataWorkbench,
  EmbeddedView,
  useColorMode,
  useViewNavigation,
  ViewHost,
  type ViewBinding,
  type ViewHostProps,
  type ViewRouter,
} from '../src/ui/index.js';
import { useAddressedBoard } from '../src/ui/workbench/address.js';
import { useRoutedNavigate } from '../src/ui/workbench/ViewEngineProvider.js';
import {
  mine,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  resourcesOf,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

/** What a hook returned on a probe's last render. */
function lastOf<T>() {
  const told = vi.fn<(value: T) => void>();
  return {
    told,
    get value(): T {
      const call = told.mock.lastCall;
      if (!call) throw new Error('The probe has not rendered.');
      return call[0];
    },
  };
}

const other = { ...mine, id: 'orders-2', title: 'Other' };

const ALL = systemInstanceId('orders', 'all');

function engineOf() {
  return new ViewEngine({
    resources: resourcesOf(
      [
        ordersDefinition({
          title: text('orders.title'),
          views: [
            { id: 'all', title: text('orders.all'), config: recordConfig() },
          ],
        }),
        overviewDefinition({
          views: [
            {
              id: 'home',
              title: 'Home board',
              config: {
                refresh: { interval: null },
                kind: 'dashboard',
                columns: 24,
                fixed: { op: 'and', children: [] },
                tabs: [],
                fields: [],
                panels: [],
              },
            },
          ],
        }),
      ],
      () => testSource(),
    ),
    store: new MemoryViewStore({ instances: [mine, other] }),
    onIssue: () => {},
  });
}

const EN = { 'orders.title': 'Orders', 'orders.all': 'All orders' };
const ZH = { 'orders.title': '订单', 'orders.all': '全部订单' };

/** Where each resource lives: the orders workbench, the boards page. */
const ROUTES: ViewBinding[] = [
  bind('orders', {
    route: view => (view ? `/orders?view=${view}` : '/orders'),
  }),
  bind('overview', { route: board => (board ? `/?board=${board}` : '/') }),
];

/**
 * A host on React Router: every page under one `ViewHost` whose router is
 * the adapter, as a host writes it. `pages` maps a path to what it draws.
 */
function hosted(
  pages: Record<string, ReactNode>,
  start: InitialEntry = '/',
  props: Partial<ViewHostProps> = {},
) {
  const engine = props.engine ?? engineOf();
  function Host({ children }: { children: ReactNode }) {
    return (
      <ViewHost
        engine={engine}
        messages={EN}
        router={useReactRouter()}
        bindings={ROUTES}
        colorMode="host"
        {...props}
      >
        {children}
      </ViewHost>
    );
  }
  const router = createMemoryRouter(
    Object.entries(pages).map(([path, page]) => ({
      path,
      element: <Host>{page}</Host>,
    })),
    { initialEntries: [start] },
  );
  render(<RouterProvider router={router} />);
  return { router, engine, at: () => router.state.location };
}

/** The current view is the one the sidebar marks. */
function current(): string | null {
  return (
    screen.getAllByRole('button').find(button => button.ariaCurrent === 'true')
      ?.textContent ?? null
  );
}

describe('the router port: every way off through the route of its resource', () => {
  it('goes to the route with what the page opens with, and the page opens it', async () => {
    const { at } = hosted({
      '/': <EmbeddedView instanceId={ALL} interaction="interactive" />,
      '/orders': <DataWorkbench definitionId="orders" />,
    });
    await userClick(
      await screen.findByRole('button', { name: 'Open in the workbench' }),
    );
    await waitFor(() => expect(at().pathname).toBe('/orders'));
    expect(at().search).toBe(`?view=${ALL}`);
    expect(at().state).toMatchObject({
      handOver: { kind: 'view', definitionId: 'orders', instanceId: ALL },
    });
    // The workbench there opens what the address names.
    await waitFor(() => expect(current()).toMatch(/^All orders/));
  });

  it('hands a view over as history state alone where the route names no view', async () => {
    const routes = [bind('orders', { route: () => '/orders' })];
    const { at } = hosted(
      {
        '/': <EmbeddedView instanceId={ALL} interaction="interactive" />,
        '/orders': <DataWorkbench definitionId="orders" />,
      },
      '/',
      { bindings: routes },
    );
    await userClick(
      await screen.findByRole('button', { name: 'Open in the workbench' }),
    );
    await waitFor(() => expect(at().pathname).toBe('/orders'));
    // The workbench opens the view the entry holds, not its default, and
    // names it in the address — on the same entry, the view it was handed
    // still there for a reload.
    await waitFor(() => expect(current()).toMatch(/^All orders/));
    await waitFor(() =>
      expect(new URLSearchParams(at().search).get('view')).toBe(ALL),
    );
    expect(at().state).toMatchObject({ handOver: { instanceId: ALL } });
  });

  it('takes a path of the host’s own through the router, opens another site apart, and drops a target with no route', () => {
    const opened = vi.spyOn(window, 'open').mockImplementation(() => null);
    const go = lastOf<((to: ViewNavigation) => void) | undefined>();
    function Probe() {
      go.told(useRoutedNavigate());
      return null;
    }
    const { at } = hosted({ '/': <Probe />, '/help': <Probe /> });
    act(() => go.value?.({ kind: 'url', url: '/help?topic=a' }));
    expect(at().pathname).toBe('/help');
    expect(at().search).toBe('?topic=a');
    act(() => go.value?.({ kind: 'url', url: 'https://example.com/x' }));
    expect(opened).toHaveBeenCalledWith(
      'https://example.com/x',
      '_blank',
      'noopener,noreferrer',
    );
    // `//host` is another site too, not a path.
    act(() => go.value?.({ kind: 'url', url: '//example.com/y' }));
    expect(opened).toHaveBeenCalledTimes(2);
    act(() =>
      go.value?.({
        kind: 'dashboard',
        definitionId: 'unbound',
        instanceId: 'x',
        filters: { values: {} },
      }),
    );
    expect(at().pathname).toBe('/help');
  });

  it('leaves every way off to the host’s navigate where it gives one', () => {
    const navigate = vi.fn();
    const go = lastOf<((to: ViewNavigation) => void) | undefined>();
    function Probe() {
      go.told(useRoutedNavigate());
      return null;
    }
    const { at } = hosted({ '/': <Probe /> }, '/', { navigate });
    act(() => go.value?.({ kind: 'url', url: '/help' }));
    expect(navigate).toHaveBeenCalledWith({ kind: 'url', url: '/help' });
    expect(at().pathname).toBe('/');
  });
});

/** A click, flushed. */
async function userClick(element: HTMLElement) {
  await act(async () => {
    fireEvent.click(element);
    await Promise.resolve();
  });
}

describe('the address: `?view=`, a new entry per view', () => {
  it('opens the view the address names and writes the reader’s pick back', async () => {
    const { router, at } = hosted(
      { '/orders': <DataWorkbench definitionId="orders" /> },
      '/orders?view=orders-1',
    );
    await waitFor(() => expect(current()).toBe('Mine'));
    fireEvent.click(screen.getByRole('button', { name: 'Other' }));
    await waitFor(() => expect(at().search).toBe('?view=orders-2'));
    await waitFor(() => expect(current()).toBe('Other'));
    // A view is a link and a step back.
    await act(() => router.navigate(-1));
    await waitFor(() => expect(current()).toBe('Mine'));
  });

  it('keeps a host’s own `instanceId` in charge', async () => {
    const { at } = hosted(
      {
        '/orders': (
          <DataWorkbench definitionId="orders" instanceId="orders-2" />
        ),
      },
      '/orders?view=orders-1',
    );
    await waitFor(() => expect(current()).toBe('Other'));
    expect(at().search).toBe('?view=orders-1');
  });
});

describe('the address: two workbenches on one page', () => {
  /** The view a workbench's sidebar marks as open, by its definition's title. */
  function openIn(definition: string): string | null {
    const list = screen.getByRole('navigation', { name: definition });
    return (
      within(list)
        .getAllByRole('button')
        .find(button => button.ariaCurrent === 'true')?.textContent ?? null
    );
  }

  it('each opens only its own definition’s view, and neither writes over the other’s', async () => {
    const { at } = hosted(
      {
        '/': (
          <>
            <DataWorkbench definitionId="orders" />
            <DashboardWorkbench definitionId="overview" />
          </>
        ),
      },
      '/?view=orders-1',
    );
    await waitFor(() => expect(openIn('Orders')).toBe('Mine'));
    // The board workbench sets the orders view aside: its own default, and
    // no 「belongs to another page」 left on screen.
    await waitFor(() => expect(openIn('Overview')).toMatch(/^Home board/));
    expect(screen.queryByText(/belongs to another page/)).toBeNull();
    // Its default is not said back over the orders view.
    expect(at().search).toBe('?view=orders-1');
    // The reader moves in the orders workbench; the boards stay put.
    fireEvent.click(screen.getByRole('button', { name: 'Other' }));
    await waitFor(() => expect(at().search).toBe('?view=orders-2'));
    await waitFor(() => expect(openIn('Orders')).toBe('Other'));
    expect(openIn('Overview')).toMatch(/^Home board/);
  });

  it('asks for a view its list did not hold before setting it aside', async () => {
    const engine = engineOf();
    // A store that cut its list short (WowViewStore lists 1000 of an
    // audience): the view the address names is past the cut.
    const list = engine.store.list.bind(engine.store);
    vi.spyOn(engine.store, 'list').mockImplementation(async definitionId =>
      (await list(definitionId)).filter(item => item.id !== 'orders-2'),
    );
    const asked = vi.spyOn(engine.store, 'get');
    hosted(
      {
        '/': (
          <>
            <DataWorkbench definitionId="orders" />
            <DashboardWorkbench definitionId="overview" />
          </>
        ),
      },
      '/?view=orders-2',
      { engine },
    );

    await waitFor(() => expect(openIn('Overview')).toMatch(/^Home board/));
    // The orders workbench opened it, though its list never named it.
    const titles = () =>
      [...document.querySelectorAll('[data-slot="view-title"]')].map(
        title => title.textContent,
      );
    await waitFor(() => expect(asked).toHaveBeenCalledWith('orders-2'));
    // Its answer, and the render it causes.
    await act(nextTask);
    await act(nextTask);
    expect(titles()).toContain('Other');
  });

  it('sets a declared view of another definition aside by its id alone', async () => {
    const { at } = hosted(
      {
        '/': (
          <>
            <DataWorkbench definitionId="orders" />
            <DashboardWorkbench definitionId="overview" />
          </>
        ),
      },
      `/?view=${ALL}`,
    );
    await waitFor(() => expect(openIn('Orders')).toMatch(/^All orders/));
    await waitFor(() => expect(openIn('Overview')).toMatch(/^Home board/));
    expect(at().search).toBe(`?view=${ALL}`);
  });
});

describe('the address: `?id=`, the record a bound resource’s detail holds', () => {
  const reading = [
    ...ROUTES,
    bind('orders', {
      route: view => (view ? `/orders?view=${view}` : '/orders'),
      reading: { title: row => `Order ${String(row.key)}` },
    }),
  ];

  it('opens the record the address names, and writes the one the reader opens in place', async () => {
    const { router, at } = hosted(
      { '/orders': <DataWorkbench definitionId="orders" /> },
      '/orders?view=orders-1&id=o-1',
      { bindings: reading },
    );
    const panel = await screen.findByRole('dialog');
    expect(within(panel).getByText('Order o-1')).toBeTruthy();
    fireEvent.click(within(panel).getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(at().search).toBe('?view=orders-1'));
    const entries = router.state.historyAction;
    expect(entries).toBe('REPLACE');
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    fireEvent.keyDown(screen.getAllByRole('row')[2], { key: 'Enter' });
    await waitFor(() => expect(at().search).toBe('?view=orders-1&id=o-2'));
    const opened = await screen.findByRole('dialog');
    expect(within(opened).getByText('Order o-2')).toBeTruthy();
  });

  it('leaves a detail the host holds itself alone', async () => {
    const held = [
      bind('orders', {
        route: () => '/orders',
        reading: { open: null, title: row => `Order ${String(row.key)}` },
      }),
    ];
    hosted(
      { '/orders': <DataWorkbench definitionId="orders" /> },
      '/orders?view=orders-1&id=o-1',
      { bindings: held },
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

describe('the address: a board’s filters and tab in the history entry', () => {
  type Board = Parameters<typeof useAddressedBoard>[0];
  const Given = createContext<Board>({});
  const last = lastOf<Board>();
  function Probe() {
    last.told(useAddressedBoard(useContext(Given), 'b'));
    return null;
  }

  function board(start: InitialEntry) {
    const engine = engineOf();
    const router = createMemoryRouter(
      [
        {
          path: '*',
          element: (
            <RoutedChildren engine={engine}>
              <Probe />
            </RoutedChildren>
          ),
        },
      ],
      { initialEntries: [start] },
    );
    const draw = (given: Board) => (
      <Given.Provider value={given}>
        <RouterProvider router={router} />
      </Given.Provider>
    );
    const view = render(draw({}));
    return { router, give: (given: Board) => view.rerender(draw(given)) };
  }

  it('opens on the entry’s filters and tab, and replaces the entry as they change', async () => {
    const filters = { values: { day: 'yesterday' } };
    const { router } = board({
      pathname: '/',
      search: '?board=b',
      state: { filters, tab: 'week' },
    });
    expect(last.value.initialFilters).toEqual(filters);
    expect(last.value.initialTab).toBe('week');
    const next = { values: { day: 'today' } };
    act(() => last.value.onFiltersChange?.(next));
    // Written under the board, beside what the page was handed.
    await waitFor(() =>
      expect(router.state.location.state).toEqual({
        filters,
        tab: 'week',
        boards: { b: { filters: next } },
      }),
    );
    expect(last.value.initialFilters).toEqual(next);
    expect(router.state.historyAction).toBe('REPLACE');
    expect(router.state.location.search).toBe('?board=b');
    // The same filters again write nothing.
    const key = router.state.location.key;
    act(() => last.value.onFiltersChange?.({ values: { day: 'today' } }));
    expect(router.state.location.key).toBe(key);
    act(() => last.value.onTabChange?.('month'));
    await waitFor(() =>
      expect(router.state.location.state).toMatchObject({
        boards: { b: { filters: next, tab: 'month' } },
      }),
    );
    // The tab it was handed is already where it is: nothing to write.
    const month = router.state.location.key;
    act(() => last.value.onTabChange?.('month'));
    expect(router.state.location.key).toBe(month);
  });

  it('keeps each board’s own, so several on one page each find theirs again', async () => {
    type Told = ReturnType<typeof lastOf<Board>>;
    const boards: Record<string, Told> = {
      north: lastOf<Board>(),
      south: lastOf<Board>(),
    };
    function Boards() {
      boards.north.told(useAddressedBoard({}, 'north'));
      boards.south.told(useAddressedBoard({}, 'south'));
      return null;
    }
    const page = (start: InitialEntry) => {
      const router = createMemoryRouter(
        [
          {
            path: '*',
            element: (
              <RoutedChildren engine={engineOf()}>
                <Boards />
              </RoutedChildren>
            ),
          },
        ],
        { initialEntries: [start] },
      );
      return { router, view: render(<RouterProvider router={router} />) };
    };
    const first = page('/boards');
    // Each board says what it opened on, as an embed does as it mounts.
    const north = { values: { region: 'north' } };
    const south = { values: { region: 'south' } };
    act(() => boards.north.value.onFiltersChange?.(north));
    await waitFor(() =>
      expect(first.router.state.location.state).toMatchObject({
        boards: { north: { filters: north } },
      }),
    );
    act(() => boards.south.value.onFiltersChange?.(south));
    act(() => boards.north.value.onTabChange?.('week'));
    await waitFor(() =>
      expect(first.router.state.location.state).toMatchObject({
        boards: { north: { tab: 'week' }, south: { filters: south } },
      }),
    );
    // Read again from the entry — a reload: each board finds its own.
    const { location } = first.router.state;
    first.view.unmount();
    page(location);
    expect(boards.north.value.initialFilters).toEqual(north);
    expect(boards.north.value.initialTab).toBe('week');
    expect(boards.south.value.initialFilters).toEqual(south);
    expect(boards.south.value.initialTab).toBeUndefined();
  });

  it('keeps a host’s own pair, each on its own', () => {
    const { give } = board({ pathname: '/', state: { tab: 'week' } });
    const onTabChange = vi.fn();
    give({ initialTab: 'day', onTabChange });
    expect(last.value.initialTab).toBe('day');
    expect(last.value.onTabChange).toBe(onTabChange);
    // The filters, which the host left out, are still the entry's.
    expect(last.value.initialFilters).toBeNull();
  });
});

function RoutedChildren({
  engine,
  children,
}: {
  engine: ViewEngine;
  children: ReactNode;
}) {
  return (
    <ViewHost engine={engine} router={useReactRouter()} colorMode="host">
      {children}
    </ViewHost>
  );
}

describe('light and dark (4.1)', () => {
  const root = document.documentElement;
  let dark = false;
  const listeners = new Set<() => void>();

  beforeEach(() => {
    dark = false;
    listeners.clear();
    root.className = '';
    root.style.colorScheme = '';
    window.localStorage.clear();
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => ({
        get matches() {
          return dark;
        },
        addEventListener: (_: string, listener: () => void) =>
          listeners.add(listener),
        removeEventListener: (_: string, listener: () => void) =>
          listeners.delete(listener),
      })),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function Switch() {
    const { mode, setMode } = useColorMode();
    return (
      <>
        <output>{mode}</output>
        <button type="button" onClick={() => setMode('dark')}>
          Dark
        </button>
        <button type="button" onClick={() => setMode('light')}>
          Light
        </button>
      </>
    );
  }

  it('follows the system by default, live, and puts `<html>` back as it found it', () => {
    dark = true;
    const view = render(
      <ViewHost engine={engineOf()}>
        <Switch />
      </ViewHost>,
    );
    expect(root.classList.contains('dark')).toBe(true);
    expect(root.style.colorScheme).toBe('dark');
    expect(screen.getByRole('status').textContent).toBe('system');
    dark = false;
    act(() => listeners.forEach(listener => listener()));
    expect(root.classList.contains('dark')).toBe(false);
    expect(root.style.colorScheme).toBe('light');
    view.unmount();
    expect(listeners.size).toBe(0);
    expect(root.style.colorScheme).toBe('');
  });

  it('starts pinned, and keeps the reader’s pick where the host asked', () => {
    const view = render(
      <ViewHost
        engine={engineOf()}
        colorMode="light"
        rememberColorMode="console.mode"
      >
        <Switch />
      </ViewHost>,
    );
    expect(root.classList.contains('dark')).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Dark' }));
    expect(root.classList.contains('dark')).toBe(true);
    expect(window.localStorage.getItem('console.mode')).toBe('dark');
    view.unmount();
    render(
      <ViewHost
        engine={engineOf()}
        colorMode="light"
        rememberColorMode="console.mode"
      >
        <Switch />
      </ViewHost>,
    );
    expect(screen.getByRole('status').textContent).toBe('dark');
    expect(root.classList.contains('dark')).toBe(true);
    // The host's own starting mode is no pick to keep.
    fireEvent.click(screen.getByRole('button', { name: 'Light' }));
    expect(root.classList.contains('dark')).toBe(false);
    expect(window.localStorage.getItem('console.mode')).toBeNull();
  });

  it('leaves `<html>` to a host that paints its own mode', () => {
    root.classList.add('dark');
    render(
      <ViewHost engine={engineOf()} colorMode="host">
        <Switch />
      </ViewHost>,
    );
    expect(root.classList.contains('dark')).toBe(true);
    expect(root.style.colorScheme).toBe('');
    expect(screen.getByRole('status').textContent).toBe('host');
    fireEvent.click(screen.getByRole('button', { name: 'Dark' }));
    expect(root.style.colorScheme).toBe('');
  });

  it('is painted by the outermost host alone', () => {
    render(
      <ViewHost engine={engineOf()} colorMode="light">
        <ViewHost colorMode="dark">
          <Switch />
        </ViewHost>
      </ViewHost>,
    );
    expect(root.classList.contains('dark')).toBe(false);
    // The inner host's switch is the outer one's.
    expect(screen.getByRole('status').textContent).toBe('light');
  });

  it('reads light where the browser has no system preference to ask', () => {
    vi.stubGlobal('matchMedia', undefined);
    render(<ViewHost engine={engineOf()}>{null}</ViewHost>);
    expect(root.style.colorScheme).toBe('light');
  });
});

describe('the theme’s two paths (4.1)', () => {
  const root = document.documentElement;

  beforeEach(() => {
    root.removeAttribute('data-fve-preset');
    root.style.removeProperty('--fve-brand');
  });

  it('names the preset and the brand on `<html>`, and takes them off again', () => {
    const view = render(
      <ViewHost
        engine={engineOf()}
        colorMode="host"
        preset="porcelain"
        brand="#1d4ed8"
      >
        {null}
      </ViewHost>,
    );
    expect(root.getAttribute('data-fve-preset')).toBe('porcelain');
    expect(root.style.getPropertyValue('--fve-brand')).toBe('#1d4ed8');
    view.unmount();
    expect(root.hasAttribute('data-fve-preset')).toBe(false);
    expect(root.style.getPropertyValue('--fve-brand')).toBe('');
  });

  it('names nothing where the engine follows the host’s theme', () => {
    render(
      <ViewHost
        engine={engineOf()}
        colorMode="host"
        theme="host"
        preset="azure"
        brand="#1d4ed8"
      >
        {null}
      </ViewHost>,
    );
    expect(root.hasAttribute('data-fve-preset')).toBe(false);
    expect(root.style.getPropertyValue('--fve-brand')).toBe('');
  });
});

describe('the navigation as data (4.3)', () => {
  function Navigation() {
    const items = useViewNavigation();
    return (
      <pre data-testid="nav">
        {JSON.stringify(
          items.map(({ id, kind, title, path, current, views }) => ({
            id,
            kind,
            title,
            path,
            current,
            views,
          })),
        )}
      </pre>
    );
  }
  const read = () =>
    JSON.parse(screen.getByTestId('nav').textContent ?? '[]') as ReturnType<
      typeof useViewNavigation
    >;

  it('lists each routed resource with its system views, where the address is', async () => {
    hosted({ '/orders': <Navigation /> }, `/orders?view=${ALL}`);
    const [orders, overview] = read();
    expect(orders).toEqual({
      id: 'orders',
      kind: 'data',
      title: 'Orders',
      path: '/orders',
      current: true,
      views: [
        {
          id: ALL,
          title: 'All orders',
          path: `/orders?view=${ALL}`,
          current: true,
        },
      ],
    });
    expect(overview).toMatchObject({
      id: 'overview',
      kind: 'dashboard',
      title: 'Overview',
      path: '/',
      current: false,
      views: [
        {
          id: systemInstanceId('overview', 'home'),
          title: 'Home board',
          current: false,
        },
      ],
    });
  });

  it('tells pages apart by the parameters their paths name, on a host routed by its search', () => {
    const byPage = [
      bind('orders', {
        route: view => `/?page=orders${view ? `&view=${view}` : ''}`,
      }),
      bind('overview', { route: () => '/?page=boards' }),
    ];
    hosted({ '/': <Navigation /> }, `/?page=orders&view=${ALL}`, {
      bindings: byPage,
    });
    const [orders, overview] = read();
    expect(orders.current).toBe(true);
    expect(orders.views[0].current).toBe(true);
    expect(overview.current).toBe(false);
  });

  it('says its titles in the words in force, and follows the bindings', () => {
    const engine = engineOf();
    const page = (messages: Record<string, string>, bound: ViewBinding[]) => (
      <ViewHost engine={engine} messages={messages} bindings={bound}>
        <Navigation />
      </ViewHost>
    );
    const view = render(page(EN, ROUTES));
    expect(read().map(item => item.title)).toEqual(['Orders', 'Overview']);
    view.rerender(page(ZH, ROUTES));
    expect(read()[0].title).toBe('订单');
    expect(read()[0].views[0].title).toBe('全部订单');
    // Without a router nothing is current; an unrouted resource is no place.
    expect(read().some(item => item.current)).toBe(false);
    view.rerender(page(ZH, [ROUTES[1]]));
    expect(read().map(item => item.id)).toEqual(['overview']);
  });
});

describe('the adapter itself', () => {
  it('is the router where it stands, and goes where it is told', async () => {
    const port = lastOf<ViewRouter>();
    function Probe() {
      port.told(useReactRouter());
      return null;
    }
    const router = createMemoryRouter([{ path: '*', element: <Probe /> }], {
      initialEntries: [{ pathname: '/a', search: '?x=1', state: { s: 1 } }],
    });
    render(<RouterProvider router={router} />);
    expect(port.value.location).toEqual({
      pathname: '/a',
      search: '?x=1',
      state: { s: 1 },
    });
    act(() => port.value.go('/b?y=2', { state: { t: 2 } }));
    await waitFor(() => expect(port.value.location.pathname).toBe('/b'));
    expect(router.state.historyAction).toBe('PUSH');
    expect(port.value.location).toEqual({
      pathname: '/b',
      search: '?y=2',
      state: { t: 2 },
    });
    act(() => port.value.go('/c', { replace: true }));
    await waitFor(() => expect(port.value.location.pathname).toBe('/c'));
    expect(router.state.historyAction).toBe('REPLACE');
  });
});
