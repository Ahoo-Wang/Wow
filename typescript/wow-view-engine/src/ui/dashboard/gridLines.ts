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
  calcGridCellDimensions,
  defaultGridConfig,
} from 'react-grid-layout/core';

/** What the grid is laid out at: the width it was handed, its columns, its row. */
export interface GridLinesInput {
  width: number;
  cols: number;
  rowHeight: number;
}

/**
 * The board's cells while it is built, as the CSS variables the stylesheet's
 * `[data-grid-lines]` rule paints them from (`styles.css`): each cell's edges
 * a hairline, so a panel dragged or resized comes to rest on lines the
 * reader can see.
 *
 * The sums are the library's own (`calcGridCellDimensions`), over the margin
 * and the padding the grid is laid out with (`defaultGridConfig`: the board
 * sets neither), so a line and a panel's edge are one computation and never
 * two that agree today. The lines are a background of the element the grid
 * sits in, not one element a cell: nothing to walk past, nothing to press.
 *
 * `undefined` for a grid not measured yet — no lines are better than lines
 * at a width the panels are not at.
 */
export function gridLines({
  width,
  cols,
  rowHeight,
}: GridLinesInput): CSSProperties | undefined {
  if (width <= 0 || cols <= 0) return undefined;
  const cell = calcGridCellDimensions({
    width,
    cols,
    rowHeight,
    margin: defaultGridConfig.margin,
    containerPadding: defaultGridConfig.containerPadding,
  });
  return {
    '--grid-cell-w': `${cell.cellWidth}px`,
    '--grid-cell-h': `${cell.cellHeight}px`,
    '--grid-gap-x': `${cell.gapX}px`,
    '--grid-gap-y': `${cell.gapY}px`,
    '--grid-offset-x': `${cell.offsetX}px`,
    '--grid-offset-y': `${cell.offsetY}px`,
  } as CSSProperties;
}
