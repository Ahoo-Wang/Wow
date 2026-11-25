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

package me.ahoo.wow.spi.call

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.spi.id.DerivedIds
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The command a decide function is deciding, by its aggregate [ref] and its [requestId], in its coroutine context
 * while it decides: one for each execution of the command, every attempt of it included ([nextAttempt]).
 *
 * A command a Wow client sends from there takes [nextRequestId] as its request id unless its request gives one, as a
 * saga's derived commands take theirs: a call that stands. The request id, not the command id, keys it: the gateway
 * gives every arrival a new command id, while a caller's retry keeps the request id. So a decision decided again,
 * after a version conflict or for a caller's retry of an attempt that called a peer and did not commit, sends the same
 * requests again, which their targets answer as repeats rather than run twice: the n-th command an attempt sends is
 * the n-th request of every attempt, as long as the decision sends them in order.
 *
 * The aggregate's id is not part of it, its type and tenant are: a request id names one request of its aggregate type
 * in its tenant, whichever aggregate it names (the log's request key), and admission gives a creation that names no
 * aggregate a new id each time it handles it. So a caller's retry of such a creation, a copy its command topic
 * delivers again and a saga's derived creation handled again send the same requests too.
 *
 * A repeat is answered with the first call's commit, so a call decided again must be that same call. Within an
 * execution, a call whose target, command type or body differs from the call that first took its index fails the
 * command ([Misuse.State]): it would be answered with another call's commit. A call the decision undoes in
 * `onAppendFailure` takes an id of its attempt's own instead (`DecideScope.attemptKey()`), and a call that may change
 * takes a business request id.
 */
@InternalWowApi
class DecidingCommand(val ref: AggregateRef, val requestId: String) : AbstractCoroutineContextElement(Key) {
    private val lock = Any()

    /** The index the next call of this attempt takes. */
    private var sent = 0

    /** The call that first took each index in this execution: the one its target committed. */
    private val calls = ArrayList<CommandRequest<*>>()

    /** Starts another attempt of the command: its calls take their indices from 0 again. */
    fun nextAttempt() {
        synchronized(lock) { sent = 0 }
    }

    /**
     * The request id of [call], the next command sent while deciding without a request id of its own: [requestIdOf]
     * the command and the call's index. A [Misuse.State] when an earlier attempt of this execution sent another call
     * at that index.
     */
    fun nextRequestId(call: CommandRequest<*>): String {
        val index = synchronized(lock) {
            val index = sent++
            if (index == calls.size) {
                calls += call
            } else {
                val first = calls[index]
                if (!sameCall(first, call)) throw changed(index, first, call)
            }
            index
        }
        return requestIdOf(ref.type, ref.tenantId, requestId, index)
    }

    private fun changed(index: Int, first: CommandRequest<*>, call: CommandRequest<*>): Misuse.State = Misuse.State(
        "$ref, decided again for request $requestId, sent ${describe(call)} as its call #${index + 1}, where an " +
            "earlier attempt sent ${describe(first)}: a call decided again takes the same request id, and its target " +
            "answers it with the first call's commit. Undo a call that may change in onAppendFailure and send it " +
            "with requestId = attemptKey(), or give it a business request id.",
    )

    override fun toString(): String = "DecidingCommand($ref, $requestId)"

    @InternalWowApi
    companion object Key : CoroutineContext.Key<DecidingCommand> {
        private const val CALL = "call"

        /**
         * The request id of the [index]-th command (from 0) sent while deciding the request [requestId] of [type] in
         * [tenantId]: a name-based UUID of the aggregate type, the tenant, the request id and the index, in
         * [DerivedIds.encoded]. It leaves out the aggregate's id, which every attempt of a request does not share.
         */
        fun requestIdOf(type: AggregateType, tenantId: String, requestId: String, index: Int): String = DerivedIds.idOf(
            DerivedIds.encoded(CALL, type.context, type.name, tenantId, requestId, index.toString()),
        )

        /**
         * Whether [call] asks what [first] asked: the same target (type, id, tenant and scope), command class and
         * body, equal or written as the same JSON.
         */
        internal fun sameCall(first: CommandRequest<*>, call: CommandRequest<*>): Boolean =
            first.command.javaClass == call.command.javaClass &&
                first.type == call.type &&
                first.id == call.id &&
                first.targetTenant == call.targetTenant &&
                first.targetScope == call.targetScope &&
                (first.command == call.command || treeOf(first.command) == treeOf(call.command))

        private fun treeOf(body: Any): JsonNode = WowJson.mapper.valueToTree(body)

        private fun describe(call: CommandRequest<*>): String {
            val target = call.type?.let { type -> call.id?.let { "$type/$it" } ?: "$type" } ?: call.id ?: "its aggregate"
            return "${call.command.javaClass.simpleName} ${treeOf(call.command)} to $target"
        }
    }
}

/**
 * The request id of [request], a command a client sends in this context: its own ([CommandRequest.requestId]), else
 * the next of the command being decided ([DecidingCommand.nextRequestId]), else null, for the client to choose.
 */
@InternalWowApi
fun CoroutineContext.requestIdOf(request: CommandRequest<*>): String? =
    request.requestId ?: this[DecidingCommand]?.nextRequestId(request)
