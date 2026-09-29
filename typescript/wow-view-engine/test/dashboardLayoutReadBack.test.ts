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
 * D68's read-back, as a property: whatever a hand does on a board drawn
 * with some panels grown (D52) — a drag, a corner, a keyboard step — the
 * board saved and grown again is the board the author let go of, cell for
 * cell; the saved board never puts two panels on one cell; no untouched
 * panel saves a height it did not choose; and letting the panel go again
 * where it now is saves nothing.
 */

import { describe, expect, it } from 'vitest';
import {
  arrangePanel,
  compactLayout,
  grownLayout,
  overlaps,
  placePanel,
  placePanelIn,
  type ArrangeStep,
  type DashboardPanel,
  type DashboardViewConfig,
  type PanelLayout,
  type PlacedPanel,
} from '../src/index.js';
import { dashboardConfig } from './fixtures.js';

const COLUMNS = 24;

/** A seeded generator, so a failure names a case that comes back. */
function random(seed: number) {
  let state = seed >>> 0;
  const next = () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  /** An integer from `low` to `high`, both included. */
  const int = (low: number, high: number) =>
    low + Math.floor(next() * (high - low + 1));
  return { next, int };
}

type Random = ReturnType<typeof random>;

function configOf(boxes: readonly (PlacedPanel & PanelLayout)[]) {
  return dashboardConfig({
    panels: boxes.map(({ id, ...layout }) => ({
      id,
      kind: 'markdown',
      content: '',
      layout,
    })) as DashboardPanel[],
  });
}

function boxesOf(config: DashboardViewConfig): PlacedPanel[] {
  return (config.panels as DashboardPanel[]).map(panel => ({
    id: panel.id,
    ...panel.layout,
  }));
}

/** Four numbers, the mark left out: what the screen draws. */
function cells(boxes: readonly PlacedPanel[]) {
  return Object.fromEntries(
    boxes.map(({ id, x, y, w, h }) => [id, { x, y, w, h }]),
  );
}

/**
 * A stored board: panels of any size put anywhere and pushed down clear of
 * one another — holes and all — and, half the time, floated up as a hand
 * leaves it. Some are sized by hand.
 */
function board(rng: Random): PlacedPanel[] {
  const boxes: PlacedPanel[] = [];
  const count = rng.int(2, 7);
  for (let index = 0; index < count; index += 1) {
    const w = rng.int(2, COLUMNS);
    const h = rng.int(1, 8);
    const panel: PlacedPanel = {
      id: `p${index}`,
      x: rng.int(0, COLUMNS - w),
      y: rng.int(0, 16),
      w,
      h,
      ...(rng.next() < 0.15 ? { fixedHeight: true } : {}),
    };
    while (boxes.some(other => overlaps(other, panel))) panel.y += 1;
    boxes.push(panel);
  }
  if (rng.next() < 0.5) return compactLayout(boxes);
  return boxes;
}

/** The rows the screen grows panels to (`grownRows`): never a sized one. */
function growth(rng: Random, boxes: readonly PlacedPanel[]) {
  const heights = new Map<string, number>();
  for (const panel of boxes)
    if (!panel.fixedHeight && panel.h < 8 && rng.next() < 0.5)
      heights.set(panel.id, rng.int(panel.h + 1, 8));
  return heights;
}

const STEPS: ArrangeStep[] = [
  'left',
  'right',
  'up',
  'down',
  'wider',
  'narrower',
  'taller',
  'shorter',
];
const SIZES = new Set<ArrangeStep>(['wider', 'narrower', 'taller', 'shorter']);

/**
 * What a hand puts down on the board as drawn, as the grid hands it over
 * (`gridPlacement.ts`, `DashboardGrid`'s `arrange`): a drag at the size
 * drawn, a corner marked as sized by hand, or a keyboard step.
 */
function gesture(
  rng: Random,
  drawn: readonly PlacedPanel[],
): { id: string; layout: PanelLayout } | null {
  const panel = drawn[rng.int(0, drawn.length - 1)];
  const { x, y, w, h } = panel;
  const kind = rng.int(0, 2);
  if (kind === 0)
    return {
      id: panel.id,
      layout: { x: rng.int(0, COLUMNS - w), y: rng.int(0, 30), w, h },
    };
  if (kind === 1) {
    const wide = rng.int(1, COLUMNS - x);
    return {
      id: panel.id,
      layout: { x, y, w: wide, h: rng.int(1, 10), fixedHeight: true },
    };
  }
  const step = STEPS[rng.int(0, STEPS.length - 1)];
  const target = arrangePanel(drawn, panel.id, step, COLUMNS);
  if (!target) return null;
  return {
    id: panel.id,
    layout: SIZES.has(step) ? { ...target, fixedHeight: true } : target,
  };
}

/**
 * One case: the board, what grows on it, and one placement, held to all
 * four promises.
 */
function check(
  boxes: readonly PlacedPanel[],
  grown: ReadonlyMap<string, number>,
  id: string,
  layout: PanelLayout,
): void {
  const config = configOf(boxes);
  const drawn = grownLayout(boxes, grown);
  const dropped = placePanel(drawn, id, layout, COLUMNS);
  if (!dropped) return;
  const saved = placePanelIn(config, id, layout, COLUMNS, grown);
  const after = boxesOf(saved);

  // Sized by hand, the panel no longer grows; the rest grow as they did,
  // since each kept its saved height.
  const sized = after.find(panel => panel.id === id)?.fixedHeight === true;
  const again = new Map(grown);
  if (sized) again.delete(id);

  // Read back as the author let it go.
  expect(cells(grownLayout(after, again))).toEqual(cells(dropped));
  // Never two panels on one cell.
  for (const one of after)
    for (const other of after)
      if (one !== other) expect(overlaps(one, other)).toBe(false);
  // No untouched panel saved a height of the day's.
  for (const panel of after)
    if (panel.id !== id)
      expect(panel.h).toBe(boxes.find(entry => entry.id === panel.id)!.h);
  // Let go again where it now is: nothing to save.
  const now = dropped.find(panel => panel.id === id)!;
  const { x, y, w, h } = now;
  expect(placePanelIn(saved, id, { x, y, w, h }, COLUMNS, again)).toBe(saved);
}

describe('a placement on a grown board reads back as it was let go (D68)', () => {
  it('reads a full-width panel dropped under unevenly grown ones where it was dropped', () => {
    // The case #3779's re-review found: `p0` dragged to row 13 was saved at
    // row 10 and read back at 16, three rows lower than the author left it.
    const boxes = [
      { id: 'p5', x: 4, y: 0, w: 6, h: 5 },
      { id: 'p3', x: 0, y: 5, w: 8, h: 2 },
      { id: 'p2', x: 12, y: 10, w: 8, h: 6 },
      { id: 'p4', x: 18, y: 0, w: 6, h: 4 },
      { id: 'p0', x: 0, y: 16, w: 24, h: 4 },
    ];
    const grown = new Map([
      ['p5', 8],
      ['p3', 5],
      ['p2', 8],
    ]);
    check(boxes, grown, 'p0', { x: 0, y: 13, w: 24, h: 4 });
  });

  it('holds for every drag, corner and keyboard step on random boards', () => {
    const rng = random(0x3779);
    let placed = 0;
    for (let round = 0; round < 4000; round += 1) {
      const boxes = board(rng);
      const grown = growth(rng, boxes);
      const hand = gesture(rng, grownLayout(boxes, grown));
      if (!hand) continue;
      check(boxes, grown, hand.id, hand.layout);
      placed += 1;
    }
    // Enough of them were placements to say something.
    expect(placed).toBeGreaterThan(3000);
  });

  it('saves nothing for a drop where a panel is drawn on a board at rest', () => {
    const rng = random(68);
    for (let round = 0; round < 1000; round += 1) {
      const boxes = compactLayout(board(rng));
      const grown = growth(rng, boxes);
      const drawn = grownLayout(boxes, grown);
      const config = configOf(boxes);
      for (const { id, x, y, w, h } of drawn)
        expect(placePanelIn(config, id, { x, y, w, h }, COLUMNS, grown)).toBe(
          config,
        );
    }
  });

  it('reads a board nothing grows on exactly as saved', () => {
    const rng = random(52);
    for (let round = 0; round < 500; round += 1) {
      const boxes = board(rng);
      expect(grownLayout(boxes, new Map())).toEqual(boxes);
    }
  });
});
