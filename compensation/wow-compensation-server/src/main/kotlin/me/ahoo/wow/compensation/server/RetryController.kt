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

package me.ahoo.wow.compensation.server

import me.ahoo.wow.compensation.api.IExecutionFailedState
import me.ahoo.wow.compensation.api.query.PagedList
import me.ahoo.wow.compensation.api.query.PagedQuery
import me.ahoo.wow.compensation.api.query.QueryApi
import me.ahoo.wow.compensation.api.query.RetryQuery
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/retry")
class RetryController(
    private val retryQuery: RetryQuery,
    private val compensationScheduler: CompensationScheduler
) : QueryApi {

    @PutMapping("{limit}")
    fun retry(@PathVariable limit: Int): Mono<Long> {
        return compensationScheduler.retry(limit)
    }

    @PostMapping("all")
    override fun findAll(@RequestBody pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        return retryQuery.findAll(pagedQuery)
    }

    @PostMapping("next-retry")
    override fun findNextRetry(@RequestBody pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        return retryQuery.findNextRetry(pagedQuery)
    }

    @PostMapping("to-retry")
    override fun findToRetry(@RequestBody pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        return retryQuery.findToRetry(pagedQuery)
    }

    @PostMapping("completed-failures")
    override fun findCompletedFailures(@RequestBody pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        return retryQuery.findCompletedFailures(pagedQuery)
    }

    @PostMapping("success")
    override fun findSuccess(@RequestBody pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        return retryQuery.findSuccess(pagedQuery)
    }
}