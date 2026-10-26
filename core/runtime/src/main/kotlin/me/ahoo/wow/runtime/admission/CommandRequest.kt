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

package me.ahoo.wow.runtime.admission

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.spi.wait.WaitContext
import tools.jackson.databind.JsonNode

/**
 * A command as the edge received it, before admission (decision D92): what the route fixed ([context], [aggregate],
 * [command] and, on an instance route, [aggregateId]), the undecoded [body], who sent it ([caller]), and the facts the
 * request's headers gave: a cross-scope [target] (`Wow-Scope`), a [requestId] (`Wow-Request-Id`), an
 * [expectedVersion] (`If-Match`), the request facts in [headers], and the [wait] plan (`Wow-Wait`; the default plan
 * when absent).
 *
 * [command] is the command type's model name (or a former name, `@Alias`), or the part of it after the aggregate type
 * (`AddItem` for `shop.cart.AddItem`). A request built from a command object ([of] with a [CommandBody.Value] and an
 * [AggregateRef], decision D258) names its command by the object's class name, and admission derives the command type
 * from the class: from the models of the node for a context it serves, from the contract of a peer context (decision
 * D234). Identity is not taken from anywhere else: not from [headers] (a header named `Wow-…` or `wow.…` is reserved and
 * refused) and never from the body (D68). [tenantId] is the tenant the request's ref names: admission decides the
 * tenant as always (the caller's, a cross-scope target's or the one fixed for the type) and refuses a request whose
 * ref names another.
 *
 * [trace] holds the trace context the request arrived with (decision D174), such as the W3C `traceparent` and
 * `tracestate` headers: delivery metadata, never a fact, which only an observer reads.
 *
 * [call] is the root command a service identity's synchronous call acts for (decision D193, the `Wow-Call` header):
 * admission takes it only from a caller that is a [service][Caller.service] identity.
 *
 * Fields may be added (decision D59): construct a request with [of] or [builder]; the constructor is internal to Wow.
 */
class CommandRequest @InternalWowApi constructor(
    val context: String,
    val aggregate: String,
    val command: String,
    val body: CommandBody,
    val caller: Caller = Caller.ANONYMOUS,
    val aggregateId: String? = null,
    val target: TargetScope? = null,
    val requestId: String? = null,
    val expectedVersion: Int? = null,
    val headers: Map<String, String> = emptyMap(),
    val wait: WaitPlan? = null,
    val trace: Map<String, String> = emptyMap(),
    val call: CallContext? = null,
    val tenantId: String? = null,
) {
    init {
        require(context.isNotBlank()) { "A command request names its context." }
        require(aggregate.isNotBlank()) { "A command request names its aggregate." }
        require(command.isNotBlank()) { "A command request names its command." }
    }

    /** This CommandRequest with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        context: String = this.context,
        aggregate: String = this.aggregate,
        command: String = this.command,
        body: CommandBody = this.body,
        caller: Caller = this.caller,
        aggregateId: String? = this.aggregateId,
        target: TargetScope? = this.target,
        requestId: String? = this.requestId,
        expectedVersion: Int? = this.expectedVersion,
        headers: Map<String, String> = this.headers,
        wait: WaitPlan? = this.wait,
        trace: Map<String, String> = this.trace,
        call: CallContext? = this.call,
        tenantId: String? = this.tenantId,
    ): CommandRequest = CommandRequest(
        context,
        aggregate,
        command,
        body,
        caller,
        aggregateId,
        target,
        requestId,
        expectedVersion,
        headers,
        wait,
        trace,
        call,
        tenantId,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is CommandRequest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "CommandRequest(context=$context, aggregate=$aggregate, command=$command, body=$body, " +
            "caller=$caller, aggregateId=$aggregateId, target=$target, requestId=$requestId, " +
            "expectedVersion=$expectedVersion, headers=$headers, wait=$wait, trace=$trace, call=$call, " +
            "tenantId=$tenantId)"

    private fun valueFields(): List<Any?> = listOf(
        context, aggregate, command, body, caller, aggregateId, target, requestId, expectedVersion, headers, wait,
        trace, call, tenantId
    )

    /**
     * Builds a [CommandRequest] from what [of] takes and any of the request's facts, each setter replacing the fact it
     * names; [build] checks the request. A fact added to the request later comes with a setter of its own.
     */
    class Builder internal constructor(
        private val context: String,
        private val aggregate: String,
        private val command: String,
        private val body: CommandBody,
    ) {
        private var caller: Caller = Caller.ANONYMOUS
        private var aggregateId: String? = null
        private var target: TargetScope? = null
        private var requestId: String? = null
        private var expectedVersion: Int? = null
        private var headers: Map<String, String> = emptyMap()
        private var wait: WaitPlan? = null
        private var trace: Map<String, String> = emptyMap()
        private var tenantId: String? = null

        /** Who sends the command; anonymous by default. */
        fun caller(caller: Caller): Builder = apply { this.caller = caller }

        /** The aggregate the command targets; none for a command that creates one with an id it is given. */
        fun aggregateId(aggregateId: String?): Builder = apply { this.aggregateId = aggregateId }

        /** The tenant the request's ref names, which must be the one admission decides; none to take that one. */
        fun tenantId(tenantId: String?): Builder = apply { this.tenantId = tenantId }

        /** The scope a cross-scope caller acts in (`Wow-Scope`). */
        fun target(target: TargetScope?): Builder = apply { this.target = target }

        /** The request id that makes the command idempotent (`Wow-Request-Id`); a new one by default. */
        fun requestId(requestId: String?): Builder = apply { this.requestId = requestId }

        /** The version the caller believes the aggregate is at (`If-Match`). */
        fun expectedVersion(expectedVersion: Int?): Builder = apply { this.expectedVersion = expectedVersion }

        /** The request facts admission may keep on the command. */
        fun headers(headers: Map<String, String>): Builder = apply { this.headers = headers }

        /** What the caller waits for (`Wow-Wait`); the default plan when none is given. */
        fun wait(wait: WaitPlan?): Builder = apply { this.wait = wait }

        /** The trace context the request arrived with (decision D174). */
        fun trace(trace: Map<String, String>): Builder = apply { this.trace = trace }

        /** The request. */
        fun build(): CommandRequest = CommandRequest(
            context, aggregate, command, body, caller, aggregateId, target, requestId, expectedVersion, headers, wait,
            trace, tenantId = tenantId,
        )
    }

    companion object {
        /** A request for [command] of [aggregate] in [context] with [body], from an anonymous caller, with no other fact. */
        @JvmStatic
        fun of(context: String, aggregate: String, command: String, body: CommandBody): CommandRequest =
            CommandRequest(context, aggregate, command, body)

        /** A [Builder] of a request for [command] of [aggregate] in [context] with [body]. */
        @JvmStatic
        fun builder(context: String, aggregate: String, command: String, body: CommandBody): Builder =
            Builder(context, aggregate, command, body)

        /**
         * A request for the command object [command] to the aggregate [ref] names (decision D258), from an anonymous
         * caller, with no other fact: its context and aggregate are [ref]'s type, its command type the one admission
         * derives from [command]'s class, its tenant [ref]'s and its aggregate id [ref]'s, none for an
         * [unassigned][AggregateRef.isAssigned] ref (a creation that leaves its id to admission, decision D191).
         * The object is not encoded: admission checks it as it is, and the command carries it, so it is not changed
         * once sent.
         */
        @JvmStatic
        fun of(command: Any, ref: AggregateRef): CommandRequest = builder(command, ref).build()

        /** A [Builder] of a request for the command object [command] to the aggregate [ref] names, as [of] builds. */
        @JvmStatic
        fun builder(command: Any, ref: AggregateRef): Builder =
            Builder(ref.type.context, ref.type.name, command.javaClass.name, CommandBody.Value(command))
                .aggregateId(ref.id.takeIf { ref.isAssigned })
                .tenantId(ref.tenantId)
    }
}

/**
 * The body of a [CommandRequest]: JSON [Bytes] as received, a JSON [Tree] already parsed, or the command object itself
 * ([Value], decision D258).
 */
sealed interface CommandBody {
    /** A JSON document as received; decoding it is admission's job, so a malformed one is answered as such. */
    class Bytes(val bytes: ByteArray) : CommandBody {
        override fun toString(): String = "Bytes(${bytes.size})"
    }

    /** A parsed JSON document. */
    data class Tree(val node: JsonNode) : CommandBody

    /**
     * The command object itself (decision D258): admission takes it as it is, without decoding it, and checks it as it
     * checks a decoded body; it must be of the command type the request names.
     */
    data class Value(val command: Any) : CommandBody

    companion object {
        /** [json] as a body. */
        @JvmStatic
        fun of(json: String): CommandBody = Bytes(json.toByteArray())
    }
}

/**
 * The root command a synchronous call acts for (decision D193), as the calling service asserts it: the call's command
 * id [commandId] (the id its saga derived, so a wait that follows the saga finds it), and the root command's tenant
 * [tenantId], [scope], [operator] (decisions D163, D189) and the [causation] that makes it a derived command (decision
 * D199), with the [wait] context a wait that follows the saga goes on with, and the root request's [facts] that
 * propagate ([RequestFacts.PROPAGATED], decision D236 ③), such as the calling application: only those, whatever the
 * map it is built with holds, so a call never carries the client's address or `User-Agent`.
 *
 * Admission trusts it only from a [service][Caller.service] identity, and then acts as for a command another context
 * sent through its command topic: the command is in [tenantId] and [scope], an aggregate it creates belongs to them, an
 * existing one is not checked against them, its propagating facts are [facts] (not the calling service's own), and its
 * result is the one at COMMITTED.
 *
 * Fields may be added (decision D216): construct it with [of]; the constructor is internal to Wow.
 */
class CallContext @InternalWowApi constructor(
    val commandId: String,
    val tenantId: String,
    val scope: Scope,
    val causation: Causation,
    val operator: Operator? = null,
    val wait: WaitContext? = null,
    facts: Map<String, String> = emptyMap(),
) {
    /** The root request's facts that propagate ([RequestFacts.PROPAGATED]), such as `app_id`. */
    val facts: Map<String, String> = RequestFacts.propagatedOf(facts)

    init {
        require(commandId.isNotBlank()) { "A call names its command id." }
        require(tenantId.isNotBlank()) { "A call names its tenant." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is CallContext && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "CallContext(commandId=$commandId, tenantId=$tenantId, scope=$scope, causation=$causation, " +
            "operator=$operator, wait=$wait, facts=$facts)"

    private fun valueFields(): List<Any?> = listOf(commandId, tenantId, scope, causation, operator, wait, facts)

    companion object {
        /**
         * The call [commandId] acting for a root command in [tenantId] and [scope], derived by [causation], with the
         * root command's [operator], the [wait] context a wait that follows the calling saga goes on with, and the
         * root request's [facts] that propagate.
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            commandId: String,
            tenantId: String,
            scope: Scope,
            causation: Causation,
            operator: Operator? = null,
            wait: WaitContext? = null,
            facts: Map<String, String> = emptyMap(),
        ): CallContext = CallContext(commandId, tenantId, scope, causation, operator, wait, facts)
    }
}
