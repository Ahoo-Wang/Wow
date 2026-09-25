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
  QueryDescriptorResult,
} from '@ahoo-wang/wow-client';
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
 *
 * Written out rather than `Pick<QueryApi, 'paged' | 'cursor' | 'aggregate'>`,
 * for three reasons that all point the same way — this is the one type every
 * source implements, and it must say exactly what this package needs:
 *
 * - `QueryApi.paged` takes `PagedQueryRequest`, which is
 *   `FilterPagedQuery | PagedQuery`, and `PagedQuery` is deprecated. Picking
 *   it would write the deprecated Wow API into the port that every source
 *   fills, which `test/architecture.test.ts` bans everywhere else;
 * - `QueryApi.aggregate` answers whatever row type its caller asserts
 *   (`Row extends object`), and an asserted row is a row nothing checks.
 *   Here it is `RecordData`, so every value arrives as `unknown` and is read
 *   through a field's kind;
 * - the pick would spread `QueryApi`'s two type parameters and its per-method
 *   generics over every implementation, a test's stub included, for no gain:
 *   all three methods are used at one instantiation only.
 *
 * What the pick would have bought — noticing upstream drift — is bought
 * instead by an assignability assertion the compiler checks
 * (`test/architecture.test.ts`, "Wow protocol"): a `QueryApi` must go on
 * being a `ViewSource` without an adapter.
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
  /**
   * The source's capability descriptor (capabilities.md 3): given the
   * version already held, an unchanged one answers `notModified`.
   * wow-client's `describeSnapshot` or `describeEventStream` fits it as it
   * is. The engine reads it once per source before the first view over it
   * runs, narrows each definition to what it admits and takes its limits,
   * and checks it again on its own schedule. Left out, a view runs on the
   * definition and the default limits alone, as it did before descriptors.
   */
  describe?(
    previous?: string,
    attributes?: Record<string, unknown>,
    abortController?: AbortController,
  ): Promise<QueryDescriptorResult>;
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
