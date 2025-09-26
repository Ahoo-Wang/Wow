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

import { QueryClientFactory, QueryClientOptions } from '@ahoo-wang/fetcher-wow';
import {
  CompensationPrepared,
  ExecutionFailedAggregatedFields,
  ExecutionFailedApplied,
  ExecutionFailedCreated,
  ExecutionFailedState,
  ExecutionSuccessApplied,
  FunctionChanged,
  RecoverableMarked,
  RetrySpecApplied,
} from './types.ts';
import { ResourceAttributionPathSpec } from '@ahoo-wang/fetcher-wow/dist/types/endpoints.ts';

const DEFAULT_CLIENT_OPTIONS: QueryClientOptions = {
  contextAlias: 'compensation',
  aggregateName: 'execution_failed',
  resourceAttribution: ResourceAttributionPathSpec.TENANT,
};

type DomainEventTypes =
  | CompensationPrepared
  | ExecutionFailedApplied
  | ExecutionFailedCreated
  | ExecutionSuccessApplied
  | FunctionChanged
  | RecoverableMarked
  | RetrySpecApplied;

export const executionFailedQueryClientFactory = new QueryClientFactory<
  ExecutionFailedState,
  ExecutionFailedAggregatedFields | string,
  DomainEventTypes
>(DEFAULT_CLIENT_OPTIONS);
