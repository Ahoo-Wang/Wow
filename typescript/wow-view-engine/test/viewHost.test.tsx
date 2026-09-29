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
 * `ViewHost` and `bind` (host-integration.md 4): one engine for
 * the application, its words at render time, each definition's route and
 * reading bound once, surfaces taking only what differs where they stand.
 */

import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  systemInstanceId,
  text,
  type ViewNavigation,
} from '../src/index.js';
import {
  bind,
  DataWorkbench,
  EmbeddedView,
  ViewHost,
  type ViewBinding,
  type ViewDestination,
} from '../src/ui/index.js';
import { resolveNavigation } from '../src/testing/index.js';
import {
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

const EN = { 'orders.title': 'Orders', 'orders.all': 'All orders' };
const ZH = { 'orders.title': '订单', 'orders.all': '全部订单' };

function keyedEngine() {
  const source = testSource();
  const engine = new ViewEngine({
    resources: [
      {
        definition: ordersDefinition({
          title: text('orders.title'),
          views: [
            { id: 'all', title: text('orders.all'), config: recordConfig() },
          ],
        }),
        source,
      },
      { definition: overviewDefinition() },
    ],
    store: new MemoryViewStore(),
    onIssue: () => {},
  });
  return { engine, source };
}

describe('one engine, every language', () => {
  it('switches the words of what is open without rebuilding or reopening anything', async () => {
    const { engine, source } = keyedEngine();
    const open = vi.spyOn(engine, 'open');
    const page = (messages: Record<string, string>, locale: string) => (
      <ViewHost engine={engine} messages={messages} locale={locale}>
        <DataWorkbench definitionId="orders" />
      </ViewHost>
    );
    const { rerender } = render(page(EN, 'en'));
    expect(
      await screen.findByRole('heading', { name: 'All orders' }),
    ).toBeTruthy();
    await waitFor(() => expect(source.paged).toHaveBeenCalled());
    const queries = vi.mocked(source.paged).mock.calls.length;
    const [runtime] = engine.openRuntimes();

    rerender(page(ZH, 'zh-CN'));
    expect(
      await screen.findByRole('heading', { name: '全部订单' }),
    ).toBeTruthy();
    expect(screen.queryByText('All orders')).toBeNull();
    expect(open).toHaveBeenCalledTimes(1);
    expect(engine.openRuntimes()).toEqual([runtime]);
    expect(vi.mocked(source.paged).mock.calls.length).toBe(queries);
    // The definition and the state keep their keys; only the screen says them.
    expect(engine.definitions.get('orders')?.title).toBe(text('orders.title'));
    expect(runtime.getSnapshot().title).toBe(text('orders.all'));
  });
});

describe('an engine from the provider, or the surface’s own', () => {
  it('draws the provider’s engine, and an inner provider or a prop overrides it', async () => {
    const outer = keyedEngine().engine;
    const inner = keyedEngine().engine;
    const own = keyedEngine().engine;
    render(
      <ViewHost engine={outer} messages={EN}>
        <EmbeddedView
          instanceId={systemInstanceId('orders', 'all')}
          withTitle
        />
        <ViewHost engine={inner} messages={ZH}>
          <EmbeddedView
            instanceId={systemInstanceId('orders', 'all')}
            withTitle
          />
          <EmbeddedView
            engine={own}
            instanceId={systemInstanceId('orders', 'all')}
            withTitle
          />
        </ViewHost>
      </ViewHost>,
    );
    expect(await screen.findByText('All orders')).toBeTruthy();
    // The inner provider's words, for its engine and a surface's own alike.
    expect(await screen.findAllByText('全部订单')).toHaveLength(2);
    await waitFor(() => {
      expect(outer.openRuntimes()).toHaveLength(1);
      expect(inner.openRuntimes()).toHaveLength(1);
      expect(own.openRuntimes()).toHaveLength(1);
    });
  });

  it('says so when there is no engine at all', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => render(<DataWorkbench definitionId="orders" />)).toThrow(
      /ViewHost/,
    );
  });
});

const BINDINGS: ViewBinding[] = [
  bind('orders', { route: id => (id ? `/orders?view=${id}` : '/orders') }),
  bind('overview', { route: id => `/boards/${id}` }),
];

function bindingOf(bindings: readonly ViewBinding[]) {
  return (id: string) => bindings.find(entry => entry.definitionId === id);
}

describe('routes (host-integration.md 4)', () => {
  it('resolves a view, an unsaved view and a board through the route of the definition they lead to', () => {
    const view: ViewNavigation = {
      kind: 'view',
      definitionId: 'orders',
      instanceId: 'mine',
      scopeFilter: null,
      filter: null,
    };
    expect(resolveNavigation(view, bindingOf(BINDINGS))).toEqual({
      kind: 'route',
      path: '/orders?view=mine',
      state: { handOver: view },
      target: view,
    });
    const unsaved: ViewNavigation = {
      kind: 'unsaved',
      definitionId: 'orders',
      title: 'Orders · CN',
      config: recordConfig(),
      scopeFilter: null,
    };
    expect(resolveNavigation(unsaved, bindingOf(BINDINGS))).toMatchObject({
      path: '/orders',
      state: { handOver: unsaved },
    });
    const board: ViewNavigation = {
      kind: 'dashboard',
      definitionId: 'overview',
      instanceId: 'ops',
      filters: { values: {} },
      tab: 'week',
    };
    expect(resolveNavigation(board, bindingOf(BINDINGS))).toMatchObject({
      path: '/boards/ops',
      state: { filters: { values: {} }, tab: 'week' },
    });
  });

  it('hands a URL and a definition bound no route over as they came', () => {
    const url: ViewNavigation = { kind: 'url', url: '/elsewhere' };
    expect(resolveNavigation(url, bindingOf(BINDINGS))).toBe(url);
    const board: ViewNavigation = {
      kind: 'dashboard',
      definitionId: 'overview',
      instanceId: 'ops',
      filters: { values: {} },
    };
    expect(resolveNavigation(board, bindingOf([]))).toBe(board);
  });

  it('routes a surface’s way off through the provider, and a surface’s own route wins', async () => {
    const { engine } = keyedEngine();
    const navigate = vi.fn<(to: ViewDestination) => void>();
    const own = vi.fn<(to: ViewNavigation) => void>();
    render(
      <ViewHost
        engine={engine}
        messages={EN}
        navigate={navigate}
        bindings={BINDINGS}
      >
        <EmbeddedView
          instanceId={systemInstanceId('orders', 'all')}
          interaction="interactive"
        />
        <ViewHost bindings={[bind('orders')]}>
          <EmbeddedView
            instanceId={systemInstanceId('orders', 'all')}
            interaction="interactive"
          />
        </ViewHost>
        <EmbeddedView
          instanceId={systemInstanceId('orders', 'all')}
          interaction="interactive"
          onNavigate={own}
        />
      </ViewHost>,
    );
    const [routed, unrouted, overridden] = await screen.findAllByRole(
      'button',
      { name: 'Open in the workbench' },
    );
    await userEvent.click(routed);
    expect(navigate).toHaveBeenLastCalledWith(
      expect.objectContaining({
        kind: 'route',
        path: `/orders?view=${systemInstanceId('orders', 'all')}`,
      }),
    );
    // An inner provider's binding wins by id: this one has no route, so
    // the target reaches the host's navigate as it came.
    await userEvent.click(unrouted);
    expect(navigate).toHaveBeenLastCalledWith(
      expect.objectContaining({ kind: 'view', definitionId: 'orders' }),
    );
    await userEvent.click(overridden);
    expect(own).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledTimes(2);
  });
});

describe('reading (D60)', () => {
  it('reads a record the way the host bound, wherever the detail opens', async () => {
    const { engine } = keyedEngine();
    render(
      <ViewHost
        engine={engine}
        messages={EN}
        bindings={[
          bind('orders', {
            reading: {
              title: row => `Order ${String(row.key)}`,
              render: () => <p>Read the host’s way</p>,
            },
          }),
        ]}
      >
        <EmbeddedView
          instanceId={systemInstanceId('orders', 'all')}
          interaction="interactive"
          detail
        />
      </ViewHost>,
    );
    const rows = await screen.findAllByRole('row');
    await userEvent.click(rows[1]);
    expect(await screen.findByText('Read the host’s way')).toBeTruthy();
    expect(screen.getByText('Order o-1')).toBeTruthy();
  });
});

describe('nested providers and one engine’s words (review of #3761, 3)', () => {
  it('say the definitions in the words of the provider nearest them, as they say the engine’s own', async () => {
    const { engine } = keyedEngine();
    const page = (inner: Record<string, string>) => (
      <ViewHost engine={engine} messages={EN}>
        <ViewHost engine={engine} messages={inner}>
          <EmbeddedView
            instanceId={systemInstanceId('orders', 'all')}
            withTitle
          />
        </ViewHost>
        <ViewHost messages={inner}>
          <EmbeddedView
            instanceId={systemInstanceId('orders', 'all')}
            withTitle
          />
        </ViewHost>
      </ViewHost>
    );
    const { rerender } = render(page(ZH));
    expect(await screen.findAllByText('全部订单')).toHaveLength(2);
    rerender(page({ ...ZH, 'orders.all': '所有订单' }));
    await waitFor(() =>
      expect(screen.getAllByText('所有订单')).toHaveLength(2),
    );
    expect(screen.queryByText('All orders')).toBeNull();
    expect(engine.definitions.get('orders')?.title).toBe(text('orders.title'));
  });

  it('check the words of the outermost provider that names the engine for the keys they lack', async () => {
    const issues: string[] = [];
    const engine = new ViewEngine({
      resources: [
        {
          definition: ordersDefinition({
            title: text('orders.title'),
            views: [
              { id: 'all', title: text('orders.all'), config: recordConfig() },
            ],
          }),
          source: testSource(),
        },
      ],
      store: new MemoryViewStore(),
      onIssue: found =>
        issues.push(`${found.code}:${String(found.params?.key)}`),
    });
    render(
      <ViewHost engine={engine} messages={{ 'orders.title': 'O' }}>
        <ViewHost engine={engine} messages={EN}>
          <EmbeddedView
            instanceId={systemInstanceId('orders', 'all')}
            withTitle
          />
        </ViewHost>
      </ViewHost>,
    );
    expect(await screen.findByText('All orders')).toBeTruthy();
    expect(issues).toEqual(['definition.text.unknown:orders.all']);
  });
});

describe('an embed’s bound reading (review of #3761, 5)', () => {
  it('reads the host’s way, but holds its own open record', async () => {
    const { engine } = keyedEngine();
    const onOpenChange = vi.fn();
    render(
      <ViewHost
        engine={engine}
        messages={EN}
        bindings={[
          bind('orders', {
            reading: {
              open: null,
              onOpenChange,
              render: () => <p>Read the host’s way</p>,
            },
          }),
        ]}
      >
        <EmbeddedView
          instanceId={systemInstanceId('orders', 'all')}
          interaction="interactive"
          detail
        />
        <EmbeddedView
          instanceId={systemInstanceId('orders', 'all')}
          interaction="interactive"
          detail
        />
      </ViewHost>,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(6));
    await userEvent.click(screen.getAllByRole('row')[1]);
    expect(await screen.findAllByText('Read the host’s way')).toHaveLength(1);
    expect(onOpenChange).not.toHaveBeenCalled();
  });
});

describe('the words the engine started with (review of #3761, 12)', () => {
  it('stay under a provider that gives no words of its own', async () => {
    const source = testSource();
    const engine = new ViewEngine({
      resources: [
        {
          definition: ordersDefinition({
            views: [
              { id: 'all', title: text('orders.all'), config: recordConfig() },
            ],
          }),
          source,
        },
      ],
      store: new MemoryViewStore(),
      text: key => (ZH as Record<string, string>)[key],
      onIssue: () => {},
    });
    render(
      <ViewHost engine={engine}>
        <EmbeddedView
          instanceId={systemInstanceId('orders', 'all')}
          withTitle
        />
      </ViewHost>,
    );
    expect(await screen.findByText('全部订单')).toBeTruthy();
  });
});

describe('an engine named again under another engine (second review of #3761)', () => {
  it('says it in the nearest provider’s words through A > B > A', async () => {
    const { engine } = keyedEngine();
    const other = keyedEngine().engine;
    const page = (inner: Record<string, string>) => (
      <ViewHost engine={engine} messages={EN}>
        <ViewHost engine={other} messages={ZH}>
          <ViewHost engine={engine} messages={inner}>
            <EmbeddedView
              instanceId={systemInstanceId('orders', 'all')}
              withTitle
            />
          </ViewHost>
        </ViewHost>
      </ViewHost>
    );
    const { rerender } = render(page(ZH));
    expect(await screen.findByText('全部订单')).toBeTruthy();
    rerender(page({ ...ZH, 'orders.all': '所有订单' }));
    await waitFor(() => expect(screen.getByText('所有订单')).toBeTruthy());
    expect(engine.definitions.get('orders')?.title).toBe(text('orders.title'));
    expect(other.definitions.get('orders')?.title).toBe(text('orders.title'));
  });
});

describe('a host whose definitions speak through ViewEngineOptions.text (third review of #3761, 3)', () => {
  it('hears of no fallback under a provider with words of its own for none of them', async () => {
    const issues: string[] = [];
    const engine = new ViewEngine({
      resources: [
        {
          definition: ordersDefinition({
            views: [
              { id: 'all', title: text('orders.all'), config: recordConfig() },
            ],
          }),
          source: testSource(),
        },
      ],
      store: new MemoryViewStore(),
      text: key => (EN as Record<string, string>)[key],
      onIssue: found => issues.push(found.code),
    });
    render(
      <ViewHost
        engine={engine}
        locale="en"
        messages={{ 'label.filter.apply': 'Apply' }}
      >
        <EmbeddedView
          instanceId={systemInstanceId('orders', 'all')}
          withTitle
        />
      </ViewHost>,
    );
    expect(await screen.findByText('All orders')).toBeTruthy();
    expect(issues.filter(code => code.startsWith('definition.text.'))).toEqual(
      [],
    );
  });
});
