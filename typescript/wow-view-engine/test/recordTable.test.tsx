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

import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type { ViewInstance, ViewSource } from '../src/index.js';
import type { RecordTableController } from '../src/react/index.js';
import {
  RecordCards,
  RecordTable,
  RecordWorkbench,
  ViewSurface,
} from '../src/ui/index.js';
import {
  INSTANT,
  ZONE,
  inZone,
  ordersDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';
import { mine } from './fixtures/ui.js';

afterEach(cleanup);

function tableController(
  overrides: Partial<RecordTableController> = {},
): RecordTableController {
  return {
    columns: [
      {
        field: 'amount',
        label: 'Amount',
        kind: 'number',
        cell: 'number',
        sortable: true,
        numberFormat: { style: 'currency', currency: 'CNY' },
      },
      {
        field: 'warehouse',
        label: 'Warehouse',
        kind: 'string',
        cell: 'string',
        sortable: false,
      },
    ],
    rows: [
      { key: 'o-1', data: { amount: 10, warehouse: 'CN' } },
      { key: 'o-2', data: { amount: null, warehouse: true } },
    ],
    card: {
      title: 'warehouse',
      fields: [{ field: 'amount', label: 'Amount' }],
    },
    paging: { mode: 'paged', index: 1, total: 2 },
    summaries: null,
    status: 'success',
    error: null,
    loading: false,
    sort: [],
    sortOf: () => null,
    toggleSort: () => {},
    layout: 'table',
    layouts: ['table', 'card'],
    setLayout: () => {},
    columnFields: ['amount', 'warehouse'],
    setColumns: () => {},
    pageSize: 20,
    pageSizes: [10, 20, 50, 100],
    setPageSize: () => {},
    selection: [],
    selectedRows: [],
    isSelected: () => false,
    toggle: () => {},
    toggleAll: () => {},
    clearSelection: () => {},
    goTo: () => {},
    hasNext: true,
    next: () => {},
    previous: () => {},
    refresh: () => {},
    ...overrides,
  };
}

describe('RecordTable on its own', () => {
  it('hands a custom cell the null the record holds', () => {
    const seen: unknown[] = [];
    render(
      <RecordTable
        table={tableController()}
        renderCell={cell => {
          if (cell.key === 'o-2' && cell.column.field === 'amount')
            seen.push(cell.value);
          return null;
        }}
      />,
    );
    expect(seen).toEqual([null]);
  });

  it('formats each value by what the column declared', () => {
    render(<RecordTable table={tableController()} />);

    expect(screen.getByText('CN¥10.00')).toBeDefined();
    expect(screen.getByText('Yes')).toBeDefined();
    // A sortable column gets a button; a plain one is just its label.
    expect(screen.getByRole('button', { name: /Amount/ })).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Warehouse' })).toBeNull();
  });

  /**
   * Wow keeps a time as epoch milliseconds and an enum as its code, so a
   * table that printed values as they came was a column of thirteen-digit
   * numbers beside a column of constants.
   */
  it('shows a time in the zone and language of its surface, and an enum by its label', () => {
    render(
      <ViewSurface locale="en-GB" timeZone={ZONE}>
        <RecordTable
          table={tableController({
            columns: [
              {
                field: 'createdAt',
                label: 'Created',
                kind: 'datetime',
                cell: 'datetime',
                sortable: false,
              },
              {
                field: 'status',
                label: 'Status',
                kind: 'enum',
                cell: 'enum',
                sortable: false,
                options: [{ value: 'FAILED', label: 'Failed' }],
              },
            ],
            rows: [
              { key: 'o-1', data: { createdAt: INSTANT, status: 'FAILED' } },
            ],
          })}
        />
      </ViewSurface>,
    );

    expect(screen.getByText(inZone(INSTANT))).toBeDefined();
    expect(screen.getByText('Failed')).toBeDefined();
  });

  it('still renders a column whose number format Intl refuses', () => {
    render(
      <RecordTable
        table={tableController({
          columns: [
            {
              field: 'amount',
              label: 'Amount',
              kind: 'number',
              cell: 'number',
              sortable: false,
              numberFormat: { style: 'currency' },
            },
          ],
          rows: [{ key: 'o-1', data: { amount: 10 } }],
        })}
      />,
    );

    expect(screen.getByText('10')).toBeDefined();
  });

  it('marks sort direction on the column it applies to', () => {
    render(
      <RecordTable
        table={tableController({
          sortOf: field => (field === 'amount' ? 'DESC' : null),
        })}
      />,
    );
    expect(screen.getByRole('button', { name: /Amount/ })).toBeDefined();

    cleanup();
    render(
      <RecordTable
        table={tableController({
          sortOf: field => (field === 'amount' ? 'ASC' : null),
        })}
      />,
    );
    expect(screen.getByRole('button', { name: /Amount/ })).toBeDefined();
  });

  it('shows skeletons on a first load and an empty state after it', () => {
    render(
      <RecordTable table={tableController({ status: 'loading', rows: [] })} />,
    );
    expect(screen.getAllByRole('row').length).toBeGreaterThan(1);

    cleanup();
    render(<RecordTable table={tableController({ rows: [] })} />);
    expect(screen.getByText('Nothing to show')).toBeDefined();
  });

  it('takes a renderer for the cells', () => {
    render(
      <RecordTable
        table={tableController()}
        renderCell={cell => <em>{String(cell.value)}</em>}
      />,
    );
    expect(screen.getByText('10')).toBeDefined();
  });
});

describe('RecordCards on its own', () => {
  it('reads a nested title field by its path', () => {
    render(
      <RecordCards
        table={tableController({
          card: { title: 'customer.name', fields: [] },
          rows: [{ key: 'o-1', data: { customer: { name: 'Acme' } } }],
        })}
      />,
    );
    expect(screen.getByText('Acme')).toBeDefined();
  });

  it('titles a card by the field the card spec names', () => {
    render(<RecordCards table={tableController()} />);

    const [first, second] = screen.getAllByText(
      (_text, element) =>
        (element as HTMLElement | null)?.dataset.slot === 'card-title',
    );
    expect(first.textContent).toContain('CN');
    // The second row's title field is a boolean, which reads as Yes.
    expect(second.textContent).toContain('Yes');
  });

  it('shows a card value as its column would', () => {
    render(
      <ViewSurface locale="en-GB" timeZone={ZONE}>
        <RecordCards
          table={tableController({
            card: {
              title: 'status',
              titleField: {
                field: 'status',
                label: 'Status',
                options: [{ value: 'FAILED', label: 'Failed' }],
              },
              fields: [
                { field: 'createdAt', label: 'Created', kind: 'datetime' },
                {
                  field: 'amount',
                  label: 'Amount',
                  numberFormat: { style: 'currency', currency: 'CNY' },
                },
              ],
            },
            rows: [
              {
                key: 'o-1',
                data: { status: 'FAILED', createdAt: INSTANT, amount: 10 },
              },
            ],
          })}
        />
      </ViewSurface>,
    );

    expect(screen.getByText('Failed')).toBeDefined();
    expect(screen.getByText(inZone(INSTANT))).toBeDefined();
    expect(screen.getByText('CN¥10.00')).toBeDefined();
  });

  it("leaves a card's values to the host's renderer when it has one", () => {
    render(
      <ViewSurface locale="en-GB" timeZone={ZONE}>
        <RecordCards
          table={tableController({
            card: {
              title: 'id',
              fields: [
                { field: 'createdAt', label: 'Created', kind: 'datetime' },
              ],
            },
            rows: [{ key: 'o-1', data: { id: 'o-1', createdAt: INSTANT } }],
          })}
          renderValue={value => <em>raw {String(value)}</em>}
        />
      </ViewSurface>,
    );

    expect(screen.getByText(`raw ${INSTANT}`)).toBeDefined();
    expect(screen.queryByText(inZone(INSTANT))).toBeNull();
  });

  it('falls back to the row key without a title field', () => {
    render(
      <RecordCards
        table={tableController({ card: { title: '', fields: [] } })}
      />,
    );
    expect(screen.getByText('o-1')).toBeDefined();
  });

  /**
   * A card is not the table narrowed. Rendering `table.columns` showed the
   * column list under a title nobody configured, so every card setting a user
   * saved — which field titles it, what its body holds, its picture — was
   * stored, validated and then ignored.
   */
  it('shows the body fields and the image of the saved card', () => {
    const { container } = render(
      <RecordCards
        table={tableController({
          card: {
            title: 'warehouse',
            fields: [{ field: 'amount', label: 'Total' }],
            image: 'photo',
            columns: 2,
          },
          rows: [
            { key: 'o-1', data: { warehouse: 'CN', amount: 10, photo: '/a' } },
          ],
        })}
      />,
    );

    // The card's own label, not the column's, and none of the other columns.
    expect(screen.getByText('Total')).toBeDefined();
    expect(screen.queryByText('Warehouse')).toBeNull();
    expect(container.querySelector('img')?.getAttribute('src')).toBe('/a');
    expect(
      container
        .querySelector('[data-slot="record-cards"]')
        ?.className.includes('sm:grid-cols-2'),
    ).toBe(true);
  });
});

describe('the summary row', () => {
  const withSummary: ViewInstance = {
    ...mine,
    config: recordConfig({ summaries: [{ field: 'amount', fn: 'SUM' }] }),
  };

  function setupSummary(source: ViewSource = testSource()) {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [withSummary] }),
      resolveSource: () => source,
    });
    return render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
  }

  it('shows the total the aggregation returned', async () => {
    const { container } = setupSummary();

    const footer = await waitFor(() => {
      const found = container.querySelector('tfoot');
      expect(found).not.toBeNull();
      return found as HTMLElement;
    });

    expect(footer.dataset.scope).toBe('total');
    expect(footer.textContent).toContain('30');
  });

  /**
   * A failed summary query costs the summary, not the page. What it must not
   * cost is the reader's ability to tell the two numbers apart: the sum of the
   * rows on screen presented as the sum over everything is the one mistake
   * this row could make.
   */
  it('shows every function configured for one field', async () => {
    const both: ViewInstance = {
      ...mine,
      config: recordConfig({
        summaries: [
          { field: 'amount', fn: 'SUM' },
          { field: 'amount', fn: 'AVG' },
        ],
      }),
    };
    // The shared fixture allows only SUM on `amount`; this view asks for two.
    const definition = ordersDefinition();
    const engine = new ViewEngine({
      definitions: [
        {
          ...definition,
          fields: definition.fields.map(field =>
            field.name === 'amount'
              ? { ...field, summary: ['SUM' as const, 'AVG' as const] }
              : field,
          ),
        },
      ],
      store: new MemoryViewStore({ instances: [both] }),
      resolveSource: () => testSource(),
    });
    const { container } = render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    const footer = await waitFor(() => {
      const found = container.querySelector('tfoot');
      expect(found).not.toBeNull();
      return found as HTMLElement;
    });

    // Keying the cells by field would keep only the last one configured.
    expect(footer.textContent).toContain('SUM');
    expect(footer.textContent).toContain('AVG');
  });

  it('says so when it fell back to the rows on screen', async () => {
    const { container } = setupSummary(
      testSource({ aggregate: () => Promise.reject(new Error('down')) }),
    );

    const footer = await waitFor(() => {
      const found = container.querySelector('tfoot');
      expect(found).not.toBeNull();
      return found as HTMLElement;
    });

    expect(footer.dataset.scope).toBe('page');
    expect(footer.textContent).toContain('This page');
  });
});
