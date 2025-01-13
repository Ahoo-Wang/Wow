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

package me.ahoo.wow.command.wait

abstract class WaitingForAfterProcessed : AbstractWaitingFor() {
    @Volatile
    private var processedSignal: WaitSignal? = null

    @Volatile
    private var waitingForSignal: WaitSignal? = null
    private val result: MutableMap<String, Any> = mutableMapOf()
    protected fun nextSignal() {
        val waitingForSignal = waitingForSignal
        if (processedSignal == null || waitingForSignal == null) {
            return
        }
        val mergedSignal = waitingForSignal.copyResult(result)
        sink.tryEmitValue(mergedSignal)
    }

    open fun isWaitingForSignal(signal: WaitSignal): Boolean {
        if (signal.stage != stage || !isSameBoundedContext(signal.function)) {
            return false
        }
        if (processorName.isBlank()) {
            return true
        }
        return signal.function.processorName == processorName
    }

    override fun next(signal: WaitSignal) {
        result.putAll(signal.result)
        if (signal.stage == CommandStage.PROCESSED) {
            processedSignal = signal
            if (!signal.succeeded) {
                sink.tryEmitValue(signal)
                return
            }
        }
        if (isWaitingForSignal(signal)) {
            waitingForSignal = signal
        }
        nextSignal()
    }
}
