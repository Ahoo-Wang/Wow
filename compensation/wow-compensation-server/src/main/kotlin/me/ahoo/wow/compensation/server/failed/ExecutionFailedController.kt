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

package me.ahoo.wow.compensation.server.failed

import me.ahoo.wow.compensation.api.IExecutionFailedState
import me.ahoo.wow.compensation.api.query.ExecutionFailedQuery
import me.ahoo.wow.compensation.api.query.PagedList
import me.ahoo.wow.compensation.api.query.PagedQuery
import me.ahoo.wow.compensation.api.query.QueryApi
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/failed")
class ExecutionFailedController(
    private val executionFailedQuery: ExecutionFailedQuery
) : QueryApi {

    @PostMapping("all")
    override fun findAll(@RequestBody pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        return executionFailedQuery.findAll(pagedQuery)
    }

    @PostMapping("next-retry")
    override fun findNextRetry(@RequestBody pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        return executionFailedQuery.findNextRetry(pagedQuery)
    }

    @PostMapping("executing")
    override fun findExecuting(@RequestBody pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        return executionFailedQuery.findExecuting(pagedQuery)
    }

    @PostMapping("to-retry")
    override fun findToRetry(@RequestBody pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        return executionFailedQuery.findToRetry(pagedQuery)
    }

    @PostMapping("non-retryable")
    override fun findNonRetryable(@RequestBody pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        return executionFailedQuery.findNonRetryable(pagedQuery)
    }

    @PostMapping("success")
    override fun findSuccess(@RequestBody pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        return executionFailedQuery.findSuccess(pagedQuery)
    }

    @PostMapping("unrecoverable")
    override fun findUnrecoverable(@RequestBody pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        return executionFailedQuery.findUnrecoverable(pagedQuery)
    }
}