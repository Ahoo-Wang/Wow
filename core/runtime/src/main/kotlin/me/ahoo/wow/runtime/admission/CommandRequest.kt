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
 */
@InternalWowApi
data class CommandRequest(
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
}

/** The undecoded body of a [CommandRequest]: JSON [Bytes] as received, or a JSON [Tree] already parsed. */
@InternalWowApi
sealed interface CommandBody {
    /** A JSON document as received; decoding it is admission's job, so a malformed one is answered as such. */
    @InternalWowApi
    class Bytes(val bytes: ByteArray) : CommandBody {
        override fun toString(): String = "Bytes(${bytes.size})"
    }

    /** A parsed JSON document. */
    @InternalWowApi
    data class Tree(val node: JsonNode) : CommandBody

    @InternalWowApi
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
