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

import type { FilterTree } from '../model/index.js';
import { outOfDate } from '../runtime/autoApply.js';
import { useViewRuntime, type ViewRuntimeStore } from './useViewRuntime.js';

/** What the reading takes of a runtime: its state and the scope beside it. */
type Asking = ViewRuntimeStore<Parameters<typeof outOfDate>[0]> & {
  readonly scopeFilter: FilterTree | null;
  /** A Record view's alone: another page than the one on screen is on its way. */
  readonly turningPage?: boolean;
};

/**
 * Whether the rows a view has on screen answer an older question than the
 * one on its way (`outOfDate`): its own config applied anew, or the scope
 * injected beside it moved — a board's filter under a panel — and the
 * answer has not landed — or another page of the same question is on its
 * way (`RecordRuntime.turningPage`): the rows on screen are not the ones
 * asked for either. A surface keeps the rows and fades them
 * (`ui/kit/stale.ts`).
 *
 * Not a refresh of the same question: its rows are the answer to what is
 * being asked.
 */
export function useStale(runtime: Asking | null | undefined): boolean {
  const state = useViewRuntime(runtime ?? null);
  if (!state || !runtime) return false;
  return outOfDate(state, runtime.scopeFilter) || runtime.turningPage === true;
}
