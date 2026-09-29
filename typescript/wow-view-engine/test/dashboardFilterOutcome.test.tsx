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
 * A change of the board's filters is said once its panels have settled
 * (WCAG 4.1.3): which filters, how many panels they reached, and how many
 * of those could not load — in the board's one voice, in both catalogues.
 */

import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type DashboardPanel,
  type DashboardViewConfig,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import { DashboardWorkbench, zhCN } from '../src/ui/index.js';
import type { ViewMessages } from '../src/ui/kit/messages.js';
import {
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  resourcesOf,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

const views: ViewInstance[] = ['one', 'two', 'three'].map(id => ({
  id,
  definitionId: 'orders',
  title: `List ${id}`,
  scope: 'shared',
  revision: 'r1',
  config: recordConfig(),
}));

function panel(id: string, x: number, wired: boolean): DashboardPanel {
  return {
    id,
    kind: 'view',
    instanceId: id,
    bindings: wired ? [{ globalField: 'region', panelField: 'warehouse' }] : [],
    layout: { x, y: 0, w: 8, h: 4 },
    title: `Panel ${id}`,
  } as DashboardPanel;
}

/** A region filter wired to two of three panels. */
function board(): DashboardViewConfig {
  return dashboardConfig({
    fields: [{ name: 'region', label: 'Region', kind: 'string' }],
    panels: [
      panel('one', 0, true),
      panel('two', 8, true),
      panel('three', 16, false),
    ],
  });
}

function setup(source: ViewSource = testSource(), messages?: ViewMessages) {
  const store = new MemoryViewStore({
    instances: [
      ...views,
      {
        id: 'board',
        definitionId: 'overview',
        title: 'Operations',
        scope: 'personal',
        revision: '1',
        config: board(),
      },
    ],
  });
  const engine = new ViewEngine({
    resources: resourcesOf(
      [ordersDefinition(), overviewDefinition()],
      () => source,
    ),
    store,
  });
  render(
    <DashboardWorkbench
      engine={engine}
      definitionId="overview"
      instanceId="board"
      {...(messages ? { messages } : {})}
    />,
  );
  const runtime = () =>
    engine
      .openRuntimes()
      .find(
        (open): open is DashboardViewRuntime =>
          open instanceof DashboardViewRuntime,
      )!;
  return { runtime };
}

const said = () =>
  document.querySelector('[data-slot="dashboard-announcement"]')?.textContent ??
  '';

async function opened(runtime: () => DashboardViewRuntime) {
  await screen.findByRole('region', { name: /Filters|筛选/ });
  await waitFor(() =>
    expect(
      runtime()
        .getSnapshot()
        .panels.every(
          entry => entry.runtime?.getSnapshot().query.status === 'success',
        ),
    ).toBe(true),
  );
}

describe('a change of the board’s filters', () => {
  it('says nothing as the board opens', async () => {
    const { runtime } = setup();
    await opened(runtime);
    expect(said()).toBe('');
  });

  it('says which filter and how many panels it reached, once they settle', async () => {
    const { runtime } = setup();
    await opened(runtime);

    act(() => void runtime().setFilterValue('region', ['CN']));
    await waitFor(() =>
      expect(said()).toBe('Filtered by Region; 2 panels updated.'),
    );

    act(() => void runtime().setFilterValue('region', null));
    await waitFor(() =>
      expect(said()).toBe('Cleared Region; 2 panels updated.'),
    );
  });

  it('says how many of them could not load', async () => {
    let fail = false;
    const source = testSource({
      paged: vi.fn(() =>
        fail
          ? Promise.reject(new Error('down'))
          : Promise.resolve({ total: 2, list: [] }),
      ),
    });
    const { runtime } = setup(source);
    await opened(runtime);

    fail = true;
    act(() => void runtime().setFilterValue('region', ['CN']));
    await waitFor(() =>
      expect(said()).toBe(
        'Filtered by Region; 0 panels updated. 2 panels could not load.',
      ),
    );
  });

  it('says it in the board’s language', async () => {
    const { runtime } = setup(testSource(), zhCN);
    await opened(runtime);

    act(() => void runtime().setFilterValue('region', ['CN']));
    await waitFor(() =>
      expect(said()).toBe('已按Region筛选，2 个面板已更新。'),
    );
  });
});
