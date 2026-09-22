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

import { cleanup, render, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type { ViewInstance, ViewSource } from '../src/index.js';
import type { RecordTableController } from '../src/react/index.js';
import { RecordTable, RecordWorkbench } from '../src/ui/index.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';
import { mine, twoColumnTable } from './fixtures/ui.js';

afterEach(cleanup);

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
    expect(footer.textContent).toContain('All records');
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
