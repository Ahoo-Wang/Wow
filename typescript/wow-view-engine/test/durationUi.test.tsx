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
 * A time between two moments in the tray (N3): 「两个时刻之差」 in the add
 * menu where the capability computes expressions, its card's two times,
 * unit and summary, and 「按这个时长分区间」 turning it into a dimension.
 */

import {
  AggregationDateUnit,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisCapability,
  type AnalysisViewConfig,
  type ViewInstance,
} from '../src/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { ordersDefinition, testSource } from './fixtures.js';
import { openTray } from './fixtures/workbench.js';

afterEach(cleanup);

async function open(analysis: Partial<AnalysisCapability> = {}) {
  const config = {
    kind: 'analysis',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    groups: [{ type: 'TERMS', field: 'warehouse', alias: 'wh' }],
    metrics: [{ type: 'COUNT', alias: 'orders' }],
    sort: [],
    limit: 100,
    layout: 'table',
    table: { columns: [] },
    chart: {
      type: 'bar',
      cartesian: { x: 'wh', series: [{ metric: 'orders' }] },
    },
  } as unknown as AnalysisViewConfig;
  const instance: ViewInstance = {
    id: 'orders-1',
    definitionId: 'orders',
    title: 'By warehouse',
    scope: 'personal',
    revision: '1',
    config,
  };
  const definition = ordersDefinition({
    fields: [
      { name: 'id', label: 'Order', kind: 'string', sortable: true },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'status', label: 'Status', kind: 'string' },
      { name: 'amount', label: 'Amount', kind: 'number' },
      { name: 'paidAt', label: 'Paid', kind: 'datetime' },
      { name: 'shippedAt', label: 'Shipped', kind: 'datetime' },
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
        ...(['paidAt', 'shippedAt'] as const).map(field => ({
          field,
          groups: [AggregationGroupType.DATE_HISTOGRAM],
          functions: [],
          dateUnits: [AggregationDateUnit.DAY],
        })),
      ],
      ...analysis,
    },
  });
  const engine = new ViewEngine({
    definitions: [definition],
    store: new MemoryViewStore({ instances: [instance] }),
    resolveSource: () => testSource(),
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
      kinds={['analysis']}
    />,
  );
  const tray = await openTray();
  return {
    tray,
    draft: () =>
      engine.openRuntimes()[0]!.getSnapshot().draft as AnalysisViewConfig,
  };
}

const addItem = async () => {
  fireEvent.click(screen.getByRole('button', { name: 'Add metric' }));
  await screen.findByRole('menu');
  return screen.queryByRole('menuitem', { name: 'Time between two moments' });
};

describe('a time between two moments in the tray', () => {
  it('is built on its card: two times, a unit and a summary', async () => {
    const user = userEvent.setup();
    const { tray, draft } = await open();
    fireEvent.click((await addItem())!);
    await waitFor(() =>
      expect(draft().metrics[1]).toEqual({
        type: 'NUMERIC',
        alias: 'duration_1',
        function: 'AVG',
        expression: {
          type: 'DATE_DIFF',
          from: 'paidAt',
          to: 'shippedAt',
          unit: 'HOUR',
        },
      }),
    );
    const card = tray.querySelectorAll<HTMLElement>(
      '[data-slot="metric-card"]',
    )[1]!;
    const select = (name: RegExp) =>
      within(card).getByRole('combobox', { name });
    expect(select(/^From which time/).textContent).toContain('Paid');
    expect(select(/^To which time/).textContent).toContain('Shipped');

    await user.click(select(/^Unit of /));
    await user.click(await screen.findByRole('option', { name: 'Days' }));
    await waitFor(() =>
      expect(draft().metrics[1]).toMatchObject({
        expression: { unit: 'DAY' },
      }),
    );

    await user.click(select(/^From which time/));
    await user.click(await screen.findByRole('option', { name: 'Shipped' }));
    await waitFor(() =>
      expect(draft().metrics[1]).toMatchObject({
        expression: { from: 'shippedAt', to: 'shippedAt' },
      }),
    );
    await user.click(select(/^To which time/));
    await user.click(await screen.findByRole('option', { name: 'Paid' }));
    await waitFor(() =>
      expect(draft().metrics[1]).toMatchObject({
        expression: { from: 'shippedAt', to: 'paidAt' },
      }),
    );

    await user.click(select(/^Summary for /));
    await user.click(await screen.findByRole('option', { name: 'Percentile' }));
    await waitFor(() =>
      expect(draft().metrics[1]).toEqual({
        type: 'PERCENTILE',
        alias: 'duration_1',
        percentile: 95,
        expression: {
          type: 'DATE_DIFF',
          from: 'shippedAt',
          to: 'paidAt',
          unit: 'DAY',
        },
      }),
    );
    await user.click(select(/^Summary for /));
    await user.click(await screen.findByRole('option', { name: 'Max' }));
    await waitFor(() =>
      expect(draft().metrics[1]).toEqual({
        type: 'NUMERIC',
        alias: 'duration_1',
        function: 'MAX',
        expression: {
          type: 'DATE_DIFF',
          from: 'shippedAt',
          to: 'paidAt',
          unit: 'DAY',
        },
      }),
    );

    // From its menu, the durations become bands: a dimension of their own.
    fireEvent.click(
      card.querySelector<HTMLElement>('[data-slot="card-menu"]')!,
    );
    fireEvent.click(
      await screen.findByRole('menuitem', { name: 'Group by this duration' }),
    );
    await waitFor(() =>
      expect(draft().groups[1]).toEqual({
        type: 'HISTOGRAM',
        alias: 'band_1',
        expression: {
          type: 'DATE_DIFF',
          from: 'shippedAt',
          to: 'paidAt',
          unit: 'DAY',
        },
        interval: 1,
      }),
    );
    const dimension = tray.querySelector<HTMLElement>(
      '[data-slot="dimension-card"][data-field="(band_1)"]',
    )!;
    expect(dimension.textContent).toContain('Shipped → Paid');
    fireEvent.change(within(dimension).getByLabelText('Band width'), {
      target: { value: '2' },
    });
    await waitFor(() =>
      expect(draft().groups[1]).toMatchObject({ interval: 2 }),
    );
  });

  it('is not offered where the capability computes no expressions, or measures in no unit', async () => {
    await open({ expressions: false });
    expect(await addItem()).toBeNull();
    cleanup();
    await open({ dateDiffUnits: [] });
    expect(await addItem()).toBeNull();
  });

  it('is offered greyed while the counting unit holds fewer than two times', async () => {
    await open({
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        {
          field: 'paidAt',
          groups: [AggregationGroupType.DATE_HISTOGRAM],
          functions: [],
          dateUnits: [AggregationDateUnit.DAY],
        },
      ],
    });
    expect((await addItem())?.getAttribute('aria-disabled')).toBe('true');
  });
});
