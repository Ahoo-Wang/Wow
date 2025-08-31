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

import { EventStreamQueryApi, EventStreamQueryEndpointPaths } from './eventStreamQueryApi';
import { Condition } from '../condition';
import { ListQuery, PagedList, PagedQuery } from '../queryable';
import { DomainEventStream } from './domainEventStream';
import { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import { QueryClient } from '../queryApi';
import { ClientOptions } from '../../types';
import { ContentTypeValues } from '@ahoo-wang/fetcher';
import { ResultExtractors } from '@ahoo-wang/fetcher-decorator';

export class EventStreamQueryClient extends QueryClient implements EventStreamQueryApi {
  constructor(options: ClientOptions) {
    super(options);
  }

  count(condition: Condition): Promise<number> {
    return this.query(EventStreamQueryEndpointPaths.COUNT, condition);
  }

  list(listQuery: ListQuery): Promise<Partial<DomainEventStream>[]> {
    return this.query(EventStreamQueryEndpointPaths.LIST, listQuery);
  }

  listStream(listQuery: ListQuery): Promise<ReadableStream<JsonServerSentEvent<Partial<DomainEventStream>>>> {
    return this.query(EventStreamQueryEndpointPaths.LIST, listQuery, ContentTypeValues.TEXT_EVENT_STREAM, ResultExtractors.JsonEventStream);
  }

  paged(pagedQuery: PagedQuery): Promise<PagedList<Partial<DomainEventStream>>> {
    return this.query(EventStreamQueryEndpointPaths.PAGED, pagedQuery);
  }
}