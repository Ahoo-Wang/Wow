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

package me.ahoo.wow.command.wait.stage

import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.WaitSignal
import reactor.core.publisher.Mono

/**
 * Abstract base class for wait strategies that wait for a specific stage after command processing is complete.
 * This class ensures that both PROCESSED stage and the target stage are completed before considering
 * the wait strategy satisfied. It's designed for stages that occur after the basic command processing.
 */
abstract class WaitingForAfterProcessed : WaitingForStage() {
    @Volatile
    private var processedSignal: WaitSignal? = null

    @Volatile
    private var waitingForSignal: WaitSignal? = null

    private fun tryComplete() {
        if (completed) {
            return
        }
        val waitingForSignal = waitingForSignal
        if (processedSignal == null || waitingForSignal == null) {
            return
        }
        super.complete()
    }

    open fun isWaitingFor(signal: WaitSignal): Boolean = signal.stage == stage

    override fun waitingLast(): Mono<WaitSignal> {
        return waiting().collectList().mapNotNull { signals ->
            if (signals.isEmpty()) {
                return@mapNotNull null
            }
            val result: MutableMap<String, Any> = mutableMapOf()
            signals.forEach { signal ->
                result.putAll(signal.result)
            }
            val waitingForSignal = waitingForSignal ?: return@mapNotNull signals.last().copyResult(result)
            waitingForSignal.copyResult(result)
        }
    }

    override fun next(signal: WaitSignal) {
        nextSignal(signal)
        if (signal.stage == CommandStage.PROCESSED) {
            processedSignal = signal
        }
        if (isWaitingFor(signal)) {
            waitingForSignal = signal
        }
        tryComplete()
    }
}
