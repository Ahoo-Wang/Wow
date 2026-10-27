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

package me.ahoo.wow.runtime.cluster

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.log.TransportCodec
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * A command in its wire form (design 08 §3): `{ type, id, requestId, ref, body, expectedVersion, scope, headers,
 * causation, operator, replyTo }`. [type] is the command type's model name, which the receiver reads [body] by; the wait plan
 * does not travel, only a wait context does.
 *
 * [replyTo] is set only on a command a saga sends to another context (decision D136, an addition within format 1):
 * where the target sends the receipt of a rejection, and, when it asks for one, of the commit (decision D192). [operator] (decision D163, an addition within format 1 too) is the
 * command's operator; a reader of an older version ignores it, and one reading an older writer's command has none.
 */
class CommandWire @InternalWowApi constructor(
    val type: String,
    val id: String,
    val requestId: String,
    val ref: AggregateRef,
    val body: JsonNode,
    val expectedVersion: Int? = null,
    val scope: Scope = Scope.NONE,
    val headers: Map<String, String> = emptyMap(),
    val causation: Causation? = null,
    val operator: Operator? = null,
    val replyTo: ReplyTo? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is CommandWire && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "CommandWire(" +
        "type=$type, id=$id, requestId=$requestId, ref=$ref, body=$body, expectedVersion=$expectedVersion, scope=$scope, headers=$headers, causation=$causation, operator=$operator, replyTo=$replyTo)"

    private fun valueFields(): List<Any?> = listOf(type, id, requestId, ref, body, expectedVersion, scope, headers, causation, operator, replyTo)

    /** This CommandWire with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    @Suppress("LongParameterList")
    fun copyWith(
        type: String = this.type,
        id: String = this.id,
        requestId: String = this.requestId,
        ref: AggregateRef = this.ref,
        body: JsonNode = this.body,
        expectedVersion: Int? = this.expectedVersion,
        scope: Scope = this.scope,
        headers: Map<String, String> = this.headers,
        causation: Causation? = this.causation,
        operator: Operator? = this.operator,
        replyTo: ReplyTo? = this.replyTo,
    ): CommandWire = CommandWire(type, id, requestId, ref, body, expectedVersion, scope, headers, causation, operator, replyTo)

    companion object {
        /** A CommandWire of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            type: String,
            id: String,
            requestId: String,
            ref: AggregateRef,
            body: JsonNode,
            expectedVersion: Int? = null,
            scope: Scope = Scope.NONE,
            headers: Map<String, String> = emptyMap(),
            causation: Causation? = null,
            operator: Operator? = null,
            replyTo: ReplyTo? = null
        ): CommandWire = CommandWire(type, id, requestId, ref, body, expectedVersion, scope, headers, causation, operator, replyTo)
    }
}

/**
 * Where the receipt of a cross-context command goes when the target rejects it (decisions D47, D136): the receipts
 * topic of [context], the sender's, and the saga handler [subscription] (`processor.function`) whose execution of the
 * commit [ref]@[version] derived it, the [executionId] the saga's `@OnRejected` handler gets. The design's
 * `{context, subscription}` carries the commit too, so the receipt names the execution exactly.
 *
 * [committed] (decision D192, an addition within format 1) asks for the success receipt too: the saga has an
 * `@OnCommitted` handler of the command, so the target sends the receipt of its commit there as well. A reader of an
 * older version ignores it and sends none; one reading an older writer's reply has it false.
 */
class ReplyTo @InternalWowApi constructor(
    val context: String,
    val subscription: String,
    val ref: AggregateRef,
    val version: Int,
    val committed: Boolean = false,
) {
    init {
        require(context.isNotBlank()) { "A reply names its context." }
        require(subscription.isNotBlank()) { "A reply names its subscription." }
    }

    /** The saga's execution that derived the command. */
    fun executionId(): ExecutionId = ExecutionId(subscription, ref, version)

    override fun equals(other: Any?): Boolean =
        this === other || other is ReplyTo && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ReplyTo(" +
        "context=$context, subscription=$subscription, ref=$ref, version=$version, committed=$committed)"

    private fun valueFields(): List<Any?> = listOf(context, subscription, ref, version, committed)

    /** This ReplyTo with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        context: String = this.context,
        subscription: String = this.subscription,
        ref: AggregateRef = this.ref,
        version: Int = this.version,
        committed: Boolean = this.committed,
    ): ReplyTo = ReplyTo(context, subscription, ref, version, committed)

    companion object {
        /** A ReplyTo of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            context: String,
            subscription: String,
            ref: AggregateRef,
            version: Int,
            committed: Boolean = false
        ): ReplyTo = ReplyTo(context, subscription, ref, version, committed)

        /** The reply to [execution], a saga's of [context], asking for the success receipt too when [committed]. */
        fun of(context: String, execution: ExecutionId, committed: Boolean = false): ReplyTo =
            ReplyTo(context, execution.subscription, execution.ref, execution.version, committed)
    }
}

/**
 * The cluster channel's one message kind for now (design 02 §3): a node forwards [command] to the owner of its
 * partition and waits for the reply until [deadline] (epoch milliseconds). [transport] carries the command's wait
 * context and the trace context it executes in (decision D174), in the one form of a transport ([TransportCodec],
 * finding R5.B-3); null when it has neither. The wait's origin stays the node the command arrived at, so later hops
 * signal that node directly (decision D65), never through the channel.
 *
 * Every cluster message carries its format version [v] (design 08 §9). A reader ignores the fields it does not know,
 * so a node reads the messages of the next minor version (decision D48).
 */
@InternalWowApi
data class ForwardCommand(
    val v: Int = VERSION,
    val command: CommandWire,
    val transport: Transport? = null,
    val deadline: Long,
) {
    @InternalWowApi
    companion object {
        const val VERSION: Int = 1
    }
}

/**
 * The owner's reply to a [ForwardCommand]: the [result] its execution reached at COMMITTED (a commit, a duplicate,
 * no change, a rejection or a failure; design 08 §7), followed by the later hops it [reached] at once (the next
 * HANDLED hop of a command that changed nothing, or of a duplicate whose subscription is past it, decision D63).
 * Hops reached later are signalled to the wait's origin.
 *
 * A node that does not take the command answers [refused] instead, with one of the [Refusal] codes; the sender then
 * executes the command itself (decision D71).
 */
@InternalWowApi
data class ForwardReply(
    val v: Int = VERSION,
    val result: CommandResult? = null,
    val reached: List<CommandResult> = emptyList(),
    val refused: String? = null,
) {
    init {
        require((result == null) != (refused == null)) { "A forward reply has either a result or a refusal." }
    }

    @InternalWowApi
    companion object {
        const val VERSION: Int = 1

        fun refused(code: String): ForwardReply = ForwardReply(refused = code)
    }
}

/** Why a node refused a forwarded command. */
@InternalWowApi
object Refusal {
    /** The node neither owns the command's partition nor is about to. */
    const val NOT_OWNER = "not-owner"

    /** The forward's deadline passed before the node took it. */
    const val EXPIRED = "expired"

    /** The node does not know the command's aggregate or command type, such as a node of an older version. */
    const val UNKNOWN_TYPE = "unknown-type"

    /**
     * The forward is larger than the node's internal port takes (`wow.internal.max-message-size`, review A3-9): the
     * node did not read it, so the sender runs the command itself.
     */
    const val TOO_LARGE = "too-large"
}

/**
 * Reads and writes the messages between the nodes of a service as JSON (decision D26): the cluster channel's
 * [ForwardCommand] and [ForwardReply], and the [WaitSignal] a node sends to the origin of a wait (design 08 §8),
 * reading leniently: unknown fields are ignored at every level (decision D48).
 *
 * The mapper is Wow's ([WowJson], decision D122): it registers a fixed set of modules, the Kotlin module only, and
 * never discovers others on the class path, since the wire form is the protocol and must not depend on what a host
 * happens to bring; and it keeps every number exact, so a forwarded command's body reaches its owner with `2.50`
 * as `2.50`.
 */
@InternalWowApi
object ClusterCodec {
    internal val mapper: JsonMapper = WowJson.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    fun encode(message: ForwardCommand): ByteArray = mapper.writeValueAsBytes(message)

    fun encode(message: ForwardReply): ByteArray = mapper.writeValueAsBytes(message)

    fun decodeCommand(bytes: ByteArray): ForwardCommand = mapper.readValue(bytes, ForwardCommand::class.java)

    fun decodeReply(bytes: ByteArray): ForwardReply = mapper.readValue(bytes, ForwardReply::class.java)

    fun encode(signal: WaitSignal): ByteArray = mapper.writeValueAsBytes(signal)

    fun decodeSignal(bytes: ByteArray): WaitSignal = mapper.readValue(bytes, WaitSignal::class.java)

    /**
     * A batch of signals to one node (review A5-12), a JSON array of what [encode] writes for each. A node that reads
     * only single signals refuses the array as malformed; its sender then falls back to one signal a request.
     */
    fun encodeSignals(signals: List<WaitSignal>): ByteArray = mapper.writeValueAsBytes(signals)

    /** The signals of a request body: one signal ([encode]) or a batch ([encodeSignals]). */
    fun decodeSignals(bytes: ByteArray): List<WaitSignal> {
        val tree = mapper.readTree(bytes)
        return if (tree.isArray) {
            tree.asSequence().map { mapper.treeToValue(it, WaitSignal::class.java) }.toList()
        } else {
            listOf(mapper.treeToValue(tree, WaitSignal::class.java))
        }
    }

    /** [command], a command of [model], in its wire form. */
    fun wire(model: AggregateModel<*>, command: Command<*>): CommandWire =
        wire(model.commandType(command.body.javaClass).name, command)

    /** [command], of the command type named [type], in its wire form, with the [replyTo] of a rejection. */
    fun wire(type: String, command: Command<*>, replyTo: ReplyTo? = null): CommandWire = CommandWire(
        type = type,
        id = command.id,
        requestId = command.requestId,
        ref = command.ref,
        body = mapper.valueToTree(command.body),
        expectedVersion = command.expectedVersion,
        scope = command.scope,
        headers = command.headers,
        causation = command.causation,
        operator = command.operator,
        replyTo = replyTo,
    )

    /** The command [wire] holds, its body read as [model]'s command type of that name; null when it has none. */
    fun command(model: AggregateModel<*>, wire: CommandWire): Command<*>? {
        val type = model.findCommandType(wire.type) ?: return null
        return Command(
            id = wire.id,
            requestId = wire.requestId,
            ref = wire.ref,
            body = mapper.treeToValue(wire.body, type.type),
            expectedVersion = wire.expectedVersion,
            scope = wire.scope,
            headers = wire.headers,
            causation = wire.causation,
            operator = wire.operator,
        )
    }
}
