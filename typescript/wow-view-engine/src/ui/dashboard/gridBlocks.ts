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

import type { CSSProperties } from 'react';
import {
  calcGridColWidth,
  calcGridItemPosition,
  defaultGridConfig,
  type PositionParams,
} from 'react-grid-layout/core';

/**
 * The gap the grid keeps between two cells, across and down, and the
 * padding around them: the library's own (`defaultGridConfig`), since the
 * board sets neither.
 */
const [GAP_X, GAP_Y] = defaultGridConfig.margin;

/** The gap between two rows, which a row's pitch adds to its height. */
export const ROW_GAP = GAP_Y;

/**
 * The least a row may be: what it always was (a metric card with its title
 * reads in one row, `ui/analysis.md`). A board is never drawn shorter than
 * it was saved at (D31), so a column narrower than a square of this size
 * cuts its row into more blocks (`blockSide`) rather than its panels short.
 */
export const MIN_ROW_HEIGHT = 80;

/** The corner of a block: small, so the blocks read as a ruler, not as cards. */
const BLOCK_RADIUS = 3;

function positionParams(
  width: number,
  cols: number,
  rowHeight: number,
): PositionParams {
  return {
    margin: [GAP_X, GAP_Y],
    containerPadding: [GAP_X, GAP_Y],
    containerWidth: width,
    cols,
    rowHeight,
    maxRows: Infinity,
  };
}

/**
 * The height of one row of the board at the width it is drawn at (D33):
 * two squares as wide as a column, stacked with the grid's gap between them.
 *
 * So a row is as tall as two columns are wide, and a panel keeps its shape
 * as the board widens — a chart twelve columns by four rows is the same
 * rectangle on a laptop and on a wall screen, the way Metabase derives its
 * row from its width. Two squares and not one, because a row is what a
 * panel's saved `h` counts: one square a row would halve every saved panel's
 * height. The square's side is a whole pixel so every row starts on one, and
 * never less than `MIN_ROW_HEIGHT` allows.
 */
export function boardRowHeight(width: number, cols: number): number {
  const floor = (MIN_ROW_HEIGHT - GAP_Y) / 2;
  if (width <= 0 || cols <= 0) return MIN_ROW_HEIGHT;
  const column = calcGridColWidth(positionParams(width, cols, 0));
  return 2 * Math.max(floor, Math.round(column)) + GAP_Y;
}

/**
 * How tall a block is, a row being cut into the number of blocks that come
 * nearest a square: two wherever the row follows the column (`boardRowHeight`),
 * and more on a grid so narrow the row stays at `MIN_ROW_HEIGHT` — three at
 * 800px, where two would be half again as tall as wide. Each cut is one gap.
 */
function blockSide(rowHeight: number, column: number): number {
  let best = rowHeight;
  for (let count = 2; count <= 4; count++) {
    const side = (rowHeight - (count - 1) * GAP_Y) / count;
    if (Math.abs(Math.log(side / column)) < Math.abs(Math.log(best / column)))
      best = side;
  }
  return best;
}

/** What the grid is laid out at: the width it was handed, its columns, its row. */
export interface GridBlocksInput {
  width: number;
  cols: number;
  rowHeight: number;
}

/**
 * The board's cells while it is built, as the CSS variables the stylesheet's
 * `dashboard-grid-blocks` layer is painted through (`styles.css`): a soft
 * square block on each place a panel's edge can come to rest, two to a row
 * (three on a narrow grid, `blockSide`).
 *
 * `--grid-blocks` is the shape of one row — one SVG, as wide as the grid, a
 * row of blocks down each column — used as a mask and repeated down from the
 * grid's padding; the colour is the stylesheet's, so it follows the theme.
 * Each column's left edge and width are the library's own sums
 * (`calcGridItemPosition`, rounded as it rounds a panel's), and the row is
 * the one the grid is laid out with, so a block's edge and a panel's edge
 * are one computation and never two that agree today. One drawing, not one
 * element a cell: nothing to walk past, nothing to press.
 *
 * `undefined` for a grid not measured yet — no blocks are better than blocks
 * at a width the panels are not at.
 */
export function gridBlocks({
  width,
  cols,
  rowHeight,
}: GridBlocksInput): CSSProperties | undefined {
  if (width <= 0 || cols <= 0) return undefined;
  const params = positionParams(width, cols, rowHeight);
  const side = blockSide(rowHeight, calcGridColWidth(params));
  const pitch = rowHeight + GAP_Y;
  const down = Array.from(
    { length: Math.round(pitch / (side + GAP_Y)) },
    (_, at) => at * (side + GAP_Y),
  );
  const rects = Array.from({ length: cols }, (_, x) => {
    const { left, width: w } = calcGridItemPosition(params, x, 0, 1, 1);
    return down
      .map(
        y =>
          `<rect x='${left}' y='${y}' width='${w}' height='${side}' rx='${BLOCK_RADIUS}'/>`,
      )
      .join('');
  }).join('');
  const svg = `<svg xmlns='http://www.w3.org/2000/svg' width='${width}' height='${pitch}'>${rects}</svg>`;
  return {
    '--grid-blocks': `url("data:image/svg+xml,${encodeURIComponent(svg)}")`,
    '--grid-blocks-size': `${width}px ${pitch}px`,
    '--grid-blocks-top': `${GAP_Y}px`,
  } as CSSProperties;
}
