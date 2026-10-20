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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.log.HandoffLog
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class HostHandoffTest {
    @Test
    fun `the node's own commits should reach its snapshot subscription in memory`() {
        startHost("wow.log.partitions" to 4).use { it.assertHandoff() }
    }

    @Test
    fun `the hand-over should follow its properties, and be turned off by them`() {
        startHost(
            "wow.log.handoff.capacity" to 16,
            "wow.log.handoff.gap-wait" to "20ms",
            "wow.log.max-poll-interval" to "200ms",
        ).use { host ->
            host.node.options.handoff.let {
                it.enabled.assert().isTrue()
                it.capacity.assert().isEqualTo(16)
                it.gapWait.assert().isEqualTo(20.milliseconds)
                it.probeInterval.assert().isEqualTo(200.milliseconds)
            }
        }
        startHost("wow.log.handoff.enabled" to false).use { host ->
            host.node.options.handoff.enabled.assert().isFalse()
            host.node.contexts.values.flatMap { context -> context.logGroups.map { it.log } }
                .filterIsInstance<HandoffLog>().assert().isEmpty()
        }
    }
}

/**
 * The hand-over of a node's own commits on a host (decision D249): with every partition open, commands that wait for
 * their snapshot (the default wait, decision D69) end, and the tails reached them without reading the store. The same
 * check runs on PostgreSQL and MongoDB in the integration tests.
 */
fun Host.assertHandoff(commands: Int = 10) {
    val logs = node.contexts.values.flatMap { context -> context.logGroups.map { it.log } }
        .filterIsInstance<HandoffLog>()
    logs.assert().isNotEmpty()
    // Every partition's tails open (each reads what is committed first), before the counts are taken.
    val opened = post("/bank/account/open-account", """{"owner":"handoff-warm"}""")
    opened.status.assert().isEqualTo(200)
    val groups = node.contexts.values.flatMap { it.logGroups }
    val deadline = System.nanoTime() + OPEN_TIMEOUT_NANOS
    while (groups.any { it.subscriptions.partitions.size < it.log.partitions } && System.nanoTime() < deadline) {
        Thread.sleep(POLL_MILLIS)
    }
    val reads = logs.sumOf { it.storeReads }
    val delivered = logs.sumOf { it.delivered }

    repeat(commands) {
        val answer = post("/bank/account/open-account", """{"owner":"handoff-$it"}""")
        answer.status.assert().isEqualTo(200)
        answer.body!!["status"].asString().assert().isEqualTo("SUCCEEDED")
        answer.body!!["hop"]["subscription"].asString().assert().isEqualTo("snapshot")
    }

    logs.sumOf { it.storeReads }.assert().isEqualTo(reads)
    (logs.sumOf { it.delivered } - delivered).assert().isGreaterThanOrEqualTo(commands.toLong())
}

private const val OPEN_TIMEOUT_NANOS = 10_000_000_000L
private const val POLL_MILLIS = 20L
