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

import type { AnalysisViewConfig } from './analysis.js';
import type { DashboardViewConfig } from './dashboard.js';
import type { FilterMode, FilterTree } from './filter.js';
import type { RecordViewConfig } from './record.js';

/**
 * What every view kind stores. These three belong to the view rather than to
 * the person looking at it, so they are saved with the config.
 */
export interface ViewConfigBase {
  /** In a dashboard this applies to every data panel. */
  filter: FilterTree;
  /** The editor mode travels with the view: `simple` cannot show an OR tree. */
  filterMode: FilterMode;
  refresh: RefreshConfig;
}

/**
 * Auto-refresh interval in seconds, or `null` when off. A non-null value is a
 * finite integer between `minRefreshInterval` and `maxRefreshInterval`.
 */
export interface RefreshConfig {
  interval: number | null;
}

/** What a user saves: the way of observing, never a data snapshot. */
export type ViewConfig =
  RecordViewConfig | AnalysisViewConfig | DashboardViewConfig;

export type ViewKind = ViewConfig['kind'];

export const VIEW_KINDS: readonly ViewKind[] = [
  'record',
  'analysis',
  'dashboard',
];

/** Narrows a config union member by its kind. */
export type ConfigOfKind<K extends ViewKind> = Extract<ViewConfig, { kind: K }>;
