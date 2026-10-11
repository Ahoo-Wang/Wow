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

import {
  nearestPageSize,
  type RecordLayout,
  type RecordViewConfig,
  type RuntimeLimits,
} from '../model/index.js';
import { defaultKanbanSpec } from '../record/index.js';
import type { RecordViewRuntime } from '../runtime/index.js';
import { pendingBesides } from '../runtime/pending.js';

/**
 * The page-size ladder a layout offers from (`RuntimeLimits`): cards are
 * whole rows at every rung; a table and a kanban's lanes, whose cards stand
 * one under another, take the product's ladder.
 */
export function ladderOf(
  limits: RuntimeLimits | undefined,
  layout: RecordLayout,
): readonly number[] {
  if (!limits) return [];
  return layout === 'card' ? limits.cardPageSizes : limits.pageSizes;
}

/**
 * Switches a record view's layout (`RecordTableController.setLayout`).
 *
 * The page size moves to the nearest rung of the new layout's ladder in the
 * same edit: a page of cards is whole rows, a page of a table is the
 * product's ladder, and the two meet at their nearest (20 rows come back as
 * 24 cards and go back as 20). A size already on the ladder stays. A new
 * size is a new query, so it runs — and so does a switch into or out of the
 * kanban, which asks one question per lane (D83); switching into one whose
 * half the draft lacks writes the definition's default in the same edit.
 * `editAndApply` is the controller's one write that edits and runs while
 * nothing else waits.
 */
export function switchLayout(
  runtime: RecordViewRuntime,
  layout: RecordLayout,
  editAndApply: (patch: Partial<RecordViewConfig>) => void,
): void {
  const { draft } = runtime.getSnapshot();
  const current = draft.pageSize;
  const size = nearestPageSize(
    ladderOf(runtime.limits, layout).filter(
      rung => rung <= runtime.limits.maxPageSize,
    ),
    current,
  );
  const kanban =
    layout === 'kanban' && !draft.kanban && runtime.definition.kind === 'data'
      ? defaultKanbanSpec(runtime.definition)
      : undefined;
  const crosses = (layout === 'kanban') !== (draft.layout === 'kanban');
  if (size !== current || crosses) {
    editAndApply({
      layout,
      ...(size !== current ? { pageSize: size } : {}),
      ...(kanban ? { kanban } : {}),
    });
    return;
  }
  runtime.edit({ layout });
  // Table and cards draw the same result, so switching between them
  // normally needs no query. A view whose saved layout the definition no
  // longer allows has no result at all, though: `apply` was refused on
  // open, `refresh` is a no-op until something has been admitted, and the
  // switch that repairs it would otherwise leave the screen as empty as it
  // found it. Under the same rule as every other write here: not while
  // another edit waits for Apply.
  const state = runtime.getSnapshot();
  if (
    state.result === null &&
    state.query.status === 'idle' &&
    !pendingBesides(state, {})
  )
    runtime.apply();
}
