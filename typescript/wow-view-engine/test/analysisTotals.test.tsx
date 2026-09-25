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
 * The totals row and the footer, where they and the rows above them part
 * ways (2026-09-23 audit P1).
 *
 * The totals mean every record in the range (D20), and that meaning stays.
 * What changes is what is said beside them: the groups past the first N and
 * the groups 「只保留」 dropped are in the totals and in no row, and without a
 * dimension there are no groups at all — the one row is the whole range, so
 * no totals row repeats it and the footer counts no 「1 组」.
 */

import { AggregationFunction } from '@ahoo-wang/wow-client';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  asksForWhole,
  projectAnalysis,
  type AnalysisViewConfig,
  type RecordData,
  type ViewSource,
} from '../src/index.js';
import {
  DataWorkbench,
  defaultMessages,
  formatMessage,
} from '../src/ui/index.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';

afterEach(cleanup);

/** Four warehouses, answered up to the limit asked; the totals row alone. */
function source(count = 4): ViewSource {
  const rows: RecordData[] = Array.from({ length: count }, (_u, index) => ({
    warehouse: `W-${index}`,
    orders: count - index,
  }));
  return testSource({
    aggregate: vi.fn((query: { groupBy?: unknown; limit?: number }) =>
      Promise.resolve(
        query.groupBy === undefined
          ? [{ orders: 10 }]
          : rows.slice(0, query.limit ?? count),
      ),
    ),
  });
}

function show(
  config: Partial<AnalysisViewConfig>,
  from = source(),
  fields = ordersDefinition().analysis!.fields,
) {
  const engine = new ViewEngine({
    definitions: [
      ordersDefinition({
        analysis: { ...ordersDefinition().analysis!, having: true, fields },
      }),
    ],
    store: new MemoryViewStore({
      instances: [
        {
          id: 'orders-1',
          definitionId: 'orders',
          title: 'By warehouse',
          scope: 'personal',
          revision: '1',
          config: analysisConfig({
            table: { columns: [], totals: true },
            ...config,
          }),
        },
      ],
    }),
    resolveSource: () => from,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
      kinds={['analysis']}
    />,
  );
  return { from };
}

const slot = (name: string) =>
  document.querySelector<HTMLElement>(`[data-slot="${name}"]`);

describe('the totals row beside rows that leave some out', () => {
  it('says the groups past the first N are in it', async () => {
    show({ limit: 2 });
    await waitFor(() => expect(slot('totals-row')).not.toBeNull());

    expect(slot('totals-scope')!.textContent).toBe(
      defaultMessages['label.analysis.totals-scope'],
    );
    expect(slot('totals-hidden')!.textContent).toBe(
      formatMessage(defaultMessages, 'label.analysis.totals-hidden.cut', {
        limit: '2',
      }),
    );
  });

  it('says the groups 「只保留」 dropped are in it', async () => {
    show({
      having: { type: 'CONDITION', metric: 'orders', operator: 'GT', value: 2 },
    });
    await waitFor(() => expect(slot('totals-row')).not.toBeNull());

    expect(slot('totals-hidden')!.textContent).toBe(
      defaultMessages['label.analysis.totals-hidden.kept'],
    );
  });

  it('says both when both are true, and nothing when neither is', async () => {
    show({
      limit: 2,
      having: {
        type: 'CONDITION',
        metric: 'orders',
        operator: 'GT',
        value: 0,
      },
    });
    await waitFor(() => expect(slot('totals-row')).not.toBeNull());
    expect(slot('totals-hidden')!.textContent).toBe(
      [
        formatMessage(defaultMessages, 'label.analysis.totals-hidden.cut', {
          limit: '2',
        }),
        defaultMessages['label.analysis.totals-hidden.kept'],
      ].join(' · '),
    );

    cleanup();
    show({});
    await waitFor(() => expect(slot('totals-row')).not.toBeNull());
    expect(slot('totals-hidden')).toBeNull();
  });
});

describe('an analysis with no dimension', () => {
  it('asks for no second, ungrouped query: its answer is already the whole', () => {
    const whole = analysisConfig({
      groups: [],
      table: { columns: [], totals: true },
    });
    expect(asksForWhole(whole)).toBe(false);

    const view = projectAnalysis(
      ordersDefinition(),
      whole,
      [{ orders: 10 }],
      [{ orders: 10 }],
    );
    expect(view.totals).toBeUndefined();
    expect(view.narrowed).toBeUndefined();
  });

  it('draws no totals row, and its footer counts no groups', async () => {
    const { from } = show({
      groups: [],
      chart: { type: 'metric', metric: { metric: 'orders' } },
    });
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());

    expect(slot('totals-row')).toBeNull();
    // One query: the ungrouped answer is the one row.
    expect(vi.mocked(from.aggregate)).toHaveBeenCalledTimes(1);
    const caption = slot('analysis-caption')!.textContent!;
    expect(caption).toBe(
      // An answer faster than the footer's smallest step says so rather
      // than 「0 秒」 (the 2026-09-23 audit, P2-9).
      formatMessage(defaultMessages, 'label.analysis.caption-whole', {
        seconds: '<0.01',
      }),
    );
  });

  it('counts one group as one', async () => {
    show({ limit: 1 });
    await waitFor(() => expect(slot('totals-row')).not.toBeNull());
    expect(slot('analysis-caption')!.textContent).toBe(
      'Showing 1 group · took <0.01 s',
    );
  });

  it('keeps the counting footer once there is a dimension', async () => {
    show({});
    await waitFor(() => expect(slot('totals-row')).not.toBeNull());
    expect(slot('analysis-caption')!.textContent).toBe(
      formatMessage(defaultMessages, 'label.analysis.caption', {
        count: '4',
        seconds: '<0.01',
      }),
    );
  });
});

/**
 * The totals row is the ungrouped query, and every aggregate over it is the
 * right number for the whole range (kernels.md 「合计行」) — bar `ANY`,
 * which is one record's value. Over the whole range that value is some
 * record's, a random buyer's nickname, and under 「合计」 it reads as the
 * whole's: the row leaves it blank rather than say it.
 */
describe('a metric the whole range has no value of', () => {
  const metrics: AnalysisViewConfig['metrics'] = [
    { alias: 'orders', type: 'COUNT' },
    { alias: 'anyStatus', type: 'ANY', field: 'status' },
    {
      alias: 'amountMax',
      type: 'NUMERIC',
      function: 'MAX',
      expression: { type: 'FIELD', field: 'amount' },
    },
  ];

  it('leaves an ANY out of the totals and keeps what is right for the whole', () => {
    const view = projectAnalysis(
      ordersDefinition(),
      analysisConfig({ metrics, table: { columns: [], totals: true } }),
      [
        { warehouse: 'W-0', orders: 6, anyStatus: 'paid', amountMax: 90 },
        { warehouse: 'W-1', orders: 4, anyStatus: 'shipped', amountMax: 120 },
      ],
      [{ orders: 10, anyStatus: 'shipped', amountMax: 120 }],
    );
    expect(view.totals).toEqual({ orders: 10, amountMax: 120 });
  });

  it('draws that cell blank under 「合计」', async () => {
    show(
      { metrics },
      testSource({
        aggregate: vi.fn((query: { groupBy?: unknown }) =>
          Promise.resolve(
            query.groupBy === undefined
              ? [{ orders: 10, anyStatus: 'Carol', amountMax: 120 }]
              : [
                  {
                    warehouse: 'W-0',
                    orders: 10,
                    anyStatus: 'Alice',
                    amountMax: 120,
                  },
                ],
          ),
        ),
      }),
      [
        ordersDefinition().analysis!.fields[0],
        {
          field: 'amount',
          groups: [],
          functions: [AggregationFunction.SUM, AggregationFunction.MAX],
        },
        { field: 'status', groups: [], functions: [], any: true },
      ],
    );
    await waitFor(() => expect(slot('totals-row')).not.toBeNull());
    expect(slot('totals-row')!.textContent).not.toContain('Carol');
    expect(slot('totals-row')!.textContent).toContain('120');
  });
});
