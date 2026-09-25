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

// The query DSL: the modules `/dsl` exports.
export * from './dsl/filter/index.js';
export * from './dsl/aggregation/index.js';
export * from './dsl/sort.js';
export * from './dsl/projection.js';
export * from './dsl/pagination.js';
export * from './dsl/cursorQuery.js';
export * from './dsl/queryable.js';
export * from './dsl/deletionState.js';
export * from './dsl/documents.js';
export * from './dsl/descriptor.js';

// The clients, and the transport they share.
export * from './client/command/index.js';
export * from './client/metadata/index.js';
export * from './client/query/index.js';
export * from './client/routing.js';
export * from './transport/index.js';

export * from './model/index.js';
export * from './error/index.js';
