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

import { AggregationGroupType } from '@ahoo-wang/wow-client';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type RecordViewConfig,
  type ViewInstance,
} from '../src/index.js';
import { DataWorkbench, defaultMessages } from '../src/ui/index.js';
import {
  analysisConfig,
  mine,
  ordersDefinition,
  testSource,
} from './fixtures.js';

/**
 * The follow-up menu over a result counted by expanded elements (D38): one
 * level opens the records that hold such an element; a deeper chain keeps
 * 「查看这些记录」 in its place, greyed, with why.
 */

afterEach(cleanup);

/** Orders whose lines hold lots: two levels an analysis may expand. */
function lined(): DataViewDefinition {
  const base = ordersDefinition();
  return ordersDefinition({
    fields: [
      ...base.fields,
      {
        name: 'lines',
        label: 'Lines',
        kind: 'elementMatch',
        elements: [
          { name: 'sku', label: 'SKU', kind: 'string' },
          { name: 'qty', label: 'Qty', kind: 'number' },
          {
            name: 'lots',
            label: 'Lots',
            kind: 'elementMatch',
            elements: [{ name: 'lot', label: 'Lot', kind: 'string' }],
          },
        ],
      },
    ],
    analysis: {
      ...base.analysis!,
      elements: [
        {
          path: 'lines',
          aggregations: [
            {
              field: 'sku',
              groups: [AggregationGroupType.TERMS],
              functions: [],
            },
          ],
        },
        {
          path: 'lots',
          aggregations: [
            {
              field: 'lot',
              groups: [AggregationGroupType.TERMS],
              functions: [],
            },
          ],
        },
      ],
    },
  });
}

function instance(config: AnalysisViewConfig): ViewInstance {
  return {
    id: 'by-sku',
    definitionId: 'orders',
    title: 'By SKU',
    scope: 'shared',
    revision: '1',
    config,
  };
}

const BY_SKU = analysisConfig({
  layout: 'table',
  elements: [
    {
      path: 'lines',
      filter: {
        op: 'and',
        children: [{ field: 'lines.qty', operator: 'GTE', value: 2 }],
      },
    },
  ],
  groups: [{ alias: 'sku', field: 'lines.sku', type: 'TERMS' }],
  chart: {
    type: 'bar',
    cartesian: { x: 'sku', series: [{ metric: 'orders' }] },
  },
});

const BY_LOT = analysisConfig({
  layout: 'table',
  elements: [{ path: 'lines' }, { path: 'lots' }],
  groups: [{ alias: 'lot', field: 'lines.lots.lot', type: 'TERMS' }],
  chart: {
    type: 'bar',
    cartesian: { x: 'lot', series: [{ metric: 'orders' }] },
  },
});

function open(config: AnalysisViewConfig, row: Record<string, unknown>) {
  const source = testSource({
    aggregate: () => Promise.resolve([{ ...row, orders: 3 }]),
  });
  const engine = new ViewEngine({
    definitions: [lined()],
    store: new MemoryViewStore({ instances: [mine, instance(config)] }),
    resolveSource: () => source,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="by-sku"
      kinds={['record', 'analysis']}
      locale="en-GB"
    />,
  );
  return engine;
}

async function pressGroup() {
  const table = await screen.findByRole('table');
  const row = await waitFor(() => {
    const found = within(table).getAllByRole('row')[1];
    if (!found?.hasAttribute('data-pickable')) throw new Error('not yet');
    return found;
  });
  fireEvent.click(row);
  return await waitFor(() => {
    const menu = document.querySelector<HTMLElement>(
      '[data-slot="drill-menu"]',
    );
    if (!menu) throw new Error('no menu');
    return menu;
  });
}

describe('a group of a result counted by expanded elements', () => {
  it('opens the records holding such an element, under the level’s own gate', async () => {
    const engine = open(BY_SKU, { sku: 'A-1' });
    const menu = await pressGroup();
    // Named as the element names it.
    expect(menu.querySelector('[data-slot="drill-group"]')!.textContent).toBe(
      'SKU is A-1',
    );
    // Records only: narrowing the analysis to the group would be the
    // expansion's gate, which the menu does not write yet.
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual([defaultMessages['label.drill.records']]);

    fireEvent.click(
      await screen.findByRole('menuitem', {
        name: defaultMessages['label.drill.records'],
      }),
    );

    await waitFor(() =>
      expect(engine.openRuntimes().some(open => open.kind === 'record')).toBe(
        true,
      ),
    );
    const records = engine.openRuntimes().find(open => open.kind === 'record')!;
    expect((records.getSnapshot().draft as RecordViewConfig).filter).toEqual({
      op: 'and',
      children: [
        {
          field: 'lines',
          operator: 'ELEMENT_MATCH',
          value: {
            op: 'and',
            children: [
              { field: 'lines.qty', operator: 'GTE', value: 2 },
              { field: 'lines.sku', operator: 'EQ', value: 'A-1' },
            ],
          },
        },
      ],
    });
    // A condition the record view runs, not one it refuses.
    expect(records.getSnapshot().issues).toEqual([]);
  });

  it('keeps 「查看这些记录」 over elements of elements, greyed, and says why', async () => {
    open(BY_LOT, { lot: 'L-7' });
    const menu = await pressGroup();
    expect(menu.querySelector('[data-slot="drill-group"]')!.textContent).toBe(
      'Lot is L-7',
    );
    const records = menu.querySelector<HTMLElement>(
      '[data-slot="drill-records"]',
    )!;
    expect(records.getAttribute('data-gap')).toBe('nested-elements');
    expect(records.getAttribute('aria-disabled')).toBe('true');
    const reason = defaultMessages['label.drill.gap.nested-elements'];
    expect(records.textContent).toContain(reason);
    // The reason is read with the item, not only seen.
    const described = records.getAttribute('aria-describedby')!;
    expect(document.getElementById(described)!.textContent).toBe(reason);

    fireEvent.click(records);
    // Nothing opened: the menu is still about the group.
    expect(document.querySelector('[data-slot="origin-bar"]')).toBeNull();
  });
});
