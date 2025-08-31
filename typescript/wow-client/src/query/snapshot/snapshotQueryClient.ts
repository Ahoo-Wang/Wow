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

import { SnapshotQueryApi, SnapshotQueryEndpointPaths } from './snapshotQueryApi';
import { Condition } from '../condition';
import { ListQuery, PagedList, PagedQuery, SingleQuery } from '../queryable';
import { MaterializedSnapshot } from './snapshot';
import { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import { combineURLs, Fetcher, HttpMethod } from '@ahoo-wang/fetcher';
import { PathParams } from '../../types/endpoints';
import { ResultExtractor, ResultExtractors } from '@ahoo-wang/fetcher-decorator';
import '@ahoo-wang/fetcher-eventstream';

export interface SnapshotQueryOptions {
  basePath: string;
  pathParams: Omit<PathParams, 'id'>;
  fetcher: Fetcher;
}

export class SnapshotQueryClient<S> implements SnapshotQueryApi<S> {
  constructor(private options: SnapshotQueryOptions) {
  }

  private async query<R>(path: string, query: Condition | ListQuery | PagedQuery | SingleQuery, extractor: ResultExtractor = ResultExtractors.Json): Promise<R> {
    const url = combineURLs(this.options.basePath, path);
    const request = {
      url: url,
      method: HttpMethod.POST,
      urlParams: {
        path: this.options.pathParams,
      },
      body: query,
    };
    const exchange = await this.options.fetcher.request(request);
    return extractor(exchange);
  }

  async count(condition: Condition): Promise<number> {
    return this.query(SnapshotQueryEndpointPaths.COUNT, condition);
  }

  list(listQuery: ListQuery): Promise<Partial<MaterializedSnapshot<S>>[]> {
    return this.query(SnapshotQueryEndpointPaths.LIST, listQuery);
  }

  listStream(listQuery: ListQuery): Promise<ReadableStream<JsonServerSentEvent<Partial<MaterializedSnapshot<S>>>>> {
    return this.query(SnapshotQueryEndpointPaths.LIST_STATE, listQuery, ResultExtractors.JsonEventStream);
  }

  listState(listQuery: ListQuery): Promise<Partial<S>[]> {
    return this.query(SnapshotQueryEndpointPaths.LIST_STATE, listQuery);
  }

  listStateStream(listQuery: ListQuery): Promise<ReadableStream<JsonServerSentEvent<Partial<S>>>> {
    return this.query(SnapshotQueryEndpointPaths.LIST_STATE, listQuery, ResultExtractors.JsonEventStream);
  }

  paged(pagedQuery: PagedQuery): Promise<PagedList<Partial<MaterializedSnapshot<S>>>> {
    return this.query(SnapshotQueryEndpointPaths.PAGED, pagedQuery);
  }

  pagedState(pagedQuery: PagedQuery): Promise<PagedList<Partial<S>>> {
    return this.query(SnapshotQueryEndpointPaths.PAGED_STATE, pagedQuery);
  }

  single(singleQuery: SingleQuery): Promise<Partial<MaterializedSnapshot<S>>> {
    return this.query(SnapshotQueryEndpointPaths.SINGLE, singleQuery);
  }

  singleState(singleQuery: SingleQuery): Promise<Partial<S>> {
    return this.query(SnapshotQueryEndpointPaths.LIST_STATE, singleQuery);
  }


}