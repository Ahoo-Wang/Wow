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

import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type { RecordSort, ViewInstance, ViewSource } from '../src/index.js';
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

/** A `ResizeObserver` that reports what it was given and fires on demand. */
class ResizeSpy {
  readonly observed: Element[] = [];
  constructor(private readonly callback: ResizeObserverCallback) {}
  observe(node: Element): void {
    this.observed.push(node);
  }
  unobserve(): void {}
  disconnect(): void {}
  resize(): void {
    this.callback([], this as unknown as ResizeObserver);
  }
}

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

  it('shows the total the aggregation returned, beside the page', async () => {
    const { container } = setupSummary();

    const footer = await waitFor(() => {
      const found = container.querySelector('tfoot');
      expect(found).not.toBeNull();
      return found as HTMLElement;
    });

    // The aggregation answers for everything the conditions match; the page
    // is the two rows the source returned, added up without a second query.
    expect(scopes(footer)).toEqual(['page', 'total']);
    expect(summaryRow(footer, 'total').textContent).toContain('30');
    expect(summaryRow(footer, 'page').textContent).toContain('30');
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

    // Keying the cells by field would keep only the last one configured, and
    // the function is named rather than left as the config's token.
    expect(footer.textContent).toContain('Sum');
    expect(footer.textContent).toContain('Average');
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

    // The only row left is the page, and it says so rather than passing the
    // rows on screen off as everything the conditions match.
    expect(scopes(footer)).toEqual(['page']);
    expect(footer.textContent).toContain('This page');
    expect(footer.textContent).not.toContain('All records');
  });
});

/**
 * Two scopes, side by side.
 *
 * The page is the rows in front of you and the total is everything the
 * conditions match, and the question a summary is read for — is this page
 * representative — is exactly the difference between them. Only the total
 * costs a query; the page is derived from the rows already on screen.
 */
describe('the summary rows', () => {
  const summaries = (
    overrides: Partial<RecordTableController> = {},
  ): RecordTableController =>
    tableController({
      summaries: {
        scope: 'total',
        cells: [
          {
            field: 'amount',
            label: 'Amount',
            fn: 'SUM',
            value: 900,
            numberFormat: { style: 'currency', currency: 'CNY' },
          },
        ],
      },
      ...overrides,
    });

  it('renders no footer at all when the config asked for no summaries', () => {
    const { container } = render(<RecordTable table={tableController()} />);
    expect(container.querySelector('tfoot')).toBeNull();
  });

  it('shows this page beside every record the conditions match', () => {
    const { container } = render(<RecordTable table={summaries()} />);

    const footer = container.querySelector('tfoot')!;
    expect(scopes(footer)).toEqual(['page', 'total']);
    // The page adds up the two rows on screen; the total is the aggregation's
    // own number and is not recomputed from them.
    expect(summaryRow(footer, 'page').textContent).toContain('CN¥10.00');
    expect(summaryRow(footer, 'total').textContent).toContain('CN¥900.00');
    expect(footer.textContent).toContain('This page');
    expect(footer.textContent).toContain('All records');
  });

  it('leaves a column with no summary empty rather than showing a zero', () => {
    const { container } = render(<RecordTable table={summaries()} />);

    // Warehouse is summarised by nothing, so its footer cells say nothing.
    const row = summaryRow(container.querySelector('tfoot')!, 'page');
    expect(row.cells[2].textContent).toBe('');
  });

  /**
   * A summary is only ever configured on a field that allows it, but what a
   * field holds is the source's business: a column of strings summed is a
   * number nobody can compute, and a dash says so where a 0 would lie.
   */
  it('shows a dash where the rows hold nothing to compute', () => {
    const { container } = render(
      <RecordTable
        table={summaries({
          summaries: {
            scope: 'total',
            cells: [
              { field: 'warehouse', label: 'Warehouse', fn: 'SUM', value: 12 },
              { field: 'warehouse', label: 'Warehouse', fn: 'COUNT', value: 7 },
            ],
          },
        })}
      />,
    );

    const footer = container.querySelector('tfoot')!;
    // The page cannot sum two warehouse names, but it can count the rows.
    expect(summaryRow(footer, 'page').textContent).toContain('—');
    expect(summaryRow(footer, 'page').textContent).toContain('2');
    // The aggregation answered both, and its numbers stand as they came.
    expect(summaryRow(footer, 'total').textContent).toContain('12');
  });

  /**
   * Documented rule: with no selection column there is no spare cell, so the
   * scope labels the first column from above rather than taking its place —
   * which would hide whatever that column summarises.
   */
  it('labels the first column from above when rows cannot be picked', () => {
    const { container } = render(
      <RecordTable table={summaries()} selectable={false} />,
    );

    const row = summaryRow(container.querySelector('tfoot')!, 'page');
    // One cell per column and no more, with the label inside the first.
    expect(row.cells).toHaveLength(2);
    expect(row.cells[0].textContent).toContain('This page');
    expect(row.cells[0].textContent).toContain('CN¥10.00');
  });

  it('keeps the footer as wide as the rows when there is an action column', () => {
    const { container } = render(
      <RecordTable table={summaries()} rowActions={() => <button />} />,
    );

    const row = summaryRow(container.querySelector('tfoot')!, 'page');
    // Selection, two columns, actions.
    expect(row.cells).toHaveLength(4);
  });
});

/**
 * Sorting from the headers.
 *
 * The controller applies a sort at once, so what a header has to do is make
 * the state legible: which columns order the table, in which direction, and —
 * once more than one does — in which order they are consulted.
 */
describe('sorting from the headers', () => {
  const sorted = (sort: RecordSort[]) =>
    tableController({
      sort,
      columns: [
        {
          field: 'id',
          label: 'Order',
          kind: 'string',
          cell: 'string',
          sortable: true,
        },
        {
          field: 'amount',
          label: 'Amount',
          kind: 'number',
          cell: 'number',
          sortable: true,
        },
        {
          field: 'status',
          label: 'Status',
          kind: 'string',
          cell: 'string',
          sortable: true,
        },
        {
          field: 'warehouse',
          label: 'Warehouse',
          kind: 'string',
          cell: 'string',
          sortable: false,
        },
      ],
    });

  it('offers a neutral mark on a sortable column nobody has sorted', () => {
    const { container } = render(<RecordTable table={sorted([])} />);

    const head = header(container, 'amount');
    expect(head.querySelector('[data-slot="sort-available"]')).not.toBeNull();
    // Nothing is sorted, so nothing claims to be: a row of headers each
    // announcing `none` is noise, not information.
    expect(container.querySelectorAll('thead [aria-sort]')).toHaveLength(0);
    // A column that cannot be sorted offers nothing at all, not even a mark.
    const plain = header(container, 'warehouse');
    expect(plain.hasAttribute('aria-sort')).toBe(false);
    expect(plain.querySelector('button')).toBeNull();
  });

  it('says which way one sorted column goes, and what a click would do next', () => {
    const { container } = render(
      <RecordTable table={sorted([{ field: 'amount', direction: 'ASC' }])} />,
    );

    const head = header(container, 'amount');
    expect(head.getAttribute('aria-sort')).toBe('ascending');
    // Ascending, then descending, then off: the name is the next step.
    expect(head.querySelector('button')!.getAttribute('aria-label')).toBe(
      'Sort by Amount, descending',
    );
    // One sorted column has no position worth showing.
    expect(head.querySelector('[data-slot="sort-position"]')).toBeNull();
  });

  it('numbers each header while three columns order the table', () => {
    const { container } = render(
      <RecordTable
        table={sorted([
          { field: 'status', direction: 'ASC' },
          { field: 'amount', direction: 'DESC' },
          { field: 'id', direction: 'ASC' },
        ])}
      />,
    );

    expect(position(container, 'status')).toBe('1');
    expect(position(container, 'amount')).toBe('2');
    expect(position(container, 'id')).toBe('3');
    // ARIA marks the column the table is ordered by, and there is one of
    // those however many columns break its ties; the rest would otherwise
    // announce two columns as sorted with nothing saying which comes first.
    expect(
      [...container.querySelectorAll('thead [aria-sort]')].map(cell => [
        (cell as HTMLElement).dataset.field,
        cell.getAttribute('aria-sort'),
      ]),
    ).toEqual([['status', 'ascending']]);
    // The place in the order is read as well as seen.
    expect(
      header(container, 'amount')
        .querySelector('button')!
        .getAttribute('aria-label'),
    ).toContain('sort 2 of 3');
  });

  it('cycles a column off through the pointer and the keyboard alike', async () => {
    const toggled: string[] = [];
    const table = sorted([{ field: 'amount', direction: 'DESC' }]);
    const { container } = render(
      <RecordTable table={{ ...table, toggleSort: f => toggled.push(f) }} />,
    );

    const button = header(container, 'amount').querySelector('button')!;
    expect(button.getAttribute('aria-label')).toBe('Stop sorting by Amount');
    await userEvent.click(button);
    // Every sort is one activation of a focusable button, so the keyboard
    // reaches all of it: there is no modifier to hold and none to emulate.
    button.focus();
    await userEvent.keyboard('{Enter}');
    await userEvent.keyboard(' ');
    expect(toggled).toEqual(['amount', 'amount', 'amount']);
  });
});

/** A status is one of a known set, and reads as one. */
describe('enum cells', () => {
  const status = (
    options: { value: string; label: string }[] | undefined,
    value: unknown = 'PENDING',
  ) =>
    tableController({
      columns: [
        {
          field: 'status',
          label: 'Status',
          kind: 'enum',
          cell: 'enum',
          sortable: false,
          ...(options ? { options } : {}),
        },
      ],
      rows: [{ key: 'o-1', data: { status: value } }],
    });

  const PENDING = [{ value: 'PENDING', label: 'Pending' }];

  it('wears the option label as a badge', () => {
    const { container } = render(<RecordTable table={status(PENDING)} />);

    const badge = container.querySelector('[data-slot="badge"]')!;
    expect(badge.textContent).toBe('Pending');
    // Neutral until a definition can say otherwise; nothing here guesses
    // which of a definition's own statuses is good news.
    expect(badge.getAttribute('data-variant')).toBe('secondary');
  });

  it('gives an array of values one badge each', () => {
    const { container } = render(
      <RecordTable
        table={status(
          [...PENDING, { value: 'SHIPPED', label: 'Shipped' }],
          ['PENDING', 'SHIPPED'],
        )}
      />,
    );

    expect(
      [...container.querySelectorAll('[data-slot="badge"]')].map(
        node => node.textContent,
      ),
    ).toEqual(['Pending', 'Shipped']);
  });

  /**
   * A label is not an identity: a list may hold the same value twice and two
   * options may be worded alike, so badges keyed by their text would collide
   * — and two children under one key is a reconciliation React is free to get
   * wrong, and warns about.
   */
  it('keeps repeated values and repeated wording apart', () => {
    const complained = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { container } = render(
      <RecordTable
        table={status(
          [
            { value: 'PENDING', label: 'Open' },
            // Two codes the definition words the same way.
            { value: 'HELD', label: 'Open' },
          ],
          ['PENDING', 'HELD', 'PENDING'],
        )}
      />,
    );

    expect(
      [...container.querySelectorAll('[data-slot="badge"]')].map(
        node => node.textContent,
      ),
    ).toEqual(['Open', 'Open', 'Open']);
    expect(complained).not.toHaveBeenCalled();
  });

  it('leaves a code nobody named as plain text', () => {
    const { container } = render(
      <RecordTable table={status(undefined, 'PENDING')} />,
    );

    expect(container.querySelector('[data-slot="badge"]')).toBeNull();
    expect(screen.getByText('PENDING')).toBeDefined();
  });
});

/**
 * The chrome around the rows: what stays put while the rest moves. jsdom
 * computes no layout, so what is asserted is the contract the browser then
 * honours — a sticky position and the offset it sticks at.
 */
describe('the table chrome', () => {
  const pinned = () =>
    tableController({
      columns: [
        {
          field: 'id',
          label: 'Order',
          kind: 'string',
          cell: 'string',
          sortable: false,
          pinned: 'left',
          width: 120,
        },
        {
          field: 'amount',
          label: 'Amount',
          kind: 'number',
          cell: 'number',
          sortable: false,
        },
        {
          field: 'status',
          label: 'Status',
          kind: 'string',
          cell: 'string',
          sortable: false,
          pinned: 'right',
        },
      ],
      rows: [{ key: 'o-1', data: { id: 'o-1', amount: 10, status: 'CN' } }],
    });

  it('sticks the header over the rows and the summaries under them', () => {
    const { container } = render(
      <RecordTable
        table={tableController({
          summaries: { scope: 'page', cells: [] },
        })}
      />,
    );

    // One scroll area, so the sideways scrollbar sits under the summaries
    // rather than between them and the rows.
    const area = container.querySelector('[data-slot="record-table"]')!;
    expect(area.className).toContain('overflow-auto');
    expect(container.querySelector('thead')!.className).toContain('sticky');
    expect(container.querySelector('thead')!.className).toContain('top-0');
    expect(container.querySelector('tfoot')!.className).toContain('bottom-0');
  });

  /**
   * A box that scrolls sideways is a scrollport both ways — CSS has no
   * one-axis overflow — so inside a surface that scrolls itself the wrapper
   * must not be one at all: a dashboard panel shorter than this table's own
   * height would otherwise move the header off the top while it stayed put
   * against a box nothing ever scrolls.
   */
  it('leaves the scrolling to the surface around it when asked', () => {
    const { container } = render(
      <RecordTable
        scrolls={false}
        table={tableController({ summaries: { scope: 'page', cells: [] } })}
      />,
    );

    const area = container.querySelector('[data-slot="record-table"]')!;
    expect(area.className).not.toContain('overflow-auto');
    expect(area.className).not.toContain('max-h-');
    // The registry's own container stays out of the way either way, and the
    // header and summaries still hold — against whatever really scrolls.
    expect(area.className).toContain('overflow-visible');
    expect(container.querySelector('thead')!.className).toContain('top-0');
    expect(container.querySelector('tfoot')!.className).toContain('bottom-0');
  });

  it('pins a column on each side and leaves the middle to scroll', () => {
    const { container } = render(
      <RecordTable table={pinned()} rowActions={() => <button />} />,
    );

    // Every cell of a pinned column, header and body alike: a header that
    // stays while its cells leave is worse than no pinning at all.
    for (const cell of cellsOf(container, 'id')) {
      expect(cell.className).toContain('sticky');
      // It clears the selection column rather than sitting on it, at the
      // measured offset where there is one and the class's own until then.
      expect(cell.style.left).toBe('var(--fve-pin-left-0, calc(2.5rem))');
    }
    for (const cell of cellsOf(container, 'status')) {
      expect(cell.className).toContain('sticky');
      // And it clears the action column, whose width is the host's to say.
      expect(cell.style.right).toBe(
        'var(--fve-pin-right-2, calc(var(--fve-record-actions-width, 6rem)))',
      );
    }
    // The selection column is pinned along with them, or the pinned column
    // would scroll over the checkboxes.
    const select = container.querySelector('thead th')!;
    expect(select.className).toContain('sticky');
    // The middle column stays where it is.
    expect(cellsOf(container, 'amount')[0].className).not.toContain('sticky');
  });

  /**
   * The second pinned column clears the first from its declared width. One
   * per side needs no width at all, which is the case pinning is for; a
   * stack of them is the case a config has to measure for itself.
   */
  it('stacks two pinned columns on their declared widths', () => {
    const { container } = render(
      <RecordTable
        selectable={false}
        table={tableController({
          columns: [
            {
              field: 'id',
              label: 'Order',
              kind: 'string',
              cell: 'string',
              sortable: false,
              pinned: 'left',
              width: 120,
            },
            {
              field: 'status',
              label: 'Status',
              kind: 'string',
              cell: 'string',
              sortable: false,
              pinned: 'left',
            },
          ],
          rows: [{ key: 'o-1', data: { id: 'o-1', status: 'CN' } }],
        })}
      />,
    );

    // Nothing to the left of the first; the second starts where it ends.
    expect(cellsOf(container, 'id')[0].style.left).toBe(
      'var(--fve-pin-left-0, 0px)',
    );
    expect(cellsOf(container, 'status')[0].style.left).toBe(
      'var(--fve-pin-left-1, calc(120px))',
    );
  });

  /**
   * What the class asks for is not what the browser gives: a table lays out
   * by content, so the selection column is as wide as the widest thing in it
   * — the scope label — and a column pinned beside it by the class's own
   * `2.5rem` lands on top of the checkboxes. The header is measured after
   * every layout and the offsets go onto the table as it finds them; the
   * config's arithmetic is only the fallback until then.
   */
  it('clears the columns before it by what the header actually measures', () => {
    const rendered: Record<string, number> = {
      select: 77,
      id: 100,
      actions: 48,
    };
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(
      function (this: Element) {
        const cell = this as HTMLElement;
        const key = cell.dataset.column ?? cell.dataset.field ?? '';
        return { width: rendered[key] ?? 0 } as DOMRect;
      },
    );

    const { container } = render(
      <RecordTable
        table={tableController({
          columns: [
            {
              field: 'id',
              label: 'Order',
              kind: 'string',
              cell: 'string',
              sortable: false,
              pinned: 'left',
              // Declared narrower than it renders: the measurement wins.
              width: 60,
            },
            {
              field: 'status',
              label: 'Status',
              kind: 'string',
              cell: 'string',
              sortable: false,
              pinned: 'left',
            },
            {
              field: 'amount',
              label: 'Amount',
              kind: 'number',
              cell: 'number',
              sortable: false,
              pinned: 'right',
            },
          ],
          rows: [{ key: 'o-1', data: { id: 'o-1', status: 'CN', amount: 1 } }],
        })}
        rowActions={() => <button />}
      />,
    );

    const table = container.querySelector('table')!;
    // The first pinned column clears the selection column as rendered, not
    // as `w-10` asks; the second clears both.
    expect(table.style.getPropertyValue('--fve-pin-left-0')).toBe('77px');
    expect(table.style.getPropertyValue('--fve-pin-left-1')).toBe('177px');
    // And the right-hand side clears the host's buttons by their own width
    // rather than by the variable that stands in for them.
    expect(table.style.getPropertyValue('--fve-pin-right-2')).toBe('48px');
    // Every cell of the column reads the same offset, header to footer.
    for (const cell of cellsOf(container, 'status'))
      expect(cell.style.left).toBe(
        'var(--fve-pin-left-1, calc(2.5rem + 60px))',
      );
  });

  /**
   * A column can change width while the table's own box does not — a web
   * font finishing, a row-action button growing — and offsets published from
   * the last layout would then hold the pinned columns over their
   * neighbours. What is watched is therefore the cells the offsets are added
   * up from, not the table.
   */
  it('follows a header cell that resizes while the table does not', () => {
    const rendered: Record<string, number> = { select: 77, id: 100 };
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(
      function (this: Element) {
        const cell = this as HTMLElement;
        const key = cell.dataset.column ?? cell.dataset.field ?? '';
        return { width: rendered[key] ?? 0 } as DOMRect;
      },
    );
    const observers: ResizeSpy[] = [];
    vi.stubGlobal(
      'ResizeObserver',
      class extends ResizeSpy {
        constructor(callback: ResizeObserverCallback) {
          super(callback);
          observers.push(this);
        }
      },
    );

    const { container } = render(<RecordTable table={pinned()} />);
    const table = container.querySelector('table')!;
    expect(table.style.getPropertyValue('--fve-pin-left-0')).toBe('77px');

    // The header cells that feed the offsets, and nothing else: the table
    // itself can sit still through all of this.
    const latest = observers[observers.length - 1];
    const watching: Element[] = latest.observed;
    expect(watching.every((node: Element) => node.tagName === 'TH')).toBe(true);
    expect(watching).toContain(
      container.querySelector('thead th[data-column="select"]'),
    );
    expect(watching).not.toContain(table);

    // The scope label makes the selection column wider without the table
    // moving; the pinned column follows it rather than sitting on it.
    rendered.select = 120;
    act(() => latest.resize());
    expect(table.style.getPropertyValue('--fve-pin-left-0')).toBe('120px');
    vi.unstubAllGlobals();
  });

  it('pins nothing but the actions when no column asked for it', () => {
    const { container } = render(<RecordTable table={tableController()} />);
    expect(container.querySelector('thead th')!.className).not.toContain(
      'sticky',
    );
  });
});

/** The summary rows, by the scope each one carries. */
function scopes(footer: HTMLElement): string[] {
  return [...footer.querySelectorAll('tr')].map(
    row => row.getAttribute('data-scope') ?? '',
  );
}

function summaryRow(footer: HTMLElement, scope: string): HTMLTableRowElement {
  const row = footer.querySelector<HTMLTableRowElement>(
    `tr[data-scope="${scope}"]`,
  );
  if (!row) throw new Error(`no ${scope} summary row`);
  return row;
}

function header(container: HTMLElement, field: string): HTMLElement {
  const found = container.querySelector<HTMLElement>(
    `thead [data-field="${field}"]`,
  );
  if (!found) throw new Error(`no header for ${field}`);
  return found;
}

function position(container: HTMLElement, field: string): string | undefined {
  return header(container, field).querySelector('[data-slot="sort-position"]')
    ?.textContent as string | undefined;
}

/** Every cell of one column, in the header and in the body. */
function cellsOf(container: HTMLElement, field: string): HTMLElement[] {
  const index = [...container.querySelectorAll('thead th')].findIndex(
    cell => cell.getAttribute('data-field') === field,
  );
  return [...container.querySelectorAll('thead tr, tbody tr')].map(
    row => (row as HTMLTableRowElement).cells[index] as HTMLElement,
  );
}
