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

import { EventStreamQueryClient, SnapshotQueryClient } from '@ahoo-wang/fetcher-wow';
import {
  CompensationPrepared,
  ExecutionFailedAggregatedFields, ExecutionFailedApplied,
  ExecutionFailedCreated,
  ExecutionFailedState, ExecutionSuccessApplied, FunctionChanged, RecoverableMarked, RetrySpecApplied,
} from './types.ts';
import { ApiMetadata } from '@ahoo-wang/fetcher-decorator';
import { AGGREGATE } from './aggregate.ts';
import { ResourceAttributionPathSpec } from '@ahoo-wang/fetcher-wow/dist/types/endpoints.ts';

export const DEFAULT_BASE_PATH = AGGREGATE.aggregateName;
export const TENANT_BASE_PATH = `${ResourceAttributionPathSpec.TENANT}/${DEFAULT_BASE_PATH}`;
export const OWNER_BASE_PATH = `${ResourceAttributionPathSpec.OWNER}/${DEFAULT_BASE_PATH}`;
export const TENANT_OWNER_BASE_PATH = `${ResourceAttributionPathSpec.TENANT_OWNER}/${DEFAULT_BASE_PATH}`;

export function createExecutionFailedSnapshotQueryClient(apiMetadata: ApiMetadata = { basePath: TENANT_BASE_PATH }): SnapshotQueryClient<ExecutionFailedState, ExecutionFailedAggregatedFields | string> {
  return new SnapshotQueryClient(apiMetadata);
}

export type DomainEventTypes = CompensationPrepared |
  ExecutionFailedApplied | ExecutionFailedCreated | ExecutionSuccessApplied
  | FunctionChanged | RecoverableMarked | RetrySpecApplied
  ;

export function createExecutionFailedEventQueryClient(apiMetadata: ApiMetadata = { basePath: TENANT_BASE_PATH }): EventStreamQueryClient<DomainEventTypes> {
  return new EventStreamQueryClient(apiMetadata);
}