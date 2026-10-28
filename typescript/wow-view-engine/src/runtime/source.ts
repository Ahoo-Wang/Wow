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
  DescriptorResult,
  PagedQuery,
  PagedResult,
} from '@ahoo-wang/wow-client/dsl';
import type { FieldOption, Issue, RecordData } from '../model/index.js';
import type { AnalysisProjection } from '../analysis/index.js';
import type { RecordProjection, SummaryRow } from '../record/index.js';

/** What every read of a source takes: the signal that cancels it. */
export interface SourceOptions {
  /**
   * Aborted when a newer read takes this one's place or the view that asked
   * is disposed; hand it on to the request.
   */
  readonly signal?: AbortSignal;
}

/**
 * Where a definition's data comes from: the reads of one Wow query route,
 * in wow-client's own shapes, so any `QueryModelClient` — a generated
 * `…SnapshotQueryClient`, a dataset's `DatasetClient.dataset(…)` — is a
 * source as it is, with no adapter (`test/architecture.test.ts` holds both
 * to it). A source written by hand, for a test or another backend, answers
 * the same shapes and hands `options.signal` on.
 *
 * Rows are `object`: a client's record type is an interface, which a
 * `Record<string, unknown>` would refuse, and the engine reads every value
 * through a field's path and kind anyway. A page's `total` is `null` when
 * the route counts none, and a lower bound unless `exact`: the record
 * runtime keeps both and says so.
 */
export interface ViewSource {
  paged(
    query: PagedQuery,
    options?: SourceOptions,
  ): Promise<PagedResult<object>>;
  cursor(
    query: CursorQuery,
    options?: SourceOptions,
  ): Promise<CursorPage<object>>;
  aggregate(
    query: AggregationQuery,
    options?: SourceOptions,
  ): Promise<object[]>;
  /**
   * The source's capability descriptor (capabilities.md 3): given the
   * version already held, an unchanged one answers `notModified`. The
   * engine reads it once per source before the first view over it runs,
   * narrows each definition to what it admits and takes its limits, and
   * checks it again on its own schedule. Left out, a view runs on the
   * definition and the default limits alone.
   */
  describe?(
    version?: string,
    options?: SourceOptions,
  ): Promise<DescriptorResult>;
}

/**
 * A source as the engine reads it: the same reads, their rows as records.
 * Internal: `resources` takes each `ViewSource` once, at registration, and
 * every read inside the engine goes through this.
 */
export interface RecordSource {
  paged(
    query: PagedQuery,
    options?: SourceOptions,
  ): Promise<PagedResult<RecordData>>;
  cursor(
    query: CursorQuery,
    options?: SourceOptions,
  ): Promise<CursorPage<RecordData>>;
  aggregate(
    query: AggregationQuery,
    options?: SourceOptions,
  ): Promise<RecordData[]>;
  describe?(
    version?: string,
    options?: SourceOptions,
  ): Promise<DescriptorResult>;
}

/**
 * The engine's view of a source: its rows are read as records, through
 * fields, so the one cast the port's `object` rows need is made here.
 */
export function recordSource(source: ViewSource): RecordSource {
  return source as RecordSource;
}

/**
 * Remote candidates of a `reference` field: a paged search, and a lookup by id
 * that says the values rows and saved configs hold as their labels.
 */
export interface OptionSource {
  search(
    input: { query: string; cursor?: string },
    options?: SourceOptions,
  ): Promise<{ items: FieldOption[]; nextCursor: string | null }>;
  /**
   * The options of `ids`, matched to them by `value`. The engine asks once
   * per page for the ids it does not know yet, at most
   * `RuntimeLimits.referenceBatch` at a time; an id the answer leaves out is
   * shown as itself.
   */
  resolve(
    ids: (string | number)[],
    options?: SourceOptions,
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
  view: RecordProjection;
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
  view: AnalysisProjection;
}

/** The findings of the last result, or none while there is no result. */
export function resultIssues(
  data: ProjectedView | null | undefined,
): readonly Issue[] {
  return data?.issues ?? [];
}
