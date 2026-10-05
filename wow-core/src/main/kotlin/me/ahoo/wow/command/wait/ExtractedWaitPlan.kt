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
import me.ahoo.wow.api.messaging.function.FunctionInfo
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain.Companion.COMMAND_WAIT_CHAIN
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain.Companion.SIMPLE_CHAIN
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.extractWaitingChainTail
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.propagateWaitingChainTail
import me.ahoo.wow.messaging.propagation.MessagePropagator

/**
 * The wait plan a message carries, which propagates its wait keys to the messages derived from that message:
 *
 * - from a command to the event stream it commits: all of them, whatever the target (the event store keeps them,
 *   decision V1);
 * - from an event to a command: only for a chain wait, and only to a command that the saga function the chain waits
 *   for sends (B6, since 9.3.0). Such a command carries the chain's tail. A command another saga, an event processor
 *   or application code derives from the same event gets no wait keys: nobody waits for it. Before 9.3.0 every
 *   command derived from the event carried the tail.
 */
data class ExtractedWaitPlan(
    override val endpoint: String,
    override val waitCommandId: String,
    val plan: WaitPlan,
) : CommandWaitEndpoint,
    WaitCommandIdCapable,
    MessagePropagator {
    override fun propagate(header: Header, upstream: Message<*, *>) {
        propagateTo(header, upstream, producer = null)
    }

    override fun propagate(header: Header, upstream: Message<*, *>, producer: FunctionInfo) {
        propagateTo(header, upstream, producer)
    }

    private fun propagateTo(header: Header, upstream: Message<*, *>, producer: FunctionInfo?) {
        if (upstream is CommandMessage<*>) {
            plan.propagate(this, header)
            return
        }
        val target = plan.target as? ChainWaitTarget ?: return
        if (producer == null || !target.function.matchesWaitFunction(producer)) {
            return
        }
        header
            .propagateWaitCommandId(waitCommandId)
            .propagateCommandWaitEndpoint(endpoint)
            .propagateWaitingChainTail(target.tail.stage, target.tail.function)
    }
}

/**
 * Whether a wait with this target propagates past [upstream] at all: from a command always, from an event only for a
 * chain wait (and then only to the commands of the chain's saga function, see [ExtractedWaitPlan]).
 */
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
        return StageWaitTarget(stage, extractWaitFunction().takeIf { stage.shouldWaitFunction })
    }
    if (tail != null) {
        return StageWaitTarget(tail.stage, tail.function.takeIf { tail.stage.shouldWaitFunction })
    }
    return null
}
