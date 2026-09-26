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

import type {
  RecordColumnView,
  SummaryCell,
  SummaryRow,
} from '../../record/index.js';
import type { LeftPin, StickyPin } from './sticky.js';
import type { TablePins } from './columns.js';

/**
 * Which way a summarised column lies from the part of the table in view.
 */
export type OffscreenSide = 'left' | 'right';

/**
 * The summarised columns the reader cannot see, by field, and on which side
 * of the view each one is. A column in view is simply absent.
 */
export type OffscreenColumns = ReadonlyMap<string, OffscreenSide>;

/**
 * What a summary row says about the numbers it holds out of view (D51).
 */
export interface OffscreenHint {
  /** The column named, and the one the hint scrolls to. */
  column: RecordColumnView;
  /** Its first summary in this row — the one the hint reads out. */
  cell: SummaryCell;
  side: OffscreenSide;
  /** How many summarised columns are out of view, the named one included. */
  count: number;
}

/**
 * The hint a summary row gives, or `null` when every column it summarises is
 * in view.
 *
 * The one named is the nearest way to go: the first hidden column to the
 * right in the columns' order, which is the direction a table is read and
 * the one a table opened at its left edge hides; where nothing is hidden on
 * the right, the hidden column closest on the left. A column the row holds
 * no summary for is not counted, whatever its visibility.
 */
export function offscreenHint(
  row: SummaryRow,
  columns: readonly RecordColumnView[],
  offscreen: OffscreenColumns,
): OffscreenHint | null {
  const hidden = columns.filter(
    column =>
      offscreen.has(column.field) &&
      row.cells.some(cell => cell.field === column.field),
  );
  if (hidden.length === 0) return null;
  const column =
    hidden.find(one => offscreen.get(one.field) === 'right') ??
    hidden[hidden.length - 1];
  return {
    column,
    cell: row.cells.find(cell => cell.field === column.field)!,
    side: offscreen.get(column.field)!,
    count: hidden.length,
  };
}

/**
 * The footer cell that carries the hint, as the summary rows lay it out.
 *
 * `at` is {@link SELECT_HOST} for the selection column's cell, otherwise a field.
 * `withScope` — the hint shares the cell with the row's scope label, and so
 * carries the scope inside it. `pin` — what the cell holds against, where
 * the table itself would let it scroll: the hint has to stay at the left end
 * while the columns move, or it scrolls away with the first of them.
 */
export interface HintHost {
  at: string;
  withScope: boolean;
  pin?: StickyPin;
}

/** The key the selection column's cell answers to in a {@link HintHost}. */
export const SELECT_HOST = '\u0000select';

/**
 * Where the hint goes: the widest room at the left end that holds nothing
 * else.
 *
 * - A first column that carries the scope, holds no summary and is pinned
 *   left already is that room.
 * - Otherwise the first column pinned left that holds no summary: in a
 *   workbench that is usually the row key beside the narrow selection
 *   column, which has the room the selection column lacks.
 * - Otherwise the scope's own cell. Where nothing is pinned left it is held
 *   against the edge for the footer alone — the selection column, or a first
 *   column with no summary of its own. A first column that summarises
 *   something is left to scroll: holding it would hold its numbers away from
 *   the column they belong to, so there the hint is readable only while that
 *   column is.
 */
export function hintHost(
  columns: readonly RecordColumnView[],
  selectable: boolean,
  pins: TablePins,
  summarised: ReadonlySet<string>,
): HintHost | null {
  const first = columns[0];
  const isHeldLeft = (field: string) =>
    pins.columns.get(field)?.side === 'left';
  if (
    !selectable &&
    first &&
    !summarised.has(first.field) &&
    isHeldLeft(first.field)
  )
    return { at: first.field, withScope: true };
  const free = columns.find(
    (column, index) =>
      !(index === 0 && !selectable) &&
      isHeldLeft(column.field) &&
      !summarised.has(column.field),
  );
  if (free) return { at: free.field, withScope: false };

  const scope = selectable ? SELECT_HOST : first?.field;
  if (scope === undefined) return null;
  const held = selectable ? pins.select : pins.columns.get(scope);
  if (held) return { at: scope, withScope: true, pin: held };
  const anyLeft = columns.some(column => isHeldLeft(column.field));
  const free0 = selectable || !summarised.has(scope);
  const edge: LeftPin = { side: 'left', edge: true };
  return {
    at: scope,
    withScope: true,
    ...(!anyLeft && free0 ? { pin: edge } : {}),
  };
}
