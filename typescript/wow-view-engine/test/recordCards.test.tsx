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

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { defaultMessages, RecordCards } from '../src/ui/index.js';
import { ViewSurface } from '../src/ui/ViewSurface.js';
import { INSTANT, ZONE, inZone } from './fixtures.js';
import { recordTableController, twoColumnTable } from './fixtures/ui.js';

afterEach(cleanup);

/** Cards over one settled page of two rows, the amount as title and body. */
function cards(
  overrides: Parameters<typeof recordTableController>[0] = {},
  props: Partial<Parameters<typeof RecordCards>[0]> = {},
) {
  const table = recordTableController({
    card: {
      title: 'amount',
      titleField: { field: 'amount', label: 'Amount', kind: 'number' },
      fields: [{ field: 'amount', label: 'Amount', kind: 'number' }],
    },
    ...overrides,
  });
  render(
    <ViewSurface>
      <RecordCards table={table} {...props} />
    </ViewSurface>,
  );
  return table;
}

/**
 * The cards answer the four states the table answers (D18 V): nothing
 * before a first result, skeletons while the first is on its way, the empty
 * result when nothing matched, and the summaries whenever the table would
 * have drawn its footer. Before this the card layout was a grid of nothing
 * for every one of them.
 */
describe('RecordCards outside the rows', () => {
  it('draws nothing before a result and nothing is running', () => {
    cards({ rows: [], hasResult: false, status: 'idle' });
    expect(document.querySelector('[data-slot="record-cards"]')).toBeNull();
    expect(
      document.querySelector('[data-slot="record-cards-skeleton"]'),
    ).toBeNull();
  });

  it('draws skeleton cards while the first result is on its way', () => {
    cards({ rows: [], hasResult: false, status: 'loading', loading: true });
    expect(
      document.querySelector('[data-slot="record-cards-skeleton"]'),
    ).not.toBeNull();
    expect(document.querySelector('[data-slot="record-cards"]')).toBeNull();
  });

  it('says a result matched nothing, with the way out', () => {
    const onEmptyAction = vi.fn();
    cards({ rows: [] }, { hasConditions: true, onEmptyAction });
    expect(screen.getByText('Nothing to show')).toBeDefined();
    fireEvent.click(
      screen.getByRole('button', { name: 'Clear the conditions' }),
    );
    expect(onEmptyAction).toHaveBeenCalledTimes(1);
  });

  it('keeps the summaries under the cards, both scopes', () => {
    cards({
      summaries: {
        scope: 'total',
        cells: [{ field: 'amount', label: 'Amount', fn: 'SUM', value: 42 }],
      },
    });
    const summaries = document.querySelector(
      '[data-slot="record-summaries"][data-layout="card"]',
    );
    expect(summaries).not.toBeNull();
    expect(summaries?.querySelectorAll('[data-scope]')).toHaveLength(2);
    // The page row is added up from the rows on screen; the total is the
    // query's own answer.
    const values = [
      ...(summaries?.querySelectorAll('[data-slot="summary-value"]') ?? []),
    ].map(node => node.textContent);
    expect(values).toEqual(['3', '42']);
  });

  /**
   * A card has no column over the number, so the lines say the field
   * themselves — and a date says it as a date. The cards and the table draw
   * one `SummaryValue`, which is what makes that true in both places at
   * once, and switching layout must not turn the earliest order into
   * thirteen digits.
   */
  it('reads a date summary as a date under the cards too', () => {
    const table = recordTableController({
      card: {
        title: 'amount',
        titleField: { field: 'amount', label: 'Amount', kind: 'number' },
        fields: [{ field: 'amount', label: 'Amount', kind: 'number' }],
      },
      rows: [{ key: 'o-1', data: { amount: 1, createdAt: INSTANT } }],
      summaries: {
        scope: 'total',
        cells: [
          {
            field: 'createdAt',
            label: 'Created',
            fn: 'MAX',
            value: INSTANT,
            cell: 'datetime',
          },
        ],
      },
    });
    render(
      <ViewSurface locale="en-GB" timeZone={ZONE}>
        <RecordCards table={table} />
      </ViewSurface>,
    );

    const summaries = document.querySelector<HTMLElement>(
      '[data-slot="record-summaries"][data-layout="card"]',
    );
    expect(
      [
        ...(summaries?.querySelectorAll('[data-slot="summary-value"]') ?? []),
      ].map(node => node.textContent),
    ).toEqual([inZone(INSTANT), inZone(INSTANT)]);
    expect(summaries?.textContent).toContain(
      defaultMessages['label.summary.fn.date.MAX'],
    );
  });
});

describe('RecordCards read a value as the table does', () => {
  it("hands a host's renderer the same cell the table would", () => {
    const seen: string[] = [];
    cards(
      {},
      {
        renderCell: cell => {
          seen.push(
            `${cell.column.field}:${String(cell.value)}:${String(cell.column.sortable)}`,
          );
          return <em>{String(cell.value)}</em>;
        },
      },
    );
    // The title and the body field both go through it, with the column in
    // the table's shape — `sortable` said, so one renderer serves both.
    expect(seen).toContain('amount:1:false');
    expect(screen.getAllByText('1').length).toBeGreaterThan(0);
  });

  it('titles a card through the same reading as its column', () => {
    cards({
      card: {
        title: 'amount',
        titleField: {
          field: 'amount',
          label: 'Amount',
          kind: 'number',
          numberFormat: { style: 'currency', currency: 'CNY' },
        },
        fields: [],
      },
    });
    // A number formats as its field says rather than as a bare digit.
    expect(screen.getByText(/1\.00/)).toBeDefined();
  });

  it('falls back to the key when the title field is gone', () => {
    cards({ card: { title: 'removed', fields: [] } });
    expect(screen.getByText('o-1')).toBeDefined();
  });

  /**
   * The field is still declared, but this row holds nothing under it — which
   * every reading answers with nothing at all, title included. A card with no
   * name is a card nobody can refer to, so the key stands in for it, exactly
   * as it does when the field itself is gone.
   */
  it('falls back to the key when the title field has no value here', () => {
    cards({
      card: {
        title: 'amount',
        titleField: { field: 'absent', label: 'Absent', kind: 'string' },
        fields: [],
      },
    });

    expect(screen.getByText('o-1')).toBeDefined();
  });

  /**
   * A controller built by hand declares no kind, and the value is then read
   * by what it is: a boolean says yes in the catalogue's words rather than
   * `true` in JavaScript's.
   */
  it('reads a field with no declared kind by the value it holds', () => {
    cards({
      rows: [{ key: 'o-1', data: { shipped: true, count: 2, note: 'late' } }],
      card: {
        title: 'removed',
        fields: [
          { field: 'shipped', label: 'Shipped' },
          { field: 'count', label: 'Count' },
          { field: 'note', label: 'Note' },
        ],
      },
    });

    expect(screen.getByText('Yes')).toBeDefined();
    expect(screen.getByText('2')).toBeDefined();
    expect(screen.getByText('late')).toBeDefined();
  });

  /** An image field the row has nothing under draws no broken frame. */
  it('draws no image where the row holds none', () => {
    cards({
      rows: [{ key: 'o-1', data: { amount: 1 } }],
      card: { title: 'amount', image: 'photo', fields: [] },
    });

    expect(document.querySelector('img')).toBeNull();
  });
});

describe('RecordCards are picked as rows are', () => {
  it('names each checkbox after the card it picks, and toggles that one', () => {
    const toggle = vi.fn();
    cards({ toggle, isSelected: key => key === 'o-2' });

    const boxes = screen.getAllByRole('checkbox');
    expect(boxes.map(box => box.getAttribute('aria-label'))).toEqual([
      'Select o-1',
      'Select o-2',
    ]);
    // The second card is the picked one, and says so.
    expect(boxes[1].getAttribute('aria-checked')).toBe('true');

    fireEvent.click(boxes[0]);
    expect(toggle).toHaveBeenCalledWith('o-1');
  });

  it('offers no checkbox at all where cards cannot be picked', () => {
    cards({}, { selectable: false });

    expect(screen.queryByRole('checkbox')).toBeNull();
  });
});

describe('RecordCards on its own', () => {
  /**
   * A card is a row folded out, so its body is the same `Item` recipe the
   * lists are drawn with (decisions.md D16-3) — and a list of readings is a
   * list, which the registry's `ItemGroup` says but its `Item` does not:
   * the rows are `div`s, so each one has to claim `listitem` itself or the
   * group announces a list with nothing in it.
   */
  it('draws the card body as a list of readings', () => {
    const { container } = render(
      <RecordCards
        table={twoColumnTable({
          card: {
            title: 'warehouse',
            fields: [
              { field: 'amount', label: 'Total' },
              { field: 'status', label: 'Status' },
            ],
          },
          rows: [{ key: 'o-1', data: { warehouse: 'CN', amount: 10 } }],
        })}
      />,
    );

    const rows = [
      ...container.querySelectorAll<HTMLElement>('[data-slot="card-field"]'),
    ];
    expect(rows.map(row => row.dataset.field)).toEqual(['amount', 'status']);
    for (const row of rows) {
      expect(row.dataset.variant).toBe('default');
      expect(row.getAttribute('role')).toBe('listitem');
      expect(row.parentElement?.getAttribute('role')).toBe('list');
      // The field's name is the quiet half and the value is the loud one,
      // which is what `Item` means by description and title.
      expect(row.querySelector('[data-slot="item-description"]')).toBeTruthy();
      expect(row.querySelector('[data-slot="item-title"]')).toBeTruthy();
      // And the name reads a rung below the value: `TEXT_UI` over the
      // registry's `text-sm`, asked for with `RowItem`'s `description`
      // variant so that no typography lands on the vendored component.
      // The class rather than the size, because jsdom hangs no stylesheet;
      // the pixels are measured in the browser.
      // A **surviving class assertion**: the card's own grid template.
      expect(row.className).toContain(
        '[&_[data-slot=item-description]]:text-[length:var(--text-ui)]',
      );
    }
  });

  it('reads a nested title field by its path', () => {
    render(
      <RecordCards
        table={twoColumnTable({
          card: { title: 'customer.name', fields: [] },
          rows: [{ key: 'o-1', data: { customer: { name: 'Acme' } } }],
        })}
      />,
    );
    expect(screen.getByText('Acme')).toBeDefined();
  });

  it('titles a card by the field the card spec names', () => {
    render(<RecordCards table={twoColumnTable()} />);

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
          table={twoColumnTable({
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
          table={twoColumnTable({
            card: {
              title: 'id',
              fields: [
                { field: 'createdAt', label: 'Created', kind: 'datetime' },
              ],
            },
            rows: [{ key: 'o-1', data: { id: 'o-1', createdAt: INSTANT } }],
          })}
          renderCell={cell => <em>raw {String(cell.value)}</em>}
        />
      </ViewSurface>,
    );

    expect(screen.getByText(`raw ${INSTANT}`)).toBeDefined();
    expect(screen.queryByText(inZone(INSTANT))).toBeNull();
  });

  it('falls back to the row key without a title field', () => {
    render(
      <RecordCards
        table={twoColumnTable({ card: { title: '', fields: [] } })}
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
        table={twoColumnTable({
          card: {
            title: 'warehouse',
            fields: [{ field: 'amount', label: 'Total' }],
            image: 'photo',
            perRow: 2,
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
