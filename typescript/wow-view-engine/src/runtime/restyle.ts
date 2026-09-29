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
  columnHidden,
  presentationMembers,
  type RecordViewConfig,
} from '../model/index.js';

/**
 * How much of a record view's question an apply changes, measured against
 * the config the rows on screen ran under.
 *
 * - `drawn`: nothing the source is asked. A column made wider, moved or
 *   pinned, or a presentation member (`layout`) — the rows stay, and are
 *   drawn again under the new config.
 * - `summaries`: that, and the summary row. The aggregation is asked again
 *   and the page is not.
 * - `null`: anything else, and the whole query runs.
 *
 * Which columns are **shown** is part of the question: the query projects
 * the fields the table draws (`recordProjection`), so a column switched on
 * is a field the rows do not hold yet. Their order is not, and neither is
 * a width or a pin. Before this, every one of those ran the page again — an
 * Alt+→ on a header was a query, and a reader heard 「正在查询」 and the
 * record count for each step of a column's width, and never the width.
 */
export function restyledOnly(
  ran: RecordViewConfig,
  next: RecordViewConfig,
): 'drawn' | 'summaries' | null {
  if (!dequal(question(ran), question(next))) return null;
  return dequal(ran.summaries ?? [], next.summaries ?? [])
    ? 'drawn'
    : 'summaries';
}

/** The members of a record config the page's query is compiled from. */
function question(config: RecordViewConfig): Record<string, unknown> {
  const rest: Record<string, unknown> = { ...config };
  for (const member of presentationMembers(config.kind)) delete rest[member];
  delete rest.summaries;
  const { columns = [], ...table } = config.table ?? {};
  rest.table = {
    ...table,
    shown: columns
      .filter(column => !columnHidden(column.hidden))
      .map(column => column.field)
      .sort(),
  };
  return rest;
}
