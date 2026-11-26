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

import { isFilterGroup, type FilterCompileContext } from '../filter/index.js';
import {
  without,
  FilterOperators,
  type FilterLeaf,
  type FilterTree,
  type RecordData,
  type RecordKey,
  type RecordViewConfig,
} from '../model/index.js';
import { FIRST_PAGE, compileRecord } from '../record/index.js';
import type { KernelContext } from './execute.js';
import { withScopeFilter } from './scope.js';
import { abortWith } from './abort.js';

/**
 * One record, whole, by its row key — what a detail panel reads.
 *
 * A page of rows carries what the page shows, and a record's detail is
 * everything the definition declares about it: the error message a column
 * clipped, the stack trace no column holds. So it is asked for on its own,
 * the way a service answers "this one": the row key equal to `key`, within
 * the scope the host injected (a tenant's view never opens another tenant's
 * record) and the view's deletion state (D17-2) — **nothing else**, not the
 * conditions the page was found by. A record opened from the page may stop
 * matching them the moment a command changes it (a retried failure is no
 * longer 「失败」), and its detail is about the record, not about the list.
 * The deletion state is not one of them: a Wow source answers only records
 * not deleted unless the query says otherwise, so a recycle bin's row read
 * without it is answered as gone (#6910). It is kept where every record of
 * the page meets it, a condition under 「都满足」 groups only.
 *
 * Answers `null` when the record is no longer there. Runs beside the view,
 * as an export does: no scheduler slot, and the rows on screen untouched.
 */
export async function fetchRecord(
  context: KernelContext,
  config: RecordViewConfig,
  scope: FilterTree | null,
  key: RecordKey,
  signal?: AbortSignal,
): Promise<RecordData | null> {
  const { definition, kinds, environment, source } = context;
  const capability = definition.record;
  if (!capability)
    throw new Error(
      `Definition ${definition.id} declares no record capability`,
    );
  const one = withScopeFilter<RecordViewConfig>(
    {
      ...config,
      filter: {
        op: 'and',
        children: [
          {
            field: capability.rowKey,
            operator: FilterOperators.EQ,
            value: key,
          },
          ...deletionOf(config.filter),
        ],
      },
      sort: [],
      pageSize: 1,
    },
    scope,
  );
  const filterContext: FilterCompileContext = {
    now: environment.now(),
    timeZone: environment.timeZone,
  };
  const cursored = capability.paging === 'cursor';
  const query = whole(
    compileRecord(definition, one, {
      kinds,
      context: filterContext,
      page: cursored ? FIRST_PAGE.cursor : FIRST_PAGE.paged,
    }),
  );
  const controller = abortWith(signal);
  const page = cursored
    ? await source.cursor(query, { signal: controller.signal })
    : await source.paged(query, { signal: controller.signal });
  return page.list[0] ?? null;
}

/**
 * The view's deletion conditions every record of its page meets: those
 * under 「都满足」 groups all the way up. One under 「任一满足」 holds only
 * for some of the rows, and is not the view's state.
 */
function deletionOf(tree: FilterTree | undefined): FilterLeaf[] {
  const kept: FilterLeaf[] = [];
  const groups = tree ? [tree] : [];
  for (let group = groups.pop(); group; group = groups.pop()) {
    if (group.op !== 'and') continue;
    for (const child of group.children)
      if (isFilterGroup(child)) groups.push(child);
      else if (child.operator === FilterOperators.DELETION) kept.push(child);
  }
  return kept;
}

/**
 * The query for one whole record: no projection, since a detail is every
 * field and not the page's; and no sort. `compileRecord` ends every page on
 * the row key so a tie cannot repeat a row across pages — a query for one
 * key has one row and no tie, and a sort would only be work for the source.
 */
function whole<Q extends { sort?: unknown }>(query: Q): Q {
  const bare =
    'projection' in query
      ? (without(query as Q & { projection?: unknown }, 'projection') as Q)
      : query;
  return { ...bare, sort: [] };
}
