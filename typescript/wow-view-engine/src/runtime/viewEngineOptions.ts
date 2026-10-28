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

import type {
  Issue,
  RuntimeLimits,
  ViewDefinition,
  ViewInstanceSummary,
} from '../model/index.js';
import type { FieldKindRegistry } from '../filter/index.js';
import type { ViewStore } from '../store/ViewStore.js';
import type { RuntimeEnvironment } from './environment.js';
import type { DatasetWordingEditor } from './datasetResources.js';
import type { OptionSource, ViewSource } from './source.js';

/**
 * One thing a host registers (host-integration.md 4): a definition, and
 * where its data comes from. A board's definition has no source — it
 * queries nothing of its own.
 */
export interface ViewResource {
  definition: ViewDefinition;
  /** Where a data definition's rows come from; a board has none. */
  source?: ViewSource;
  /**
   * The editor of a dataset's wording, which the view manager's wording
   * tab reads and saves (`ViewEngine.wordingOf`). `DatasetResources` sets
   * it when its store keeps wording; a host registering a resource of its
   * own leaves it out.
   */
  wording?: DatasetWordingEditor;
}

export interface ViewEngineOptions {
  /** What the host registers: each definition, with its data's source. */
  resources: readonly ViewResource[];
  store: ViewStore;
  /** Remote candidates of `reference` fields, by the same key. */
  resolveOptions?(key: string): OptionSource;
  kinds?: FieldKindRegistry;
  /**
   * How the definitions' keys (`text(key)`) are said in the language the
   * engine starts in, for a host with no Provider; a Provider says them in
   * its own (`setText`). Keys stay in the definitions and in every
   * runtime's state, and are read into words as the engine hands them out,
   * so a change of language redraws what is open rather than rebuilding
   * anything (host-integration.md 3.1, D2). Left out, a key reads as
   * itself until a language is set.
   */
  text?(key: string): string | undefined;
  /**
   * The host's budgets over `DEFAULT_RUNTIME_LIMITS`: what is left out keeps
   * its default. The source budgets (`maxPageSize`, `maxPageWindow`,
   * `maxAnalysisRows`, `maxQueryFilterNodes`, `maxFilterValues`) are said by a source's descriptor
   * where it has one, and one given here only lowers it — so leave them out
   * unless the engine should ask for less than the server admits
   * (capabilities.md 4.5).
   */
  limits?: Partial<RuntimeLimits>;
  environment?: RuntimeEnvironment;
  /** Idempotency keys; overridden in tests to keep them readable. */
  newId?(): string;
  /**
   * Findings with no caller to reject and nothing thrown behind them: a
   * definition's admission, a list entry that was dropped, a change
   * listener that threw. Failures — a query, a store call, an export, a
   * render — go to `environment.onError` instead (D40).
   *
   * Left out, a development build (`NODE_ENV` of `development`) writes them
   * to the console, grouped by resource, each with how to fix it; any other
   * build drops them.
   */
  onIssue?(issue: Issue): void;
}

/**
 * What a definition's list is right now: every view that could be read, and
 * the reason the rest could not. The declared system views are always in
 * `items`; `failed` names the store's failure when the saved ones are not.
 */
export interface ViewListing {
  items: ViewInstanceSummary[];
  failed: Issue | null;
  /**
   * Where the list was cut (`RuntimeLimits.listedViews`): this many were
   * read, and there may be more. Absent on a whole list (R2-26).
   */
  cut?: number;
}
