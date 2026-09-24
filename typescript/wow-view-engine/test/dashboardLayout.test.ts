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
import { describe, expect, it } from 'vitest';
import {
  arrangePanel,
  bottomOf,
  compactLayout,
  fitsGrid,
  freeSpot,
  overlaps,
  placePanel,
  placePanelIn,
  readingOrder,
  reorderPanel,
  reorderPanelIn,
  stackedLayout,
  withLayouts,
  type DashboardPanel,
  type PlacedPanel,
} from '../src/index.js';
import { dashboardConfig } from './fixtures.js';

function box(id: string, x: number, y: number, w: number, h: number) {
  return { id, x, y, w, h };
}

/** The boxes by id, so a case reads as a picture rather than an order. */
function byId(boxes: readonly PlacedPanel[] | null) {
  return Object.fromEntries(
    (boxes ?? []).map(({ id, ...layout }) => [id, layout]),
  );
}

describe('fitsGrid', () => {
  it.each([
    [{ x: 0, y: 0, w: 24, h: 1 }, true],
    [{ x: 23, y: 40, w: 1, h: 9 }, true],
    [{ x: 13, y: 0, w: 12, h: 1 }, false],
    [{ x: -1, y: 0, w: 1, h: 1 }, false],
    [{ x: 0, y: 0, w: 0, h: 1 }, false],
    [{ x: 0.5, y: 0, w: 1, h: 1 }, false],
    [{ x: 0, y: 0, w: 1 }, false],
    [null, false],
  ])('%o fits: %s', (layout, fits) => {
    expect(fitsGrid(layout)).toBe(fits);
  });
});

describe('overlaps', () => {
  it('counts a shared cell, and not a shared edge', () => {
    expect(overlaps(box('a', 0, 0, 2, 2), box('b', 1, 1, 2, 2))).toBe(true);
    expect(overlaps(box('a', 0, 0, 2, 2), box('b', 2, 0, 2, 2))).toBe(false);
    expect(overlaps(box('a', 0, 0, 2, 2), box('b', 0, 2, 2, 2))).toBe(false);
  });
});

describe('compactLayout', () => {
  it('floats every panel up until something is above it, keeping the order down a column', () => {
    const holey = [
      box('a', 0, 2, 12, 2),
      box('b', 0, 7, 12, 3),
      box('c', 12, 5, 12, 1),
    ];

    expect(byId(compactLayout(holey))).toEqual({
      a: { x: 0, y: 0, w: 12, h: 2 },
      b: { x: 0, y: 2, w: 12, h: 3 },
      c: { x: 12, y: 0, w: 12, h: 1 },
    });
  });

  it('drops a panel stored on top of another below it', () => {
    const stacked = [box('a', 0, 0, 12, 4), box('b', 0, 2, 12, 4)];

    expect(byId(compactLayout(stacked))).toEqual({
      a: { x: 0, y: 0, w: 12, h: 4 },
      b: { x: 0, y: 4, w: 12, h: 4 },
    });
  });
});

/**
 * The one rule a panel is placed by, whether a pointer dropped it or a key
 * stepped it: the panel takes the cells it asked for, whatever it covers
 * makes way, and then its tab floats up — no hole is left behind a move
 * (batch-A walk, as a Metabase board does).
 */
describe('placePanel', () => {
  const column = [box('a', 0, 0, 12, 4), box('b', 0, 4, 12, 4)];

  it('moves only the panel when it lands on nothing', () => {
    expect(byId(placePanel(column, 'b', { x: 12, y: 0, w: 12, h: 4 }))).toEqual(
      {
        a: { x: 0, y: 0, w: 12, h: 4 },
        b: { x: 12, y: 0, w: 12, h: 4 },
      },
    );
  });

  it('floats the rest up into the room a panel moved out of', () => {
    const three = [...column, box('c', 0, 8, 12, 2)];

    expect(byId(placePanel(three, 'a', { x: 12, y: 0, w: 12, h: 4 }))).toEqual({
      a: { x: 12, y: 0, w: 12, h: 4 },
      b: { x: 0, y: 0, w: 12, h: 4 },
      c: { x: 0, y: 4, w: 12, h: 2 },
    });
  });

  it('floats a panel dropped into empty space up to rest under the others', () => {
    expect(byId(placePanel(column, 'a', { x: 0, y: 20, w: 12, h: 4 }))).toEqual(
      {
        a: { x: 0, y: 4, w: 12, h: 4 },
        b: { x: 0, y: 0, w: 12, h: 4 },
      },
    );
  });

  it('trades places with the panel it is put down on', () => {
    // Dropped squarely on b, a takes b's cells and b rises into the room
    // above it — a's old place.
    expect(byId(placePanel(column, 'a', { x: 0, y: 4, w: 12, h: 4 }))).toEqual({
      a: { x: 0, y: 4, w: 12, h: 4 },
      b: { x: 0, y: 0, w: 12, h: 4 },
    });
  });

  it('sends the panel above below one moved up into it', () => {
    // One row up by keyboard: b takes a's last row, a goes under b, and
    // the column closes up.
    expect(byId(placePanel(column, 'b', { x: 0, y: 3, w: 12, h: 4 }))).toEqual({
      a: { x: 0, y: 4, w: 12, h: 4 },
      b: { x: 0, y: 0, w: 12, h: 4 },
    });
  });

  it('puts back a panel stepped one row into the one under it', () => {
    // a one row down would leave a hole above it: it floats back.
    expect(byId(placePanel(column, 'a', { x: 0, y: 1, w: 12, h: 4 }))).toEqual(
      byId(column),
    );
  });

  it('pushes the panel below when one grows into it, and pulls it back when it shrinks', () => {
    expect(byId(placePanel(column, 'a', { x: 0, y: 0, w: 12, h: 5 }))).toEqual({
      a: { x: 0, y: 0, w: 12, h: 5 },
      b: { x: 0, y: 5, w: 12, h: 4 },
    });
    expect(byId(placePanel(column, 'a', { x: 0, y: 0, w: 12, h: 2 }))).toEqual({
      a: { x: 0, y: 0, w: 12, h: 2 },
      b: { x: 0, y: 2, w: 12, h: 4 },
    });
  });

  it('pushes a panel beside it when one grows wide enough to cover it', () => {
    const row = [box('a', 0, 0, 12, 4), box('b', 12, 0, 12, 2)];
    expect(byId(placePanel(row, 'a', { x: 0, y: 0, w: 13, h: 4 }))).toEqual({
      a: { x: 0, y: 0, w: 13, h: 4 },
      b: { x: 12, y: 4, w: 12, h: 2 },
    });
  });

  it('cascades: what a pushed panel lands on goes down in turn', () => {
    const stack = [...column, box('c', 0, 8, 12, 2), box('d', 12, 0, 12, 2)];
    expect(byId(placePanel(stack, 'a', { x: 0, y: 0, w: 12, h: 6 }))).toEqual({
      a: { x: 0, y: 0, w: 12, h: 6 },
      b: { x: 0, y: 6, w: 12, h: 4 },
      c: { x: 0, y: 10, w: 12, h: 2 },
      // Beside the column, so never covered.
      d: { x: 12, y: 0, w: 12, h: 2 },
    });
  });

  it('tidies a stored overlap the move reaches', () => {
    const stored = [
      box('a', 0, 0, 12, 4),
      box('b', 0, 2, 12, 4),
      box('c', 12, 0, 12, 2),
    ];
    expect(byId(placePanel(stored, 'c', { x: 12, y: 1, w: 12, h: 2 }))).toEqual(
      {
        a: { x: 0, y: 0, w: 12, h: 4 },
        b: { x: 0, y: 4, w: 12, h: 4 },
        c: { x: 12, y: 0, w: 12, h: 2 },
      },
    );
  });

  it('refuses a layout the grid does not admit, and a panel there is none of', () => {
    expect(placePanel(column, 'a', { x: 13, y: 0, w: 12, h: 4 })).toBeNull();
    expect(placePanel(column, 'a', { x: 0, y: -1, w: 12, h: 4 })).toBeNull();
    expect(placePanel(column, 'ghost', { x: 0, y: 0, w: 1, h: 1 })).toBeNull();
  });

  it('keeps only the four numbers of what it was handed', () => {
    const placed = placePanel(column, 'a', {
      x: 0,
      y: 0,
      w: 12,
      h: 4,
      moved: true,
    } as never);
    expect(placed?.[0]).toEqual({ id: 'a', x: 0, y: 0, w: 12, h: 4 });
  });
});

/**
 * A keyboard step is what the board makes of it: sideways and in size one
 * cell; up and down the smallest move that lands the panel somewhere else,
 * which on a board that floats up means past its neighbour.
 */
describe('arranging by keyboard', () => {
  const column = [
    box('a', 0, 0, 12, 4),
    box('b', 0, 4, 12, 2),
    box('c', 12, 0, 12, 6),
  ];

  it('moves down past the panel below', () => {
    const target = arrangePanel(column, 'a', 'down');
    expect(target).toEqual({ x: 0, y: 2, w: 12, h: 4 });
    expect(byId(placePanel(column, 'a', target!))).toMatchObject({
      a: { y: 2 },
      b: { y: 0 },
    });
  });

  it('moves up past the panel above', () => {
    const target = arrangePanel(column, 'b', 'up');
    expect(target).toEqual({ x: 0, y: 3, w: 12, h: 2 });
    expect(byId(placePanel(column, 'b', target!))).toMatchObject({
      a: { y: 2 },
      b: { y: 0 },
    });
  });

  it('has nowhere down for the last panel of a column, nor up for the first', () => {
    expect(arrangePanel(column, 'b', 'down')).toBeNull();
    expect(arrangePanel(column, 'c', 'down')).toBeNull();
    expect(arrangePanel(column, 'a', 'up')).toBeNull();
  });

  it('does not count tidying a stored hole as a move', () => {
    const holey = [box('a', 0, 3, 12, 2)];
    expect(arrangePanel(holey, 'a', 'up')).toBeNull();
    expect(arrangePanel(holey, 'a', 'down')).toBeNull();
  });

  it('takes one cell sideways and in size', () => {
    expect(arrangePanel(column, 'a', 'right')).toEqual({
      x: 1,
      y: 0,
      w: 12,
      h: 4,
    });
    expect(arrangePanel(column, 'a', 'taller')).toEqual({
      x: 0,
      y: 0,
      w: 12,
      h: 5,
    });
    expect(arrangePanel(column, 'c', 'right')).toBeNull();
    expect(arrangePanel(column, 'ghost', 'right')).toBeNull();
  });
});

describe('freeSpot', () => {
  const board = [box('a', 0, 0, 12, 4), box('b', 0, 4, 24, 2)];

  it('takes the first free place at rest, rows top to bottom, each left to right', () => {
    expect(freeSpot(board, { w: 12, h: 4 })).toEqual({
      x: 12,
      y: 0,
      w: 12,
      h: 4,
    });
  });

  it('lands at or below the row on screen', () => {
    expect(freeSpot(board, { w: 6, h: 2 }, 24, 6)).toEqual({
      x: 0,
      y: 6,
      w: 6,
      h: 2,
    });
  });

  it('never leaves a new panel hanging over a hole', () => {
    // From row 2 there is room at x 12, but nothing under row 1 holds it
    // up; the first place it rests is under b.
    expect(freeSpot(board, { w: 12, h: 2 }, 24, 2)).toEqual({
      x: 0,
      y: 6,
      w: 12,
      h: 2,
    });
  });

  it('starts an empty board at the top, and keeps a size inside the grid', () => {
    expect(freeSpot([], { w: 40, h: 0 })).toEqual({ x: 0, y: 0, w: 24, h: 1 });
    expect(bottomOf(board)).toBe(6);
  });
});

describe('placePanelIn', () => {
  const panels = [
    {
      id: 'a',
      kind: 'markdown',
      content: '',
      layout: { x: 0, y: 0, w: 12, h: 4 },
    },
    {
      id: 'b',
      kind: 'markdown',
      content: '',
      layout: { x: 0, y: 4, w: 12, h: 4 },
    },
  ] as DashboardPanel[];

  it('writes the placed panel and the ones that made way, and nothing else', () => {
    const config = dashboardConfig({ panels });
    const placed = placePanelIn(config, 'b', { x: 0, y: 2, w: 12, h: 4 });

    expect(placed.panels.map(entry => entry.layout)).toEqual([
      { x: 0, y: 4, w: 12, h: 4 },
      { x: 0, y: 0, w: 12, h: 4 },
    ]);
    expect(placed.filter).toBe(config.filter);
    expect(config.panels[0].layout.y).toBe(0);
  });

  it('hands back the same config when nothing moves', () => {
    const config = dashboardConfig({ panels });

    expect(placePanelIn(config, 'a', { x: 0, y: 0, w: 12, h: 4 })).toBe(config);
    expect(placePanelIn(config, 'a', { x: 13, y: 0, w: 12, h: 4 })).toBe(
      config,
    );
    expect(placePanelIn(config, 'ghost', { x: 0, y: 0, w: 1, h: 1 })).toBe(
      config,
    );
  });

  it('moves only the panels of the tab it is on', () => {
    const config = dashboardConfig({
      tabs: [
        { id: 'one', title: 'One' },
        { id: 'two', title: 'Two' },
      ],
      panels: [
        { ...panels[0], tab: 'one' },
        { ...panels[1], tab: 'one' },
        // Another grid: its numbers share no cell with tab one's.
        {
          ...panels[1],
          id: 'c',
          tab: 'two',
          layout: { x: 0, y: 6, w: 12, h: 2 },
        },
      ] as DashboardPanel[],
    });

    const placed = placePanelIn(config, 'a', { x: 12, y: 0, w: 12, h: 4 });

    expect(placed.panels.map(entry => entry.layout.y)).toEqual([0, 0, 6]);
  });

  it('leaves an entry that is no panel, or has no layout it admits, out of it', () => {
    const odd = [
      ...panels,
      'no panel',
      { id: 'broken', kind: 'markdown', content: '', layout: { x: 0, y: 3 } },
    ] as unknown as DashboardPanel[];
    const config = dashboardConfig({ panels: odd });

    const placed = placePanelIn(config, 'b', { x: 0, y: 2, w: 12, h: 4 });

    expect(placed.panels[2]).toBe(odd[2]);
    expect(placed.panels[3]).toBe(odd[3]);
    expect(placed.panels[0].layout.y).toBe(4);
  });

  it('reads a config whose panels are not a list as having none', () => {
    const config = dashboardConfig({ panels: 'nope' as never });

    expect(placePanelIn(config, 'a', { x: 0, y: 0, w: 1, h: 1 })).toBe(config);
    expect(withLayouts(config, [box('a', 0, 0, 1, 1)])).toBe(config);
  });
});

/**
 * The order a reader counts panels in — rows top to bottom, each left to
 * right — and the one-column reading a narrow screen shows, derived from
 * the stored layout and never written back to it.
 */
describe('readingOrder', () => {
  it('reads rows top to bottom, each left to right, whatever the config order', () => {
    const panels = [
      { id: 'below', layout: box('below', 0, 4, 12, 2) },
      { id: 'right', layout: box('right', 12, 0, 12, 4) },
      { id: 'left', layout: box('left', 0, 0, 12, 4) },
    ];

    expect(readingOrder(panels).map(panel => panel.id)).toEqual([
      'left',
      'right',
      'below',
    ]);
    // The config itself is left as it was.
    expect(panels.map(panel => panel.id)).toEqual(['below', 'right', 'left']);
  });
});

describe('stackedLayout', () => {
  it('stacks the panels in reading order, full width, each as tall as saved', () => {
    const stored = [
      box('right', 12, 0, 12, 4),
      box('left', 0, 0, 12, 3),
      box('below', 0, 4, 8, 2),
    ];

    expect(stackedLayout(stored)).toEqual([
      box('left', 0, 0, 1, 3),
      box('right', 0, 3, 1, 4),
      box('below', 0, 7, 1, 2),
    ]);
    // Derived, not placed: the stored boxes are untouched.
    expect(stored[0]).toEqual(box('right', 12, 0, 12, 4));
  });
});

/**
 * 「上移」／「下移」 in the one-column reading (D22 J): one step along the
 * reading order, written back onto the wide grid it is a reading of.
 */
describe('reordering the one-column reading', () => {
  /** The ids in reading order. */
  const read = (boxes: readonly PlacedPanel[] | null) =>
    readingOrder(
      (boxes ?? []).map(entry => ({ id: entry.id, layout: entry })),
    ).map(entry => entry.id);

  it('trades two panels of one size side by side, and nothing else moves', () => {
    const board = [
      box('left', 0, 0, 12, 4),
      box('right', 12, 0, 12, 4),
      box('below', 0, 4, 24, 2),
    ];

    const moved = reorderPanel(board, 'right', 'up');

    expect(byId(moved)).toEqual({
      left: { x: 12, y: 0, w: 12, h: 4 },
      right: { x: 0, y: 0, w: 12, h: 4 },
      below: { x: 0, y: 4, w: 24, h: 2 },
    });
    expect(read(moved)).toEqual(['right', 'left', 'below']);
  });

  it('moves a panel down past the one under it, each keeping its size', () => {
    const board = [box('tall', 0, 0, 24, 4), box('short', 0, 4, 24, 2)];

    expect(byId(reorderPanel(board, 'tall', 'down'))).toEqual({
      tall: { x: 0, y: 2, w: 24, h: 4 },
      short: { x: 0, y: 0, w: 24, h: 2 },
    });
  });

  it('takes a full-width panel past half of a row by moving the other half under it', () => {
    const board = [
      box('head', 0, 0, 24, 2),
      box('a', 0, 2, 12, 4),
      box('b', 12, 2, 12, 4),
      box('wide', 0, 6, 24, 3),
    ];

    const moved = reorderPanel(board, 'wide', 'up');

    expect(read(moved)).toEqual(['head', 'a', 'wide', 'b']);
    // What is read before the two stays exactly where it was, and the board
    // is at rest: a later placement lifts nothing.
    expect(byId(moved).head).toEqual(byId([board[0]]).head);
    expect(byId(moved).a).toEqual({ x: 0, y: 2, w: 12, h: 4 });
    expect(compactLayout(moved!)).toEqual(moved);
  });

  it('lands the panel one place along on any board, at rest and on the grid', () => {
    // A board whose columns do not line up: every panel, both ways.
    const board = [
      box('p0', 0, 2, 16, 5),
      box('p1', 16, 4, 6, 4),
      box('p2', 13, 0, 6, 2),
      box('p3', 20, 0, 4, 4),
      box('p4', 0, 7, 12, 4),
      box('p5', 18, 8, 4, 5),
      box('p6', 16, 13, 8, 3),
    ];
    const order = read(board);
    for (const [at, id] of order.entries())
      for (const step of ['up', 'down'] as const) {
        const moved = reorderPanel(board, id, step);
        const to = step === 'up' ? at - 1 : at + 1;
        if (to < 0 || to >= order.length) {
          expect(moved).toBeNull();
          continue;
        }
        expect(read(moved)[to]).toBe(id);
        expect(compactLayout(moved!)).toEqual(moved);
        for (const entry of moved!) expect(fitsGrid(entry)).toBe(true);
        moved!.forEach((one, index) =>
          moved!
            .slice(index + 1)
            .forEach(two => expect(overlaps(one, two)).toBe(false)),
        );
      }
  });

  it('stacks the board down one column where no placement reads right', () => {
    // Four columns: 「narrow」 comes before 「tall」 only with 「tall」 and
    // 「side」 both moving, which no single placement does.
    const board = [
      box('top', 0, 0, 2, 3),
      box('tall', 3, 0, 1, 3),
      box('narrow', 0, 3, 3, 1),
      box('side', 3, 3, 1, 3),
      box('last', 3, 6, 1, 3),
    ];

    const moved = reorderPanel(board, 'narrow', 'up', 4);

    expect(read(moved)).toEqual(['top', 'narrow', 'tall', 'side', 'last']);
    expect(byId(moved)).toEqual({
      top: { x: 0, y: 0, w: 2, h: 3 },
      narrow: { x: 0, y: 3, w: 3, h: 1 },
      tall: { x: 0, y: 4, w: 1, h: 3 },
      side: { x: 0, y: 7, w: 1, h: 3 },
      last: { x: 0, y: 10, w: 1, h: 3 },
    });
  });

  it('has nowhere to go past either end, or for a panel it does not hold', () => {
    const board = [box('a', 0, 0, 24, 2), box('b', 0, 2, 24, 2)];

    expect(reorderPanel(board, 'a', 'up')).toBeNull();
    expect(reorderPanel(board, 'b', 'down')).toBeNull();
    expect(reorderPanel(board, 'ghost', 'up')).toBeNull();
  });

  it('writes the panels of its own tab into the config, and nothing else', () => {
    const note = (id: string, tab: string, y: number) =>
      ({
        id,
        kind: 'markdown',
        content: '',
        tab,
        layout: { x: 0, y, w: 24, h: 2 },
      }) as DashboardPanel;
    const config = dashboardConfig({
      tabs: [
        { id: 'one', title: 'One' },
        { id: 'two', title: 'Two' },
      ],
      panels: [note('a', 'one', 0), note('b', 'one', 2), note('c', 'two', 0)],
    });

    const moved = reorderPanelIn(config, 'b', 'up');

    expect(moved.panels.map(panel => [panel.id, panel.layout.y])).toEqual([
      ['a', 2],
      ['b', 0],
      ['c', 0],
    ]);
    expect(reorderPanelIn(config, 'c', 'up')).toBe(config);
    expect(reorderPanelIn(config, 'ghost', 'down')).toBe(config);
  });
});
