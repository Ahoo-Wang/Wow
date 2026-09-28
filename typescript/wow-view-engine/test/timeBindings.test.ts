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
  type DashboardField,
  type DashboardPanel,
  type DashboardRuntimeState,
  type DashboardViewConfig,
  type DataViewDefinition,
} from '../src/index.js';
import { withTimeBindings } from '../src/dashboard/timeBindings.js';
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
