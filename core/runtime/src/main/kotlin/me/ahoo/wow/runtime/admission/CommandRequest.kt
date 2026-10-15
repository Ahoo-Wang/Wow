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

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
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
 * (`AddItem` for `shop.cart.AddItem`). Identity is not taken from anywhere else:
 * not from [headers] (a header named `Wow-…` or `wow.…` is reserved and refused) and never from the body (D68).
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
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is CommandRequest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "CommandRequest(context=$context, aggregate=$aggregate, command=$command, body=$body, " +
            "caller=$caller, aggregateId=$aggregateId, target=$target, requestId=$requestId, " +
            "expectedVersion=$expectedVersion, headers=$headers, wait=$wait, trace=$trace, call=$call)"

    private fun valueFields(): List<Any?> = listOf(
        context, aggregate, command, body, caller, aggregateId, target, requestId, expectedVersion, headers, wait,
        trace, call
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

        /** Who sends the command; anonymous by default. */
        fun caller(caller: Caller): Builder = apply { this.caller = caller }

        /** The aggregate the command targets; none for a command that creates one with an id it is given. */
        fun aggregateId(aggregateId: String?): Builder = apply { this.aggregateId = aggregateId }

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
            trace,
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
    }
}

/** The undecoded body of a [CommandRequest]: JSON [Bytes] as received, or a JSON [Tree] already parsed. */
sealed interface CommandBody {
    /** A JSON document as received; decoding it is admission's job, so a malformed one is answered as such. */
    class Bytes(val bytes: ByteArray) : CommandBody {
        override fun toString(): String = "Bytes(${bytes.size})"
    }

    /** A parsed JSON document. */
    data class Tree(val node: JsonNode) : CommandBody

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
 * D199), with the [wait] context a wait that follows the saga goes on with.
 *
 * Admission trusts it only from a [service][Caller.service] identity, and then acts as for a command another context
 * sent through its command topic: the command is in [tenantId] and [scope], an aggregate it creates belongs to them, an
 * existing one is not checked against them, and its result is the one at COMMITTED.
 */
data class CallContext(
    val commandId: String,
    val tenantId: String,
    val scope: Scope,
    val causation: Causation,
    val operator: Operator? = null,
    val wait: WaitContext? = null,
) {
    init {
        require(commandId.isNotBlank()) { "A call names its command id." }
        require(tenantId.isNotBlank()) { "A call names its tenant." }
    }
}
