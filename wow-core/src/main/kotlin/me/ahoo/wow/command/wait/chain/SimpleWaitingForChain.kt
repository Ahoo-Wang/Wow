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

package me.ahoo.wow.command.wait.chain

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.command.wait.COMMAND_WAIT_PREFIX
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitingFor
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.serialization.toObject

class SimpleWaitingForChain(override val materialized: SimpleWaitingChain) : WaitingFor() {
    override val id: String
        get() = materialized.id

    private val firstWaiting = WaitingForStage.sagaHandled(
        contextName = materialized.function.contextName,
        processorName = materialized.function.processorName,
        functionName = materialized.function.name
    )

    override fun isPreviousSignal(signal: WaitSignal): Boolean {
        return true
    }

    override fun next(signal: WaitSignal) {
        TODO("Not yet implemented")
    }

    companion object {
        const val COMMAND_WAIT_CHAIN = "${COMMAND_WAIT_PREFIX}chain"
        fun Header.extractWaitingForChain(): SimpleWaitingChain? {
            val chain = this[COMMAND_WAIT_CHAIN] ?: return null
            return chain.toObject<SimpleWaitingChain>()
        }
    }
}
