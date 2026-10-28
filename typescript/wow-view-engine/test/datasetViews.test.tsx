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
  render,
  renderHook,
  screen,
  waitFor,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { DescriptorResult } from '@ahoo-wang/wow-client/dsl';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  type DatasetResource,
  DatasetResources,
  type DataViewDefinition,
  type DashboardPanel,
  defaultRecordConfig,
  MemoryViewStore,
  ViewEngine,
} from '../src/index.js';
import { useDatasets } from '../src/react/index.js';
import {
  DatasetWorkbench,
  datasetBindings,
  EmbeddedDashboard,
} from '../src/ui/index.js';
import {
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  resourcesOf,
  testSource,
} from './fixtures.js';
import { ordersDescriptor } from './fixtures/descriptor.js';

afterEach(cleanup);

/** A dataset served from catalog `catalog`, as `DatasetResources` answers it. */
function served(id: string, title?: string, catalog = 0): DatasetResource {
  const [context, dataset] = id.split('.');
  return {
    id,
    entry: {
      context,
      dataset,
      ...(title === undefined ? {} : { title }),
      version: 'sha256:1',
    },
    catalog,
  };
}

const ORDERS = served('shop.orders', 'Orders');
const MEMBERS = served('crm.members', undefined, 1);

function engineOf() {
  return new ViewEngine({
    resources: resourcesOf(
      [
        { ...ordersDefinition(), id: ORDERS.id, title: 'Orders' },
        { ...ordersDefinition(), id: MEMBERS.id, title: 'Members' },
      ],
      () => testSource(),
    ),
    store: new MemoryViewStore(),
  });
}

describe('useDatasets (D93)', () => {
  function resourcesOf2(answers: (() => Promise<DatasetResource[]>)[]) {
    let call = 0;
    return {
      refresh: vi.fn(() =>
        answers[Math.min(call++, answers.length - 1)]().then(datasets => ({
          datasets,
          duplicates: [],
        })),
      ),
    };
  }

  it('reads when it mounts and when the key changes, and reads nothing while paused', async () => {
    const resources = resourcesOf2([() => Promise.resolve([ORDERS])]);
    const { result, rerender } = renderHook(
      ({ key }: { key: string | null }) =>
        useDatasets(resources, { key: key ?? undefined, paused: key === null }),
      { initialProps: { key: null as string | null } },
    );
    expect(result.current.loading).toBe(false);
    expect(resources.refresh).not.toHaveBeenCalled();

    rerender({ key: 'alice' });
    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.datasets).toEqual([ORDERS]));
    expect(result.current.loading).toBe(false);

    rerender({ key: 'bob' });
    await waitFor(() => expect(resources.refresh).toHaveBeenCalledTimes(2));
    act(() => result.current.refresh());
    await waitFor(() => expect(resources.refresh).toHaveBeenCalledTimes(3));
  });

  it('calls off the read it no longer wants: on a new key, and when it is unmounted', async () => {
    const asked: AbortSignal[] = [];
    const resources = new DatasetResources(engineOf(), {
      catalogs: {
        list: options => {
          asked.push(options!.signal!);
          return new Promise(() => {});
        },
        source: () => testSource(),
      },
    });
    const { rerender, unmount } = renderHook(
      ({ key }: { key: string }) => useDatasets(resources, { key }),
      { initialProps: { key: 'alice' } },
    );
    await waitFor(() => expect(asked).toHaveLength(1));

    rerender({ key: 'bob' });
    await waitFor(() => expect(asked).toHaveLength(2));
    expect(asked.map(signal => signal.aborted)).toEqual([true, false]);

    unmount();
    expect(asked.map(signal => signal.aborted)).toEqual([true, true]);
  });

  it('keeps what it read when a read fails, and says why', async () => {
    const resources = resourcesOf2([
      () => Promise.resolve([ORDERS]),
      () => Promise.reject(new Error('no catalog')),
    ]);
    const { result } = renderHook(() => useDatasets(resources));
    await waitFor(() => expect(result.current.datasets).toEqual([ORDERS]));
    act(() => result.current.refresh());
    await waitFor(() =>
      expect(result.current.error).toEqual(new Error('no catalog')),
    );
    expect(result.current.datasets).toEqual([ORDERS]);
    expect(result.current.duplicates).toEqual([]);
  });
});

describe('datasetBindings (D93)', () => {
  it("binds each dataset served at the host's route, with the options it asks for", () => {
    const actions = { actions: [] };
    const bindings = datasetBindings([ORDERS, MEMBERS], {
      route: (dataset, view) => `/rows/${dataset.id}${view ? `/${view}` : ''}`,
      binding: dataset => (dataset.catalog === 1 ? actions : undefined),
    });
    expect(bindings.map(binding => binding.definitionId)).toEqual([
      ORDERS.id,
      MEMBERS.id,
    ]);
    expect(bindings[0].route?.(null)).toBe('/rows/shop.orders');
    expect(bindings[1].route?.('v-1')).toBe('/rows/crm.members/v-1');
    expect(bindings[0].actions).toBeUndefined();
    expect(bindings[1].actions).toBe(actions.actions);
  });
});

describe('DatasetWorkbench (D93)', () => {
  it('opens the first dataset served, and another as the reader picks it', async () => {
    const user = userEvent.setup();
    const onDatasetChange = vi.fn();
    render(
      <DatasetWorkbench
        engine={engineOf()}
        datasets={[ORDERS, MEMBERS]}
        onDatasetChange={onDatasetChange}
      />,
    );
    const picker = await screen.findByLabelText('Dataset');
    expect(picker.textContent).toContain('Orders (shop.orders)');
    expect(await screen.findByRole('heading', { name: 'Orders' })).toBeTruthy();

    await user.click(picker);
    await user.click(
      await screen.findByRole('option', { name: 'crm.members' }),
    );
    expect(onDatasetChange).toHaveBeenCalledWith(MEMBERS.id);
    expect(
      await screen.findByRole('heading', { name: 'Members' }),
    ).toBeTruthy();
  });

  it("follows a host's route when it holds the dataset open", async () => {
    const engine = engineOf();
    const { rerender } = render(
      <DatasetWorkbench
        engine={engine}
        datasets={[ORDERS, MEMBERS]}
        datasetId={MEMBERS.id}
      />,
    );
    expect(
      await screen.findByRole('heading', { name: 'Members' }),
    ).toBeTruthy();
    rerender(
      <DatasetWorkbench
        engine={engine}
        datasets={[ORDERS, MEMBERS]}
        datasetId="shop.gone"
      />,
    );
    expect(await screen.findByRole('heading', { name: 'Orders' })).toBeTruthy();
  });

  it('offers to read the datasets again when the host can', async () => {
    const onRefresh = vi.fn();
    const { rerender } = render(
      <DatasetWorkbench engine={engineOf()} datasets={[ORDERS]} />,
    );
    expect(screen.queryByLabelText('Read the datasets again')).toBeNull();
    rerender(
      <DatasetWorkbench
        engine={engineOf()}
        datasets={[ORDERS]}
        onRefresh={onRefresh}
      />,
    );
    await userEvent.click(screen.getByLabelText('Read the datasets again'));
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });

  it('says so while no dataset is served, and while the datasets are read', async () => {
    const { rerender } = render(
      <DatasetWorkbench engine={engineOf()} datasets={[]} />,
    );
    expect(await screen.findByText('No dataset yet')).toBeTruthy();
    rerender(<DatasetWorkbench engine={engineOf()} datasets={[]} loading />);
    expect(screen.queryByText('No dataset yet')).toBeNull();
    expect(screen.getByLabelText('Reading the datasets…')).toBeTruthy();
  });
});

describe('a board over datasets of several services (D93)', () => {
  it("holds panels over datasets two catalogs serve, as any board's", async () => {
    const engine = new ViewEngine({
      resources: resourcesOf([overviewDefinition({ id: 'boards' })], () =>
        testSource(),
      ),
      store: new MemoryViewStore(),
    });
    const sources: ReturnType<typeof testSource>[] = [];
    const catalog = (context: string, dataset: string) => ({
      list: () => Promise.resolve([{ context, dataset, version: 'sha256:1' }]),
      source: () => {
        const result: DescriptorResult = {
          notModified: false,
          descriptor: ordersDescriptor(),
          version: 'sha256:1',
        };
        const source = testSource({
          describe: vi.fn(() => Promise.resolve(result)),
        });
        sources.push(source);
        return source;
      },
    });
    const served = await new DatasetResources(engine, {
      catalogs: [catalog('shop', 'orders'), catalog('crm', 'members')],
    }).refresh();
    expect(served.datasets.map(({ id }) => id)).toEqual([
      'shop.orders',
      'crm.members',
    ]);

    const view = (id: string, title: string) =>
      engine.save(
        engine.create(id, {
          title,
          scope: 'shared',
          config: defaultRecordConfig(
            engine.definitions.get(id) as DataViewDefinition,
          ),
        }),
      );
    const orders = await view('shop.orders', 'Shop orders');
    const members = await view('crm.members', 'CRM members');
    const panel = (id: string, instanceId: string, x: number) =>
      ({
        id,
        kind: 'view',
        instanceId,
        bindings: [],
        layout: { x, y: 0, w: 12, h: 4 },
      }) as DashboardPanel;
    const board = await engine.save(
      engine.create('boards', {
        title: 'Across services',
        scope: 'shared',
        config: dashboardConfig({
          panels: [
            panel('orders', orders.id, 0),
            panel('members', members.id, 12),
          ],
        }),
      }),
    );

    render(<EmbeddedDashboard engine={engine} instanceId={board.id} />);
    expect(await screen.findByText('Shop orders')).toBeTruthy();
    expect(await screen.findByText('CRM members')).toBeTruthy();
    await waitFor(() =>
      sources.forEach(source => expect(source.paged).toHaveBeenCalled()),
    );
  });

  it('shows a panel whose dataset registers after the board opened', async () => {
    const store = new MemoryViewStore();
    const orders = { ...ordersDefinition(), id: ORDERS.id, title: 'Orders' };
    // A session that saved the board while the dataset was served.
    const before = new ViewEngine({
      resources: resourcesOf(
        [orders, overviewDefinition({ id: 'boards' })],
        () => testSource(),
      ),
      store,
    });
    const view = await before.save(
      before.create(ORDERS.id, {
        title: 'Shop orders',
        scope: 'shared',
        config: defaultRecordConfig(orders),
      }),
    );
    const board = await before.save(
      before.create('boards', {
        title: 'Across services',
        scope: 'shared',
        config: dashboardConfig({
          panels: [
            {
              id: 'orders',
              kind: 'view',
              instanceId: view.id,
              bindings: [],
              layout: { x: 0, y: 0, w: 12, h: 4 },
            } as DashboardPanel,
          ],
        }),
      }),
    );

    // The next session opens the board before the directory is read.
    const engine = new ViewEngine({
      resources: resourcesOf([overviewDefinition({ id: 'boards' })], () =>
        testSource(),
      ),
      store,
    });
    render(<EmbeddedDashboard engine={engine} instanceId={board.id} />);
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="panel-unavailable"]'),
      ).toBeTruthy(),
    );
    const source = testSource();
    act(() => engine.register({ definition: orders, source }));
    expect(await screen.findByText('Shop orders')).toBeTruthy();
    await waitFor(() => expect(source.paged).toHaveBeenCalled());
  });
});
