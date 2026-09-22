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

import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RecordColumnView } from '../src/index.js';
import type { RecordTableController } from '../src/react/index.js';
import { RecordTable } from '../src/ui/index.js';
import {
  ACTIONS_COLUMN,
  pinnedSlots,
  SELECT_COLUMN,
  tablePins,
} from '../src/ui/record/columns.js';

afterEach(cleanup);

/**
 * The edge of a pinned column, which is on at rest as well as while rows
 * pass under it (D13). jsdom computes no layout and applies no stylesheet,
 * so what is pinned here is the contract the browser then honours: which
 * cells carry the edge and which do not. The browser story `PinnedEdges`
 * reads the shadow itself, still and scrolled.
 */
describe('the pinned edges', () => {
  const LEFT_EDGE = 'shadow-[inset_-1px_0_0_var(--border)';
  const RIGHT_EDGE = 'shadow-[inset_1px_0_0_var(--border)';

  it('draws the edge on the boundary with the middle, and nowhere else', () => {
    const { container } = render(
      <RecordTable
        table={controller([
          column('id', 'left'),
          column('amount', 'left'),
          column('warehouse'),
          column('status', 'right'),
        ])}
      />,
    );
    // The last column pinned left and the one the table holds on the right
    // face the scrolling middle; the column before the last pinned one
    // faces another frozen column, and nothing ever passes between the two.
    for (const cell of cellsOf(container, 'amount'))
      expect(cell.className).toContain(LEFT_EDGE);
    for (const cell of cellsOf(container, 'id'))
      expect(cell.className).not.toContain(LEFT_EDGE);
    for (const cell of cellsOf(container, 'status'))
      expect(cell.className).toContain(RIGHT_EDGE);
    expect(cellsOf(container, 'warehouse')[0].className).not.toContain(
      'shadow-[inset',
    );
  });

  /**
   * The action column is the only column ever held on the right while the
   * host gives one (D19), so it carries that edge outright: the table's own
   * last column lets go rather than leaving a seam between two held columns
   * that nothing passes between.
   */
  it('gives the right edge to the actions, and the last column lets go', () => {
    const { container } = render(
      <RecordTable
        table={controller([
          column('id', 'left'),
          column('warehouse'),
          column('status', 'right'),
        ])}
        rowActions={() => <button />}
      />,
    );
    for (const cell of actionCells(container))
      expect(cell.className).toContain(RIGHT_EDGE);
    for (const cell of cellsOf(container, 'status')) {
      expect(cell.className).not.toContain('sticky');
      expect(cell.className).not.toContain(RIGHT_EDGE);
    }
  });

  /**
   * The rule D13 replaced. Tied to the scroll position, the edge said
   * "something is moving under me right now" — true, and of no use: a table
   * nobody had scrolled yet, or one that fits and so never scrolls at all,
   * showed no frame and its held ends read as a layout that had come apart.
   * Nothing is listened to now and nothing is written on the table.
   */
  it('wears its edge before anything has scrolled', () => {
    const { container } = render(
      <RecordTable
        table={controller([column('id', 'left'), column('warehouse')])}
      />,
    );

    const table = container.querySelector('table')!;
    for (const cell of cellsOf(container, 'id'))
      expect(cell.className).toContain(LEFT_EDGE);
    // Nothing on the table says where it is scrolled to, and no cell reads
    // such a thing through a group variant: the edge is not a fact about
    // scrolling any more.
    expect(table.hasAttribute('data-scrolled-left')).toBe(false);
    expect(table.hasAttribute('data-scrolled-right')).toBe(false);
    expect(table.className).not.toContain('group/table');
    expect(container.innerHTML).not.toContain('scrolled-');
  });

  /**
   * Header, rows and summaries read the same class, so a held column is
   * framed from top to bottom rather than in the rows alone.
   */
  /**
   * Preflight collapses table borders, and in collapsed mode Chromium paints
   * no outer box-shadow on a cell: the edge classes above were computed and
   * drew nothing. The table therefore separates its borders and puts the
   * hairline on the cells, where the row's own border no longer renders.
   */
  it('separates its borders so the edge can be painted, hairlines on the cells', () => {
    const { container } = render(
      <RecordTable table={controller([column('id', 'left')])} />,
    );
    const table = container.querySelector('table')!;
    expect(table.className).toContain('border-separate');
    expect(table.className).toContain('border-spacing-0');
    expect(table.className).toContain('[&_td]:border-b');
    expect(table.className).toContain('[&_th]:border-b');
    // The frame's pagination draws the line under the last summary row.
    expect(table.className).toContain('[&_tfoot_tr:last-child_td]:border-b-0');
  });

  /**
   * A pinned cell inherits its row's colour, so the row's hover has to be
   * opaque: the registry's `bg-muted/50` is a wash, and a wash over the
   * column a pinned cell is holding the place of shows that column's text
   * through it the moment the pointer arrives.
   */
  it('hovers its rows with an opaque shade, not a wash', () => {
    const { container } = render(
      <RecordTable table={controller([column('id', 'left')])} />,
    );
    const row = container.querySelector('tbody tr')!;
    expect(row.className).not.toContain('hover:bg-muted/50');
    // The mix is a theme token now (`--row-hover`), so a host can move it
    // and the class says which colour rather than how it was made.
    expect(row.className).toContain('hover:bg-row-hover');
    expect(row.className).toContain('has-aria-expanded:bg-row-hover');
    // And the pinned cell still follows the row.
    for (const cell of cellsOf(container, 'id'))
      expect(cell.className).toContain('bg-inherit');
  });

  it('puts the same edge on the header, the rows and the summaries', () => {
    const { container } = render(
      <RecordTable
        table={controller([column('id', 'left'), column('amount', 'right')])}
      />,
    );

    expect(cellsOf(container, 'id')).toHaveLength(3);
    for (const cell of cellsOf(container, 'id'))
      expect(cell.className).toContain(LEFT_EDGE);
    for (const cell of cellsOf(container, 'amount'))
      expect(cell.className).toContain(RIGHT_EDGE);
  });
});

/**
 * Where the surplus width goes (P-11).
 *
 * The registry's table is `w-full` and an auto layout hands the surplus to
 * the columns in proportion: in a 1300px result area the four-column fixture
 * drew `金额` 446px wide around `¥2,450.00`. A trailing cell asking for all
 * the width takes it instead, so the columns come out at what their own
 * content asks for while the row still reaches the frame.
 *
 * jsdom lays nothing out, so what is asserted here is the structure — which
 * rows carry the cell, that it is last, and that nothing is told about it;
 * the browser story `ColumnsKeepTheirWidthAndRowsFillTheFrame` measures the
 * widths themselves.
 */
describe('where the surplus width goes', () => {
  const fillers = (container: HTMLElement) => [
    ...container.querySelectorAll<HTMLElement>('[data-column="filler"]'),
  ];

  it('ends every row with a cell that takes what the columns did not', () => {
    const { container } = render(
      <RecordTable
        table={controller([column('id', 'left'), column('amount')])}
        rowActions={() => <button />}
      />,
    );

    // One in the header, one in the body row, one in each summary row.
    const rows = [...container.querySelectorAll('tr')];
    expect(rows.length).toBeGreaterThan(0);
    for (const row of rows) {
      const cells = [...(row as HTMLTableRowElement).cells];
      const last = cells[cells.length - 1];
      expect(last.dataset.column).toBe('filler');
      // Last, so nothing that counts cells by index has to step over it.
      expect(cells.filter(cell => cell.dataset.column === 'filler')).toEqual([
        last,
      ]);
    }
  });

  it('takes the width and adds nothing else to the row', () => {
    const { container } = render(
      <RecordTable
        table={controller([column('id', 'left'), column('amount')])}
      />,
    );

    for (const cell of fillers(container)) {
      expect(cell.className).toContain('w-full');
      // The registry pads every cell; this one holds nothing to pad.
      expect(cell.className).toContain('px-0');
      expect(cell.textContent).toBe('');
      // Never held against an edge, so it draws no edge either.
      expect(cell.dataset.pin).toBeUndefined();
      expect(cell.className).not.toContain('sticky');
    }
  });

  /**
   * Drawn, not announced: a column with no name and no values is not a
   * column, and this file's first-load header refuses a blank `<th>` for the
   * same reason. `getAllByRole` is the check that matters — it reads the
   * accessible grid, which is what a screen reader walks.
   */
  it('is not one more column to anybody reading the table', () => {
    const { container } = render(
      <RecordTable
        table={controller([column('id', 'left'), column('amount')])}
      />,
    );

    for (const cell of fillers(container))
      expect(cell.getAttribute('aria-hidden')).toBe('true');
    expect(
      screen.getAllByRole('columnheader').map(head => head.textContent),
    ).toHaveLength(3); // select, id, amount — and no fourth
  });

  /**
   * The cap weighs what is held against what the port can show. The filler
   * is neither: it *is* the width the columns did not need, so counting it
   * would be counting the empty half of the table.
   */
  it('is not a slot the cap can weigh or watch', () => {
    const { container } = render(
      <RecordTable
        table={controller([column('id', 'left'), column('amount')])}
      />,
    );

    const head = container.querySelector<HTMLElement>(
      'thead [data-column="filler"]',
    )!;
    expect(head.dataset.field).toBeUndefined();
    expect(
      pinnedSlots([column('id', 'left'), column('amount')], {
        selectable: true,
        actions: true,
      }).map(slot => slot.key),
    ).not.toContain('filler');
  });

  /**
   * And the header keeps its name while it does. The sort button carries the
   * registry's `max-w-full`, which is the cell's *content* box, while the
   * button pulls that padding back out with `-mx-2`: capped at the content
   * box it is 16px short of what it needs and `订单号` came out as `订…` —
   * one hover away in the header's tooltip, which is the way back from a
   * column too narrow for its name and not from one that had the room.
   * The ceiling is the cell's padding box — where the negative margins reach
   * and no further, so a narrowed column still clips rather than spills.
   */
  it('lets a header button reach the padding box of its cell', () => {
    const { container } = render(
      <RecordTable
        table={controller([column('id', 'left'), column('amount')])}
      />,
    );

    // Every column header; the filler at the end holds no button.
    for (const cell of container.querySelectorAll(
      'thead th:not([data-column="filler"])',
    ))
      expect(cell.className).toContain('[&>button]:max-w-[calc(100%+1rem)]');
  });
});

/**
 * The cap on the held group (D17-4).
 *
 * A pinned column is a fixed number of pixels, so the narrower the port the
 * larger its share. At 420×860 the wide fixture's three held columns —
 * checkbox 42, the key 86, the host's actions 104 — measured 232px against a
 * 286px result area: 81%, leaving 54px for nineteen columns none of which is
 * that narrow. Beyond half the port the outermost pins are let go until the
 * group fits; the key never lets go, and nothing is written to the config.
 *
 * jsdom lays nothing out, so the three numbers the rule turns on are
 * injected here the way the browser would report them: the header cells'
 * widths, and the port's visible and content widths. The browser story
 * `PinnedGroupCapped` measures what the reader gets at 420px for real.
 */
describe('the pinned group against a narrow port', () => {
  const widths: Record<string, number> = { select: 42, id: 86, actions: 104 };
  let port = 286;

  /** What the browser would measure, for the one layout jsdom cannot do. */
  function measured(content = 2000): void {
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(
      function (this: Element) {
        const cell = this as HTMLElement;
        const key = cell.dataset.column ?? cell.dataset.field ?? '';
        return { width: widths[key] ?? 0 } as DOMRect;
      },
    );
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(
      function (this: HTMLElement) {
        return this.dataset.slot === 'record-table' ? port : 0;
      },
    );
    // Wider than it is visible: the middle really does scroll, which is the
    // only shape the cap has anything to say about.
    vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(
      function (this: HTMLElement) {
        return this.dataset.slot === 'record-table' ? content : 0;
      },
    );
  }

  const key = (): RecordColumnView => ({
    ...column('id', 'left'),
    label: 'Waybill',
    primary: true,
  });

  /** The pin each of the three held columns draws, as the DOM says it. */
  function pins(container: HTMLElement) {
    const head = (selector: string) =>
      container
        .querySelector(`thead th[${selector}]`)
        ?.getAttribute('data-pin');
    return {
      select: head('data-column="select"'),
      id: head('data-field="id"'),
      actions: head('data-column="actions"'),
    };
  }

  it('lets the outermost pin go, and never the key', () => {
    port = 286;
    measured();
    const setPinned = vi.fn();
    const { container } = render(
      <RecordTable
        table={controller([key(), column('amount')], { setPinned })}
        rowActions={() => <button />}
      />,
    );

    // 232 against 286 is 81%. The actions are the outermost of the group,
    // so they go first — and once they have, 128 of 286 is under the half
    // and the checkbox beside the key keeps its place.
    expect(pins(container)).toEqual({
      select: 'left',
      id: 'left',
      actions: null,
    });
    // The whole column, not the header alone: the buttons scroll with their
    // row, and the right edge goes with them.
    for (const cell of actionCells(container)) {
      expect(cell.className).not.toContain('sticky');
      expect(cell.className).not.toContain('shadow-[inset');
    }
    // A rendering cap and not an edit: the config still pins what it pinned.
    expect(setPinned).not.toHaveBeenCalled();
  });

  it('gives the pin back as the port widens, and takes it again', () => {
    port = 286;
    measured();
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

    const { container } = render(
      <RecordTable
        table={controller([key(), column('amount')])}
        rowActions={() => <button />}
      />,
    );
    const area = container.querySelector<HTMLElement>(
      '[data-slot="record-table"]',
    )!;
    expect(pins(container).actions).toBe(null);

    // The port is watched along with the cells, so a host that widens its
    // column gets its pins back without the table being re-mounted.
    const watching = () => {
      const found = observers.filter(spy => spy.observed.includes(area));
      return found[found.length - 1];
    };
    port = 900;
    act(() => watching().resize());
    expect(pins(container)).toEqual({
      select: 'left',
      id: 'left',
      actions: 'right',
    });

    port = 286;
    act(() => watching().resize());
    expect(pins(container).actions).toBe(null);
    vi.unstubAllGlobals();
  });

  it('lets a config-pinned column go before the key, and never the key', () => {
    port = 300;
    widths.status = 90;
    measured();
    const { container } = render(
      <RecordTable
        table={controller([key(), column('status', 'left'), column('amount')])}
        rowActions={() => <button />}
      />,
    );

    // 322 against 300 is over the half by 172: the actions go, then the
    // checkbox — outermost first — and then the one column the user pinned
    // themselves. The key is left holding the frame on its own.
    expect(headerOf(container, 'status').getAttribute('data-pin')).toBe(null);
    expect(pins(container)).toEqual({
      select: null,
      id: 'left',
      actions: null,
    });
    // The column that let go scrolls with the middle, every cell of it.
    for (const cell of cellsOf(container, 'status'))
      expect(cell.className).not.toContain('sticky');
  });

  it('keeps every pin where the columns all fit', () => {
    port = 286;
    // The same held group against the same port, and nothing to scroll: a
    // pin let go here would buy no width at all and take D13's frame off a
    // table standing still.
    measured(280);
    const { container } = render(
      <RecordTable
        table={controller([key(), column('amount')])}
        rowActions={() => <button />}
      />,
    );

    expect(pins(container)).toEqual({
      select: 'left',
      id: 'left',
      actions: 'right',
    });
  });

  /**
   * `scrollWidth` is an integer rounded up from fractional cell widths, so
   * a table that fits to the sub-pixel can report itself a pixel too wide —
   * and a header button that once overhung its cell by 2px made every table
   * do so. A pixel is nothing for a pin to give back.
   */
  it('keeps every pin on a table a pixel wider than its port', () => {
    port = 286;
    measured(287);
    const { container } = render(
      <RecordTable
        table={controller([key(), column('amount')])}
        rowActions={() => <button />}
      />,
    );

    expect(pins(container)).toEqual({
      select: 'left',
      id: 'left',
      actions: 'right',
    });
  });

  /**
   * D13 makes the first and last drawn columns the table's frame; the cap
   * takes what the layout and the config added before it takes the frame,
   * and never the key. So the last column goes last — after the host's
   * column, the selection column and the columns pinned beside the key.
   */
  it("lets the table's own last column go after every other pin", () => {
    const columns = [
      key(),
      column('warehouse', 'left'),
      column('note', 'right'),
    ];
    const names = (slots: readonly { key: string; fixed: boolean }[]) =>
      slots.map(slot => `${slot.key}${slot.fixed ? '!' : ''}`);

    // Without a host action column the last column is the table's right
    // frame: it goes last, after the pins the layout and the user added,
    // and the key never goes at all.
    expect(
      names(pinnedSlots(columns, { selectable: true, actions: false })),
    ).toEqual([SELECT_COLUMN, 'id!', 'warehouse', 'note']);
    // With one, the action column is the frame instead and the last column
    // is not held: there is nothing of it for the cap to let go.
    expect(
      names(pinnedSlots(columns, { selectable: true, actions: true })),
    ).toEqual([ACTIONS_COLUMN, SELECT_COLUMN, 'id!', 'warehouse']);
  });

  /**
   * The right edge is the action column's whenever the host gives one
   * (D19), so nothing else is held there: the table's own last column hands
   * the place over rather than sitting beside it behind a seam.
   */
  it("gives the right-hand pin up to the host's actions", () => {
    const columns = [key(), column('amount', 'right')];
    const held = tablePins(columns, { selectable: false, actions: false });
    const letGo = tablePins(columns, { selectable: false, actions: true });

    expect(held.columns.get('amount')?.side).toBe('right');
    // Held against the edge itself: nothing is ever pinned outside it.
    expect(held.columns.get('amount')?.style).toEqual({
      right: 'var(--fve-pin-right-1, 0px)',
    });
    expect(letGo.columns.has('amount')).toBe(false);
  });

  it('keeps the key pinned even where it alone is more than half', () => {
    port = 286;
    widths.id = 200;
    measured();
    const { container } = render(
      <RecordTable
        table={controller([key(), column('amount')])}
        rowActions={() => <button />}
      />,
    );

    // Everything the cap may take, it takes; what is left is over the half
    // and stays anyway. A row scrolled sideways without the column saying
    // which record it is is a row nobody can read.
    expect(pins(container)).toEqual({
      select: null,
      id: 'left',
      actions: null,
    });
    widths.id = 86;
  });
});

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

function headerOf(container: HTMLElement, field: string): HTMLElement {
  const found = container.querySelector<HTMLElement>(
    `thead [data-field="${field}"]`,
  );
  if (!found) throw new Error(`no header for ${field}`);
  return found;
}

function column(field: string, pinned?: 'left' | 'right'): RecordColumnView {
  return {
    field,
    label: field,
    kind: 'string',
    cell: 'string',
    sortable: false,
    ...(pinned ? { pinned } : {}),
  };
}

function controller(
  columns: RecordColumnView[],
  overrides: Partial<RecordTableController> = {},
): RecordTableController {
  const data = Object.fromEntries(columns.map(entry => [entry.field, 'x']));
  return {
    columns,
    rows: [{ key: 'o-1', data }],
    card: { title: columns[0].field, fields: [] },
    paging: { mode: 'paged', index: 1, total: 1 },
    summaries: { scope: 'page', cells: [] },
    status: 'success',
    hasResult: true,
    selection: [],
    sort: [],
    pageSize: 20,
    layout: 'table',
    columnFields: columns.map(entry => entry.field),
    isSelected: () => false,
    toggle: () => {},
    toggleAll: () => {},
    clearSelection: () => {},
    toggleSort: () => {},
    setSort: () => {},
    setPageSize: () => {},
    setColumns: () => {},
    setColumnOrder: () => {},
    setPinned: () => {},
    setSummary: () => {},
    setLayout: () => {},
    pinnedOf: () => false,
    summaryOf: () => null,
    goTo: () => {},
    next: () => {},
    previous: () => {},
    refresh: () => {},
    ...overrides,
  } as unknown as RecordTableController;
}

function cellsOf(container: HTMLElement, field: string): HTMLElement[] {
  const index = [...container.querySelectorAll('thead th')].findIndex(
    cell => cell.getAttribute('data-field') === field,
  );
  return [...container.querySelectorAll('thead tr, tbody tr, tfoot tr')].map(
    row => (row as HTMLTableRowElement).cells[index] as HTMLElement,
  );
}

function actionCells(container: HTMLElement): HTMLElement[] {
  const index = [...container.querySelectorAll('thead th')].findIndex(
    cell => cell.getAttribute('data-column') === 'actions',
  );
  return [...container.querySelectorAll('thead tr, tbody tr, tfoot tr')].map(
    row => (row as HTMLTableRowElement).cells[index] as HTMLElement,
  );
}
