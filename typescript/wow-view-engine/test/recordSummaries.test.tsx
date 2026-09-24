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
  act,
  cleanup,
  render,
  renderHook,
  waitFor,
} from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type {
  DashboardRuntime,
  ViewInstance,
  ViewSource,
} from '../src/index.js';
import { cursorPaging, pagedPaging } from '../src/record/index.js';
import {
  useDashboard,
  type RecordTableController,
} from '../src/react/index.js';
import {
  defaultMessages,
  DashboardGrid,
  RecordCards,
  RecordTable,
  DataWorkbench,
  ViewSurface,
} from '../src/ui/index.js';
import {
  INSTANT,
  ROWS,
  ZONE,
  dashboardConfig,
  inZone,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { panel, pending } from './fixtures/dashboard.js';
import { mine, twoColumnTable } from './fixtures/ui.js';

afterEach(cleanup);

/** Forty-two records at twenty a page: the page is a part of the result. */
const MANY_PAGES = pagedPaging({ index: 1, size: 20, total: 42 });

/** The fixture's two rows, from a source that says there are forty-two. */
const MORE_THAN_A_PAGE: Partial<ViewSource> = {
  paged: () => Promise.resolve({ total: 42, list: [...ROWS] }),
};

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

describe('the summary row', () => {
  const withSummary: ViewInstance = {
    ...mine,
    config: recordConfig({ summaries: [{ field: 'amount', fn: 'SUM' }] }),
  };

  function setupSummary(source: ViewSource = testSource(MORE_THAN_A_PAGE)) {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [withSummary] }),
      resolveSource: () => source,
    });
    return render(
      <DataWorkbench
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
      <DataWorkbench
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
    expect(footer.textContent).not.toContain('All rows');
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
    twoColumnTable({
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
      // More than one page, so the page is a part of the result and the two
      // scopes say different things (D26 Q40 is the single-page case below).
      paging: MANY_PAGES,
      ...overrides,
    });

  it('renders no footer at all when the config asked for no summaries', () => {
    const { container } = render(<RecordTable table={twoColumnTable()} />);
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
    expect(footer.textContent).toContain('All rows');
  });

  /**
   * Two rows, even where the two numbers agree. The scope is part of the
   * number rather than a note beside it: twenty rows' average presented as
   * forty thousand rows' average is the one mistake these rows could make,
   * and collapsing them whenever they happen to match is how that mistake
   * gets made on the page where it matters.
   */
  it('keeps both scopes apart even when they say the same thing', () => {
    const { container } = render(
      <RecordTable
        table={summaries({
          summaries: {
            scope: 'total',
            cells: [
              { field: 'amount', label: 'Amount', fn: 'COUNT', value: 2 },
            ],
          },
        })}
      />,
    );

    const footer = container.querySelector('tfoot')!;
    expect(scopes(footer)).toEqual(['page', 'total']);
    expect(summaryRow(footer, 'page').textContent).toContain('2');
    expect(summaryRow(footer, 'total').textContent).toContain('2');
  });

  /**
   * A date column's earliest and latest, in the words and the format of the
   * column they stand under.
   *
   * Two things are being kept here. The value goes through the same reading
   * a cell of that column goes through — the surface's language and zone —
   * because a footer showing epoch milliseconds under a column of dates is
   * the raw-value bug the cells were fixed for. And the function is named in
   * the vocabulary of what it summarises: the earliest of a moment, not its
   * smallest.
   */
  it('reads a date summary the way the column reads its cells', () => {
    const { container } = render(
      <ViewSurface locale="en-GB" timeZone={ZONE}>
        <RecordTable
          table={twoColumnTable({
            columns: [
              {
                field: 'createdAt',
                label: 'Created',
                kind: 'datetime',
                cell: 'datetime',
                sortable: false,
              },
            ],
            rows: [{ key: 'o-1', data: { createdAt: INSTANT } }],
            paging: MANY_PAGES,
            summaries: {
              scope: 'total',
              cells: [
                {
                  field: 'createdAt',
                  label: 'Created',
                  fn: 'MIN',
                  value: INSTANT,
                  cell: 'datetime',
                },
              ],
            },
          })}
        />
      </ViewSurface>,
    );

    const footer = container.querySelector('tfoot')!;
    // Both scopes: the aggregation's own instant, and the rows on screen
    // reduced to the one instant they hold.
    for (const scope of ['page', 'total'] as const) {
      const row = summaryRow(footer, scope);
      expect(row.textContent).toContain(inZone(INSTANT));
      expect(row.textContent).toContain(
        defaultMessages['label.summary.fn.date.MIN'],
      );
      expect(row.textContent).not.toContain(String(INSTANT));
      expect(row.textContent).not.toContain(
        defaultMessages['label.summary.fn.MIN'],
      );
    }
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
    // One cell per column plus the filler, with the label inside the first.
    expect(row.cells).toHaveLength(3);
    expect(row.cells[2].dataset.column).toBe('filler');
    expect(row.cells[0].textContent).toContain('This page');
    expect(row.cells[0].textContent).toContain('CN¥10.00');
  });

  it('keeps the footer as wide as the rows when there is an action column', () => {
    const { container } = render(
      <RecordTable table={summaries()} rowActions={() => <button />} />,
    );

    const row = summaryRow(container.querySelector('tfoot')!, 'page');
    // Selection, two columns, actions, and the filler after them all.
    expect(row.cells).toHaveLength(5);
  });
});

/**
 * One page, one row (D26 Q40, the exception to D18 V's two rows).
 *
 * When the page holds every record the conditions match, "this page" and
 * "all" are the same rows, so the page row would repeat the totals under
 * another name. The totals row is the one kept: it stays true when the
 * result grows past a page. The rule is the paging's, not the numbers' — two
 * pages whose sums happen to agree still show both (above).
 */
describe('the summary rows on a single page', () => {
  const summed = (
    overrides: Partial<RecordTableController> = {},
  ): RecordTableController =>
    twoColumnTable({
      summaries: {
        scope: 'total',
        cells: [{ field: 'amount', label: 'Amount', fn: 'COUNT', value: 2 }],
      },
      ...overrides,
    });

  it('shows only the total when the page holds every record', () => {
    // The shared table: two rows, twenty a page, a total of two.
    const { container } = render(<RecordTable table={summed()} />);

    const footer = container.querySelector('tfoot')!;
    expect(scopes(footer)).toEqual(['total']);
    expect(footer.textContent).toContain('All rows');
    expect(footer.textContent).not.toContain('This page');
  });

  it('says the same under the cards', () => {
    const { container } = render(
      <RecordCards table={summed({ layout: 'card' })} />,
    );

    const scoped = [...container.querySelectorAll('[data-scope]')].map(node =>
      node.getAttribute('data-scope'),
    );
    expect(scoped).toEqual(['total']);
  });

  it('keeps both where the page cannot know it is the only one', () => {
    // A cursor result has no total, and a first page with no next cursor
    // looks the same to the footer as the first of many; a paged result
    // without a total is in the same place.
    for (const paging of [
      cursorPaging(null),
      pagedPaging({ index: 1, size: 20 }),
    ]) {
      const { container, unmount } = render(
        <RecordTable table={summed({ paging })} />,
      );
      expect(scopes(container.querySelector('tfoot')!)).toEqual([
        'page',
        'total',
      ]);
      unmount();
    }
  });

  it('keeps the page row when the totals query failed, one page or not', () => {
    const { container } = render(
      <RecordTable
        table={summed({
          summaries: {
            scope: 'page',
            cells: [
              { field: 'amount', label: 'Amount', fn: 'COUNT', value: 2 },
            ],
          },
        })}
      />,
    );

    // The rows on screen are all of them here, but the runtime said the
    // number is the page's, and the label follows what was computed.
    expect(scopes(container.querySelector('tfoot')!)).toEqual(['page']);
  });

  it('draws the one row in a dashboard panel as in the workbench', async () => {
    const summarised: ViewInstance = {
      ...pending,
      config: recordConfig({ summaries: [{ field: 'amount', fn: 'SUM' }] }),
    };
    const store = new MemoryViewStore({ instances: [summarised] });
    const engine = new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store,
      resolveSource: () => testSource(),
      environment: testEnvironment().environment,
    });
    const board = await store.create(
      {
        definitionId: 'overview',
        title: 'Overview',
        scope: 'personal',
        config: dashboardConfig({ panels: [panel()] }),
      },
      { requestId: 'r' },
    );
    const runtime = (await engine.open(board.id)) as DashboardRuntime;
    const view = renderHook(() => useDashboard(runtime));
    await act(async () => {
      await Promise.resolve();
    });

    const { container } = render(
      <ViewSurface>
        <DashboardGrid dashboard={view.result.current} />
      </ViewSurface>,
    );

    const footer = await waitFor(() => {
      const found = container.querySelector('tfoot');
      expect(found).not.toBeNull();
      return found as HTMLElement;
    });
    // The fixture's two orders are one page of the panel's view.
    expect(scopes(footer)).toEqual(['total']);
    expect(footer.textContent).toContain('30');
  });
});
