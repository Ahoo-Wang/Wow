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

import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type RuntimeLimits,
  type ViewInstance,
  type ViewListPage,
} from '../src/index.js';
import { toSummary } from '../src/model/instance.js';
import { DataWorkbench } from '../src/ui/index.js';
import {
  ordersDefinition,
  recordConfig,
  resourcesOf,
  testEnvironment,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

const shared = (count: number): ViewInstance[] =>
  Array.from({ length: count }, (_, at) => ({
    id: `ours-${at}`,
    definitionId: 'orders',
    title: `Ours ${at}`,
    scope: 'shared',
    revision: '1',
    config: recordConfig(),
  }));

/**
 * An engine over `instances` that lists at most `listedViews` of them, from
 * a store answering one view a page, so the cut falls across pages.
 */
function engineOver(instances: ViewInstance[], listedViews?: number) {
  return new ViewEngine({
    resources: resourcesOf([ordersDefinition()], () => testSource()),
    store: new MemoryViewStore({ instances, pageSize: 1 }),
    ...(listedViews === undefined ? {} : { limits: { listedViews } }),
  });
}

const CUT =
  'Only the first 2 views are listed; a link to another still opens it.';

describe('a list cut at RuntimeLimits.listedViews (R2-26)', () => {
  it('is said by the engine where the stored views reach the limit', async () => {
    const listing = await engineOver(shared(3), 2).list('orders');
    expect(listing.cut).toBe(2);
    expect(listing.items.map(item => item.id)).toEqual([
      'system:orders:all',
      'ours-0',
      'ours-1',
    ]);
    // Just under it, or at the default limit, the list is whole.
    expect((await engineOver(shared(1), 2).list('orders')).cut).toBeUndefined();
    expect((await engineOver(shared(3)).list('orders')).cut).toBeUndefined();
  });

  it('is said under the sidebar’s list and in Manage views', async () => {
    const user = userEvent.setup();
    render(
      <DataWorkbench
        engine={engineOver(shared(3), 2)}
        definitionId="orders"
        instanceId="ours-0"
      />,
    );
    await screen.findAllByRole('row');
    expect(await screen.findByText(CUT)).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Manage views' }));
    const dialog = await screen.findByRole('dialog');
    expect(
      dialog.querySelector('[data-slot="view-manager-cut"]')?.textContent,
    ).toBe(CUT);
  });

  it('says nothing of a whole list', async () => {
    render(
      <DataWorkbench
        engine={engineOver(shared(1), 2)}
        definitionId="orders"
        instanceId="ours-0"
      />,
    );
    await screen.findAllByRole('row');
    expect(screen.queryByText(CUT)).toBeNull();
  });
});

/** One page of a store's list, as a summary of each `shared(…)` view. */
const page = (
  views: ViewInstance[],
  nextCursor: string | null,
): ViewListPage => ({ items: views.map(toSummary), nextCursor });

describe('ViewEngine.list reads the store a page at a time', () => {
  /** An engine over a store whose list answers `pages`, by the cursor asked. */
  function engineOn(
    pages: Record<string, ViewListPage>,
    limits: Partial<RuntimeLimits> = {},
  ) {
    const store = new MemoryViewStore();
    const list = vi
      .spyOn(store, 'list')
      .mockImplementation((_, options) =>
        Promise.resolve(pages[options?.cursor ?? 'first']!),
      );
    const engine = new ViewEngine({
      resources: resourcesOf([ordersDefinition()], () => testSource()),
      store,
      limits,
    });
    return { engine, list };
  }

  it('reads every page in order, each with the cursor the one before answered', async () => {
    const [a, b, c] = shared(3);
    const { engine, list } = engineOn({
      first: page([a!], 'p2'),
      p2: page([b!], 'p3'),
      p3: page([c!], null),
    });

    const listing = await engine.list('orders');

    expect(list.mock.calls.map(([, options]) => options?.cursor)).toEqual([
      undefined,
      'p2',
      'p3',
    ]);
    expect(listing.items.map(item => item.id)).toEqual([
      'system:orders:all',
      'ours-0',
      'ours-1',
      'ours-2',
    ]);
    expect(listing.cut).toBeUndefined();
    expect(listing.failed).toBeNull();
  });

  it('lists a view two pages both answer once, where it was first seen', async () => {
    const [a, b, c] = shared(3);
    const { engine } = engineOn({
      // A view created between two reads moves the rest down a place.
      first: page([a!, b!], 'p2'),
      p2: page([b!, c!], null),
    });

    const listing = await engine.list('orders');

    expect(listing.items.map(item => item.id)).toEqual([
      'system:orders:all',
      'ours-0',
      'ours-1',
      'ours-2',
    ]);
  });

  it('stops asking at RuntimeLimits.listedViews, and says where it cut', async () => {
    const views = shared(4);
    const { engine, list } = engineOn(
      {
        first: page(views.slice(0, 2), 'p2'),
        p2: page(views.slice(2, 3), 'p3'),
        p3: page(views.slice(3), null),
      },
      { listedViews: 3 },
    );

    const listing = await engine.list('orders');

    // The third page is never asked for: the limit is reached before it.
    expect(list).toHaveBeenCalledTimes(2);
    expect(listing.cut).toBe(3);
    expect(listing.items.map(item => item.id)).toEqual([
      'system:orders:all',
      'ours-0',
      'ours-1',
      'ours-2',
    ]);
  });

  it('hands the abort back to the caller rather than as a failed list', async () => {
    const store = new MemoryViewStore({ instances: shared(1) });
    const seen: (AbortSignal | undefined)[] = [];
    vi.spyOn(store, 'list').mockImplementation(
      (_, options) =>
        new Promise((_, reject) => {
          seen.push(options?.signal);
          options?.signal?.addEventListener('abort', () =>
            reject(options.signal!.reason),
          );
        }),
    );
    const reported: unknown[] = [];
    const engine = new ViewEngine({
      resources: resourcesOf([ordersDefinition()], () => testSource()),
      store,
      environment: {
        ...testEnvironment().environment,
        onError: event => reported.push(event),
      },
    });
    const controller = new AbortController();

    const listing = engine.list('orders', { signal: controller.signal });
    controller.abort();

    await expect(listing).rejects.toMatchObject({ name: 'AbortError' });
    expect(seen).toEqual([controller.signal]);
    // Called off is nobody's failure: the host hears of none.
    expect(reported).toEqual([]);
  });
});
