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

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.api.messaging.function.NullableFunctionInfoCapable
import me.ahoo.wow.api.naming.CompletedCapable
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandStageCapable
import me.ahoo.wow.command.wait.ProcessingStageShouldNotifyPredicate
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitSignalShouldNotifyPredicate
import me.ahoo.wow.command.wait.isWaitingForFunction

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = WaitingChain.TYPE
)
@JsonSubTypes(
    JsonSubTypes.Type(value = WaitingSagaNode::class, name = WaitingSagaNode.TYPE),
    JsonSubTypes.Type(value = WaitingTailNode::class, name = WaitingTailNode.TYPE),
)
interface WaitingChain : CompletedCapable, CommandStageCapable, ProcessingStageShouldNotifyPredicate,
    WaitSignalShouldNotifyPredicate,
    NullableFunctionInfoCapable<NamedFunctionInfoData>,
    me.ahoo.wow.api.naming.Materialized {
    companion object {
        const val TYPE = "type"
    }

    override fun shouldNotify(processingStage: CommandStage): Boolean {
        return stage.shouldNotify(processingStage)
    }

    override fun shouldNotify(signal: WaitSignal): Boolean {
        if (stage.isPrevious(signal.stage)) {
            return true
        }
        if (stage != signal.stage) {
            return false
        }
        return this.function.isWaitingForFunction(signal.function)
    }

}

class WaitingSagaNode(
    override val function: NamedFunctionInfoData? = null,
    val children: List<WaitingChain> = listOf()
) : WaitingChain {
    @field:JsonIgnore
    override val stage: CommandStage = CommandStage.SAGA_HANDLED

    @field:JsonIgnore
    override var completed: Boolean = false
        private set

    companion object {
        const val TYPE = "saga"
    }
}

class WaitingTailNode(
    override val stage: CommandStage,
    override val function: NamedFunctionInfoData? = null
) : WaitingChain {

    @field:JsonIgnore
    override var completed: Boolean = false
        private set

    companion object {
        const val TYPE = "tail"
    }
}