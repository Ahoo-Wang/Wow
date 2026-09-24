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
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import { DataWorkbench, type WorkbenchFeatures } from '../src/ui/index.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';
import { mine } from './fixtures/ui.js';

afterEach(cleanup);

/** The same definition with one saved analysis view open on a chart. */
function analysisEngine() {
  return new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({
      instances: [
        {
          id: 'orders-2',
          definitionId: 'orders',
          title: 'By warehouse',
          scope: 'personal',
          revision: '1',
          config: analysisConfig({ layout: 'chart' }),
        },
      ],
    }),
    resolveSource: () => testSource(),
  });
}

function engineWith(rows = true) {
  return new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances: [mine] }),
    resolveSource: () =>
      rows
        ? testSource()
        : testSource({
            paged: vi.fn(() => Promise.resolve({ total: 0, list: [] })),
          }),
  });
}

const OFF: Required<WorkbenchFeatures> = {
  export: false,
  layouts: false,
  columns: false,
  sort: false,
  visualization: false,
  manage: false,
  search: false,
};

/**
 * Which of the workbench's own controls exist is the host's to say (D18 XI),
 * and one it says no to is absent rather than disabled (D4). Before this
 * the only switch was `expandable`; a view that must not be exported had to
 * be a workbench rebuilt from parts.
 */
describe('workbench features', () => {
  it('draws every control by default', async () => {
    render(
      <DataWorkbench
        engine={engineWith()}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    for (const name of ['Export', 'Columns', 'Sort', 'Manage views'])
      expect(screen.getByRole('button', { name })).toBeDefined();
    expect(screen.getByRole('group', { name: 'Layout' })).toBeDefined();
  });

  it('leaves out every control a host switched off', async () => {
    render(
      <DataWorkbench
        engine={engineWith()}
        definitionId="orders"
        instanceId="orders-1"
        features={OFF}
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    for (const name of ['Export', 'Columns', 'Sort', 'Manage views'])
      expect(screen.queryByRole('button', { name })).toBeNull();
    expect(screen.queryByRole('group', { name: 'Layout' })).toBeNull();
    // Absent, not disabled: nothing on the page is a grey control.
    expect(screen.queryByRole('group', { name: 'Table settings' })).toBeNull();
    // The rows, the pagination and the filter are not features; they stay.
    expect(
      screen.getByRole('navigation', { name: 'Pagination' }),
    ).toBeDefined();
  });

  it('keeps the manager out of the switcher too', async () => {
    render(
      <DataWorkbench
        engine={engineWith()}
        definitionId="orders"
        instanceId="orders-1"
        defaultSidebarOpen={false}
        features={{ manage: false }}
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    fireEvent.click(screen.getByRole('button', { name: 'Switch view' }));
    expect(
      await screen.findByRole('menuitem', { name: 'New view' }),
    ).toBeDefined();
    expect(screen.queryByRole('menuitem', { name: 'Manage views' })).toBeNull();
  });

  /**
   * The analysis view's own feature (D20 屏 I／J). It is the panel and the
   * one way into it at once: a host that switched it off has a result read
   * as the saved config says, and no control anywhere that offers to change
   * that — because a panel nobody can reach is a panel that does not exist.
   */
  it('opens the visualization panel by default and drops it when switched off', async () => {
    const { rerender } = render(
      <DataWorkbench
        engine={analysisEngine()}
        definitionId="orders"
        instanceId="orders-2"
        kinds={['analysis']}
      />,
    );
    fireEvent.click(await screen.findByRole('button', { name: 'Visualize' }));
    expect(await screen.findByRole('radio', { name: 'bar' })).toBeDefined();

    rerender(
      <DataWorkbench
        engine={analysisEngine()}
        definitionId="orders"
        instanceId="orders-2"
        kinds={['analysis']}
        features={{ visualization: false }}
      />,
    );
    // The result is still read — the layout switch beside it is not this
    // feature — but nothing offers to draw it another way.
    await waitFor(() =>
      expect(
        screen.getByRole('group', { name: 'Show result as' }),
      ).toBeDefined(),
    );
    expect(screen.queryByRole('button', { name: 'Visualize' })).toBeNull();
    expect(document.querySelector('[data-slot="chart-picker"]')).toBeNull();
    expect(document.querySelector('[data-slot="view-panel"]')).toBeNull();
  });
});

describe("the empty result's action", () => {
  it("is the workbench's by default, the host's when given, and none on null", async () => {
    const own = vi.fn();
    const { rerender } = render(
      <DataWorkbench
        engine={engineWith(false)}
        definitionId="orders"
        instanceId="orders-1"
        record={{ emptyAction: own }}
      />,
    );
    // Which of the two ways out it is depends on the conditions; either
    // way the press is the host's.
    const button = await screen.findByRole('button', { name: /condition/ });
    fireEvent.click(button);
    expect(own).toHaveBeenCalledTimes(1);

    rerender(
      <DataWorkbench
        engine={engineWith(false)}
        definitionId="orders"
        instanceId="orders-1"
        record={{ emptyAction: null }}
      />,
    );
    // A new engine opens the view again, so the empty result is awaited:
    // the sentence stays, and the button does not come back with it.
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="empty-title"]')?.textContent,
      ).toBe('Nothing to show'),
    );
    expect(screen.queryByRole('button', { name: /condition/ })).toBeNull();
  });
});
