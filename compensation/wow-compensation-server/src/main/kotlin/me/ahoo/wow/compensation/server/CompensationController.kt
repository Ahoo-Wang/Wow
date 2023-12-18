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
import me.ahoo.wow.compensation.domain.ToRetryQuery
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/compensation")
class CompensationController(
    private val toRetryQuery: ToRetryQuery,
    private val compensationScheduler: CompensationScheduler
) {

    @GetMapping("/to-retry/{limit}")
    fun findToRetry(@PathVariable limit: Int): Flux<out IExecutionFailedState> {
        return toRetryQuery.findToRetry(limit)
    }

    @PutMapping("/retry/{limit}")
    fun retry(@PathVariable limit: Int): Mono<Long> {
        return compensationScheduler.retry(limit)
    }
}