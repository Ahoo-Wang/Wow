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
 * A saved view a panel shows that could not be read: gone for this reader
 * (the store's `NOT_FOUND` or `FORBIDDEN`) is `dashboard.panel.unavailable`,
 * 「deleted, or not shared with you」; anything that may pass — the store
 * unreachable, or answering with an error of its own — is
 * `dashboard.panel.failed`, and the panel's retry, or the board's refresh,
 * reads it again. A 503 used to send the reader to the view's owner.
 */

import { describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  type DashboardPanel,
  type ViewInstance,
} from '../src/index.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import {
  dashboardConfig,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testEnvironment,
  testSource,
  resourcesOf,
} from './fixtures.js';

const pending: ViewInstance = {
  id: 'pending',
  definitionId: 'orders',
  title: 'Pending orders',
  scope: 'shared',
  revision: 'r1',
  config: recordConfig(),
};

const onePanel = {
  id: 'orders',
  kind: 'view',
  instanceId: 'pending',
  bindings: [],
  layout: { x: 0, y: 0, w: 12, h: 4 },
} as DashboardPanel;

/** A board of one panel over `pending`, whose first read fails with `error`. */
async function openBoard(error: unknown) {
  const clock = testEnvironment();
  const store = new MemoryViewStore({ instances: [pending] });
  const engine = new ViewEngine({
    resources: resourcesOf([ordersDefinition(), overviewDefinition()], () =>
      testSource(),
    ),
    store,
    environment: clock.environment,
  });
  const board = await store.create(
    {
      definitionId: 'overview',
      title: 'Overview',
      scope: 'personal',
      config: dashboardConfig({ panels: [onePanel] }),
    },
    { requestId: 'r' },
  );
  const get = store.get.bind(store);
  const reads = vi
    .spyOn(store, 'get')
    .mockImplementationOnce(get)
    .mockImplementationOnce(() => Promise.reject(error));
  const runtime = await engine.open(board.id);
  await nextTask();
  if (!(runtime instanceof DashboardViewRuntime))
    throw new Error('expected a dashboard');
  /** How many times the panel's view was read. */
  const panelReads = () =>
    reads.mock.calls.filter(([id]) => id === 'pending').length;
  return { runtime, panelReads };
}

const codes = (issues: readonly { code: string }[]) =>
  issues.map(found => found.code);

describe('a panel whose view could not be read', () => {
  it.each([
    ['NOT_FOUND', new ViewStoreError('NOT_FOUND', 'gone')],
    ['FORBIDDEN', new ViewStoreError('FORBIDDEN', 'not yours')],
  ])('is said to be gone when the store answers %s', async (_, error) => {
    const { runtime } = await openBoard(error);
    const state = runtime.getSnapshot();

    expect(codes(state.panels[0].issues)).toEqual([
      'dashboard.panel.unavailable',
    ]);
  });

  it.each([
    ['unreachable', new ViewStoreError('UNAVAILABLE', 'offline')],
    [
      'answering with an error',
      new ViewStoreError('UNAVAILABLE', '503', { reachable: true }),
    ],
    ['failing in a way nobody named', new TypeError('Failed to fetch')],
  ])(
    'is said to have failed, not to be gone, when the store is %s',
    async (_, error) => {
      const { runtime } = await openBoard(error);
      const state = runtime.getSnapshot();

      expect(codes(state.panels[0].issues)).toEqual(['dashboard.panel.failed']);
      expect(state.panels[0].issues[0].params).toMatchObject({
        instance: 'pending',
      });
      expect(state.panels[0].runtime).toBeNull();
      // Not above the board either: the panel says it, once.
      expect(codes(state.issues)).toEqual(['dashboard.panel.failed']);
      expect(state.resolving).toBe(false);
    },
  );

  it('reads it again on its retry, and runs once it is read', async () => {
    const { runtime, panelReads } = await openBoard(
      new ViewStoreError('UNAVAILABLE', 'offline'),
    );
    expect(panelReads()).toBe(1);

    runtime.refreshPanel('orders');
    expect(runtime.getSnapshot().resolving).toBe(true);
    await nextTask();

    const state = runtime.getSnapshot();
    expect(panelReads()).toBe(2);
    expect(state.panels[0].issues).toEqual([]);
    expect(state.panels[0].runtime).not.toBeNull();
    expect(state.resolving).toBe(false);
  });

  it("reads it again on the board's refresh", async () => {
    const { runtime, panelReads } = await openBoard(
      new ViewStoreError('UNAVAILABLE', 'offline'),
    );

    runtime.refresh();
    await nextTask();

    expect(panelReads()).toBe(2);
    expect(runtime.getSnapshot().panels[0].runtime).not.toBeNull();
  });

  it('is not read again when it is gone', async () => {
    const { runtime, panelReads } = await openBoard(
      new ViewStoreError('NOT_FOUND', 'gone'),
    );

    runtime.refresh();
    runtime.refreshPanel('orders');
    await nextTask();

    expect(panelReads()).toBe(1);
    expect(codes(runtime.getSnapshot().panels[0].issues)).toEqual([
      'dashboard.panel.unavailable',
    ]);
  });
});
