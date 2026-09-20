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
  AggregationQuery,
  CursorPage,
  CursorQuery,
  FilterPagedQuery,
  PagedList,
} from '@ahoo-wang/fetcher-wow';
import type { FieldOption, Issue, RecordData } from '../model/index.js';
import type { AnalysisView } from '../analysis/index.js';
import type { RecordView, SummaryRow } from '../record/index.js';

/**
 * Where a definition's data comes from. Three of `QueryApi`'s methods, so a
 * Wow snapshot client satisfies it as it is; anything else can be written by
 * hand for a test or for a non-Wow backend.
 *
 * Cancellation is an `AbortController` rather than a signal because that is
 * what `QueryApi` accepts.
 */
export interface ViewSource {
  paged(
    query: FilterPagedQuery,
    attributes?: Record<string, unknown>,
    abortController?: AbortController,
  ): Promise<PagedList<RecordData>>;
  cursor(
    query: CursorQuery,
    attributes?: Record<string, unknown>,
    abortController?: AbortController,
  ): Promise<CursorPage<RecordData>>;
  aggregate(
    query: AggregationQuery,
    attributes?: Record<string, unknown>,
    abortController?: AbortController,
  ): Promise<RecordData[]>;
}

/**
 * Remote candidates of a `reference` field: a paged search, and a lookup by id
 * for the values a saved config already holds.
 */
export interface OptionSource {
  search(
    input: { query: string; cursor?: string },
    signal?: AbortSignal,
  ): Promise<{ items: FieldOption[]; nextCursor: string | null }>;
  resolve(
    ids: (string | number)[],
    signal?: AbortSignal,
  ): Promise<FieldOption[]>;
}

/**
 * What one execution produced, already shaped by its kernel. The discriminant
 * equals the config's `kind`, so a renderer switches once and gets the view
 * type its layout needs.
 */
export type ProjectedView = ProjectedRecord | ProjectedAnalysis;

/**
 * What both kinds carry besides their view: what is wrong with *these
 * numbers*, as opposed to what is wrong with the config.
 *
 * `ViewRuntimeState.issues` cannot hold these. It is admission of the draft,
 * recomputed on every keystroke, so a finding put there would vanish the
 * moment the user touched the editor while the numbers it described were
 * still on screen. A finding about a result belongs to the result, and lives
 * exactly as long as it does: until the next successful execution replaces
 * it. The strips read it beside `state.issues` and cannot tell them apart,
 * which is the point — both are `warning`s the view has to say.
 */
export interface ProjectedBase {
  /** Empty when the numbers mean what they appear to mean. */
  issues: readonly Issue[];
}

export interface ProjectedRecord extends ProjectedBase {
  kind: 'record';
  view: RecordView;
  /**
   * Absent when the config asked for no summaries. Present with
   * `scope: 'page'` when the totals query failed and the visible rows were
   * added up instead — the numbers stay, `scope` says what they cover, and
   * `issues` carries the warning that says why.
   */
  summaries: SummaryRow | null;
}

export interface ProjectedAnalysis extends ProjectedBase {
  kind: 'analysis';
  view: AnalysisView;
}

/** The findings of the last result, or none while there is no result. */
export function resultIssues(
  data: ProjectedView | null | undefined,
): readonly Issue[] {
  return data?.issues ?? [];
}
