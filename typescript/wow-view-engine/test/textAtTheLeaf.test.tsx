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
import { systemInstanceId, type ViewEngine } from '../src/index.js';
import {
  DashboardWorkbench,
  DataWorkbench,
  EmbeddedDashboard,
  EmbeddedView,
  ViewHost,
} from '../src/ui/index.js';
import { EN, ZH, keyedEngine, markersIn } from './fixtures/keyed.js';
import { editorToggle, openTray } from './fixtures/workbench.js';

afterEach(cleanup);

/** Every chart drawn, as ECharts holds it: its option is what it shows. */
function drawnCharts(): { getOption(): unknown }[] {
  return [...document.querySelectorAll('[_echarts_instance_]')].flatMap(
    element => getInstanceByDom(element as HTMLElement) ?? [],
  );
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

/**
 * Opens each trigger `triggers` finds — a menu, a list, a popup — one at a
 * time, and looks for markers in what opens: the nested menus a popup or a
 * tray holds, which drawing the popup alone never shows. `reopen` puts the
 * outer popup back where Escape took it too.
 */
async function openEach(
  triggers: () => HTMLElement[],
  reopen: () => Promise<void> = async () => {},
): Promise<string[]> {
  const opened: string[] = [];
  const count = triggers().length;
  for (let at = 0; at < count; at += 1) {
    let trigger = triggers()[at];
    if (!trigger) {
      await reopen();
      trigger = triggers()[at];
    }
    if (!trigger || trigger.hasAttribute('disabled')) continue;
    const name =
      trigger.getAttribute('aria-label') ?? trigger.textContent ?? '';
    fireEvent.click(trigger);
    await settled();
    opened.push(name);
    expect(markersIn(document.body), name).toEqual([]);
    fireEvent.keyDown(document.activeElement ?? document.body, {
      key: 'Escape',
    });
    await settled();
  }
  return opened;
}

/** The triggers of popups inside `root`: menus, lists, dialogs. */
function popupTriggers(root: () => ParentNode | null | undefined) {
  return () => [
    ...(root()?.querySelectorAll<HTMLElement>(
      '[aria-haspopup]:not([aria-haspopup="false"])',
    ) ?? []),
  ];
}

describe('no key reaches the document (D2, words at the leaf)', () => {
  it('a record view', async () => {
    const { engine } = keyedEngine();
    render(
      <ViewHost engine={engine} messages={EN}>
        <DataWorkbench
          definitionId="orders"
          instanceId={systemInstanceId('orders', 'all')}
        />
      </ViewHost>,
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
      <ViewHost engine={engine} messages={EN}>
        <DataWorkbench
          definitionId="orders"
          instanceId={systemInstanceId('orders', 'by-warehouse')}
        />
      </ViewHost>,
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
      <ViewHost engine={engine} messages={EN}>
        <DashboardWorkbench
          definitionId="overview"
          instanceId={systemInstanceId('overview', 'main')}
        />
      </ViewHost>,
    );
    await waitFor(() => expect(screen.getAllByRole('table').length).toBe(3));
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
      <ViewHost engine={engine} messages={EN}>
        <EmbeddedView
          instanceId={systemInstanceId('orders', 'all')}
          withTitle
        />
        <EmbeddedDashboard
          instanceId={systemInstanceId('overview', 'main')}
          withTitle
        />
      </ViewHost>,
    );
    await waitFor(() => expect(screen.getAllByRole('table').length).toBe(4));
    await settled();
    expectNoMarkers();
    expect(screen.getByRole('heading', { name: 'All orders' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Main board' })).toBeTruthy();
  });

  it('the view list and the filter editor', async () => {
    const { engine } = keyedEngine();
    render(
      <ViewHost engine={engine} messages={EN}>
        <DataWorkbench
          definitionId="orders"
          instanceId={systemInstanceId('orders', 'all')}
          defaultSidebarOpen
        />
      </ViewHost>,
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
      <ViewHost engine={engine} messages={EN}>
        <DataWorkbench
          definitionId="orders"
          instanceId={systemInstanceId('orders', 'chart')}
        />
      </ViewHost>,
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

  it.each([
    ['line', ['Order count', 'How many']],
    ['pie', ['China']],
    ['funnel', ['Placed', 'Amount total']],
  ])('a %s chart, and the option ECharts is given', async (view, words) => {
    const { engine } = keyedEngine();
    render(
      <ViewHost engine={engine} messages={EN}>
        <DataWorkbench
          definitionId="orders"
          instanceId={systemInstanceId('orders', view)}
        />
      </ViewHost>,
    );
    await waitFor(() => expect(drawnCharts().length).toBeGreaterThan(0));
    await settled();
    expectNoMarkers();
    const option = JSON.stringify(
      drawnCharts().map(chart => chart.getOption()),
    );
    expect(option).not.toMatch(/[\uE000\uE001]/);
    for (const word of words) expect(option).toContain(word);
  });

  it('a CSV export', async () => {
    const created = vi.fn((blob: Blob) => {
      void blob;
      return 'blob:leaf';
    });
    Object.assign(URL, { createObjectURL: created, revokeObjectURL: vi.fn() });
    const { engine } = keyedEngine();
    render(
      <ViewHost engine={engine} messages={EN}>
        <EmbeddedView
          instanceId={systemInstanceId('orders', 'all')}
          interaction="interactive"
          withExport
        />
      </ViewHost>,
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
      <ViewHost engine={engine} messages={EN}>
        <DataWorkbench
          definitionId="orders"
          instanceId={systemInstanceId('orders', instance)}
        />
      </ViewHost>,
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
    // A field its source deprecates says why, in words.
    expect(
      picker
        .querySelector('[data-slot="field-deprecated"]')
        ?.getAttribute('title'),
    ).toBe('Read the state instead');
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

  it('every menu inside the sort and columns popups', async () => {
    workbench('all');
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    const opened: string[] = [];
    for (const name of [/Sort/, 'Columns']) {
      const open = async () => {
        fireEvent.click(screen.getAllByRole('button', { name })[0]);
        await settled();
      };
      await open();
      const popup = () =>
        document.querySelector<HTMLElement>('[role="dialog"]');
      opened.push(...(await openEach(popupTriggers(popup), open)));
      fireEvent.keyDown(document.activeElement ?? document.body, {
        key: 'Escape',
      });
      await settled();
    }
    // The sort's field menu, grouped under the definition's field groups.
    expect(opened.join('|')).toMatch(/Sort by a field|Add/);
  });

  it('every menu in the analysis tray: add a group, add a metric, a card’s own', async () => {
    workbench('by-warehouse');
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    await openTray();
    await settled();
    const tray = () =>
      document.querySelector<HTMLElement>('[data-slot="analysis-tray"]');
    const opened = await openEach(popupTriggers(tray));
    expect(opened.length).toBeGreaterThan(2);
    expect(document.body.textContent).toContain('Order count');
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
      <ViewHost engine={engine} messages={EN}>
        <DashboardWorkbench definitionId="overview" instanceId="mine" />
      </ViewHost>,
    );
    await waitFor(() => expect(screen.getAllByRole('table').length).toBe(3));
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
      <ViewHost engine={engine} messages={words}>
        <DataWorkbench
          definitionId="orders"
          instanceId={systemInstanceId('orders', 'by-warehouse')}
        />
      </ViewHost>
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
      <ViewHost engine={engine} messages={words}>
        <DataWorkbench
          definitionId="orders"
          instanceId={systemInstanceId('orders', 'by-warehouse')}
        />
      </ViewHost>
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
      <ViewHost engine={engine} messages={EN}>
        <DataWorkbench definitionId="orders" instanceId={saved.id} />
      </ViewHost>,
    );
    await screen.findByRole('heading', { name: 'By warehouse' });
    const [runtime] = engine
      .openRuntimes()
      .filter(open => open.getSnapshot().saved?.id === saved.id);
    expect(runtime.getSnapshot().dirty).toBe(false);
    // Saved as it was drawn up: the keys went to the store.
    expect(JSON.stringify(saved.config)).toMatch(/\uE000/);

    rerender(
      <ViewHost engine={engine} messages={ZH}>
        <DataWorkbench definitionId="orders" instanceId={saved.id} />
      </ViewHost>,
    );
    await screen.findByRole('heading', { name: '中:By warehouse' });
    expect(runtime.getSnapshot().dirty).toBe(false);
    expect(screen.queryByText(/unsaved/i)).toBeNull();
    expectNoMarkers();
  });
});
