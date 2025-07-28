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

abstract class WaitingForAfterProcessed : WaitingForStage() {
    @Volatile
    private var processedSignal: WaitSignal? = null

    @Volatile
    private var waitingForSignal: WaitSignal? = null
    private fun tryComplete() {
        val waitingForSignal = waitingForSignal
        if (processedSignal == null || waitingForSignal == null) {
            return
        }
        super.complete()
    }

    open fun isWaitingForSignal(signal: WaitSignal): Boolean {
        return signal.stage == stage
    }

    override fun waitingLast(): Mono<WaitSignal> {
        return waiting().collectList().map { signals ->
            val result: MutableMap<String, Any> = mutableMapOf()
            signals.forEach { signal ->
                result.putAll(signal.result)
            }
            signals.last().copyResult(result)
        }
    }

    override fun next(signal: WaitSignal) {
        nextSignal(signal)
        if (signal.stage == CommandStage.PROCESSED) {
            processedSignal = signal
            if (!signal.succeeded) {
                super.complete()
                return
            }
        }
        if (isWaitingForSignal(signal)) {
            waitingForSignal = signal
        }
        tryComplete()
    }
}
