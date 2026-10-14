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
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.subscribe.CommandCommitted
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
 *   the saga handled, so the receipts of one saga aggregate keep their order. A success receipt (decision D192), the
 *   same kind and id, carries the commit instead: `{commandId, commandType, ref, version, result, causation,
 *   executionId, operator, scope}`, its `result` the command's [CommandResult] at its `committed` hop, no state.
 *
 * Bodies and payloads are read and written with the cluster channel's lenient mapper ([ClusterCodec]): unknown fields
 * are ignored, and numbers keep their exact value.
 */
object CommandMessages {
    private val mapper = ClusterCodec.mapper

    /** The message of [wire] in [partition], carrying [wait]. */
    fun command(wire: CommandWire, partition: Int, wait: WaitContext? = null): TopicMessage {
        val envelope = Envelope(
            kind = EnvelopeKind.COMMAND,
            id = wire.id,
            type = wire.type,
            payload = mapper.valueToTree(wire),
            transport = wait?.let { Transport(wait = it) },
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

    /** The receipt of [rejected]. */
    fun receipt(rejected: CommandRejected<*>): TopicMessage {
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
        )
        return EnvelopeCodec.message(rejected.executionId.ref.toString(), envelope)
    }

    /**
     * The success receipt of [committed] (decision D192): `id` is the command's id, as a rejection's, and `payload`
     * `{commandId, commandType, ref, version, result, causation, executionId, operator, scope}`, without the state.
     */
    fun receipt(committed: CommandCommitted<*>): TopicMessage {
        val envelope = Envelope(
            kind = EnvelopeKind.COMMAND_RECEIPT,
            id = committed.commandId,
            type = committed.commandType,
            payload = mapper.valueToTree(
                CommittedWire(
                    committed.commandId,
                    committed.commandType,
                    committed.ref,
                    committed.version,
                    committed.result,
                    committed.causation,
                    committed.executionId,
                    committed.operator,
                    committed.scope,
                ),
            ),
        )
        return EnvelopeCodec.message(committed.executionId.ref.toString(), envelope)
    }

    /**
     * What the receipt [envelope] holds: a [CommandCommitted] when its payload carries a `result` (decision D192),
     * else a [CommandRejected].
     */
    fun receiptOf(envelope: Envelope): Any {
        require(envelope.kind == EnvelopeKind.COMMAND_RECEIPT) { "A ${envelope.kind} envelope holds no receipt." }
        return if (envelope.payload.has(RESULT)) committedOf(envelope) else rejectedOf(envelope)
    }

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

    /** The commit the success receipt [envelope] holds (decision D192). */
    fun committedOf(envelope: Envelope): CommandCommitted<Any> {
        require(envelope.kind == EnvelopeKind.COMMAND_RECEIPT) { "A ${envelope.kind} envelope holds no receipt." }
        val wire = mapper.treeToValue(envelope.payload, CommittedWire::class.java)
        return CommandCommitted(
            wire.commandId,
            wire.commandType,
            wire.ref,
            wire.version,
            wire.result,
            wire.causation,
            wire.executionId,
            wire.operator,
            wire.scope,
        )
    }

    private const val RESULT = "result"

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

    private data class CommittedWire(
        val commandId: String,
        val commandType: String,
        val ref: AggregateRef,
        val version: Int,
        val result: CommandResult,
        val causation: Causation,
        val executionId: ExecutionId,
        val operator: Operator? = null,
        val scope: Scope = Scope.NONE,
    )
}
