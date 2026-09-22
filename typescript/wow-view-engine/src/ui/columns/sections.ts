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

import type { FieldGroupDefinition } from '../../model/index.js';
import { fieldGroups } from '../../filter/index.js';
import {
  regionRows,
  type ColumnRegion,
  type ColumnSettingRow,
} from './rows.js';

/**
 * One block of rows inside an area: the catalogue group it is listed under,
 * or none for the rows no group lists.
 */
export interface ColumnSection {
  group: FieldGroupDefinition | undefined;
  rows: ColumnSettingRow[];
}

/**
 * One area's rows as the panel draws them — under the catalogue's headings
 * where there is a catalogue, and as one unheaded block where there is not.
 *
 * The areas stay the primary split: an area *is* a pinning, and a column
 * joins the other one by being pinned rather than by being listed
 * elsewhere. The catalogue is a second level **inside the scrolling area
 * only**, because the held area is short by construction — a handful of
 * columns against the left edge — while the scrolling one is the whole
 * table on a wide definition, and that is the list a reader gets lost in.
 *
 * Laid out the way every other field picker lays the catalogue out (see
 * `fieldGroups`): the fields no group lists first and without a heading,
 * then each declared group in the definition's order. A group none of the
 * area's rows belong to is not drawn.
 *
 * Inside one section the rows keep the order they arrived in, which is the
 * order the table draws them — and **not** the order the group declares its
 * fields in, which is the one place this picker parts from the others. They
 * have no order of their own to respect; this list is the column order, and
 * that is also what keeps a drag honest: a drop is committed as "put this
 * column where that one is" over the whole area's order, and because a
 * section's rows are a subsequence of that order, the column lands next to
 * the row it was dropped on while the columns of the other sections — which
 * the reader is not looking at — keep their places.
 */
export function columnSections(
  rows: readonly ColumnSettingRow[],
  region: ColumnRegion,
  groups: readonly FieldGroupDefinition[],
): ColumnSection[] {
  const own = regionRows(rows, region);
  if (own.length === 0) return [];
  if (region !== 'scrolling' || groups.length === 0)
    return [{ group: undefined, rows: own }];
  const at = new Map(own.map((row, index) => [row.field, index]));
  return fieldGroups(own, groups, row => row.field).map(entry => ({
    group: entry.group,
    rows: [...entry.items].sort(
      (one, other) => at.get(one.field)! - at.get(other.field)!,
    ),
  }));
}

/**
 * The rows a search keeps: the ones whose word contains what was typed,
 * ignoring case.
 *
 * Matched on the word the row wears rather than on the field name — that is
 * what the reader is looking at, and it is the only one of the two a
 * definition translates. Every row carries one: a broken column has no
 * label to show, so it wears its own field name.
 *
 * What it does **not** do is change the list: the rows it keeps are in the
 * order they came in, hidden columns among them, so a search can neither
 * reorder a table nor lose a column that happens to be switched off
 * (D17-8). Everything the panel counts — the last column that may not be
 * hidden, where a move lands — is counted over the whole list, never over
 * this one.
 */
export function matchingRows(
  rows: readonly ColumnSettingRow[],
  query: string,
): ColumnSettingRow[] {
  const needle = query.trim().toLocaleLowerCase();
  if (needle === '') return [...rows];
  return rows.filter(row => row.label.toLocaleLowerCase().includes(needle));
}
