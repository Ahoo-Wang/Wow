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

import type { FilterTree, ViewConfig } from '../../model/index.js';
import type { ViewRuntimeState } from '../../runtime/index.js';
import { conditionsDrifted } from '../../runtime/savedConditions.js';

/**
 * The one way out of a query that matched nothing, by what was asked.
 *
 * - `restore` — a saved view whose conditions were added to: the rows are
 *   missing because of what the reader added, so the way out is back to
 *   the conditions the view was saved with, not to none. Clearing them
 *   would turn 「不可恢复」 into 「全部」 — another view under its name.
 * - `edit` — a saved view asking exactly what it was saved to ask: the view
 *   is empty right now, which is an answer. Its conditions are what it is,
 *   so nothing is taken away; the way on is to ask something else.
 * - `clear` — a view never saved, under conditions: nothing to go back to,
 *   so the conditions go and what there is shows.
 * - `add` — no conditions at all: everything there is was shown, and the
 *   only thing left is a question.
 */
export type EmptyWayOut = 'restore' | 'edit' | 'clear' | 'add';

export function emptyWayOut(
  /** `conditionsDrifted`: `null` for a view never saved. */
  drifted: boolean | null,
  hasConditions: boolean,
): EmptyWayOut {
  if (drifted === null) return hasConditions ? 'clear' : 'add';
  if (drifted) return 'restore';
  return hasConditions ? 'edit' : 'add';
}

/** What `wayOutOf` reads and acts on; a record view and an analysis alike. */
export interface WayOutTarget {
  /** The open view's state: what is in force, and what it was saved as. */
  state: Pick<ViewRuntimeState<ViewConfig>, 'applied' | 'saved'> | null;
  /** Whether conditions of the view's own are in force on the rows. */
  hasConditions: boolean;
  /** Takes every condition out of the draft; `submit` then asks again. */
  filter: { clear(): void; submit(): void };
  /** The open view, to put the saved conditions back in force. */
  runtime: { edit(patch: { filter: FilterTree }): void; apply(): void } | null;
  /** Opens the editor the question is changed in. */
  openEditor(): void;
}

/**
 * The way out of an empty result, and the press that takes it.
 *
 * The two data views share the rule and the press: the record view's empty
 * result and the analysis's say different sentences over them, and nothing
 * else. Clearing asks again — `clear` alone would leave the rows on screen
 * fetched under the conditions the button just took away; going back puts
 * the saved conditions in force and nothing else of the draft (`restore` is
 * only ever chosen when the saved config is of the open view's kind,
 * `conditionsDrifted`); and asking something else opens the editor, because
 * the question to change is behind a fold that may not even be on screen.
 */
export function wayOutOf({
  state,
  hasConditions,
  filter,
  runtime,
  openEditor,
}: WayOutTarget): { wayOut: EmptyWayOut; take(): void } {
  const wayOut = emptyWayOut(
    state ? conditionsDrifted(state.applied, state.saved) : null,
    hasConditions,
  );
  const saved = state?.saved?.config;
  const take = () => {
    if (wayOut === 'clear') {
      filter.clear();
      filter.submit();
      return;
    }
    if (
      wayOut === 'restore' &&
      saved?.kind !== 'dashboard' &&
      saved &&
      runtime
    ) {
      runtime.edit({ filter: saved.filter });
      runtime.apply();
      return;
    }
    openEditor();
  };
  return { wayOut, take };
}
