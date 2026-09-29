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
  QueryValueKind,
  SensitivityLevel,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  bindPanel,
  builtinFieldKinds,
  defineView,
  removeFilter,
  replacePanelView,
  stringFieldKind,
  text,
  unbindPanels,
  validateDefinition,
  withFieldKinds,
  type DashboardField,
  type DashboardPanel,
  type DashboardViewConfig,
  type DefineViewSpec,
} from '../src/index.js';
import { narrowDefinition } from '../src/capabilities/index.js';
import { describedField as described } from '../src/capabilities/match.js';
import {
  storedTimeWires,
  withTimeBindings,
} from '../src/dashboard/timeBindings.js';
import { admit } from '../src/testing/index.js';
import { describedField, ordersDescriptor } from './fixtures/descriptor.js';
import { analysisConfig, dashboardConfig } from './fixtures.js';

/** The second review of #3744: each item, held by the test that failed before its fix. */
const spec = (
  fields: DefineViewSpec['fields'],
  more: Partial<DefineViewSpec> = {},
): DefineViewSpec => ({
  id: 'orders',
  source: 'orders',
  title: 'Orders',
  fields,
  ...more,
});
const masked = { level: SensitivityLevel.DISPLAY, comparable: true };
const WINDOW: DashboardField = {
  name: 'window',
  label: 'Range',
  kind: 'datetime',
};
const SHIPPED: DashboardField = {
  name: 'shipped',
  label: 'Shipped',
  kind: 'datetime',
};
const CREATED = { name: 'createdAt', label: 'Created', kind: 'datetime' };
const SHIP = { name: 'shippedAt', label: 'Shipped', kind: 'datetime' };
const owned = (id: string, extra: Partial<DashboardPanel> = {}) =>
  ({
    id,
    kind: 'view',
    owned: { definitionId: 'orders', config: analysisConfig() },
    bindings: [],
    layout: { x: 0, y: 0, w: 6, h: 4 },
    ...extra,
  }) as DashboardPanel;
const read = (config: DashboardViewConfig) =>
  withTimeBindings(config, () => CREATED as never);
const wires = (config: DashboardViewConfig) =>
  config.panels.map(panel => (panel as { bindings?: unknown }).bindings);

describe('the second review', () => {
  it('1: a value the source masks never analyses, whatever the snapshot or a hand-written definition says', () => {
    const snapshot = ordersDescriptor({
      fields: [describedField('id'), describedField('email')],
    });
    const live = ordersDescriptor({
      fields: [
        describedField('id'),
        describedField('email', { sensitivity: masked }),
      ],
    });
    const definition = defineView(
      snapshot,
      spec({ id: 'Order', email: 'Mail' }),
    );
    const { definition: narrowed, findings } = narrowDefinition(
      definition,
      live,
      builtinFieldKinds,
    );
    expect(narrowed.analysis?.fields.map(entry => entry.field)).toEqual(['id']);
    expect(findings).toContainEqual(
      expect.objectContaining({
        code: 'capability.analysis.field-unavailable',
      }),
    );
    // Written by hand, the same.
    const { definition: byHand } = narrowDefinition(
      { ...definition, described: undefined },
      live,
      builtinFieldKinds,
    );
    expect(byHand.analysis?.fields.map(entry => entry.field)).toEqual(['id']);
  });

  it('1: a path masked in any variant is masked', () => {
    const variants: QueryModelDescriptor = ordersDescriptor({
      fields: [
        describedField('lines', {
          types: ['OBJECT'],
          kind: QueryValueKind.ARRAY,
        }),
      ],
      elements: [{ path: 'lines', filter: true, aggregate: true }],
      variants: {
        element: 'lines',
        discriminator: 'type',
        values: [
          { value: 'A', fields: [describedField('note')] },
          {
            value: 'B',
            fields: [describedField('note', { sensitivity: masked })],
          },
        ],
      },
    });
    expect(described(variants, 'lines.note', 'lines')?.sensitive).toBe(true);
  });

  it('2: a date filter auto-connect wired keeps its field once the board is down to it', () => {
    const board = read({
      ...dashboardConfig({
        fields: [WINDOW, SHIPPED],
        panels: [owned('a'), owned('b')],
      }),
      derivesTime: true,
    });
    const { config } = bindPanel(board, 'shipped', 'a', 'shippedAt', () => [
      CREATED as never,
      SHIP as never,
    ]);
    const after = read(removeFilter(read(storedTimeWires(config)), 'window'));
    expect(wires(after)[1]).toEqual([
      { globalField: 'shipped', panelField: 'shippedAt', auto: true },
    ]);
  });

  it('3: an analysis view over a snapshot with no metric is said, never refused', () => {
    const none = ordersDescriptor({
      fields: [describedField('id', { aggregate: undefined })],
    });
    none.analysis = { ...none.analysis, metrics: [] };
    const definition = defineView(
      none,
      spec(
        { id: 'Order' },
        { views: [{ id: 'a', title: 'A', config: analysisConfig() }] },
      ),
    );
    const found = validateDefinition(definition, builtinFieldKinds);
    expect(found.filter(entry => entry.severity === 'error')).toEqual([]);
    expect(found).toContainEqual(
      expect.objectContaining({
        code: 'definition.view.analysis-open',
        severity: 'warning',
      }),
    );
  });

  it('4: declining auto-connect puts the panels back, reading the range as before', () => {
    const board = dashboardConfig({
      fields: [WINDOW, SHIPPED],
      panels: [owned('a'), owned('b')],
    });
    const { config, connected } = bindPanel(
      board,
      'shipped',
      'a',
      'shippedAt',
      () => [CREATED as never, SHIP as never],
    );
    const undone = unbindPanels(config, 'shipped', connected, {
      byHand: false,
    });
    expect(undone.panels[1]).not.toHaveProperty('ignoresTime');
  });

  it('5: a derived wire goes when the board gains a second date filter, and with a replaced view', () => {
    const one = read({
      ...dashboardConfig({ fields: [WINDOW], panels: [owned('a')] }),
    });
    expect(wires(one)[0]).toHaveLength(1);
    const two = read({ ...one, fields: [WINDOW, SHIPPED] });
    expect(wires(two)[0]).toEqual([]);
    const replaced = replacePanelView(
      { ...one, fields: [WINDOW, SHIPPED] },
      'a',
      'system:orders:other',
    );
    expect(wires(replaced)[0]).toEqual([]);
  });

  it('6: a field of a host’s own kind gets the snapshot’s comparisons, given the kinds', () => {
    const kinds = withFieldKinds(builtinFieldKinds, [
      { ...stringFieldKind, id: 'sku' },
    ]);
    const snapshot = ordersDescriptor({
      fields: [
        describedField('code', { filter: { operators: ['EQ'] as never } }),
      ],
    });
    const definition = defineView(
      snapshot,
      spec({ code: { label: 'Code', kind: 'sku' } }),
      { kinds },
    );
    expect(definition.fields[0].operators).toEqual(['EQ']);
  });

  it('7: a board whose derived wires stand as they were is the same board', () => {
    const board = read(
      dashboardConfig({ fields: [WINDOW], panels: [owned('a')] }),
    );
    expect(read(board)).toBe(board);
  });

  it('8: admit calls a host’s catalogue as its method, on the host', () => {
    const host = {
      words: { 'orders.title': 'Orders', 'orders.id': 'Order' } as Record<
        string,
        string
      >,
      text(key: string) {
        return this.words[key];
      },
    };
    const definition = defineView(
      ordersDescriptor(),
      spec({ id: text('orders.id') }, { title: text('orders.title') }),
    );
    expect(admit([definition], { orders: ordersDescriptor() }, host)).toEqual(
      [],
    );
  });
});
