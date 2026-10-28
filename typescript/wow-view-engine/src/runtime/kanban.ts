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

import type { CursorPage, PagedResult } from '@ahoo-wang/wow-client/dsl';
import {
  FIRST_PAGE,
  appendLane,
  compileKanbanCounts,
  compileRecord,
  kanbanLanes,
  laneConfig,
  projectKanban,
  type KanbanLane,
  type LanePage,
  type RecordProjection,
} from '../record/index.js';
import { issue, type FilterCompileContext } from '../filter/index.js';
import type {
  RecordData,
  RecordPageTarget,
  RecordViewConfig,
} from '../model/index.js';
import { isCalledOff } from './failure/failures.js';
import type { ProjectedView } from './source.js';
import type { KernelContext } from './execute.js';

/**
 * A kanban's result: every lane's first page and the lane
 * aggregation, asked together in the one slot the view's query has.
 *
 * A lane that fails fails the whole: the board would otherwise show a stage
 * as empty that only did not answer. The aggregation failing costs the
 * counts and the summaries, said by `runtime.kanban.counts-unavailable`,
 * and never the cards.
 */
export async function executeKanban(
  context: KernelContext,
  config: RecordViewConfig,
  filterContext: FilterCompileContext,
  controller: AbortController,
): Promise<ProjectedView> {
  const { definition, kinds, source } = context;
  const counts = compileKanbanCounts(definition, config, kinds, filterContext);
  const [pages, rows] = await Promise.all([
    Promise.all(
      kanbanLanes(definition, config).map(lane =>
        lanePage(context, config, lane, null, filterContext, controller),
      ),
    ),
    counts
      ? source
          .aggregate(counts, { signal: controller.signal })
          .catch(async (error: unknown) => {
            if (!isCalledOff(error, controller.signal))
              await context.queryFailed('kanban', error);
            return null;
          })
      : null,
  ]);
  const view = projectKanban(definition, config, pages, rows, context.limits);
  return {
    kind: 'record',
    view,
    summaries: null,
    issues:
      counts && rows === null
        ? [
            issue(
              'runtime.kanban.counts-unavailable',
              ['kanban'],
              undefined,
              'warning',
            ),
          ]
        : [],
  };
}

/**
 * One lane's next page put after what it has (「加载更多」), every other lane
 * and the counts as they were. `view` is the kanban on screen; a lane it does
 * not hold, or one with no next page, hands it back as it is.
 */
export async function executeLanePage(
  context: KernelContext,
  config: RecordViewConfig,
  view: RecordProjection,
  id: string,
  issues: ProjectedView['issues'],
  controller: AbortController,
): Promise<ProjectedView> {
  const filterContext: FilterCompileContext = {
    now: context.environment.now(),
    timeZone: context.environment.timeZone,
  };
  const { definition } = context;
  const held = view.lanes?.find(lane => lane.id === id);
  const lane = kanbanLanes(definition, config).find(found => found.id === id);
  if (!held?.next || !lane)
    return { kind: 'record', view, summaries: null, issues };
  const next = await lanePage(
    context,
    config,
    lane,
    held.next,
    filterContext,
    controller,
  );
  return {
    kind: 'record',
    view: appendLane(
      definition,
      config,
      view,
      id,
      next.page,
      next.index,
      context.limits,
    ),
    summaries: null,
    issues,
  };
}

async function lanePage(
  context: KernelContext,
  config: RecordViewConfig,
  lane: KanbanLane,
  page: RecordPageTarget | null,
  filterContext: FilterCompileContext,
  controller: AbortController,
): Promise<LanePage> {
  const { definition, kinds, source } = context;
  const paging = definition.record?.paging ?? 'paged';
  const target = page ?? FIRST_PAGE[paging];
  const query = compileRecord(
    definition,
    laneConfig(definition, config, lane),
    kinds,
    filterContext,
    target,
  );
  const answer: PagedResult<RecordData> | CursorPage<RecordData> =
    'cursor' in target
      ? await source.cursor(query, { signal: controller.signal })
      : await source.paged(query, { signal: controller.signal });
  return { lane, page: answer, index: 'index' in target ? target.index : 1 };
}
