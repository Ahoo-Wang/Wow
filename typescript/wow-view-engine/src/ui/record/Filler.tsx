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

import { TableCell, TableHead } from '../components/table.js';

/**
 * How the cell that takes the leftover width is found. It lives here rather
 * than in `columns.ts` so the cap can skip it without the two files having
 * to import each other.
 */
export const FILLER_COLUMN = 'filler';

/**
 * What it wears: all the width that is going, and none of the registry's own
 * padding — `px-0` beats `px-2` through `cn`, so the cell adds nothing to
 * the row but the space nobody else wanted. The hairline between rows comes
 * from `TABLE_CELLS` like every other cell's, and that is the whole of the
 * border it draws.
 */
const FILLER_CELL = 'w-full px-0';

/**
 * The last cell of every row, which is not a column.
 *
 * The registry's table is `w-full` and an auto layout hands the surplus to
 * the columns in proportion to what they already ask for, so on a wide
 * screen one of them becomes a cell with nothing in it: the four-column
 * fixture in a 1300px result area drew `金额` **446px** around
 * `¥2,450.00`, and the eye had to cross most of the screen to get from an
 * order to its amount. A trailing cell asking for `width: 100%` takes that
 * surplus instead, and every real column comes out at the width its own
 * content asks for — nothing wraps, nothing is cut, nothing is stretched.
 *
 * **And the row still runs to the frame.** Capping the table at
 * `max-content` was tried first and left a 371px table inside a 692px
 * result area: the hairlines, the hover band and both summary rows stopped
 * mid-frame with 320px of bare frame beside them, while the toolbar above
 * and the pagination below ran to its edge — a table floating in a box, and
 * against `ui/record.md`'s own rule that the table runs to that edge. The
 * filler keeps the row as wide as the port, so what the pointer highlights
 * and what a picked row tints is a whole row again.
 *
 * **It is drawn, not announced.** `aria-hidden` keeps it out of the table's
 * accessible grid, so nobody is told about a column with no name and no
 * values — which is what the first-load header means when it refuses to
 * draw an empty `<th>` over rows that do not exist. Here there *are* rows;
 * what is refused is only the pretence that this is one more column of them.
 * It is in no `table.columns`, carries no width in any config, is never
 * pinned, and the cap's header scan steps over it.
 *
 * It sits after everything, the host's action column included, so the last
 * *data* column keeps D13's edge exactly where it was: the rule and the
 * shadow say "this column is held", and past them there is nothing rather
 * than more table. Being last is also why `ColumnResizer`'s `cellIndex`
 * walk and the summary rows' own cells never had to learn about it. On a
 * table wider than its port there is no surplus at all, so it collapses to
 * nothing and every number the cap reads is the one it read before.
 */
export function FillerHead() {
  return (
    <TableHead
      aria-hidden
      data-column={FILLER_COLUMN}
      className={FILLER_CELL}
    />
  );
}

/** The same cell in a body, summary or skeleton row. */
export function FillerCell() {
  return (
    <TableCell
      aria-hidden
      data-column={FILLER_COLUMN}
      className={FILLER_CELL}
    />
  );
}
