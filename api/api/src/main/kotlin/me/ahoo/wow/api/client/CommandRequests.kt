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

@file:JvmName("CommandRequests")

package me.ahoo.wow.api.client

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.requireUse

/**
 * Marks the receiver of a block that sets a [CommandRequest]'s fields ([CommandRequest.Builder]), so that a block of
 * it nested in another sees the inner receiver's names only.
 */
@DslMarker
@Target(AnnotationTarget.CLASS)
annotation class CommandRequestDsl

/**
 * A request of this command: to the aggregate [id] names (none for a creation, whose id the server assigns, or a
 * command its body or a transformer addresses), or to the one the ref [to] names, as [CommandRequest.to] sends (its
 * type, its id when assigned, its tenant), with the fields [block] sets, by the names of [CommandRequest.Builder]:
 *
 * ```kotlin
 * PayOrder(amount).toCommandRequest(orderId)
 * PayOrder(amount).toCommandRequest(orderId) { wait(WaitPlan.COMMITTED); requestId(paymentId) }
 * RecordActionRefused(code).toCommandRequest(to = rejected.source)
 * ```
 *
 * A request names its aggregate by an id or by a ref, not both: giving both fails.
 *
 * It is the request a saga's handler returns, and the one every entry that takes a command sends
 * ([SagaContext.call][me.ahoo.wow.api.subscribe.call], [SagaContext.schedule][me.ahoo.wow.api.subscribe.schedule],
 * [CommandClient.send], [CommandClient.wait], [CommandClient.stream]): `context.call(command, id) { ... }` is
 * `context.call(command.toCommandRequest(id) { ... })`. Java builds the same request with [CommandRequest.builder].
 *
 * A request is not a command: one already built is passed as it is, and its fields changed with
 * [CommandRequest.toBuilder]; making a request of it fails as [CommandRequest] says.
 */
fun <C : Any> C.toCommandRequest(
    id: String? = null,
    to: AggregateRef? = null,
    block: CommandRequest.Builder<C>.() -> Unit = {},
): CommandRequest<C> {
    requireUse(id == null || to == null) {
        "A request names its aggregate by an id or by a ref, not both."
    }
    val builder = to?.let { CommandRequest.builder(it, this) } ?: CommandRequest.builder(this).id(id)
    return builder.apply(block).build()
}
