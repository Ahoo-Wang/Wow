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
  fitsGrid,
  overlaps,
  placePanel,
  placePanelIn,
  readingOrder,
  stackedLayout,
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
    [{ x: 0, y: 0, w: 12, h: 1 }, true],
    [{ x: 11, y: 40, w: 1, h: 9 }, true],
    [{ x: 7, y: 0, w: 6, h: 1 }, false],
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

/**
 * The one rule a panel is placed by, whether a pointer dropped it or a key
 * stepped it: the panel wins the cells it asked for, the ones it covers are
 * pushed straight down, and nothing else moves.
 */
describe('placePanel', () => {
  const column = [box('a', 0, 0, 6, 4), box('b', 0, 4, 6, 4)];

  it('moves only the panel when it lands on nothing', () => {
    expect(byId(placePanel(column, 'b', { x: 6, y: 0, w: 6, h: 4 }))).toEqual({
      a: { x: 0, y: 0, w: 6, h: 4 },
      b: { x: 6, y: 0, w: 6, h: 4 },
    });
  });

  it('pushes a panel it covers down below it', () => {
    // One step up by keyboard: b takes a's last row, and a goes under b.
    expect(byId(placePanel(column, 'b', { x: 0, y: 3, w: 6, h: 4 }))).toEqual({
      a: { x: 0, y: 7, w: 6, h: 4 },
      b: { x: 0, y: 3, w: 6, h: 4 },
    });
  });

  it('pushes the panel below when one grows into it', () => {
    expect(byId(placePanel(column, 'a', { x: 0, y: 0, w: 6, h: 5 }))).toEqual({
      a: { x: 0, y: 0, w: 6, h: 5 },
      b: { x: 0, y: 5, w: 6, h: 4 },
    });
  });

  it('pushes a panel beside it when one grows wide enough to cover it', () => {
    const row = [box('a', 0, 0, 6, 4), box('b', 6, 0, 6, 2)];
    expect(byId(placePanel(row, 'a', { x: 0, y: 0, w: 7, h: 4 }))).toEqual({
      a: { x: 0, y: 0, w: 7, h: 4 },
      b: { x: 6, y: 4, w: 6, h: 2 },
    });
  });

  it('cascades: what a pushed panel lands on is pushed in turn', () => {
    const stack = [...column, box('c', 0, 8, 6, 2), box('d', 6, 8, 6, 2)];
    expect(byId(placePanel(stack, 'a', { x: 0, y: 0, w: 6, h: 6 }))).toEqual({
      a: { x: 0, y: 0, w: 6, h: 6 },
      b: { x: 0, y: 6, w: 6, h: 4 },
      c: { x: 0, y: 10, w: 6, h: 2 },
      // Beside the column, so never covered.
      d: { x: 6, y: 8, w: 6, h: 2 },
    });
  });

  it('keeps pushing until the panel clears every one it met', () => {
    const stack = [
      box('m', 6, 0, 6, 1),
      box('d', 0, 1, 3, 2),
      box('x', 0, 4, 6, 1),
    ];
    // m grows over both. d goes under it first; x, pushed under m next,
    // would then sit on d, so it goes on down under d.
    const placed = placePanel(stack, 'm', { x: 0, y: 0, w: 6, h: 6 });
    expect(byId(placed)).toEqual({
      m: { x: 0, y: 0, w: 6, h: 6 },
      d: { x: 0, y: 6, w: 3, h: 2 },
      x: { x: 0, y: 8, w: 6, h: 1 },
    });
  });

  it('leaves an overlap the config already had alone', () => {
    const stored = [
      box('a', 0, 0, 6, 4),
      box('b', 0, 2, 6, 4),
      box('c', 6, 0, 6, 2),
    ];
    expect(byId(placePanel(stored, 'c', { x: 6, y: 1, w: 6, h: 2 }))).toEqual({
      a: { x: 0, y: 0, w: 6, h: 4 },
      b: { x: 0, y: 2, w: 6, h: 4 },
      c: { x: 6, y: 1, w: 6, h: 2 },
    });
  });

  it('refuses a layout the grid does not admit, and a panel there is none of', () => {
    expect(placePanel(column, 'a', { x: 7, y: 0, w: 6, h: 4 })).toBeNull();
    expect(placePanel(column, 'a', { x: 0, y: -1, w: 6, h: 4 })).toBeNull();
    expect(placePanel(column, 'ghost', { x: 0, y: 0, w: 1, h: 1 })).toBeNull();
  });

  it('keeps only the four numbers of what it was handed', () => {
    const placed = placePanel(column, 'a', {
      x: 0,
      y: 0,
      w: 6,
      h: 4,
      moved: true,
    } as never);
    expect(placed?.[0]).toEqual({ id: 'a', x: 0, y: 0, w: 6, h: 4 });
  });
});

describe('placePanelIn', () => {
  const panels = [
    {
      id: 'a',
      kind: 'markdown',
      content: '',
      layout: { x: 0, y: 0, w: 6, h: 4 },
    },
    {
      id: 'b',
      kind: 'markdown',
      content: '',
      layout: { x: 0, y: 4, w: 6, h: 4 },
    },
  ] as DashboardPanel[];

  it('writes the placed panel and the ones it pushed, and nothing else', () => {
    const config = dashboardConfig({ panels });
    const placed = placePanelIn(config, 'b', { x: 0, y: 2, w: 6, h: 4 });

    expect(placed.panels.map(entry => entry.layout)).toEqual([
      { x: 0, y: 6, w: 6, h: 4 },
      { x: 0, y: 2, w: 6, h: 4 },
    ]);
    expect(placed.filter).toBe(config.filter);
    expect(config.panels[0].layout.y).toBe(0);
  });

  it('hands back the same config when nothing moves', () => {
    const config = dashboardConfig({ panels });

    expect(placePanelIn(config, 'a', { x: 0, y: 0, w: 6, h: 4 })).toBe(config);
    expect(placePanelIn(config, 'a', { x: 9, y: 0, w: 6, h: 4 })).toBe(config);
  });

  it('leaves an entry that is no panel, or has no layout it admits, out of it', () => {
    const odd = [
      ...panels,
      'no panel',
      { id: 'broken', kind: 'markdown', content: '', layout: { x: 0, y: 3 } },
    ] as unknown as DashboardPanel[];
    const config = dashboardConfig({ panels: odd });

    const placed = placePanelIn(config, 'b', { x: 0, y: 2, w: 6, h: 4 });

    expect(placed.panels[2]).toBe(odd[2]);
    expect(placed.panels[3]).toBe(odd[3]);
    expect(placed.panels[0].layout.y).toBe(6);
  });

  it('reads a config whose panels are not a list as having none', () => {
    const config = dashboardConfig({ panels: 'nope' as never });

    expect(placePanelIn(config, 'a', { x: 0, y: 0, w: 1, h: 1 })).toBe(config);
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
      { id: 'below', layout: box('below', 0, 4, 6, 2) },
      { id: 'right', layout: box('right', 6, 0, 6, 4) },
      { id: 'left', layout: box('left', 0, 0, 6, 4) },
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
      box('right', 6, 0, 6, 4),
      box('left', 0, 0, 6, 3),
      box('below', 0, 4, 4, 2),
    ];

    expect(stackedLayout(stored)).toEqual([
      box('left', 0, 0, 1, 3),
      box('right', 0, 3, 1, 4),
      box('below', 0, 7, 1, 2),
    ]);
    // Derived, not placed: the stored boxes are untouched.
    expect(stored[0]).toEqual(box('right', 6, 0, 6, 4));
  });
});
