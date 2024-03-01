/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITH WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.compensation.api.query

import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.compensation.api.IExecutionFailedState
import reactor.core.publisher.Mono

@Deprecated("Use SnapshotQuery instead.")
interface ExecutionFailedQuery {
    fun findAll(pagedQuery: PagedQuery): Mono<PagedList<IExecutionFailedState>>
    fun findNextRetry(pagedQuery: PagedQuery): Mono<PagedList<IExecutionFailedState>>
    fun findExecuting(pagedQuery: PagedQuery): Mono<PagedList<IExecutionFailedState>>
    fun findToRetry(pagedQuery: PagedQuery): Mono<PagedList<IExecutionFailedState>>
    fun findNonRetryable(pagedQuery: PagedQuery): Mono<PagedList<IExecutionFailedState>>
    fun findSuccess(pagedQuery: PagedQuery): Mono<PagedList<IExecutionFailedState>>
    fun findUnrecoverable(pagedQuery: PagedQuery): Mono<PagedList<IExecutionFailedState>>
}
