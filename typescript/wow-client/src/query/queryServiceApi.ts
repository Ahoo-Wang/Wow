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

import { ListQuery, PagedList, PagedQuery, SingleQuery } from './queryable';
import { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import { Condition } from './condition';

export interface QueryServiceApi<R> {

  single(singleQuery: SingleQuery): Promise<Partial<R>>;

  list(listQuery: ListQuery): Promise<Partial<R>[]>;

  listStream(listQuery: ListQuery): Promise<ReadableStream<JsonServerSentEvent<Partial<R>>>>;

  paged(pagedQuery: PagedQuery): Promise<PagedList<Partial<R>>>;

  count(condition: Condition): Promise<number>;
}