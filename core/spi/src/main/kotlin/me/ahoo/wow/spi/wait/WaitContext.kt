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

package me.ahoo.wow.spi.wait

import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage

/**
 * The node a wait started on (decision D65): its [node] id, its internal [address] (`host:port` of the internal
 * signal entry, never the public one) and its [service]. A signal goes straight to it, not through a load balancer.
 */
data class Origin(val node: String, val address: String, val service: String) {
    init {
        require(node.isNotBlank()) { "An origin names its node." }
        require(service.isNotBlank()) { "An origin names its service." }
    }
}

/**
 * What travels with a command's messages and its log entry while its caller waits beyond COMMITTED (design 04
 * §6.2, decision D25): transport metadata, never a fact, invisible to domain code.
 *
 * - [waitId] identifies the wait on its [origin].
 * - [remaining] are the hops not reached yet. On a log entry they start after COMMITTED, so the first one, a
 *   HANDLED hop, names the subscription that signals once it has handled the entry.
 * - [deadline] is when the wait gives up, in epoch milliseconds; past it no one signals (decision D64).
 */
data class WaitContext(val waitId: String, val origin: Origin, val remaining: List<Hop>, val deadline: Long) {
    init {
        require(waitId.isNotBlank()) { "A wait context names its wait." }
    }

    /** Whether the wait is over at [now]. */
    fun expiredAt(now: Long): Boolean = now > deadline

    /** The HANDLED hop an entry carrying this context waits for next, if any. */
    val nextHandled: Hop? get() = remaining.firstOrNull()?.takeIf { it.stage == Stage.HANDLED }

    /**
     * This context for the commands a saga derives at the hop [remaining] starts with: the hops after it, or null
     * when there are none (the chain ends at the saga).
     */
    fun derived(): WaitContext? = remaining.drop(1).takeIf { it.isNotEmpty() }?.let { copy(remaining = it) }

    /** This context once its command is committed: without the leading ACCEPTED and COMMITTED hops. */
    fun afterCommitted(): WaitContext = copy(remaining = remaining.dropWhile { it.stage != Stage.HANDLED })
}
