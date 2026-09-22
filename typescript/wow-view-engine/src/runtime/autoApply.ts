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
  type Issue,
  type ViewConfig,
} from '../model/index.js';
import { hasError } from './runtimeStore.js';

/**
 * 「改了就跑」 (D20; todo 批 7): a question that changed runs again on its
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

export interface AutoApplyState {
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
