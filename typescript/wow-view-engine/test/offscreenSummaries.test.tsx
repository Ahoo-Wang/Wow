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
  fireEvent,
  render,
  screen,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  pagedPaging,
  type RecordColumnView,
  type SummaryRow,
} from '../src/record/index.js';
import { RecordTable, ViewSurface, zhCN } from '../src/ui/index.js';
import {
  hintHost,
  offscreenHint,
  SELECT_HOST,
  type OffscreenColumns,
} from '../src/ui/record/offscreenSummaries.js';
import { revealCell } from '../src/ui/record/useOffscreenColumns.js';
import type { TablePins } from '../src/ui/record/columns.js';
import type { StickyPin } from '../src/ui/record/sticky.js';
import { twoColumnTable } from './fixtures/ui.js';

/**
 * D51: a summary row names the summarised columns it holds out of view, as a
 * button that scrolls to the first of them. The pure half decides what the
 * row says and where; the observer half is the browser's, measured in the
 * Storybook play `OffscreenTotals`.
 */

const column = (
  field: string,
  pinned?: RecordColumnView['pinned'],
): RecordColumnView => ({
  field,
  label: field.toUpperCase(),
  kind: 'number',
  cell: 'number',
  sortable: false,
  ...(pinned ? { pinned } : {}),
});

const COLUMNS = ['key', 'a', 'b', 'c', 'd'].map(field => column(field));

const row = (...fields: string[]): SummaryRow => ({
  scope: 'total',
  cells: fields.map(field => ({
    field,
    label: field.toUpperCase(),
    fn: 'SUM',
    value: 1,
  })),
});

const hidden = (...entries: [string, 'left' | 'right'][]): OffscreenColumns =>
  new Map(entries);

describe('what a summary row says about the columns out of view', () => {
  it('says nothing while every summarised column is in view', () => {
    expect(offscreenHint(row('b', 'd'), COLUMNS, hidden())).toBeNull();
  });

  it('names the one hidden column and the side it lies on', () => {
    const hint = offscreenHint(row('b', 'd'), COLUMNS, hidden(['d', 'right']));
    expect(hint).toMatchObject({ side: 'right', count: 1 });
    expect(hint?.column.field).toBe('d');
    expect(hint?.cell.field).toBe('d');
  });

  it('names the first hidden on the right and counts them all', () => {
    const hint = offscreenHint(
      row('a', 'b', 'd'),
      COLUMNS,
      hidden(['a', 'left'], ['d', 'right'], ['b', 'right']),
    );
    expect(hint?.column.field).toBe('b');
    expect(hint?.count).toBe(3);
  });

  it('names the nearest on the left when nothing is hidden on the right', () => {
    const hint = offscreenHint(
      row('a', 'b', 'd'),
      COLUMNS,
      hidden(['a', 'left'], ['b', 'left']),
    );
    expect(hint).toMatchObject({ side: 'left', count: 2 });
    expect(hint?.column.field).toBe('b');
  });

  it('counts only the columns this row summarises', () => {
    const hint = offscreenHint(
      row('d'),
      COLUMNS,
      hidden(['c', 'right'], ['d', 'right']),
    );
    expect(hint?.column.field).toBe('d');
    expect(hint?.count).toBe(1);
  });
});

describe('the cell that carries the hint', () => {
  const left = (edge = false): StickyPin => ({ side: 'left', edge });
  const pins = (
    columns: [string, StickyPin][] = [],
    select?: StickyPin,
  ): TablePins => ({
    columns: new Map(columns),
    ...(select?.side === 'left' ? { select } : {}),
  });

  it('takes the first pinned column with no summary, beside the selection column', () => {
    const host = hintHost(
      COLUMNS,
      true,
      pins([['key', left(true)]], left()),
      new Set(['d']),
    );
    expect(host).toEqual({ at: 'key', withScope: false });
  });

  it('keeps to the narrow selection cell, held for the footer, when nothing is pinned', () => {
    expect(hintHost(COLUMNS, true, pins(), new Set(['d']))).toEqual({
      at: SELECT_HOST,
      withScope: true,
      pin: { side: 'left', edge: true },
    });
  });

  it('keeps to the selection cell when every pinned column summarises', () => {
    expect(
      hintHost(
        COLUMNS,
        true,
        pins([['key', left(true)]], left()),
        new Set(['key']),
      ),
    ).toEqual({ at: SELECT_HOST, withScope: true, pin: left() });
  });

  it('shares the first column with the scope when rows cannot be picked', () => {
    expect(
      hintHost(COLUMNS, false, pins([['key', left(true)]]), new Set(['d'])),
    ).toEqual({ at: 'key', withScope: true });
    // Nothing pinned: the first column is held for the footer alone.
    expect(hintHost(COLUMNS, false, pins(), new Set(['d']))).toEqual({
      at: 'key',
      withScope: true,
      pin: { side: 'left', edge: true },
    });
  });

  it('lets a first column that summarises scroll with its numbers', () => {
    expect(hintHost(COLUMNS, false, pins(), new Set(['key']))).toEqual({
      at: 'key',
      withScope: true,
    });
  });
});

/** The one observer the footer builds, driven by hand. */
class ObserverDouble {
  static latest: ObserverDouble | null = null;
  readonly targets: Element[] = [];
  constructor(
    readonly callback: IntersectionObserverCallback,
    readonly options?: IntersectionObserverInit,
  ) {
    ObserverDouble.latest = this;
  }
  observe(target: Element): void {
    this.targets.push(target);
  }
  unobserve(): void {}
  disconnect(): void {}
  takeRecords(): IntersectionObserverEntry[] {
    return [];
  }
  /** Say where each field's readings are: in view, or off to one side. */
  report(where: Record<string, 'in' | 'left' | 'right'>): void {
    const entries = this.targets.map(target => {
      const at = where[target.getAttribute('data-summary-field') ?? ''];
      const x = at === 'left' ? -500 : at === 'right' ? 900 : 100;
      return {
        target,
        isIntersecting: at === 'in',
        intersectionRatio: at === 'in' ? 1 : 0,
        boundingClientRect: { left: x, width: 80 },
        rootBounds: { left: 0, width: 400 },
      } as unknown as IntersectionObserverEntry;
    });
    act(() => this.callback(entries, this as unknown as IntersectionObserver));
  }
}

describe('the hint in a record table', () => {
  const scrollIntoView = vi.fn();
  beforeEach(() => {
    ObserverDouble.latest = null;
    vi.stubGlobal('IntersectionObserver', ObserverDouble);
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: scrollIntoView,
    });
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView;
  });

  const table = () =>
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
      paging: pagedPaging({ index: 1, size: 20, total: 42 }),
    });

  /** What the button shows: its words less the part only a reader hears. */
  const shown = (button: HTMLElement) => {
    const words = button.querySelector('.truncate')!.cloneNode(true) as Element;
    words.querySelector('.sr-only')?.remove();
    return words.textContent;
  };

  /** The button a query by its whole accessible name finds. */
  const named = (name: string) => screen.getByRole('button', { name });

  const buttons = (container: HTMLElement) => [
    ...container.querySelectorAll<HTMLButtonElement>(
      '[data-slot="summary-offscreen"]',
    ),
  ];

  it('watches only the readings under the columns, stretched without end vertically', () => {
    render(<RecordTable table={table()} />);
    const observer = ObserverDouble.latest!;
    // One reading per row: this page and all rows.
    expect(
      observer.targets.map(target => target.getAttribute('data-summary-field')),
    ).toEqual(['amount', 'amount']);
    expect(observer.options?.rootMargin).toMatch(/^100000px .* 100000px /);
  });

  /**
   * A summary in a held column is in view however the table is scrolled,
   * and it sits inside the inset the held columns take off the root — asked
   * about, it would read as hidden in a table that does not even scroll.
   */
  it('does not ask about a summary in a held column', () => {
    const base = twoColumnTable();
    render(
      <RecordTable
        table={twoColumnTable({
          columns: [base.columns[0], { ...base.columns[1], pinned: 'right' }],
          summaries: {
            scope: 'total',
            cells: [
              { field: 'amount', label: 'Amount', fn: 'SUM', value: 900 },
              { field: 'warehouse', label: 'Warehouse', fn: 'COUNT', value: 2 },
            ],
          },
        })}
      />,
    );
    expect(
      ObserverDouble.latest!.targets.map(target =>
        target.getAttribute('data-summary-field'),
      ),
    ).toEqual(['amount']);
  });

  it('names the hidden column in each row, and goes when it is in view', () => {
    const { container } = render(<RecordTable table={table()} />);
    expect(buttons(container)).toEqual([]);

    ObserverDouble.latest!.report({ amount: 'right' });
    const [page, total] = buttons(container);
    // Each row its own number, the scope inside the narrow selection cell;
    // the name starts with the words the button shows (WCAG 2.5.3).
    expect(
      named('This page · Amount Sum CN¥10.00, out of view. Scroll to Amount'),
    ).toBe(page);
    expect(
      named('All rows · Amount Sum CN¥900.00, out of view. Scroll to Amount'),
    ).toBe(total);
    expect(shown(total)).toBe('All rows · Amount Sum CN¥900.00');
    expect(total.dataset.side).toBe('right');
    // Held against the left edge for the footer alone.
    expect(total.closest('td')?.dataset.pin).toBe('left');

    ObserverDouble.latest!.report({ amount: 'in' });
    expect(buttons(container)).toEqual([]);
    // The scope is back on its own.
    expect(
      container.querySelector('tr[data-scope="total"] td')?.textContent,
    ).toBe('All rows');
  });

  it('scrolls to the column and hands focus to its summary', () => {
    const { container } = render(<RecordTable table={table()} />);
    ObserverDouble.latest!.report({ amount: 'right' });

    fireEvent.click(buttons(container)[1]);
    const cell = container
      .querySelector('tr[data-scope="total"] [data-summary-field="amount"]')!
      .closest('td')!;
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView.mock.contexts[0]).toBe(cell);
    expect(scrollIntoView).toHaveBeenCalledWith({
      inline: 'nearest',
      block: 'nearest',
    });
    expect(document.activeElement).toBe(cell);
  });

  it('says in Chinese how many more there are', () => {
    const { container } = render(
      <ViewSurface messages={zhCN}>
        <RecordTable
          table={twoColumnTable({
            summaries: {
              scope: 'total',
              cells: [
                { field: 'amount', label: 'Amount', fn: 'SUM', value: 900 },
                { field: 'warehouse', label: '仓库', fn: 'COUNT', value: 2 },
              ],
            },
          })}
        />
      </ViewSurface>,
    );
    ObserverDouble.latest!.report({ amount: 'left', warehouse: 'right' });
    const [total] = buttons(container);
    expect(shown(total)).toBe('全部 · Warehouse 计数 2 等 2 项');
    expect(
      named('全部 · Warehouse 计数 2 等 2 项，不在视野内，滚动到Warehouse'),
    ).toBe(total);
  });
});

describe('bringing a summarised column into view', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  /** A port 400px wide at 0, holding 60px on the left and 50px on the right. */
  function port(at: { left: number; right: number }) {
    document.body.innerHTML = `
      <div id="root" style="overflow-x: auto">
        <table><tfoot><tr>
          <td data-pin="left"></td><td id="target"></td><td data-pin="right"></td>
        </tr></tfoot></table>
      </div>`;
    const box = (left: number, right: number) =>
      ({ left, right, width: right - left }) as DOMRect;
    const [held, target, end] = [...document.querySelectorAll('td')];
    const root = document.getElementById('root')!;
    vi.spyOn(root, 'getBoundingClientRect').mockReturnValue(box(0, 400));
    vi.spyOn(held, 'getBoundingClientRect').mockReturnValue(box(0, 60));
    vi.spyOn(end, 'getBoundingClientRect').mockReturnValue(box(350, 400));
    vi.spyOn(target, 'getBoundingClientRect').mockReturnValue(
      box(at.left, at.right),
    );
    const scrollBy = vi.fn();
    root.scrollBy = scrollBy as typeof root.scrollBy;
    return { target, scrollBy };
  }

  it('scrolls a column past the end back in, just short of the held one', () => {
    const { target, scrollBy } = port({ left: 500, right: 600 });
    revealCell(target);
    expect(scrollBy).toHaveBeenCalledWith({ left: 250 });
  });

  it('scrolls a column under the held ones out from under them', () => {
    const { target, scrollBy } = port({ left: 20, right: 120 });
    revealCell(target);
    expect(scrollBy).toHaveBeenCalledWith({ left: -40 });
  });

  it('leaves a column already in view where it is', () => {
    const { target, scrollBy } = port({ left: 100, right: 200 });
    revealCell(target);
    expect(scrollBy).not.toHaveBeenCalled();
  });
});
