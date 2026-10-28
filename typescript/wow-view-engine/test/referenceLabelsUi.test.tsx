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
 * D354 on screen: a reference field's ids read as names wherever a view
 * shows them — table, cards, the record detail, an analysis table, a
 * chart's categories, the export file, a pill — the id standing in while
 * the name is asked or missing, and one `resolve` for a page.
 */

import {
  cleanup,
  render,
  renderHook,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type * as React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  AggregationGroupType,
  FilterOperator,
} from '@ahoo-wang/wow-client/dsl';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type {
  DataViewDefinition,
  FieldOption,
  FilterTree,
  OptionSource,
  ViewConfig,
  ViewInstance,
} from '../src/index.js';
import { useOpenView } from '../src/react/index.js';
import { EmbeddedView, ViewSurface } from '../src/ui/index.js';
import { useSeriesName, useValueLabel } from '../src/ui/charts/family.js';
import type { ReferenceId } from '../src/runtime/referenceLabels.js';
import {
  analysisConfig,
  deferred,
  mine,
  ordersDefinition,
  recordConfig,
  resourcesOf,
  testSource,
} from './fixtures.js';
import { settle } from './fixtures/ui.js';

afterEach(cleanup);

const ROWS = [
  { id: 'o-1', customer: 'c1', amount: 10 },
  { id: 'o-2', customer: 'c2', amount: 20 },
  { id: 'o-3', customer: 'gone', amount: 30 },
];

const NAMES: Record<string, string> = { c1: 'Ada Lovelace', c2: 'Alan Turing' };

/** Orders whose customer is a reference to another dataset. */
function definition(): DataViewDefinition {
  const base = ordersDefinition();
  return {
    ...base,
    fields: [
      ...base.fields,
      {
        name: 'customer',
        label: 'Customer',
        kind: 'reference',
        remote: 'customers',
      },
    ],
    analysis: {
      ...base.analysis!,
      fields: [
        ...base.analysis!.fields,
        {
          field: 'customer',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
      ],
    },
  };
}

/**
 * The customers' source: each `resolve` is kept, and answered at once or
 * when the test says (`hold`). `gone` is no customer.
 */
function customers(hold = false) {
  const asked: ReferenceId[][] = [];
  const answer = deferred<void>();
  const source: OptionSource = {
    search: () => Promise.resolve({ items: [], nextCursor: null }),
    resolve: async ids => {
      asked.push([...ids]);
      if (hold) await answer.promise;
      return ids.flatMap((id): FieldOption[] =>
        NAMES[id] ? [{ value: id, label: NAMES[id] }] : [],
      );
    },
  };
  return { source, asked, release: () => answer.resolve() };
}

function engineOver(config: ViewConfig, source: OptionSource): ViewEngine {
  const instance: ViewInstance = { ...mine, config };
  return new ViewEngine({
    resources: resourcesOf([definition()], () =>
      testSource({
        paged: vi.fn(() =>
          Promise.resolve({ total: 3, exact: true, list: [...ROWS] }),
        ),
        aggregate: vi.fn(() =>
          Promise.resolve([
            { customer: 'c1', orders: 2 },
            { customer: 'c2', orders: 1 },
          ]),
        ),
      }),
    ),
    store: new MemoryViewStore({ instances: [instance] }),
    resolveOptions: () => source,
  });
}

const tableConfig = (filter?: FilterTree) =>
  recordConfig({
    table: { columns: [{ field: 'id' }, { field: 'customer' }] },
    card: { title: 'id', fields: ['customer'] },
    ...(filter ? { filter } : {}),
  });

const byCustomer = analysisConfig({
  groups: [
    { alias: 'customer', field: 'customer', type: AggregationGroupType.TERMS },
  ],
  chart: {
    type: 'bar',
    cartesian: { x: 'customer', series: [{ metric: 'orders' }] },
  },
});

/** The customer cell of the row of `key`. */
function customerCell(key: string): HTMLElement {
  const row = screen.getByText(key).closest('tr');
  if (!row) throw new Error(`no row ${key}`);
  const cell = row.querySelector<HTMLElement>('[data-slot="cell-reference"]');
  if (!cell) throw new Error(`no reference in ${key}`);
  return cell;
}

describe('a record table reads reference ids as names', () => {
  it('shows the id while pending, then the name, with one resolve for the page', async () => {
    const remote = customers(true);
    render(
      <EmbeddedView
        engine={engineOver(tableConfig(), remote.source)}
        instanceId="orders-1"
      />,
    );

    await waitFor(() => expect(customerCell('o-1').textContent).toBe('c1'));
    expect(customerCell('o-1').getAttribute('data-label')).toBe('pending');

    remote.release();
    await waitFor(() =>
      expect(customerCell('o-1').textContent).toBe('Ada Lovelace'),
    );
    expect(customerCell('o-1').hasAttribute('data-label')).toBe(false);
    expect(customerCell('o-2').textContent).toBe('Alan Turing');
    expect(remote.asked).toEqual([['c1', 'c2', 'gone']]);
  });

  it('shows an id the source does not know as itself, marked unresolved', async () => {
    const remote = customers();
    render(
      <EmbeddedView
        engine={engineOver(tableConfig(), remote.source)}
        instanceId="orders-1"
      />,
    );

    await waitFor(() =>
      expect(customerCell('o-3').getAttribute('data-label')).toBe('unresolved'),
    );
    expect(customerCell('o-3').textContent).toBe('gone');
  });
});

describe('cards and the record detail read names', () => {
  it('names the customer on each card', async () => {
    const remote = customers();
    render(
      <EmbeddedView
        engine={engineOver({ ...tableConfig(), layout: 'card' }, remote.source)}
        instanceId="orders-1"
      />,
    );

    await waitFor(() => {
      const fields = [
        ...document.querySelectorAll('[data-slot="card-field"]'),
      ].map(field => field.textContent);
      expect(fields).toContain('CustomerAda Lovelace');
      expect(fields).toContain('CustomerAlan Turing');
    });
    expect(remote.asked).toHaveLength(1);
  });

  it('names the customer in the detail a row opens', async () => {
    const remote = customers();
    render(
      <EmbeddedView
        engine={engineOver(tableConfig(), remote.source)}
        instanceId="orders-1"
        interaction="interactive"
        detail
      />,
    );

    await waitFor(() =>
      expect(customerCell('o-1').textContent).toBe('Ada Lovelace'),
    );
    const row = screen.getByText('o-1').closest('tr')!;
    await userEvent.click(within(row).getAllByRole('cell')[1]!);
    const panel = await screen.findByRole('dialog');
    await within(panel).findByText('Ada Lovelace');
  });
});

describe('an analysis grouped by a reference reads names', () => {
  it('names each group in the result table', async () => {
    const remote = customers();
    render(
      <EmbeddedView
        engine={engineOver(byCustomer, remote.source)}
        instanceId="orders-1"
      />,
    );

    await screen.findByText('Ada Lovelace');
    expect(screen.getByText('Alan Turing')).toBeTruthy();
    expect(screen.queryByText('c1')).toBeNull();
    expect(remote.asked).toEqual([['c1', 'c2']]);
  });

  it("names a chart's categories and series", async () => {
    const remote = customers();
    const engine = engineOver(byCustomer, remote.source);
    const opened = renderHook(() => useOpenView(engine, 'orders-1'));
    await waitFor(() => expect(opened.result.current.runtime).not.toBeNull());
    const runtime = opened.result.current.runtime!;
    await waitFor(() => expect(runtime.getSnapshot().result).toBeTruthy());
    const data = runtime.getSnapshot().result!.data;
    const columns = data.kind === 'analysis' ? data.view.columns : [];

    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <ViewSurface labels={runtime.labels}>{children}</ViewSurface>
    );
    const { result } = renderHook(
      () => {
        const label = useValueLabel(columns);
        return { label, series: useSeriesName(columns, label) };
      },
      { wrapper },
    );

    await waitFor(() =>
      expect(result.current.label('customer', 'c1')).toBe('Ada Lovelace'),
    );
    expect(result.current.series('customer', 'c2')).toBe('Alan Turing');
  });
});

describe('the export file reads names', () => {
  it('writes the customer by name', async () => {
    const createObjectURL = vi.fn((blob: Blob) => {
      void blob;
      return 'blob:labels';
    });
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() });
    const user = userEvent.setup();
    const remote = customers();
    render(
      <EmbeddedView
        engine={engineOver(tableConfig(), remote.source)}
        instanceId="orders-1"
        interaction="interactive"
        withExport
      />,
    );

    await waitFor(() =>
      expect(customerCell('o-1').textContent).toBe('Ada Lovelace'),
    );
    await user.click(await screen.findByRole('button', { name: /Export/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Export' });
    await user.click(within(dialog).getByRole('button', { name: 'Export' }));
    await waitFor(() => expect(createObjectURL).toHaveBeenCalledTimes(1));
    const text = await createObjectURL.mock.calls[0][0].text();
    expect(text).toContain('Ada Lovelace');
    expect(text).toContain('Alan Turing');
    // An id with no name is written as the id.
    expect(text).toContain('gone');
  });
});

describe('a pill reads a stored id as its name', () => {
  it('describes an item whose label is its id by the cached name', async () => {
    const remote = customers();
    const engine = engineOver(
      tableConfig({
        op: 'and',
        children: [
          {
            field: 'customer',
            operator: FilterOperator.IN,
            value: { items: [{ id: 'c1', label: 'c1' }] },
          },
        ],
      }),
      remote.source,
    );
    render(<EmbeddedView engine={engine} instanceId="orders-1" />);
    const bar = () =>
      document.querySelector('[data-slot="applied-bar"]')?.textContent ?? '';

    // The row's id is asked for the table, and the pill reads the answer.
    await waitFor(() => expect(bar()).toContain('Ada Lovelace'));
    await settle();
    expect(bar()).not.toContain('c1');
  });
});
