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
  autoRunMembers,
  presentationMembers,
  type FilterTree,
  type Issue,
  type ViewConfig,
} from '../model/index.js';
import { hasError } from './runtimeStore.js';
import { withScopeFilter } from './scope.js';

/**
 * 「改了就跑」 (todo 批 7): a question that changed runs again on its
 * own, a moment after the last edit, so the analyst reads the answer rather
 * than pressing for it. Which members are the question is the model's to
 * say, beside the types they are members of (`autoRunMembers`): a kind with
 * none declared never runs on its own, and the runtime carries no rule of
 * any one kind. Three things hold a draft back:
 *
 * - the draft is refused — a question that cannot run is not run;
 * - a member outside the question changed — the **range** above all, whose
 *   conditions still wait for Apply (D20); and while they wait nothing else
 *   runs either, because Apply runs the whole draft and a half-applied draft
 *   would say two things at once;
 * - the view has it switched off (`ViewPreferences.autoRun`), in which case
 *   Apply is the one way to run.
 *
 * The delay merges a burst of edits — a dimension added and its granularity
 * changed — into one query.
 */
export const AUTO_APPLY_DELAY_MS = 300;

interface AutoApplyState {
  draft: ViewConfig;
  applied: ViewConfig;
  issues: readonly Issue[];
  autoApply: boolean;
}

/** Whether the draft is one the runtime should run on its own now. */
export function autoApplyDue(state: AutoApplyState): boolean {
  if (!state.autoApply || hasError(state.issues)) return false;
  const changed = changedMembers(state.draft, state.applied);
  if (changed.length === 0) return false;
  const question = autoRunMembers(state.draft.kind);
  return changed.every(member => question.includes(member));
}

/**
 * Whether the rows on screen answer an older question than the one on its
 * way: a query is in flight and what it asks differs from what the result
 * answered, in a member that does more than draw it.
 *
 * The other half of a result that is out of date. `autoApplyDue` covers the
 * moment before the question is sent — the debounce — and went false the
 * moment it was, so a slow answer left the old rows standing at full
 * strength as though they were the new ones (2026-09-23 audit). A refresh of
 * the same question is not this: its rows are the answer to what is being
 * asked, and they stay as they are until the new ones land.
 *
 * `scope` is the condition injected beside the view's own
 * (`ViewRuntime.scopeFilter`): a board's filter reaches its panels that
 * way, and changes what a panel asks without changing what it applied.
 * Given — `null` for none in force — the conditions on their way are read
 * with it, against the conditions the rows ran under (`result.config`), so
 * a panel whose board filter moved is out of date like any other. Left out,
 * only the view's own config is read.
 */
export function answeringAnew(
  state: {
    applied: ViewConfig;
    query: { status: string };
    result: { own: ViewConfig; config?: ViewConfig } | null;
  },
  scope?: FilterTree | null,
): boolean {
  if (state.query.status !== 'loading' || state.result === null) return false;
  if (changedMembers(state.applied, state.result.own).length > 0) return true;
  return scope !== undefined && scopeMoved(state.applied, state.result, scope);
}

/**
 * Both halves of a result that is out of date: the draft is about to run
 * on its own, or what was applied — under `scope`, where one is given — is
 * on its way.
 */
export function outOfDate(
  state: AutoApplyState & Parameters<typeof answeringAnew>[0],
  scope?: FilterTree | null,
): boolean {
  return autoApplyDue(state) || answeringAnew(state, scope);
}

/**
 * Whether the conditions on their way — the applied ones under `scope` —
 * are other than the ones the rows on screen ran under. A dashboard has no
 * conditions of its own to run, and a result that does not say what it ran
 * under is taken at its word.
 */
function scopeMoved(
  applied: ViewConfig,
  result: { config?: ViewConfig },
  scope: FilterTree | null,
): boolean {
  const ran = result.config;
  if (!ran || applied.kind === 'dashboard' || ran.kind === 'dashboard')
    return false;
  return !dequal(withScopeFilter(applied, scope).filter, ran.filter);
}

/**
 * The members the draft says differently from the applied config, leaving
 * out the ones that only draw the result (`presentationMembers`): those
 * redraw without asking the source, so they neither run nor hold a run.
 */
function changedMembers(draft: ViewConfig, applied: ViewConfig): string[] {
  const was: Record<string, unknown> = { ...applied };
  const now: Record<string, unknown> = { ...draft };
  const presentation = presentationMembers(draft.kind);
  return [...new Set([...Object.keys(now), ...Object.keys(was)])].filter(
    member =>
      !presentation.includes(member) && !dequal(now[member], was[member]),
  );
}
