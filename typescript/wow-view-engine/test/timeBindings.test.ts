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

import { describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  unbindPanels,
  bindPanel,
  removeFilter,
  retypeFilter,
  type DashboardField,
  type DashboardPanel,
  type DashboardRuntime,
  type DashboardRuntimeState,
  type DashboardViewConfig,
  type DataViewDefinition,
} from '../src/index.js';
import {
  withTimeBindings,
  withTimeIgnored,
} from '../src/dashboard/timeBindings.js';
import {
  analysisConfig,
  dashboardConfig,
  namedOrdersDefinition,
  overviewDefinition,
  testSource,
} from './fixtures.js';

const WINDOW: DashboardField = {
  name: 'window',
  label: 'Range',
  kind: 'datetime',
};

const CREATED = { name: 'createdAt', label: 'Created', kind: 'datetime' };

function owned(id: string, extra: Partial<DashboardPanel> = {}) {
  return {
    id,
    kind: 'view',
    owned: { definitionId: 'orders', config: analysisConfig() },
    bindings: [],
    layout: { x: 0, y: 0, w: 6, h: 4 },
    ...extra,
  } as DashboardPanel;
}

function board(
  panels: DashboardPanel[],
  fields: DashboardField[] = [WINDOW],
): DashboardViewConfig {
  return dashboardConfig({ fields, panels });
}

/**
 * todo D: a board's time filter reaches every panel whose data says when
 * its records happen, without a wire written for it.
 */
describe('withTimeBindings', () => {
  const timed = () => CREATED;

  it('wires the board’s one date filter to each panel through its time field', () => {
    const read = withTimeBindings(board([owned('a')]), timed);
    expect(read.panels[0]).toMatchObject({
      bindings: [
        { globalField: 'window', panelField: 'createdAt', auto: true },
      ],
    });
  });

  it('leaves a wire written by hand, a panel read whole and one with no time', () => {
    const written = owned('written', {
      bindings: [{ globalField: 'window', panelField: 'shippedAt' }],
    });
    const whole = owned('whole', { ignoresTime: true });
    const config = board([written, whole, owned('untimed')]);
    const read = withTimeBindings(config, (panel: DashboardPanel) =>
      panel.id === 'untimed' ? null : CREATED,
    );
    expect(read).toBe(config);
  });

  it('wires nothing on a board with no date filter, or two', () => {
    const none = board([owned('a')], []);
    expect(withTimeBindings(none, timed)).toBe(none);
    const two = board([owned('a')], [WINDOW, { ...WINDOW, name: 'shipped' }]);
    expect(withTimeBindings(two, timed)).toBe(two);
  });

  it('is taken off a panel by hand and stays off, and wired by hand again', () => {
    const config = board([owned('a')]);
    const off = unbindPanels(
      withTimeBindings(config, () => CREATED),
      'window',
      ['a'],
    );
    expect(off.panels[0]).toMatchObject({ ignoresTime: true, bindings: [] });
    expect(withTimeBindings(off, timed)).toBe(off);
    const on = bindPanel(off, 'window', 'a', 'createdAt', () => [
      CREATED as never,
    ]).config;
    expect(on.panels[0]).not.toHaveProperty('ignoresTime');
    expect(on.panels[0]).toMatchObject({
      bindings: [{ globalField: 'window', panelField: 'createdAt' }],
    });
  });
});

describe('a board read by its runtime', () => {
  const orders: DataViewDefinition = {
    ...namedOrdersDefinition(),
    timeField: 'createdAt',
    views: [
      { id: 'by-warehouse', title: 'By warehouse', config: analysisConfig() },
      {
        id: 'whole',
        title: 'Whole',
        config: analysisConfig(),
        timeField: null,
      },
    ],
  };

  async function open(panels: DashboardPanel[]) {
    const engine = new ViewEngine({
      definitions: [
        orders,
        overviewDefinition({
          views: [{ id: 'board', title: 'Board', config: board(panels) }],
        }),
      ],
      store: new MemoryViewStore(),
      resolveSource: () => testSource(),
    });
    const runtime = await engine.open('system:overview:board');
    await new Promise(settled => setTimeout(settled, 0));
    const state = runtime.getSnapshot() as DashboardRuntimeState;
    runtime.dispose();
    return state;
  }

  it('wires the window through the definition’s time field or the view’s own, and not to a view read whole', async () => {
    const reference = (id: string, view: string) =>
      ({
        id,
        kind: 'view',
        instanceId: `system:orders:${view}`,
        bindings: [],
        layout: { x: 6, y: 0, w: 6, h: 4 },
      }) as DashboardPanel;
    const { applied, dirty } = await open([
      owned('owned'),
      reference('saved', 'by-warehouse'),
      reference('whole', 'whole'),
      owned('ignoring', { ignoresTime: true }),
    ]);
    expect(
      applied.panels.map(panel => [
        panel.id,
        (panel as never as { bindings: unknown }).bindings,
      ]),
    ).toEqual([
      [
        'owned',
        [{ globalField: 'window', panelField: 'createdAt', auto: true }],
      ],
      [
        'saved',
        [{ globalField: 'window', panelField: 'createdAt', auto: true }],
      ],
      ['whole', []],
      ['ignoring', []],
    ]);
    // Read into the baseline too: the board opens clean.
    expect(dirty).toBe(false);
  });
});

/**
 * D1 (user 2026-09-28): a wire made from a view's time field is read into
 * the board and never stored; a board stored before reads as it did. And
 * the review of #3744's findings about the wires, each held by a test.
 */
describe('time wires derived, never stored', () => {
  const orders: DataViewDefinition = {
    ...namedOrdersDefinition(),
    timeField: 'createdAt',
    fields: [
      ...namedOrdersDefinition().fields,
      { name: 'shippedAt', label: 'Shipped', kind: 'datetime' },
    ],
    views: [
      { id: 'timed', title: 'Timed', config: analysisConfig() },
      {
        id: 'whole',
        title: 'Whole',
        config: analysisConfig(),
        timeField: null,
      },
    ],
  };
  const reference = (id: string, view: string, x = 6) =>
    ({
      id,
      kind: 'view',
      instanceId: `system:orders:${view}`,
      bindings: [],
      layout: { x, y: 0, w: 6, h: 4 },
    }) as DashboardPanel;

  async function stored(config: DashboardViewConfig) {
    const store = new MemoryViewStore();
    const engine = new ViewEngine({
      definitions: [orders, overviewDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    const instance = await store.create(
      { definitionId: 'overview', title: 'Board', scope: 'personal', config },
      { requestId: 'r' },
    );
    const runtime = (await engine.open(instance.id)) as DashboardRuntime;
    await new Promise(settled => setTimeout(settled, 0));
    return { engine, store, runtime, id: instance.id };
  }
  const bindingsOf = (config: DashboardViewConfig, id: string) =>
    (config.panels.find(panel => panel.id === id) as { bindings?: unknown })
      ?.bindings;

  it('3: reads a board stored before as it was — an unwired panel stays unwired', async () => {
    const { runtime } = await stored(board([owned('a')]));
    const { applied } = runtime.getSnapshot() as DashboardRuntimeState;
    expect(bindingsOf(applied, 'a')).toEqual([]);
    expect(applied.panels[0]).toMatchObject({ ignoresTime: true });
    runtime.dispose();
  });

  it('3: migrates a board stored before once, keeping what auto-connect wired', () => {
    const before = board([
      owned('wired', {
        bindings: [
          { globalField: 'window', panelField: 'createdAt', auto: true },
        ],
      }),
      owned('unwired'),
    ]);
    const read = withTimeIgnored(before);
    expect(read.derivesTime).toBe(true);
    expect(read.panels[0]).toMatchObject({
      bindings: [{ globalField: 'window', panelField: 'createdAt' }],
    });
    expect(read.panels[0]).not.toHaveProperty('ignoresTime');
    expect(read.panels[1]).toMatchObject({ ignoresTime: true });
    expect(withTimeIgnored(read)).toBe(read);
  });

  it('stores no wire it derives, marks the board, and reads it back clean', async () => {
    const { engine, store, runtime, id } = await stored({
      ...board([owned('a')]),
      derivesTime: true,
    });
    const before = runtime.getSnapshot() as DashboardRuntimeState;
    expect(bindingsOf(before.applied, 'a')).toEqual([
      { globalField: 'window', panelField: 'createdAt', auto: true },
    ]);
    runtime.setBuilding(true);
    runtime.renamePanel('a', 'Orders');
    await engine.save(runtime);
    const saved = (await store.get(id)).config as DashboardViewConfig;
    expect(saved.derivesTime).toBe(true);
    expect(bindingsOf(saved, 'a')).toEqual([]);
    const after = runtime.getSnapshot() as DashboardRuntimeState;
    expect(after.dirty).toBe(false);
    expect(bindingsOf(after.draft, 'a')).toEqual([
      { globalField: 'window', panelField: 'createdAt', auto: true },
    ]);
    runtime.dispose();
  });

  it('4: wires a new panel through its view’s time field, not the name the board uses most', async () => {
    const { runtime } = await stored({
      ...board([
        owned('by-hand', {
          bindings: [{ globalField: 'window', panelField: 'shippedAt' }],
        }),
      ]),
      derivesTime: true,
    });
    runtime.setBuilding(true);
    const added = runtime.addPanel({
      kind: 'view',
      owned: { definitionId: 'orders', config: analysisConfig() },
    });
    const { draft } = runtime.getSnapshot() as DashboardRuntimeState;
    expect(bindingsOf(draft, added ?? '')).toEqual([
      { globalField: 'window', panelField: 'createdAt', auto: true },
    ]);
    runtime.dispose();
  });

  it('5: leaves no wire of a panel’s previous view once another replaces it', async () => {
    const { runtime } = await stored({
      ...board([reference('p', 'timed')]),
      derivesTime: true,
    });
    const wired = runtime.getSnapshot() as DashboardRuntimeState;
    expect(bindingsOf(wired.draft, 'p')).toHaveLength(1);
    runtime.setBuilding(true);
    runtime.replacePanelView('p', 'system:orders:whole');
    await new Promise(settled => setTimeout(settled, 0));
    const { draft } = runtime.getSnapshot() as DashboardRuntimeState;
    expect(bindingsOf(draft, 'p')).toEqual([]);
    runtime.dispose();
  });

  it('2: does not auto-connect the time filter to a panel that reads whole', () => {
    const config = board([owned('a'), owned('whole', { ignoresTime: true })]);
    const { config: wired, connected } = bindPanel(
      config,
      'window',
      'a',
      'createdAt',
      () => [CREATED as never],
    );
    expect(connected).toEqual([]);
    expect(wired.panels[1]).toMatchObject({ bindings: [], ignoresTime: true });
  });

  it('10: a filter retyped to a date does not make its panels read whole', () => {
    const text: DashboardField = {
      name: 'region',
      label: 'Region',
      kind: 'string',
    };
    const config = board(
      [
        owned('a', {
          bindings: [{ globalField: 'region', panelField: 'warehouse' }],
        }),
      ],
      [text],
    );
    const retyped = retypeFilter(config, 'region', 'date');
    expect(retyped.panels[0]).not.toHaveProperty('ignoresTime');
  });

  it('10: a date filter taken off by hand stays off once it is the board’s only one', () => {
    const shipped: DashboardField = { ...WINDOW, name: 'shipped' };
    const config = board(
      [
        owned('a', {
          bindings: [
            { globalField: 'window', panelField: 'createdAt' },
            { globalField: 'shipped', panelField: 'shippedAt' },
          ],
        }),
      ],
      [WINDOW, shipped],
    );
    const off = removeFilter(unbindPanels(config, 'window', ['a']), 'shipped');
    expect(withTimeBindings(off, () => CREATED as never)).toMatchObject({
      panels: [{ bindings: [], ignoresTime: true }],
    });
  });
});
