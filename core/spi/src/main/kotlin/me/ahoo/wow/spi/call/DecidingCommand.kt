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

import me.ahoo.wow.api.annotation.InternalWowApi
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The command a decide function is deciding, in its coroutine context while it decides. A command
 * a Wow client sends from there takes [nextRequestId] as its request id unless its caller gives one, as a saga's
 * derived commands take theirs: a decision decided again after a version conflict sends the same requests again,
 * which their targets answer as repeats rather than run twice. The kernel puts a new one in for each attempt, so the
 * n-th command an attempt sends is the n-th request of every attempt, as long as the decision sends them in order.
 */
@InternalWowApi
class DecidingCommand(val commandId: String) : AbstractCoroutineContextElement(Key) {
    private val sent = AtomicInteger()

    /** The request id of the next command sent while deciding: [requestIdOf] the command and the call's index. */
    fun nextRequestId(): String = requestIdOf(commandId, sent.getAndIncrement())

    override fun toString(): String = "DecidingCommand($commandId)"

    @InternalWowApi
    companion object Key : CoroutineContext.Key<DecidingCommand> {
        /** The request id of the [index]-th command (from 0) sent while deciding [commandId]: a name-based UUID. */
        fun requestIdOf(commandId: String, index: Int): String =
            UUID.nameUUIDFromBytes("$commandId#call-$index".toByteArray(Charsets.UTF_8)).toString()
    }
}

/**
 * The request id of a command a client sends in this context: [given], the caller's own, else the next of the
 * command being decided ([DecidingCommand]), else null, for the client to choose.
 */
@InternalWowApi
fun CoroutineContext.requestIdOf(given: String?): String? = given ?: this[DecidingCommand]?.nextRequestId()
