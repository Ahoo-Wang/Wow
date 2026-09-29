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
  DashboardViewConfig,
  Issue,
  ViewInstance,
  ViewScope,
} from '../../model/index.js';
import type { FieldKindRegistry } from '../../filter/index.js';
import { admitFilters } from '../../dashboard/index.js';
import type { ViewQueryState } from '../viewRuntimeTypes.js';
import type { DashboardRuntimeState } from './contract.js';
import { NO_HISTORY } from './history.js';
import { shownTab } from './panels.js';

const IDLE: ViewQueryState = { status: 'idle' };

/**
 * A board's state as it opens: the config as draft and applied alike, its
 * first tab, its filters at their defaults, nothing asked yet, nothing being
 * built — and dirty only when it was never saved.
 */
export function openingState(opening: {
  saved: ViewInstance | null;
  title: string;
  scope: ViewScope;
  config: DashboardViewConfig;
  issues: Issue[];
  kinds: FieldKindRegistry;
}): DashboardRuntimeState {
  const { saved, title, scope, config, issues, kinds } = opening;
  return {
    saved,
    title,
    scope,
    draft: config,
    applied: config,
    issues,
    dirty: saved === null,
    query: IDLE,
    result: null,
    selection: [],
    write: null,
    editing: false,
    autoApply: false,
    nextRefreshAt: null,
    panels: [],
    resolving: false,
    tab: shownTab(config, null),
    filters: admitFilters(config, null, kinds).filters,
    history: NO_HISTORY,
    building: false,
    readerRefresh: null,
    filtersRun: 0,
  };
}
