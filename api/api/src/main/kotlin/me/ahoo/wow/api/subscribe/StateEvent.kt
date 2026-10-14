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

package me.ahoo.wow.api.subscribe

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation

/**
 * What a subscription handler receives (design 04 §1, design 08 §5): one committed event stream of an aggregate,
 * its [events] decoded (and upgraded to their current revision), with the aggregate's [state] after the commit.
 *
 * - [ref] and [version] identify the commit: [version] is the aggregate's version after it. [id] (`ref@version`) is
 *   the causation id of the commands a saga derives from it.
 * - [commandId], [requestId] and [commandType] (the model's name of the command, decision D55) say which command
 *   produced it; [commitTime] is when, in epoch milliseconds.
 * - [scope] is the aggregate's owner and space after the commit (decision D56), not the caller's; the commands a saga
 *   derives from it carry it on (decision D189).
 * - [causation] and [headers] come from the command: the chain it belongs to and the request facts admission kept.
 * - [operator] is whose name the commit is recorded in (decision D163); the commands a saga derives from it keep it.
 *
 * [state] is a copy read from the commit's state view, never the live instance: changing it affects no one. A
 * handler of another context's published stream declares a nullable state, `StateEvent<OrderView?>`, when the
 * upstream publishes the stream without it (decision D140): [state] is then null. `S` is covariant, so a
 * `StateEvent<OrderView>` is a `StateEvent<OrderView?>`.
 */
data class StateEvent<out S>(
    val ref: AggregateRef,
    val version: Int,
    val commandId: String,
    val requestId: String,
    val commandType: String,
    val commitTime: Long,
    val events: List<Any>,
    val state: S,
    val scope: Scope = Scope.NONE,
    val causation: Causation? = null,
    val headers: Map<String, String> = emptyMap(),
    val operator: Operator? = null,
) {
    init {
        require(version >= 1) { "A committed version starts at 1." }
        require(commandId.isNotBlank()) { "A state event names its command." }
        require(commandType.isNotBlank()) { "A state event names the type of its command." }
        require(events.isNotEmpty()) { "A state event holds at least one event." }
    }

    /** The identity of the commit: `ref@version` (design 08 §2). */
    val id: String get() = "$ref@$version"

    /** The id of the root command of the chain: this commit's own command when it has no causation. */
    val correlationId: String get() = causation?.correlationId ?: commandId
}
