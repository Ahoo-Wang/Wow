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

import type { Issue, ViewConfig } from '../model/index.js';
import { comparePending } from './pending.js';
import { hasError } from './runtimeStore.js';

/**
 * 「改了就跑」 (D20; todo 批 7): an analysis whose question changed runs
 * again on its own, a moment after the last edit, so the analyst reads
 * the answer rather than pressing for it. Three things hold it:
 *
 * - the draft is refused — a question that cannot run is not run;
 * - the **range** changed — the conditions still wait for Apply (D20), and
 *   while they wait nothing else runs either, because Apply runs the whole
 *   draft and a half-applied draft would say two things at once;
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
  const report = comparePending(state.draft, state.applied, state.issues);
  return report.pending && !report.conditions;
}
