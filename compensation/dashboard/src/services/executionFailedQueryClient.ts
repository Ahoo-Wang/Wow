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

import { executionFailedQueryClientFactory } from "../generated";
import type {
  AggregationQuery,
  DynamicDocument,
  FilterPagedQuery,
  FilterSingleQuery,
  QueryClientOptions,
} from "@ahoo-wang/fetcher-wow";
import { ExecutionFailedAggregatedFields } from "../generated";

const executionFailedQueryClientOptions: QueryClientOptions = {
  contextAlias: "",
};

const executionFailedSnapshotQueryClient =
  executionFailedQueryClientFactory.createSnapshotQueryClient(
    executionFailedQueryClientOptions,
  );

export function queryExecutionFailedPage(
  query: FilterPagedQuery,
  attributes?: Record<string, unknown>,
  abortController?: AbortController,
) {
  return executionFailedSnapshotQueryClient.pagedState(
    query,
    attributes,
    abortController,
  );
}

export function queryExecutionFailedState(
  query: FilterSingleQuery,
  attributes?: Record<string, unknown>,
  abortController?: AbortController,
) {
  return executionFailedSnapshotQueryClient.singleState(
    query,
    attributes,
    abortController,
  );
}

export function aggregateExecutionFailedSnapshots<Row extends DynamicDocument>(
  query: AggregationQuery<ExecutionFailedAggregatedFields>,
  attributes?: Record<string, unknown>,
  abortController?: AbortController,
) {
  return executionFailedSnapshotQueryClient.aggregate<Row>(
    query,
    attributes,
    abortController,
  );
}
