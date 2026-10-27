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

package me.ahoo.wow.api.wire

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.Hop
import java.util.Base64

/**
 * The `Wow-Call` header: the root command a service's synchronous call acts for, a [CallWire], as
 * the base64url (unpadded) of its JSON. One header carries it all, so one rule guards it: the edge takes it from a
 * service identity only, and refuses it from any other caller.
 *
 * It is the framework's own header between its services, opaque to clients. This object is its framing; the JSON of
 * a [CallWire] is Wow's one JSON configuration (`WowJson`), which the API does not depend on.
 */
@InternalWowApi
object CallHeader {
    /** The header's value carrying [json], the JSON of a [CallWire]. */
    fun encode(json: ByteArray): String = ENCODER.encodeToString(json)

    /** The JSON [value] carries; an [IllegalArgumentException] when it is not base64url. */
    fun decode(value: String): ByteArray = try {
        DECODER.decode(value.trim())
    } catch (invalid: IllegalArgumentException) {
        throw IllegalArgumentException("Wow-Call is not base64url: ${invalid.message}", invalid)
    }

    private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    private val DECODER = Base64.getUrlDecoder()
}

/**
 * The root command of a call on the wire ([CallHeader]): the call's [commandId], the root command's [tenantId],
 * [ownerId] and [spaceId], its [causation] and [operator], the [wait] context a wait that follows the calling saga goes
 * on with, and [facts], the root request's facts that propagate (such as `{"app_id":"console"}`).
 */
@InternalWowApi
data class CallWire(
    val commandId: String,
    val tenantId: String,
    val ownerId: String? = null,
    val spaceId: String? = null,
    val causation: Causation,
    val operator: Operator? = null,
    val wait: WaitWire? = null,
    val facts: Map<String, String> = emptyMap(),
)

/**
 * A wait context on the wire, in `Wow-Call` and the cluster channel's messages: the wait's [waitId]
 * on its [origin], the hops it has not reached yet ([remaining]) and its [deadline] in epoch milliseconds.
 */
@InternalWowApi
data class WaitWire(val waitId: String, val origin: Origin, val remaining: List<Hop>, val deadline: Long) {
    /** The node a wait started on: its [node] id, internal [address] and [service]. */
    @InternalWowApi
    data class Origin(val node: String, val address: String, val service: String)
}
