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
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.schedule.ScheduleContext

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
 *
 * [operator] is whose name the command and what it commits are recorded in (decision D163): admission takes it from
 * the caller ([me.ahoo.wow.api.caller.Caller.toOperator]), null for an anonymous one; a command a saga derives, a
 * compensation and a scheduled command keep the root command's.
 *
 * [wait] is what the caller waits for before it is answered (design 04 §6), by default until the snapshot holds the
 * commit (decision D69). It stays on the node that took the command: only a wait context travels with it.
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
    val operator: Operator? = null,
    val wait: WaitPlan = WaitPlan.DEFAULT,
) {
    init {
        require(id.isNotBlank()) { "A command needs an id." }
        require(requestId.isNotBlank()) { "A command needs a request id." }
        require(expectedVersion == null || expectedVersion >= 0) { "An expected version is never negative." }
    }

    /**
     * Which time of a schedule this command was sent for (decision D198), read from its headers; null when it was not
     * sent by a schedule.
     */
    val schedule: ScheduleContext? get() = ScheduleContext.of(headers)
}
