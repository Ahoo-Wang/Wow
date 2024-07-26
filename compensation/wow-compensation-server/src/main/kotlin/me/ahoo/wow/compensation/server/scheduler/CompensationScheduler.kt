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

package me.ahoo.wow.compensation.server.scheduler

import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.schedule.AbstractScheduler
import me.ahoo.simba.schedule.ScheduleConfig
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.compensation.api.PrepareCompensation
import me.ahoo.wow.compensation.domain.FindNextRetry
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
@ConditionalOnSchedulerEnabled
class CompensationScheduler(
    private val findNextRetry: FindNextRetry,
    private val commandGateway: CommandGateway,
    private val schedulerProperties: SchedulerProperties,
    contendServiceFactory: MutexContendServiceFactory
) :
    AbstractScheduler(
        mutex = schedulerProperties.mutex,
        contendServiceFactory = contendServiceFactory
    ),
    SmartLifecycle {
    companion object {
        private val log = LoggerFactory.getLogger(CompensationScheduler::class.java)
        const val WORKER_NAME = "CompensationScheduler"
    }

    fun retry(limit: Int = 100): Mono<Long> {
        return findNextRetry.findNextRetry(limit)
            .flatMap {
                if (log.isDebugEnabled) {
                    log.debug(
                        "retry - ExecutionFailed[{}] - {} - {} - {}",
                        it.id,
                        it.retryState,
                        it.eventId,
                        it.function
                    )
                }
                val commandMessage = PrepareCompensation(it.id).toCommandMessage()
                commandGateway.send(commandMessage).thenReturn(commandMessage)
            }
            .count()
    }

    override val config: ScheduleConfig =
        ScheduleConfig.delay(schedulerProperties.initialDelay, schedulerProperties.period)
    override val worker: String
        get() = WORKER_NAME

    override fun work() {
        if (log.isInfoEnabled) {
            log.info("Start retry - batchSize:[{}].", schedulerProperties.batchSize)
        }
        val count = retry(schedulerProperties.batchSize)
            .block()
        if (log.isInfoEnabled) {
            log.info("Complete retry - batchSize:[{}] - count:[{}].", schedulerProperties.batchSize, count)
        }
    }

    override fun isRunning(): Boolean {
        return super.running
    }
}