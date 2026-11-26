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
import me.ahoo.wow.api.caller.Caller
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
 * A command in its wire form: `{ type, id, requestId, ref, body, expectedVersion, scope, headers,
 * causation, operator, replyTo }`. [type] is the command type's model name, which the receiver reads [body] by; the wait plan
 * does not travel, only a wait context does.
 *
 * [replyTo] is set only on a command a saga sends to another context (an addition within format 1):
 * where the target sends the receipt of a rejection, and, when it asks for one, of the commit. [operator] (an addition within format 1 too) is the
 * command's operator; a reader of an older version ignores it, and one reading an older writer's command has none.
 *
 * [caller] (an addition within format 1 too) is who sent the command, as admission resolved them ([Command.caller]):
 * the target's access policy compares its claims. A derived command has none, and neither does an older writer's
 * command nor one a producer wrote without one: under an access policy, a command that is not derived and has no
 * caller is refused. Its claims travel in the cluster channel's requests and stay in the command topic's records
 * for the topic's retention; they are never recorded in the log.
 *
 * [actingTenant] (an addition within format 1 too) is the tenant its sender sent it for: the caller's, when the
 * caller named no target; the execution's for a saga's command (its commit's, receipt's or job instance's); the
 * scheduling aggregate's for a timer's. The target decides the command's tenant from it and the tenant [ref] names, by
 * the one rule of every path (`TenantPolicy.tenantOf`): a ref naming its acting tenant names none, so a type the
 * target fixes to a tenant is in it. A command without one (an older writer's, a producer's, a caller's that named its
 * target) names the tenant of its ref.
 */
@InternalWowApi
class CommandWire(
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
    val caller: CallerWire? = null,
    val actingTenant: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is CommandWire && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "CommandWire(" +
        "type=$type, id=$id, requestId=$requestId, ref=$ref, body=$body, expectedVersion=$expectedVersion, scope=$scope, headers=$headers, causation=$causation, operator=$operator, replyTo=$replyTo, actingTenant=$actingTenant)"

    private fun valueFields(): List<Any?> =
        listOf(type, id, requestId, ref, body, expectedVersion, scope, headers, causation, operator, replyTo, caller, actingTenant)

    /** This CommandWire with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
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
        caller: CallerWire? = this.caller,
        actingTenant: String? = this.actingTenant,
    ): CommandWire = CommandWire(
        type,
        id,
        requestId,
        ref,
        body,
        expectedVersion,
        scope,
        headers,
        causation,
        operator,
        replyTo,
        caller,
        actingTenant,
    )

    @InternalWowApi
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
            replyTo: ReplyTo? = null,
            caller: CallerWire? = null,
            actingTenant: String? = null,
        ): CommandWire = CommandWire(
            type,
            id,
            requestId,
            ref,
            body,
            expectedVersion,
            scope,
            headers,
            causation,
            operator,
            replyTo,
            caller,
            actingTenant,
        )
    }
}

/**
 * Who sent a command, in its wire form ([CommandWire.caller]): the fields of a [Caller], `{tenantId, scope, subject,
 * crossScope, attributes, service}`, and nothing else: no credential, token or request header. Its [toString] leaves
 * the [attributes] out, the caller's claims.
 */
@InternalWowApi
class CallerWire(
    val tenantId: String = Caller.DEFAULT_TENANT,
    val scope: Scope = Scope.NONE,
    val subject: String? = null,
    val crossScope: Boolean = false,
    val attributes: Map<String, List<String>> = emptyMap(),
    val service: Boolean = false,
) {
    /** The caller this wire form holds. */
    fun caller(): Caller = Caller.of(tenantId, scope, subject, crossScope, attributes, service)

    override fun equals(other: Any?): Boolean =
        this === other || other is CallerWire && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "CallerWire(tenantId=$tenantId, scope=$scope, subject=$subject, crossScope=$crossScope, service=$service)"

    private fun valueFields(): List<Any?> = listOf(tenantId, scope, subject, crossScope, attributes, service)

    @InternalWowApi
    companion object {
        /** [caller] in its wire form. */
        @JvmStatic
        fun of(caller: Caller): CallerWire = CallerWire(
            caller.tenantId,
            caller.scope,
            caller.subject,
            caller.crossScope,
            caller.attributes,
            caller.service,
        )
    }
}

/**
 * Where the receipt of a cross-context command goes when the target rejects it: the receipts
 * topic of [context], the sender's, and the saga handler [subscription] (`processor.function`) whose execution of the
 * commit [ref]@[version] derived it, the [executionId] the saga's `@OnRejected` handler gets. The design's
 * `{context, subscription}` carries the commit too, so the receipt names the execution exactly.
 *
 * [committed] (an addition within format 1) asks for the success receipt too: the saga has an
 * `@OnCommitted` handler of the command, so the target sends the receipt of its commit there as well. A reader of an
 * older version ignores it and sends none; one reading an older writer's reply has it false.
 */
@InternalWowApi
class ReplyTo(
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
    fun executionId(): ExecutionId = ExecutionId(context, subscription, ref, version)

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

    @InternalWowApi
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
 * The cluster channel's one message kind for now: a node forwards [command] to the owner of its
 * partition and waits for the reply until [deadline] (epoch milliseconds). [transport] carries the command's wait
 * context and the trace context it executes in, in the one form of a transport ([TransportCodec]);
 * null when it has neither. The wait's origin stays the node the command arrived at, so later hops
 * signal that node directly, never through the channel.
 *
 * Every cluster message carries its [format], which its reader compares ([ClusterCodec]): a node refuses a forward of
 * a format it does not read ([Refusal.UNKNOWN_FORMAT]), so its sender knows it did not run it. A reader ignores the
 * fields it does not know, so a node reads what a node of the next minor version adds within the format.
 */
@InternalWowApi
data class ForwardCommand(
    val format: Int = FORMAT,
    val command: CommandWire,
    val transport: Transport? = null,
    val deadline: Long,
) {
    @InternalWowApi
    companion object {
        /** The format of this structure. */
        const val FORMAT: Int = 1
    }
}

/**
 * The owner's reply to a [ForwardCommand]: the [result] its execution reached at COMMITTED (a commit, a duplicate,
 * no change, a rejection or a failure), followed by the later hops it [reached] at once (the next
 * HANDLED hop of a command that changed nothing, or of a duplicate whose subscription is past it).
 * Hops reached later are signalled to the wait's origin.
 *
 * A node that does not take the command answers [refused] instead, with one of the [Refusal] codes; the sender then
 * executes the command itself. A reply of a format the sender does not read, or with a result of one, leaves the
 * forward's outcome unknown to it ([ClusterCodec.decodeReply]): the owner may have run the command.
 */
@InternalWowApi
data class ForwardReply(
    val format: Int = FORMAT,
    val result: CommandResult? = null,
    val reached: List<CommandResult> = emptyList(),
    val refused: String? = null,
) {
    init {
        require((result == null) != (refused == null)) { "A forward reply has either a result or a refusal." }
    }

    @InternalWowApi
    companion object {
        /** The format of this structure. */
        const val FORMAT: Int = 1

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
     * The forward is larger than the node's internal port takes (`spring.http.codecs.max-in-memory-size`): the
     * node did not read it, so the sender runs the command itself.
     */
    const val TOO_LARGE = "too-large"

    /**
     * The forward, or its command's result format, is of a format the node does not read ([ClusterCodec]), such as a
     * node of a later version's: the node did not run it, so the sender runs the command itself.
     */
    const val UNKNOWN_FORMAT = "unknown-format"

    /** The node could not read the forward at all, a body that is not a forward: it did not run it. */
    const val MALFORMED = "malformed"
}

/**
 * A message between the nodes of a service of a format this node does not read ([ClusterCodec]): its reader refuses it
 * rather than guess at it.
 */
@InternalWowApi
class UnknownFormatException(message: String) : IllegalArgumentException(message)

/**
 * Reads and writes the messages between the nodes of a service as JSON: the cluster channel's
 * [ForwardCommand] and [ForwardReply], and the [WaitSignal] a node sends to the origin of a wait,
 * reading leniently: unknown fields are ignored at every level, and an unknown status, stage or function kind of a
 * result is read as `UNKNOWN` ([WowJson.readerBuilder]), so a node of the minor version before reads a newer one's.
 *
 * Each reader compares the formats a message carries, its own and its results' ([CommandResult.format]), with the one
 * this node writes, and refuses a message of another with an [UnknownFormatException]: a later format is a change this
 * node cannot read, and reading it as format 1 would act on what it misreads. The comparison is of integers already
 * read, nothing more on the path of a forward or a signal.
 *
 * The mapper is Wow's ([WowJson]): it registers a fixed set of modules, the Kotlin module only, and
 * never discovers others on the class path, since the wire form is the protocol and must not depend on what a host
 * happens to bring; and it keeps every number exact, so a forwarded command's body reaches its owner with `2.50`
 * as `2.50`.
 */
@InternalWowApi
object ClusterCodec {
    internal val mapper: JsonMapper = WowJson.readerBuilder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    fun encode(message: ForwardCommand): ByteArray = mapper.writeValueAsBytes(message)

    fun encode(message: ForwardReply): ByteArray = mapper.writeValueAsBytes(message)

    /** The forward [bytes] hold; an [UnknownFormatException] when it is of another format. */
    fun decodeCommand(bytes: ByteArray): ForwardCommand = mapper.readValue(bytes, ForwardCommand::class.java).also {
        readable("A forward", it.format, ForwardCommand.FORMAT)
    }

    /** The reply [bytes] hold; an [UnknownFormatException] when it, or one of its results, is of another format. */
    fun decodeReply(bytes: ByteArray): ForwardReply = mapper.readValue(bytes, ForwardReply::class.java).also { reply ->
        readable("A forward's reply", reply.format, ForwardReply.FORMAT)
        reply.result?.let { readable(it) }
        reply.reached.forEach { readable(it) }
    }

    fun encode(signal: WaitSignal): ByteArray = mapper.writeValueAsBytes(signal)

    /** The signal [bytes] hold; an [UnknownFormatException] when it, or its result, is of another format. */
    fun decodeSignal(bytes: ByteArray): WaitSignal = readable(mapper.readValue(bytes, WaitSignal::class.java))

    /**
     * A batch of signals to one node, a JSON array of what [encode] writes for each, which every node of this version
     * reads. A batch a node refuses as malformed is sent again one signal a request, once, so that a signal it cannot
     * read costs only itself (`HttpSignalSender`).
     */
    fun encodeSignals(signals: List<WaitSignal>): ByteArray = mapper.writeValueAsBytes(signals)

    /**
     * The signals of a request body: one signal ([encode]) or a batch ([encodeSignals]); an [UnknownFormatException]
     * when one of them, or its result, is of another format.
     */
    fun decodeSignals(bytes: ByteArray): List<WaitSignal> {
        val tree = mapper.readTree(bytes)
        return if (tree.isArray) {
            tree.asSequence().map { readable(mapper.treeToValue(it, WaitSignal::class.java)) }.toList()
        } else {
            listOf(readable(mapper.treeToValue(tree, WaitSignal::class.java)))
        }
    }

    private fun readable(signal: WaitSignal): WaitSignal = signal.also {
        readable("A wait signal", it.format, WaitSignal.FORMAT)
        readable(it.result)
    }

    private fun readable(result: CommandResult): Unit =
        readable("A command result", result.format, CommandResult.FORMAT)

    private fun readable(what: String, format: Int, read: Int) {
        if (format != read) {
            throw UnknownFormatException("$what is of format $format; this node reads format $read.")
        }
    }

    /** [command], a command of [model], in its wire form. */
    fun wire(model: AggregateModel<*>, command: Command<*>): CommandWire =
        wire(model.commandType(command.body.javaClass).name, command)

    /**
     * [command], of the command type named [type], in its wire form, with the [replyTo] of a rejection, sent for the
     * [actingTenant] ([CommandWire.actingTenant]).
     */
    fun wire(type: String, command: Command<*>, replyTo: ReplyTo? = null, actingTenant: String? = null): CommandWire =
        CommandWire(
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
            caller = command.caller?.let(CallerWire::of),
            actingTenant = actingTenant,
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
            caller = wire.caller?.caller(),
        )
    }
}
