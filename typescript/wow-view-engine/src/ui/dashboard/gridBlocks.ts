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

/** The corner of a block: small, so the blocks read as a ruler, not as cards. */
const BLOCK_RADIUS = 3;

/**
 * More blocks than this in one row would be a texture, not a ruler; eight
 * still leaves an 80px row a block over a pixel tall.
 */
const MOST_BLOCKS_A_ROW = 8;

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
 * How tall a block is (D33): the row, a fixed height whatever the width,
 * cut into the number of blocks — one gap between each — that comes nearest
 * a square as wide as a column. One block at 1920px (70×80), two at 1200px
 * (40×35), three at 776px (22×20). The row's own edges are always a block's
 * edges, so a panel's top and bottom land on one; the cuts inside a row are
 * a finer ruler than a panel can land on.
 */
export function blockHeight(rowHeight: number, column: number): number {
  let best = rowHeight;
  for (let count = 2; count <= MOST_BLOCKS_A_ROW; count++) {
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
 * block, as near a square as the row allows (`blockHeight`), in each column
 * of each row.
 *
 * `--grid-blocks` is the shape of one row — one SVG, as wide as the grid,
 * the row's blocks down each column — used as a mask and repeated down from
 * the grid's padding; the colour is the stylesheet's, so it follows the
 * theme. Each column's left edge and width are the library's own sums
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
  const side = blockHeight(rowHeight, calcGridColWidth(params));
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
