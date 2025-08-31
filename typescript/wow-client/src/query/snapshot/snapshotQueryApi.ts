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

import { QueryApi } from '../queryApi';
import { MaterializedSnapshot } from './snapshot';
import { ListQuery, PagedList, PagedQuery, SingleQuery } from '../queryable';
import { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';

export interface SnapshotQueryApi<S> extends QueryApi<MaterializedSnapshot<S>> {
  singleState(singleQuery: SingleQuery): Promise<Partial<S>>;

  listState(listQuery: ListQuery): Promise<Partial<S>[]>;

  listStateStream(listQuery: ListQuery): Promise<ReadableStream<JsonServerSentEvent<Partial<S>>>>;

  pagedState(pagedQuery: PagedQuery): Promise<PagedList<Partial<S>>>;
}

/**
 * Provides endpoint paths for snapshot query operations.
 *
 * This class contains static readonly properties that define the endpoint paths used for various snapshot query operations.
 * These paths are used when making API calls to retrieve snapshot data in different formats such as counts, lists, paged results, and single items.
 * The paths are constructed based on a base resource name and extended with specific operation identifiers.
 */
export class SnapshotQueryEndpointPaths {
  static readonly SNAPSHOT_RESOURCE_NAME = 'snapshot';
  static readonly COUNT = `${SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME}/count`;
  static readonly LIST = `${SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME}/list`;
  static readonly LIST_STATE = `${SnapshotQueryEndpointPaths.LIST}/state`;
  static readonly PAGED = `${SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME}/paged`;
  static readonly PAGED_STATE = `${SnapshotQueryEndpointPaths.PAGED}/state`;
  static readonly SINGLE = `${SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME}/single`;
  static readonly SINGLE_STATE = `${SnapshotQueryEndpointPaths.SINGLE}/state`;
}
