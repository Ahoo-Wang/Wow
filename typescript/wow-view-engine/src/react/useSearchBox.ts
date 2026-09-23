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

import { useCallback } from 'react';
import type { DataViewConfig, ViewRuntime } from '../runtime/index.js';
import { rootSearch, searchFieldOf, withRootSearch } from '../filter/index.js';
import type { FieldDefinition } from '../model/index.js';
import { useViewRuntime } from './useViewEngine.js';

/**
 * A view's search, kept on hand rather than behind the conditions editor.
 *
 * Searching an error used to take five steps — open the conditions, add
 * one, tick the search field, close the list, type — for the one condition
 * an operator reaches for first. When the definition declares a search
 * field (`kind: 'search'`), this is its box: the search condition on the
 * draft's root (`rootSearch`), edited in place (`withRootSearch`), the same
 * condition the editor shows as a pill and the applied bar reads back.
 */
export interface SearchBoxController {
  /** The definition's search field: its label names the box. */
  field: FieldDefinition;
  /** The search in the draft, as typed. */
  value: string;
  /** The search in force — what the rows on screen were found by. */
  applied: string;
  /** Edits the draft's search; blank takes the condition away. */
  set(text: string): void;
  /** Puts the draft in force — Enter in the box. */
  submit(): void;
  /** Takes the search away, and the rows found by it with it. */
  clear(): void;
}

export function useSearchBox(
  runtime: ViewRuntime<DataViewConfig> | null,
): SearchBoxController | null {
  const state = useViewRuntime(runtime);
  const set = useCallback(
    (text: string) => {
      if (runtime) searchInDraft(runtime, text);
    },
    [runtime],
  );
  const submit = useCallback(() => runtime?.apply(), [runtime]);
  const clear = useCallback(() => {
    if (!runtime) return;
    searchInDraft(runtime, '');
    runtime.apply();
  }, [runtime]);
  const field = runtime ? searchFieldOf(runtime.definition.fields) : null;
  if (!runtime || !state || !field) return null;
  return {
    field,
    value: rootSearch(state.draft.filter, field.name),
    applied: rootSearch(state.applied.filter, field.name),
    set,
    submit,
    clear,
  };
}

/** Writes `text` as the draft's root search, where the definition has one. */
function searchInDraft(runtime: ViewRuntime<DataViewConfig>, text: string) {
  const field = searchFieldOf(runtime.definition.fields);
  if (!field) return;
  const draft = runtime.getSnapshot().draft.filter;
  runtime.edit({ filter: withRootSearch(draft, field.name, text) });
}
