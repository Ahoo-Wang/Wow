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

import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.asCommandMessage
import me.ahoo.wow.compensation.api.PrepareCompensation
import me.ahoo.wow.compensation.domain.ToRetryQuery
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class CompensationScheduler(private val toRetryQuery: ToRetryQuery, private val commandGateway: CommandGateway) {
    companion object {
        private val log = LoggerFactory.getLogger(CompensationScheduler::class.java)
    }

    fun retry(): Mono<Long> {
        return toRetryQuery.findToRetry()
            .flatMap {
                if (log.isDebugEnabled) {
                    log.debug(
                        "retry - ExecutionFailed[{}] - {} - {} - {} - {}",
                        it.id,
                        it.retryState,
                        it.eventId,
                        it.processor,
                        it.functionKind
                    )
                }
                val commandMessage = PrepareCompensation(it.id).asCommandMessage()
                commandGateway.send(commandMessage).thenReturn(commandMessage)
            }
            .count()
    }
}