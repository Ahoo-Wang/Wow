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

/**
 * The refusals a command makes of its own input before anything is sent —
 * nothing here asks the store or a permission.
 */

import {
  configBytes,
  MAX_VIEW_CONFIG_BYTES,
  MAX_VIEW_TITLE_LENGTH,
  titleProblem,
  type Issue,
  type ViewConfig,
  type ViewScope,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import { stopsSave } from './dashboardRuntime.js';
import { comparePending } from './pending.js';
import { hasError } from './runtimeStore.js';
import type {
  ManagedViewRuntime,
  ViewRuntimeState,
} from './viewRuntimeTypes.js';
import { ViewCommandError } from './write.js';

/**
 * A view needs a title, and the store keeps one of at most
 * `MAX_VIEW_TITLE_LENGTH` (`view.title.empty`, `view.title.too-long`): the
 * engine refuses it here rather than send what the store refuses. Answers
 * the title as it is stored, trimmed.
 */
export function requireTitle(title: string): string {
  switch (titleProblem(title)) {
    case 'empty':
      throw new ViewCommandError(issue('view.title.empty', ['title']));
    case 'too-long':
      throw new ViewCommandError(
        issue('view.title.too-long', ['title'], { max: MAX_VIEW_TITLE_LENGTH }),
      );
    default:
      return title.trim();
  }
}

/**
 * The draft as a runtime writes it to the store (`stored`, where its kind
 * stores less than it reads), refused when the store would not keep it.
 */
export function storable(
  runtime: ManagedViewRuntime,
  draft: ViewConfig,
): ViewConfig {
  return requireStorable(runtime.stored?.(draft) ?? draft);
}

/**
 * A config the store keeps: at most `MAX_VIEW_CONFIG_BYTES` of JSON
 * (`view.config.too-large`). A board of many panels is what reaches it.
 */
export function requireStorable<C extends ViewConfig>(config: C): C {
  const bytes = configBytes(config);
  if (bytes > MAX_VIEW_CONFIG_BYTES)
    throw new ViewCommandError(
      issue('view.config.too-large', [], {
        size: Math.ceil(bytes / 1024),
        max: MAX_VIEW_CONFIG_BYTES / 1024,
      }),
    );
  return config;
}

/** What stops a save of this runtime's kind (`stopsSave`). */
function requireSavable(
  runtime: ManagedViewRuntime,
  issues: readonly Issue[],
): void {
  if (stopsSave(runtime.kind, issues))
    throw new ViewCommandError(issue('view.config.invalid', []));
}

/**
 * The state a save writes from: the draft, **run first** (D94, audit F2).
 *
 * A save wrote the draft whatever had run, so a view could be saved with
 * conditions or a sort its author never saw the rows of, and opened on them
 * the next time — or on a failure, where the source refuses them. Now a
 * save writes a draft the source has answered:
 *
 * - what waits for apply is applied;
 * - what was applied and failed is asked again (after a failed apply
 *   nothing waits any more, and a second press of Save must not write
 *   what the first refused);
 * - a query already out is waited for;
 * - and a draft whose query ends in failure is not saved
 *   (`view.save.not-applied`): the failure is on the result, where a failed
 *   apply always is, and the draft stays as it was.
 *
 * Left as they are: a board (its panels run on their own, `PanelSync`), and
 * a draft with an error, which `apply` would not run either and the save's
 * own check refuses by name. A draft with nothing waiting and an answer on
 * screen asks the source nothing more.
 */
async function appliedDraft<C extends ViewConfig>(
  runtime: ManagedViewRuntime<C>,
): Promise<ViewRuntimeState<C>> {
  const state = runtime.getSnapshot();
  if (runtime.kind === 'dashboard' || hasError(state.issues)) return state;
  if (comparePending(state.draft, state.applied, state.issues).pending)
    runtime.apply();
  else if (state.query.status === 'error') runtime.refresh();
  const answered = await new Promise<ViewRuntimeState<C>>(resolve => {
    const read = () => {
      const now = runtime.getSnapshot();
      if (now.query.status === 'loading') return false;
      resolve(now);
      return true;
    };
    if (read()) return;
    const stop = runtime.subscribe(() => {
      if (read()) stop();
    });
  });
  if (answered.query.status === 'error')
    throw new ViewCommandError(issue('view.save.not-applied', []));
  return answered;
}

/**
 * The state a save or a copy writes from, or the refusal: what waits is run
 * first (`appliedDraft`), then the config is judged for the audience it is
 * headed for — the view's own, or the one a copy names.
 */
export async function savableState<C extends ViewConfig>(
  runtime: ManagedViewRuntime<C>,
  scope?: ViewScope,
): Promise<ViewRuntimeState<C>> {
  const state = await appliedDraft(runtime);
  requireSavable(runtime, runtime.issuesAt(scope ?? state.scope));
  return state;
}
