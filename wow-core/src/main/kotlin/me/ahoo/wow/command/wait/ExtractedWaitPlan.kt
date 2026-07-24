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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain.Companion.COMMAND_WAIT_CHAIN
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain.Companion.SIMPLE_CHAIN
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.extractWaitingChainTail
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.propagateWaitingChainTail
import me.ahoo.wow.messaging.propagation.MessagePropagator

data class ExtractedWaitPlan(
    override val endpoint: String,
    override val waitCommandId: String,
    val plan: WaitPlan,
) : CommandWaitEndpoint,
    WaitCommandIdCapable,
    MessagePropagator {
    override fun propagate(header: Header, upstream: Message<*, *>) {
        val target = plan.target
        if (!target.shouldPropagate(upstream)) {
            return
        }
        if (target is ChainWaitTarget && upstream !is CommandMessage<*>) {
            header
                .propagateWaitCommandId(waitCommandId)
                .propagateCommandWaitEndpoint(endpoint)
                .propagateWaitingChainTail(target.tail.stage, target.tail.function)
            return
        }
        plan.propagate(this, header)
    }
}

fun WaitTarget.shouldPropagate(upstream: Message<*, *>): Boolean =
    this is ChainWaitTarget || upstream is CommandMessage<*>

fun Header.propagateWaitTarget(target: WaitTarget): Header {
    if (target is ChainWaitTarget) {
        propagateWaitFunction(target.function)
        with(COMMAND_WAIT_CHAIN, SIMPLE_CHAIN)
        propagateWaitingChainTail(target.tail.stage, target.tail.function)
        return this
    }
    propagateWaitingStage(target.stage)
    propagateWaitFunction(target.function)
    return this
}

fun Header.extractWaitPlan(): ExtractedWaitPlan? {
    val waitCommandId = extractCommandWaitId() ?: return null
    val endpoint = extractCommandWaitEndpoint() ?: return null
    val target = extractWaitTarget() ?: return null
    return ExtractedWaitPlan(
        endpoint = endpoint,
        waitCommandId = waitCommandId,
        plan = SimpleWaitPlan(
            waitCommandId = waitCommandId,
            target = target,
            supportVoidCommand = target.stage == CommandStage.SENT,
        ),
    )
}

fun Header.extractWaitTarget(): WaitTarget? {
    val tail = extractWaitingChainTail()
    if (this[COMMAND_WAIT_CHAIN] == SIMPLE_CHAIN && tail != null) {
        return ChainWaitTarget(function = extractWaitFunction(), tail = tail)
    }
    val stage = extractWaitingStage()
    if (stage != null) {
        return StageWaitTarget(
            stage = stage,
            function = if (stage.shouldWaitFunction) {
                extractWaitFunction()
            } else {
                null
            },
        )
    }
    if (tail != null) {
        return StageWaitTarget(tail.stage, tail.function.takeIf { tail.stage.shouldWaitFunction })
    }
    return null
}
