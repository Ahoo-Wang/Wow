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

import { dequal } from 'dequal';
import {
  presentationMembers,
  without,
  type DataViewDefinition,
  type RecordViewConfig,
} from '../model/index.js';
import { recordProjection } from '../record/index.js';

/**
 * How much of a record view's question an apply changes, measured against
 * the config the rows on screen ran under.
 *
 * - `drawn`: nothing the source is asked. A column made wider, moved,
 *   pinned or hidden, or a presentation member (`layout`) — the rows stay,
 *   and are drawn again under the new config. Not a switch into or out of
 *   the kanban, nor its lanes changing: it asks one question per lane (D83).
 * - `summaries`: that, and the summary row. The aggregation is asked again
 *   and the page is not.
 * - `null`: anything else, and the whole query runs.
 *
 * The rows are kept only while they hold every field the new config reads
 * off them: the query's own projection (`recordProjection`) under `next`
 * must be covered by the one they ran under. A column switched on is a
 * field the rows do not hold yet, and so is a summary over a field nothing
 * shows — the page scope and the totals' fallback add it up off these rows,
 * and kept, they added up nothing until a refresh (#5566). Judged by the
 * projection rather than by which columns show, a card's field, a sort and
 * a currency come in the same way. Before this, every width ran the page
 * again — an Alt+→ on a header was a query, and a reader heard 「正在查询」
 * and the record count for each step of a column's width, and never the
 * width.
 */
export function restyledOnly(
  definition: DataViewDefinition,
  ran: RecordViewConfig,
  next: RecordViewConfig,
): 'drawn' | 'summaries' | null {
  if (!dequal(question(ran), question(next))) return null;
  const held = recordProjection(definition, ran).include ?? [];
  const asked = recordProjection(definition, next).include ?? [];
  // A path is held as itself, or under an ancestor that brings it whole.
  const holds = asked.every(path =>
    held.some(have => path === have || path.startsWith(`${have}.`)),
  );
  if (!holds) return null;
  if (dequal(ran.summaries ?? [], next.summaries ?? [])) return 'drawn';
  // A kanban's summaries are per lane, asked in the aggregation that counts
  // the lanes (D83): there is no summary row alone to ask again.
  return next.layout === 'kanban' ? null : 'summaries';
}

/**
 * What a kanban asks besides a page's question: that it is one, and
 * which lanes. Table and cards ask the same, so they compare equal here; the
 * kanban half a table keeps stored asks nothing until it is the layout.
 */
export function kanbanQuestion(config: RecordViewConfig): unknown {
  return config.layout === 'kanban' ? (config.kanban ?? null) : null;
}

/** The members of a record config the page's query is compiled from, but its projection. */
function question(config: RecordViewConfig): Record<string, unknown> {
  const rest: Record<string, unknown> = { ...config };
  for (const member of presentationMembers(config.kind)) delete rest[member];
  delete rest.summaries;
  rest.kanban = kanbanQuestion(config);
  // Which columns show is asked through the projection (`holds`); their
  // order, widths and pins are only how the rows are drawn.
  rest.table = without(config.table, 'columns');
  return rest;
}
