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
 * Words at the leaf (D2, user 2026-09-28): a definition's keys stay keys in
 * every state, snapshot and editor, and are said only where they are shown
 * or leave the engine. The guard: the main surfaces drawn from keyed
 * definitions put no key marker (U+E000 / U+E001) into the document — in
 * its text, its attributes or its fields — and neither does an export.
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
import { getInstanceByDom } from 'echarts/core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  systemInstanceId,
  type DashboardDefinition,
  type DataViewDefinition,
} from '../src/index.js';
import {
  DashboardWorkbench,
  DataWorkbench,
  EmbeddedDashboard,
  EmbeddedView,
  ViewEngineProvider,
} from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  namedOrdersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';
import { keyed, markersIn } from './fixtures/keyed.js';
import { editorToggle, openTray } from './fixtures/workbench.js';

afterEach(cleanup);

/** Every chart drawn, as ECharts holds it: its option is what it shows. */
function drawnCharts(): { getOption(): unknown }[] {
  return [...document.querySelectorAll('[_echarts_instance_]')].flatMap(
    element => getInstanceByDom(element as HTMLElement) ?? [],
  );
}

const EN: Record<string, string> = {};
const ZH: Record<string, string> = {};

/** The orders, every label a key: the definition, its views, their configs. */
function ordersDefinition(): DataViewDefinition {
  const base = namedOrdersDefinition();
  const literal: DataViewDefinition = {
    ...base,
    title: 'Orders',
    fieldGroups: [{ id: 'money', label: 'Money', fields: ['amount'] }],
    fields: base.fields.map(field =>
      field.name === 'amount'
        ? { ...field, description: 'What the order came to' }
        : field,
    ),
    views: [
      {
        id: 'all',
        title: 'All orders',
        config: recordConfig({
          table: {
            columns: [
              { field: 'id' },
              { field: 'warehouse' },
              { field: 'amount' },
            ],
          },
        }),
      },
      {
        id: 'by-warehouse',
        title: 'By warehouse',
        config: analysisConfig({
          metrics: [
            { alias: 'orders', type: 'COUNT', label: 'Order count' },
            {
              alias: 'amount_sum',
              type: 'NUMERIC',
              function: 'SUM',
              expression: { type: 'FIELD', field: 'amount' },
            },
          ],
          layout: 'table',
        }),
      },
      {
        id: 'chart',
        title: 'Orders chart',
        config: analysisConfig({
          metrics: [{ alias: 'orders', type: 'COUNT', label: 'Order count' }],
          layout: 'chart',
          chart: {
            type: 'bar',
            cartesian: {
              x: 'warehouse',
              series: [{ metric: 'orders' }],
              yAxis: { left: { label: 'How many' } },
              referenceLines: [
                { axis: 'left', value: 1, label: 'Target line' },
              ],
            },
          },
        }),
      },
    ],
  } as DataViewDefinition;
  const en = keyed(literal, EN, 'orders');
  keyed(literal, ZH, 'orders', original => `中:${original}`);
  return en;
}

function boardDefinition(): DashboardDefinition {
  const literal = overviewDefinition({
    title: 'Overview',
    views: [
      {
        id: 'main',
        title: 'Main board',
        config: dashboardConfig({
          fields: [{ name: 'region', label: 'Region', kind: 'string' }],
          panels: [
            {
              id: 'heading',
              kind: 'heading',
              content: 'Today',
              layout: { x: 0, y: 0, w: 24, h: 1 },
            },
            {
              id: 'note',
              kind: 'markdown',
              content: 'Read me',
              title: 'Note',
              layout: { x: 0, y: 1, w: 12, h: 2 },
            },
            {
              id: 'links',
              kind: 'links',
              title: 'Links',
              items: [
                {
                  label: 'Docs',
                  href: 'https://example.com',
                  description: 'The manual',
                },
              ],
              layout: { x: 12, y: 1, w: 12, h: 2 },
            },
            {
              id: 'list',
              kind: 'view',
              title: 'Order list',
              instanceId: systemInstanceId('orders', 'all'),
              bindings: [{ globalField: 'region', panelField: 'warehouse' }],
              layout: { x: 0, y: 3, w: 12, h: 4 },
            },
            {
              id: 'by',
              kind: 'view',
              instanceId: systemInstanceId('orders', 'by-warehouse'),
              bindings: [],
              layout: { x: 12, y: 3, w: 12, h: 4 },
            },
          ],
        }),
      },
    ],
  });
  const en = keyed(literal, EN, 'board');
  keyed(literal, ZH, 'board', original => `中:${original}`);
  return en;
}

function keyedEngine() {
  const source = testSource();
  const board = boardDefinition();
  const engine = new ViewEngine({
    resources: [
      { definition: ordersDefinition(), source },
      { definition: board },
    ],
    // A reader's copy of the board, saved as it was made: keys and all.
    store: new MemoryViewStore({
      instances: [
        {
          id: 'mine',
          definitionId: 'overview',
          title: board.views![0].title,
          scope: 'personal',
          revision: '1',
          config: board.views![0].config,
        },
      ],
    }),
    onIssue: found => {
      if (process.env.LEAF_DEBUG) console.log('issue', JSON.stringify(found));
    },
  });
  return { engine, source };
}

async function settled() {
  await act(async () => {
    await new Promise(resolve => setTimeout(resolve, 0));
  });
}

function expectNoMarkers() {
  if (process.env.LEAF_DEBUG)
    console.log(document.body.textContent?.slice(0, 3000));
  expect(markersIn(document.body)).toEqual([]);
}

describe('no key reaches the document (D2, words at the leaf)', () => {
  it('a record view', async () => {
    const { engine } = keyedEngine();
    render(
      <ViewEngineProvider engine={engine} messages={EN}>
        <DataWorkbench
          definitionId="orders"
          instanceId={systemInstanceId('orders', 'all')}
        />
      </ViewEngineProvider>,
    );
    await settled();
    expectNoMarkers();
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    screen.getByRole('heading', { name: 'All orders' });
    expect(
      screen.getByRole('columnheader', { name: /Warehouse/ }),
    ).toBeTruthy();
    expect(screen.getAllByText('China').length).toBeGreaterThan(0);
  });

  it('an analysis view', async () => {
    const { engine } = keyedEngine();
    render(
      <ViewEngineProvider engine={engine} messages={EN}>
        <DataWorkbench
          definitionId="orders"
          instanceId={systemInstanceId('orders', 'by-warehouse')}
        />
      </ViewEngineProvider>,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    await settled();
    expectNoMarkers();
    screen.getByRole('heading', { name: 'By warehouse' });
    expect(
      screen.getByRole('columnheader', { name: /Order count/ }),
    ).toBeTruthy();
  });

  it('a dashboard', async () => {
    const { engine } = keyedEngine();
    render(
      <ViewEngineProvider engine={engine} messages={EN}>
        <DashboardWorkbench
          definitionId="overview"
          instanceId={systemInstanceId('overview', 'main')}
        />
      </ViewEngineProvider>,
    );
    await waitFor(() => expect(screen.getAllByRole('table').length).toBe(2));
    await settled();
    expectNoMarkers();
    screen.getByText('Today');
    const bar = screen.getByRole('region', { name: 'Filters' });
    expect(within(bar).getByRole('group', { name: 'Region' })).toBeTruthy();
    expect(screen.getByText('Order list')).toBeTruthy();
  });

  it('an embedded view and an embedded board', async () => {
    const { engine } = keyedEngine();
    render(
      <ViewEngineProvider engine={engine} messages={EN}>
        <EmbeddedView
          instanceId={systemInstanceId('orders', 'all')}
          withTitle
        />
        <EmbeddedDashboard
          instanceId={systemInstanceId('overview', 'main')}
          withTitle
        />
      </ViewEngineProvider>,
    );
    await waitFor(() => expect(screen.getAllByRole('table').length).toBe(3));
    await settled();
    expectNoMarkers();
    expect(screen.getByRole('heading', { name: 'All orders' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Main board' })).toBeTruthy();
  });

  it('the view list and the filter editor', async () => {
    const { engine } = keyedEngine();
    render(
      <ViewEngineProvider engine={engine} messages={EN}>
        <DataWorkbench
          definitionId="orders"
          instanceId={systemInstanceId('orders', 'all')}
          defaultSidebarOpen
        />
      </ViewEngineProvider>,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    await settled();
    expectNoMarkers();
    fireEvent.click(screen.getByRole('button', { name: /Filter/ }));
    await settled();
    expectNoMarkers();
  });

  it('a chart, and the option ECharts is given', async () => {
    const { engine } = keyedEngine();
    render(
      <ViewEngineProvider engine={engine} messages={EN}>
        <DataWorkbench
          definitionId="orders"
          instanceId={systemInstanceId('orders', 'chart')}
        />
      </ViewEngineProvider>,
    );
    await waitFor(() => expect(drawnCharts().length).toBeGreaterThan(0));
    await settled();
    expectNoMarkers();
    const option = JSON.stringify(
      drawnCharts().map(chart => chart.getOption()),
    );
    expect(option).not.toMatch(/[\uE000\uE001]/);
    expect(option).toContain('Order count');
    expect(option).toContain('How many');
    // A reference line's name is written by a formatter, into the drawing.
    expect(document.body.textContent).toContain('Target line');
  });

  it('a CSV export', async () => {
    const created = vi.fn((blob: Blob) => {
      void blob;
      return 'blob:leaf';
    });
    Object.assign(URL, { createObjectURL: created, revokeObjectURL: vi.fn() });
    const { engine } = keyedEngine();
    render(
      <ViewEngineProvider engine={engine} messages={EN}>
        <EmbeddedView
          instanceId={systemInstanceId('orders', 'all')}
          interaction="interactive"
          withExport
        />
      </ViewEngineProvider>,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    fireEvent.click(await screen.findByRole('button', { name: /Export/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Export' });
    expectNoMarkers();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Export' }));
    await waitFor(() => expect(created).toHaveBeenCalledTimes(1));
    const csv = await created.mock.calls[0][0].text();
    expect(csv).not.toMatch(/[\uE000\uE001]/);
    expect(csv).toContain('Warehouse');
    expect(csv).toContain('China');
  });
});

describe('no key reaches an editor or a popup (D2)', () => {
  function workbench(instance: string) {
    const { engine } = keyedEngine();
    render(
      <ViewEngineProvider engine={engine} messages={EN}>
        <DataWorkbench
          definitionId="orders"
          instanceId={systemInstanceId('orders', instance)}
        />
      </ViewEngineProvider>,
    );
    return engine;
  }

  it('the filter fold, its field picker and a condition’s options', async () => {
    workbench('all');
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    fireEvent.click(editorToggle());
    await settled();
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    const picker = await screen.findByRole('dialog', {
      name: 'Choose fields',
    });
    expectNoMarkers();
    fireEvent.click(
      within(picker).getByRole('checkbox', { name: 'Warehouse' }),
    );
    fireEvent.click(within(picker).getByRole('checkbox', { name: 'Amount' }));
    fireEvent.click(within(picker).getByRole('button', { name: 'Done' }));
    await settled();
    expectNoMarkers();
  });

  it('the toolbar’s popups: columns, sort, layout', async () => {
    workbench('all');
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    for (const name of ['Columns', /Sort/]) {
      const button = screen.getAllByRole('button', { name })[0];
      fireEvent.click(button);
      await settled();
      expect(
        document.querySelectorAll('[role="dialog"],[role="menu"]').length,
      ).toBeGreaterThan(0);
      expectNoMarkers();
      fireEvent.keyDown(document.activeElement ?? document.body, {
        key: 'Escape',
      });
      await settled();
    }
  });

  it('the analysis tray and its cards', async () => {
    workbench('by-warehouse');
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    await openTray();
    await settled();
    expectNoMarkers();
    expect(document.body.textContent).toContain('Order count');
  });

  it('the view manager', async () => {
    workbench('all');
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Manage views' }));
    await screen.findByRole('dialog');
    await settled();
    expectNoMarkers();
  });

  it('a dashboard being built', async () => {
    const { engine } = keyedEngine();
    render(
      <ViewEngineProvider engine={engine} messages={EN}>
        <DashboardWorkbench definitionId="overview" instanceId="mine" />
      </ViewEngineProvider>,
    );
    await waitFor(() => expect(screen.getAllByRole('table').length).toBe(2));
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    await settled();
    expectNoMarkers();
    // A filter's settings, its name box among them.
    fireEvent.click(
      screen.getByRole('button', { name: 'Settings of “Region”' }),
    );
    await settled();
    expectNoMarkers();
    expect(
      (
        document.querySelector(
          '[data-slot="dashboard-filter-name"]',
        ) as HTMLInputElement
      ).value,
    ).toBe('Region');
    fireEvent.keyDown(document.activeElement ?? document.body, {
      key: 'Escape',
    });
    await settled();
    // A panel's menu.
    fireEvent.click(
      screen.getByRole('button', { name: 'Actions for “Order list”' }),
    );
    await settled();
    expectNoMarkers();
  });

  it('the chart picker and a chart’s options', async () => {
    workbench('chart');
    await waitFor(() => expect(drawnCharts().length).toBeGreaterThan(0));
    fireEvent.click(screen.getByRole('button', { name: 'Visualize' }));
    await settled();
    expectNoMarkers();
    // The chart's own options, one page after another.
    fireEvent.click(screen.getAllByRole('button', { name: /options/ })[0]);
    await settled();
    expectNoMarkers();
    const values = new Set<string>();
    for (const tab of screen.queryAllByRole('tab')) {
      fireEvent.click(tab);
      await settled();
      expectNoMarkers();
      for (const input of document.querySelectorAll('input'))
        values.add(input.value);
    }
    // The names the view gave its axis and its line, in words, in the boxes.
    expect(values).toContain('How many');
    expect(values).toContain('Target line');
  });

  it('a record opened', async () => {
    workbench('all');
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    fireEvent.click(screen.getAllByRole('row')[1]);
    await settled();
    expectNoMarkers();
  });
});

describe('a change of language only redraws (D2)', () => {
  it('draws the other words, and reopens, re-queries and dirties nothing', async () => {
    const { engine, source } = keyedEngine();
    const open = vi.spyOn(engine, 'open');
    const page = (words: Record<string, string>) => (
      <ViewEngineProvider engine={engine} messages={words}>
        <DataWorkbench
          definitionId="orders"
          instanceId={systemInstanceId('orders', 'by-warehouse')}
        />
      </ViewEngineProvider>
    );
    const { rerender } = render(page(EN));
    await screen.findByRole('heading', { name: 'By warehouse' });
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    await settled();
    const [runtime] = engine.openRuntimes();
    const asked = vi.mocked(source.aggregate).mock.calls.length;
    const before = runtime.getSnapshot();

    rerender(page(ZH));
    await screen.findByRole('heading', { name: '中:By warehouse' });
    expect(
      screen.getByRole('columnheader', { name: /中:Order count/ }),
    ).toBeTruthy();
    expectNoMarkers();
    expect(open).toHaveBeenCalledTimes(1);
    expect(engine.openRuntimes()).toEqual([runtime]);
    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(asked);
    // The same state: keys in it, nothing dirty.
    expect(runtime.getSnapshot()).toBe(before);
    expect(before.dirty).toBe(false);
    expect(JSON.stringify(before.draft)).toMatch(/\uE000/);
  });
});

describe('the two findings the leaf closes (review of #3761)', () => {
  function page(engine: ViewEngine, words: Record<string, string>) {
    return (
      <ViewEngineProvider engine={engine} messages={words}>
        <DataWorkbench
          definitionId="orders"
          instanceId={systemInstanceId('orders', 'by-warehouse')}
        />
      </ViewEngineProvider>
    );
  }

  /** Renames the metric through its card, as a reader types it. */
  async function rename(to: string) {
    fireEvent.click(
      screen.getByRole('button', { name: 'More settings for Order count' }),
    );
    fireEvent.click(
      await screen.findByRole('menuitem', { name: 'Display name…' }),
    );
    const box = await screen.findByRole('textbox', {
      name: 'Display name for Order count',
    });
    fireEvent.change(box, { target: { value: to } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await settled();
  }

  it('a keyed metric label edited and edited back is clean, and follows the language', async () => {
    const { engine } = keyedEngine();
    const { rerender } = render(page(engine, EN));
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    await openTray();
    await settled();
    const [runtime] = engine.openRuntimes();
    const label = () => {
      const draft = runtime.getSnapshot().draft;
      return draft.kind === 'analysis' ? draft.metrics[0].label : undefined;
    };
    const key = label();
    expect(key).toMatch(/^\uE000/);

    // The box opens on the words, never the key; words typed away and
    // back are the key again, and nothing was changed.
    fireEvent.click(
      screen.getByRole('button', { name: 'More settings for Order count' }),
    );
    fireEvent.click(
      await screen.findByRole('menuitem', { name: 'Display name…' }),
    );
    const box = (await screen.findByRole('textbox', {
      name: 'Display name for Order count',
    })) as HTMLInputElement;
    expect(box.value).toBe('Order count');
    fireEvent.change(box, { target: { value: 'Orders' } });
    fireEvent.change(box, { target: { value: 'Order count' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await settled();
    expect(label()).toBe(key);
    expect(runtime.getSnapshot().dirty).toBe(false);

    // Edited, then the edit undone: the key the view was saved with, clean.
    await rename('Orders counted');
    expect(label()).toBe('Orders counted');
    expect(runtime.getSnapshot().dirty).toBe(true);
    act(() => runtime.revert());
    await settled();
    expect(label()).toBe(key);
    expect(runtime.getSnapshot().dirty).toBe(false);

    rerender(page(engine, ZH));
    await settled();
    expect(
      screen.getByRole('columnheader', { name: /中:Order count/ }),
    ).toBeTruthy();
    expect(runtime.getSnapshot().dirty).toBe(false);
    rerender(page(engine, EN));
    await settled();
    expect(
      screen.getByRole('columnheader', { name: /^Order count/ }),
    ).toBeTruthy();
    expectNoMarkers();
  });

  it('a view made from a definition and left alone stays clean across a change of language', async () => {
    const { engine } = keyedEngine();
    const made = engine.create('orders', {
      title: engine.definitions.get('orders')!.views![1].title,
      scope: 'personal',
      config: engine.definitions.get('orders')!.views![1].config,
    });
    await engine.save(made);
    const saved = made.getSnapshot().saved!;
    made.dispose();
    const { rerender } = render(
      <ViewEngineProvider engine={engine} messages={EN}>
        <DataWorkbench definitionId="orders" instanceId={saved.id} />
      </ViewEngineProvider>,
    );
    await screen.findByRole('heading', { name: 'By warehouse' });
    const [runtime] = engine
      .openRuntimes()
      .filter(open => open.getSnapshot().saved?.id === saved.id);
    expect(runtime.getSnapshot().dirty).toBe(false);
    // Saved as it was drawn up: the keys went to the store.
    expect(JSON.stringify(saved.config)).toMatch(/\uE000/);

    rerender(
      <ViewEngineProvider engine={engine} messages={ZH}>
        <DataWorkbench definitionId="orders" instanceId={saved.id} />
      </ViewEngineProvider>,
    );
    await screen.findByRole('heading', { name: '中:By warehouse' });
    expect(runtime.getSnapshot().dirty).toBe(false);
    expect(screen.queryByText(/unsaved/i)).toBeNull();
    expectNoMarkers();
  });
});
