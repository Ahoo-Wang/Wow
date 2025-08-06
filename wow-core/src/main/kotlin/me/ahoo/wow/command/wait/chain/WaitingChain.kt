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

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.api.messaging.function.NullableFunctionInfoCapable
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandStageCapable
import me.ahoo.wow.command.wait.ProcessingStageShouldNotifyPredicate
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitSignalShouldNotifyPredicate
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.command.wait.chain.SimpleWaitingForChain.Companion.COMMAND_WAIT_CHAIN
import me.ahoo.wow.command.wait.isWaitingForFunction
import me.ahoo.wow.command.wait.propagateCommandWaitEndpoint
import me.ahoo.wow.command.wait.propagateWaitCommandId
import me.ahoo.wow.serialization.toJsonString

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
    property = WaitingChain.TYPE
)
@JsonSubTypes(
    JsonSubTypes.Type(value = SimpleWaitingChain::class, name = SimpleWaitingChain.TYPE),
    JsonSubTypes.Type(value = WaitingTailNode::class, name = WaitingTailNode.TYPE),
)
interface WaitingChain :
    CommandStageCapable,
    ProcessingStageShouldNotifyPredicate,
    WaitSignalShouldNotifyPredicate,
    NullableFunctionInfoCapable<NamedFunctionInfoData>,
    WaitStrategy.Materialized {
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val next: WaitingChain?

    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    override val function: NamedFunctionInfoData?

    companion object {
        const val TYPE = "type"
    }

    override fun propagate(commandWaitEndpoint: String, header: Header) {
        header.propagateWaitCommandId(waitCommandId)
            .propagateCommandWaitEndpoint(commandWaitEndpoint)
            .with(COMMAND_WAIT_CHAIN, this.toJsonString())
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
