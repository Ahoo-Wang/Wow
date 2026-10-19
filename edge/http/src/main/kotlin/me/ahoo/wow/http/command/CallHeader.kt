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

package me.ahoo.wow.http.command

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.cluster.WaitWire
import tools.jackson.core.JacksonException
import java.util.Base64

/**
 * The `Wow-Call` header (decision D193): the root command a service's synchronous call acts for, a [CallContext], as
 * the base64url (unpadded) of its JSON. One header carries it all, so one rule guards it: the edge takes it from a
 * service identity only, and refuses it from any other caller.
 *
 * It is the framework's own header between its services, opaque to clients: its JSON members are the call's
 * `commandId`, the root command's `tenantId`, `ownerId` and `spaceId`, its `causation` and `operator`, the `wait`
 * context a wait that follows the calling saga goes on with, and `facts`, the root request's facts that propagate
 * (decision D236 ③, such as `{"app_id":"console"}`): any other member of it is dropped as it is parsed
 * ([CallContext.facts]).
 */
object CallHeader {
    /** [call] as the header's value. */
    fun format(call: CallContext): String = ENCODER.encodeToString(HttpJson.write(CallWire.of(call)))

    /** The call [value] spells; an [IllegalArgumentException] when it does not parse. */
    fun parse(value: String): CallContext {
        val bytes = try {
            DECODER.decode(value.trim())
        } catch (invalid: IllegalArgumentException) {
            throw IllegalArgumentException("Wow-Call is not base64url: ${invalid.message}", invalid)
        }
        val wire = try {
            HttpJson.mapper.readValue(bytes, CallWire::class.java)
        } catch (invalid: JacksonException) {
            throw IllegalArgumentException("Wow-Call is not a call: ${invalid.originalMessage}", invalid)
        }
        return wire.toContext()
    }

    private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    private val DECODER = Base64.getUrlDecoder()

    /** A [CallContext] on the wire. */
    private data class CallWire(
        val commandId: String,
        val tenantId: String,
        val ownerId: String? = null,
        val spaceId: String? = null,
        val causation: Causation,
        val operator: Operator? = null,
        val wait: WaitWire? = null,
        val facts: Map<String, String> = emptyMap(),
    ) {
        fun toContext(): CallContext = CallContext(
            commandId = commandId,
            tenantId = tenantId,
            scope = Scope(ownerId, spaceId),
            causation = causation,
            operator = operator,
            wait = wait?.toContext(),
            facts = facts,
        )

        companion object {
            fun of(call: CallContext): CallWire = CallWire(
                commandId = call.commandId,
                tenantId = call.tenantId,
                ownerId = call.scope.ownerId,
                spaceId = call.scope.spaceId,
                causation = call.causation,
                operator = call.operator,
                wait = call.wait?.let(WaitWire::of),
                facts = call.facts,
            )
        }
    }
}
