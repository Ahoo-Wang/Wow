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

/**
 * `@ahoo-wang/wow-client/dsl`: the query DSL alone — `filter.*`,
 * `aggregation.*`, sort, projection, pagination, cursor queries, the query
 * factories and their types, and the metadata field names of snapshots and
 * event streams.
 *
 * It loads no HTTP code: no Fetcher, no decorators, no `reflect-metadata`,
 * and none of the global stream patches `@ahoo-wang/fetcher-eventstream`
 * installs. An application that only builds queries — and sends them through
 * a client of its own — imports this. The root entry exports all of it too.
 */
export * from './dsl/filter/index.js';
export * from './dsl/aggregation/index.js';
export * from './dsl/sort.js';
export * from './dsl/projection.js';
export * from './dsl/pagination.js';
export * from './dsl/cursorQuery.js';
export * from './dsl/queryable.js';
export * from './dsl/deletionState.js';
export * from './dsl/documents.js';
export { SnapshotMetadataFields } from './query/snapshot/snapshot.js';
export { DomainEventStreamMetadataFields } from './query/event/domainEventStream.js';
