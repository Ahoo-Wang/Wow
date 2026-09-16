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
import type { FieldOption, RecordData } from '../model/index.js';
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

export interface ProjectedRecord {
  kind: 'record';
  view: RecordView;
  /** Absent when the config asked for no summaries, or their query failed. */
  summaries: SummaryRow | null;
}

export interface ProjectedAnalysis {
  kind: 'analysis';
  view: AnalysisView;
}
