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
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import {
  cleanup,
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DataViewDefinition,
} from '../src/index.js';
import { moveMetric } from '../src/analysis/index.js';
import { useAnalysisEditor, useOpenView } from '../src/react/index.js';
import {
  DataWorkbench,
  defaultMessages,
  formatMessage,
} from '../src/ui/index.js';
import {
  analysisConfig,
  ordersDefinition,
  resourcesOf,
  testSource,
} from './fixtures.js';
import { openTray } from './fixtures/workbench.js';

afterEach(cleanup);

/**
 * The metrics row's order (D71): metrics are dragged or moved from the
 * keyboard, and a metric calculated from others never goes before one it
 * reads, nor one that is read after one that reads it. And the one ✕ that
 * would leave nothing to measure is off, saying why.
 */

const count: AnalysisMetric = { alias: 'orders', type: 'COUNT' };
const amount: AnalysisMetric = {
  alias: 'amount',
  type: 'NUMERIC',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};
const cost: AnalysisMetric = {
  alias: 'cost',
  type: 'NUMERIC',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'cost' },
};

function derived(alias: string, left: string, right: string): AnalysisMetric {
  return {
    alias,
    type: 'DERIVED',
    label: alias === 'perOrder' ? 'Per order' : alias,
    expression: {
      type: 'BINARY',
      operator: 'DIVIDE' as never,
      left: { type: 'METRIC_REF', metric: left },
      right: { type: 'METRIC_REF', metric: right },
    },
  };
}

const aliases = (metrics: readonly AnalysisMetric[]) =>
  metrics.map(metric => metric.alias);

describe('moveMetric', () => {
  const list = [count, amount, cost, derived('perOrder', 'amount', 'orders')];

  it('moves a metric that reads nothing and nothing reads anywhere', () => {
    const moved = moveMetric(list, 2, 0);
    expect(aliases(moved.metrics)).toEqual([
      'cost',
      'orders',
      'amount',
      'perOrder',
    ]);
    expect(moved.to).toBe(0);
    expect(moved.stop).toBeUndefined();
  });

  it('stops a derived metric just after the last metric it reads', () => {
    const moved = moveMetric(list, 3, 0);
    expect(aliases(moved.metrics)).toEqual([
      'orders',
      'amount',
      'perOrder',
      'cost',
    ]);
    expect(moved.to).toBe(2);
    expect(moved.stop).toEqual({ side: 'after', at: 'amount' });
  });

  it('stops a metric just before the first derived metric that reads it', () => {
    const moved = moveMetric(list, 0, 3);
    expect(aliases(moved.metrics)).toEqual([
      'amount',
      'cost',
      'orders',
      'perOrder',
    ]);
    expect(moved.stop).toEqual({ side: 'before', at: 'perOrder' });
  });

  it('refuses a move that cannot go anywhere, and says why', () => {
    const tight = [amount, derived('double', 'amount', 'amount')];
    const moved = moveMetric(tight, 1, 0);
    expect(moved.to).toBe(1);
    expect(aliases(moved.metrics)).toEqual(['amount', 'double']);
    expect(moved.stop).toEqual({ side: 'after', at: 'amount' });
  });

  it('takes a move that puts a list out of order back in it', () => {
    const broken = [derived('early', 'amount', 'amount'), amount, count];
    expect(aliases(moveMetric(broken, 1, 0).metrics)).toEqual([
      'amount',
      'early',
      'orders',
    ]);
  });
});

function definition(): DataViewDefinition {
  return ordersDefinition({
    fields: [
      { name: 'id', label: 'Order', kind: 'string', sortable: true },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'amount', label: 'Amount', kind: 'number', sortable: true },
      { name: 'cost', label: 'Cost', kind: 'number', sortable: true },
    ],
    analysis: {
      count: true,
      expressions: true,
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        { field: 'amount', groups: [], functions: [AggregationFunction.SUM] },
        { field: 'cost', groups: [], functions: [AggregationFunction.SUM] },
      ],
    },
  });
}

function engineOver(metrics: AnalysisMetric[]) {
  const store = new MemoryViewStore({
    instances: [
      {
        id: 'orders-1',
        definitionId: 'orders',
        title: 'By warehouse',
        scope: 'personal',
        revision: '1',
        config: analysisConfig({
          metrics: metrics as AnalysisViewConfig['metrics'],
        }),
      },
    ],
  });
  return new ViewEngine({
    resources: resourcesOf([definition()], () => testSource()),
    store,
  });
}

async function editor(metrics: AnalysisMetric[]) {
  const engine = engineOver(metrics);
  const { result } = renderHook(() => {
    const opened = useOpenView(engine, 'orders-1');
    return { opened, analysis: useAnalysisEditor(opened.runtime) };
  });
  await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());
  const draft = () =>
    result.current.opened.runtime!.getSnapshot().draft as AnalysisViewConfig;
  return { analysis: () => result.current.analysis, draft };
}

describe('the editor’s metric order and removal', () => {
  it('moves a metric as far as the order allows', async () => {
    const { analysis, draft } = await editor([
      count,
      amount,
      derived('perOrder', 'amount', 'orders'),
    ]);

    const moved = analysis().moveMetric(2, 0);
    expect(moved?.stop).toEqual({ side: 'after', at: 'amount' });
    await waitFor(() =>
      expect(aliases(draft().metrics)).toEqual([
        'orders',
        'amount',
        'perOrder',
      ]),
    );

    analysis().moveMetric(1, 0);
    await waitFor(() =>
      expect(aliases(draft().metrics)).toEqual([
        'amount',
        'orders',
        'perOrder',
      ]),
    );
  });

  /**
   * #3777 makes an edit that would leave nothing to measure as asked, and
   * admission refuses it; the ✕ that makes it is off instead.
   */
  it('says which metric may not be removed, and why', async () => {
    const { analysis } = await editor([
      amount,
      derived('double', 'amount', 'amount'),
    ]);
    expect(analysis().metricRemoval(0)).toBe('cascade');
    expect(analysis().metricRemoval(1)).toBe('ok');

    const lone = await editor([count]);
    expect(lone.analysis().metricRemoval(0)).toBe('last');
  });

  /** Any edit, not only an expansion: a metric removed takes its readers. */
  it('reports what a removal took along, and undoes it', async () => {
    const { analysis, draft } = await editor([
      count,
      amount,
      derived('perOrder', 'amount', 'orders'),
    ]);

    analysis().removeMetric(1);
    await waitFor(() => expect(analysis().dropped).not.toBeNull());
    expect(analysis().dropped?.cause).toBeUndefined();
    expect(aliases(analysis().dropped!.metrics)).toEqual(['perOrder']);
    expect(aliases(draft().metrics)).toEqual(['orders']);

    analysis().undoDrop();
    await waitFor(() =>
      expect(aliases(draft().metrics)).toEqual([
        'orders',
        'amount',
        'perOrder',
      ]),
    );
    expect(analysis().dropped).toBeNull();
  });
});

describe('the metrics row', () => {
  const label = (
    key: keyof typeof defaultMessages,
    values: Record<string, string | number> = {},
  ) => formatMessage(defaultMessages, key, values);

  async function open(metrics: AnalysisMetric[]) {
    const engine = engineOver(metrics);
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        kinds={['analysis']}
      />,
    );
    await openTray();
    const draft = () =>
      engine.openRuntimes()[0]!.getSnapshot().draft as AnalysisViewConfig;
    return { draft };
  }
  const handle = (name: string) =>
    screen.getByRole('button', {
      name: label('label.chart.reorder', { name }),
    });
  const note = () =>
    document.querySelector<HTMLElement>('[data-slot="metric-order-note"]');

  /**
   * The arrow keys on a card's handle move it along the row; a derived
   * metric pressed before what it reads stays where the rule stops it, and
   * the row says why, under the cards and aloud.
   */
  it('moves a card from its handle, and stops a derived one short', async () => {
    const { draft } = await open([
      count,
      amount,
      derived('perOrder', 'amount', 'orders'),
    ]);
    const sum = formatMessage(defaultMessages, 'label.summary.of', {
      field: 'Amount',
      fn: defaultMessages['label.summary.fn.SUM'],
    });

    fireEvent.keyDown(handle('Per order'), { key: 'ArrowLeft' });

    const why = label('label.analysis.move-stop.after', {
      name: 'Per order',
      other: sum,
    });
    await waitFor(() => expect(note()?.textContent).toBe(why));
    expect(aliases(draft().metrics)).toEqual(['orders', 'amount', 'perOrder']);
    expect(
      document.querySelector('[data-slot="metric-order-voice"]')?.textContent,
    ).toBe(why);

    fireEvent.keyDown(handle(sum), { key: 'ArrowLeft' });
    await waitFor(() =>
      expect(aliases(draft().metrics)).toEqual([
        'amount',
        'orders',
        'perOrder',
      ]),
    );
    expect(note()).toBeNull();
  });

  it('draws no handle on a lone metric', async () => {
    await open([count]);
    expect(
      within(
        screen.getByRole('region', {
          name: defaultMessages['label.analysis.slot.metrics'],
        }),
      ).queryAllByRole('button', { name: /^Reorder/ }),
    ).toEqual([]);
  });

  /** The ✕ that would leave nothing to measure is off and says why. */
  it('holds the ✕ whose removal would leave no metric', async () => {
    const { draft } = await open([
      amount,
      derived('double', 'amount', 'amount'),
    ]);
    const sum = formatMessage(defaultMessages, 'label.summary.of', {
      field: 'Amount',
      fn: defaultMessages['label.summary.fn.SUM'],
    });
    const remove = screen.getByRole('button', {
      name: label('label.analysis.remove-metric', { name: sum }),
    });

    expect(remove.getAttribute('aria-disabled')).toBe('true');
    expect(
      document.getElementById(remove.getAttribute('aria-describedby')!)
        ?.textContent,
    ).toBe(defaultMessages['label.analysis.remove-cascade']);
    fireEvent.click(remove);
    expect(draft().metrics).toHaveLength(2);
  });
});
