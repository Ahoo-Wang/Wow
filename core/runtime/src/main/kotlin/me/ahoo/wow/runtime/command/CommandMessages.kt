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

package me.ahoo.wow.runtime.command

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.transport.Envelope
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.EnvelopeKind
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.wait.WaitContext

/**
 * The messages of the command and receipts topics (design 08 §8; decisions D58, D135, D136), each an [Envelope]:
 *
 * - **Command** ([EnvelopeKind.COMMAND]): `id` is the command id, `type` its full type name (`ctx.agg.Command`),
 *   `payload` its wire form ([CommandWire], with `replyTo` when a saga sent it), `transport` its wait context, if any.
 *   Keyed by the aggregate ref; its partition is the one the log's partitioner gives the ref among the topic's
 *   partitions, which are the target context's log partitions.
 * - **Receipt** ([EnvelopeKind.COMMAND_RECEIPT]): `id` is the rejected command's id, the one a reader deduplicates by,
 *   `type` its type, `payload` the rejection as the saga's `@OnRejected` handler takes it, `{commandId, commandType,
 *   ref, error, causation, executionId, operator, scope}` (decisions D157, D163, D189), without the command's body. Keyed by the ref of the commit
 *   the saga handled, so the receipts of one saga aggregate keep their order.
 *
 * Bodies and payloads are read and written with the cluster channel's lenient mapper ([ClusterCodec]): unknown fields
 * are ignored, and numbers keep their exact value.
 */
object CommandMessages {
    private val mapper = ClusterCodec.mapper

    /** The message of [wire] in [partition], carrying [wait] and the propagation fields of its [trace] (D174). */
    fun command(
        wire: CommandWire,
        partition: Int,
        wait: WaitContext? = null,
        trace: Map<String, String> = emptyMap(),
    ): TopicMessage {
        val envelope = Envelope(
            kind = EnvelopeKind.COMMAND,
            id = wire.id,
            type = wire.type,
            payload = mapper.valueToTree(wire),
            transport = transportOf(wait, trace),
        )
        return EnvelopeCodec.message(wire.ref.toString(), envelope).let {
            TopicMessage(it.key, it.value, it.headers, partition)
        }
    }

    /** The command [envelope] holds. */
    fun commandOf(envelope: Envelope): CommandWire {
        require(envelope.kind == EnvelopeKind.COMMAND) { "A ${envelope.kind} envelope holds no command." }
        return mapper.treeToValue(envelope.payload, CommandWire::class.java)
    }

    /** The receipt of [rejected], carrying the propagation fields of its [trace] (decision D174). */
    fun receipt(rejected: CommandRejected<*>, trace: Map<String, String> = emptyMap()): TopicMessage {
        val envelope = Envelope(
            kind = EnvelopeKind.COMMAND_RECEIPT,
            id = rejected.commandId,
            type = rejected.commandType,
            payload = mapper.valueToTree(
                RejectedWire(
                    rejected.commandId,
                    rejected.commandType,
                    rejected.ref,
                    rejected.error,
                    rejected.causation,
                    rejected.executionId,
                    rejected.operator,
                    rejected.scope,
                ),
            ),
            transport = transportOf(null, trace),
        )
        return EnvelopeCodec.message(rejected.executionId.ref.toString(), envelope)
    }

    /** The transport of a message: [wait] and [trace]; null when it has neither. */
    private fun transportOf(wait: WaitContext?, trace: Map<String, String>): Transport? =
        if (wait == null && trace.isEmpty()) null else Transport(wait = wait, trace = trace)

    /** The rejection the receipt [envelope] holds. */
    fun rejectedOf(envelope: Envelope): CommandRejected<Any> {
        require(envelope.kind == EnvelopeKind.COMMAND_RECEIPT) { "A ${envelope.kind} envelope holds no receipt." }
        val wire = mapper.treeToValue(envelope.payload, RejectedWire::class.java)
        return CommandRejected(
            wire.commandId,
            wire.commandType,
            wire.ref,
            wire.error,
            wire.causation,
            wire.executionId,
            wire.operator,
            wire.scope,
        )
    }

    private data class RejectedWire(
        val commandId: String,
        val commandType: String,
        val ref: AggregateRef,
        val error: ErrorInfo,
        val causation: Causation,
        val executionId: ExecutionId,
        val operator: Operator? = null,
        val scope: Scope = Scope.NONE,
    )
}
