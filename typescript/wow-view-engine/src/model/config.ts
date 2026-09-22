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

import {
  ANALYSIS_AUTO_RUN_MEMBERS,
  ANALYSIS_PRESENTATION_MEMBERS,
  type AnalysisViewConfig,
} from './analysis.js';
import {
  DASHBOARD_PRESENTATION_MEMBERS,
  type DashboardViewConfig,
} from './dashboard.js';
import type { FilterMode, FilterTree } from './filter.js';
import {
  RECORD_PRESENTATION_MEMBERS,
  type RecordViewConfig,
} from './record.js';

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

/**
 * The presentation-only members every kind shares.
 *
 * A presentation member draws the result that already came back and never
 * reaches the query, so a draft that differs from the applied config only
 * there is not "changed, not applied": there is nothing left to apply, and a
 * dot asking for an Apply with nothing to run teaches the user to press
 * buttons that change nothing (`runtime/pending.ts`).
 *
 * The editor's simple/advanced mode is the one every kind has: it decides
 * what the condition builder can show, and the tree it submits is the same
 * tree either way.
 */
const BASE_PRESENTATION_MEMBERS = [
  'filterMode',
] as const satisfies readonly (keyof ViewConfigBase)[];

const BY_KIND: Record<ViewKind, readonly string[]> = {
  record: [...BASE_PRESENTATION_MEMBERS, ...RECORD_PRESENTATION_MEMBERS],
  analysis: [...BASE_PRESENTATION_MEMBERS, ...ANALYSIS_PRESENTATION_MEMBERS],
  dashboard: [...BASE_PRESENTATION_MEMBERS, ...DASHBOARD_PRESENTATION_MEMBERS],
};

/**
 * Which members of one kind's config are presentation only — declared beside
 * the type each is a member of rather than string-compared where they are
 * read, so a `satisfies` refuses a member the config does not have (A6).
 *
 * A config arrives from a store, so its `kind` may be none of the three; the
 * members every kind shares hold all the same.
 */
export function presentationMembers(kind: ViewKind): readonly string[] {
  return BY_KIND[kind] ?? BASE_PRESENTATION_MEMBERS;
}

const AUTO_RUN_BY_KIND: Record<ViewKind, readonly string[]> = {
  record: [],
  analysis: ANALYSIS_AUTO_RUN_MEMBERS,
  dashboard: [],
};

/**
 * Which members of one kind's config run again on their own a moment after
 * they change (「改了就跑」, D20) — declared beside the type each is a member
 * of, like `presentationMembers`, so the runtime carries no rule of any one
 * kind and a kind that runs nothing on its own declares nothing.
 *
 * Only an analysis has any: its question. A record view's edits — a sort, a
 * page size — are cheap to apply and wait for the press as they always did;
 * a dashboard edits its layout, not a question.
 */
export function autoRunMembers(kind: ViewKind): readonly string[] {
  return AUTO_RUN_BY_KIND[kind] ?? [];
}
