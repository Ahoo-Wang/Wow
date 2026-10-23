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

package me.ahoo.wow.benchmark.load

import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * The nodes an open-loop run sends to, round robin, as a load balancer with passive health checks would (roadmap §6
 * performance item 6): a node whose connection fails is left out for [cooldownNanos], then tried again, so a node
 * stopped in a hand-over run gets no more requests until it is back.
 *
 * An operation whose connection was refused was never sent: it runs again on the next node, its latency still counted
 * from its scheduled time ([rerouted] counts them). A connection that failed otherwise may have carried the request,
 * which a command does not repeat safely: that operation fails as it did, and its node is left out.
 */
class Targets(private val bases: List<URI>, private val cooldownNanos: Long = DEFAULT_COOLDOWN_NANOS) {
    init {
        require(bases.isNotEmpty()) { "At least one target." }
    }

    private val downUntil = AtomicLongArray(bases.size)

    /** The operations a refused connection moved to another node. */
    val rerouted = AtomicLong()

    /** The node operation [index] goes to: round robin over the nodes not left out, the round robin's when all are. */
    fun pick(index: Long): Int {
        val now = System.nanoTime()
        val first = Math.floorMod(index, bases.size.toLong()).toInt()
        return (0 until bases.size).map { (first + it) % bases.size }.firstOrNull { downUntil.get(it) <= now } ?: first
    }

    /** Runs operation [index] of [scenario] on [account] (and [other]), moving it on when a node refuses it. */
    fun run(scenario: Scenario, client: Client, index: Long, account: Int, other: Int): CompletableFuture<String?> =
        attempt(scenario, client, pick(index), account, other, bases.size)

    private fun attempt(
        scenario: Scenario,
        client: Client,
        node: Int,
        account: Int,
        other: Int,
        left: Int,
    ): CompletableFuture<String?> = scenario.run(client, Target(bases[node], account, other))
        .handle { error, failure -> error to failure }
        .thenCompose { (error, failure) ->
            val causes = generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.toList()
            if (causes.any { it is IOException }) down(node)
            if (causes.any { it is ConnectException } && left > 1) {
                rerouted.incrementAndGet()
                attempt(scenario, client, pick(node + 1L), account, other, left - 1)
            } else if (failure != null) {
                CompletableFuture.failedFuture(failure)
            } else {
                CompletableFuture.completedFuture(error)
            }
        }

    private fun down(node: Int) {
        downUntil.set(node, System.nanoTime() + cooldownNanos)
    }

    companion object {
        const val DEFAULT_COOLDOWN_NANOS = 1_000_000_000L
    }
}
