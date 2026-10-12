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

package me.ahoo.wow.api.decide

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope

/**
 * An admitted command: who it targets and what it asks, with every fact decided once at admission and read-only
 * afterwards.
 *
 * [requestId] makes the command idempotent within its tenant: a second command with the same request id is never
 * decided again. [expectedVersion], when set, is the version the caller believes the aggregate is at.
 *
 * [scope] and [headers] are what admission learned about the request: the caller's owner and space (from the
 * principal, design 05 §1; decision D56) and the request facts it chose to carry, such as the calling application. A
 * decide function reads them through [DecideScope.command]. The headers are written into the committed event stream,
 * so admission carries only request facts here; what serves only delivery, such as a trace id, travels as transport
 * metadata instead (design 08 §3).
 *
 * [causation] links the command to the chain it belongs to; a command a user sends directly has none.
 */
data class Command<C : Any>(
    val id: String,
    val requestId: String,
    val ref: AggregateRef,
    val body: C,
    val expectedVersion: Int? = null,
    val scope: Scope = Scope.NONE,
    val headers: Map<String, String> = emptyMap(),
    val causation: Causation? = null,
) {
    init {
        require(id.isNotBlank()) { "A command needs an id." }
        require(requestId.isNotBlank()) { "A command needs a request id." }
        require(expectedVersion == null || expectedVersion >= 0) { "An expected version is never negative." }
    }
}
