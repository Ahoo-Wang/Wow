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

package me.ahoo.wow.runtime.subscription

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** The checkpoints a renamed or removed subscription leaves behind, told at start (decision D372 S6). */
class SubscriptionOrphansTest {
    private val log = InMemoryEventLog(partitions = 2)
    private val checkpoints = InMemoryCheckpointStore()
    private val ledger = InMemoryFailureLedger()

    @Test
    fun `a start warns once of the checkpoints and parked entries under names the group no longer has`(): Unit =
        runBlocking {
            checkpoints.save(GROUP, "Cart.onAdded", 0, 4)
            checkpoints.save(GROUP, "Old.onAdded", 1, 9)
            checkpoints.save(GROUP, "Old.onAdded", 0, 5)
            checkpoints.save(GROUP, "Gone.onAdded", 0, 2)
            checkpoints.save(GROUP, "wow.snapshot.rebuild", 0, 7)
            ledger.park(ParkedAggregate(SubscriptionId("shop", "Old.onAdded"), cart("x1"), 0, 1, "failed", 0))
            val runtime = SubscriptionRuntime(
                GROUP,
                log,
                checkpoints,
                ledger,
                listOf(Subscription("Cart.onAdded", SubscriptionFilter("shop")) { }),
                testOptions(),
            )
            val warnings = logged {
                runtime.start(setOf(0, 1))
                runtime.stop()
                runtime.start(setOf(0))
                runtime.stop()
            }
            warnings.single().assert().contains(
                "Subscription group $GROUP has checkpoints of subscriptions it no longer has: " +
                    "Gone.onAdded (partition 0 at 2; 0 parked); " +
                    "Old.onAdded (partition 0 at 5, partition 1 at 9; 1 parked).",
                "pin its @Name to the old one",
            ).doesNotContain("Cart.onAdded", "wow.snapshot.rebuild")
        }

    @Test
    fun `a sink's other generation is an orphan with its parked entries, and no other name of Wow's is`(): Unit =
        runBlocking {
            // The Elasticsearch sink raised to 2: its generation 1 left behind.
            checkpoints.save(GROUP, "wow.elasticsearch-sink-g2.account", 0, 4)
            checkpoints.save(GROUP, "wow.elasticsearch-sink.account", 0, 3)
            // The ClickHouse sink lowered back to 1: the generation 3 it ran left behind.
            checkpoints.save(GROUP, "wow.clickhouse-sink.account", 1, 2)
            checkpoints.save(GROUP, "wow.clickhouse-sink-g3.account", 1, 6)
            // Wow's names of no sink this group runs.
            checkpoints.save(GROUP, "wow.transport-sink.account", 0, 8)
            checkpoints.save(GROUP, "wow.snapshot.rebuild", 0, 9)
            ledger.park(
                ParkedAggregate(
                    SubscriptionId("shop", "wow.elasticsearch-sink.account"),
                    cart("x1"),
                    0,
                    1,
                    "failed",
                    0,
                ),
            )
            val runtime = SubscriptionRuntime(
                GROUP,
                log,
                checkpoints,
                ledger,
                listOf(
                    Subscription("wow.elasticsearch-sink-g2.account", SubscriptionFilter("shop")) { },
                    Subscription("wow.clickhouse-sink.account", SubscriptionFilter("shop")) { },
                ),
                testOptions(),
            )
            runtime.orphans(runtime.saved(), maxParked = 10).assert().containsExactly(
                OrphanCheckpoints(
                    GROUP,
                    "wow.clickhouse-sink-g3.account",
                    listOf(Checkpoint.of("wow.clickhouse-sink-g3.account", 1, 6)),
                    0,
                ),
                OrphanCheckpoints(
                    GROUP,
                    "wow.elasticsearch-sink.account",
                    listOf(Checkpoint.of("wow.elasticsearch-sink.account", 0, 3)),
                    1,
                ),
            )
            val warnings = logged {
                runtime.start(setOf(0, 1))
                runtime.stop()
            }
            warnings.single().assert()
                .contains("wow.elasticsearch-sink.account (partition 0 at 3; 1 parked)", "skip its parked entries")
                .doesNotContain("wow.transport-sink.account", "wow.snapshot.rebuild")
        }

    @Test
    fun `a group whose checkpoints are all its subscriptions' warns of nothing`(): Unit = runBlocking {
        checkpoints.save(GROUP, "Cart.onAdded", 0, 4)
        val runtime = SubscriptionRuntime(
            GROUP,
            log,
            checkpoints,
            ledger,
            listOf(Subscription("Cart.onAdded", SubscriptionFilter("shop")) { }),
            testOptions(),
        )
        logged {
            runtime.start(setOf(0))
            runtime.stop()
        }.assert().isEmpty()
    }

    @Test
    fun `the orphans hold their positions and parked entries up to a limit, and a runtime sharing its group's checkpoints tells none`(): Unit =
        runBlocking {
            checkpoints.save(GROUP, "Cart.onAdded", 0, 4)
            checkpoints.save(GROUP, "Old.onAdded", 1, 9)
            repeat(
                3
            ) { ledger.park(ParkedAggregate(SubscriptionId("shop", "Old.onAdded"), cart("x$it"), 0, 1, "failed", 0)) }
            val runtime = SubscriptionRuntime(
                GROUP,
                log,
                checkpoints,
                ledger,
                listOf(Subscription("Cart.onAdded", SubscriptionFilter("shop")) { }),
                testOptions(),
            )
            runtime.orphans(runtime.saved(), maxParked = 2).assert().containsExactly(
                OrphanCheckpoints(GROUP, "Old.onAdded", listOf(Checkpoint.of("Old.onAdded", 1, 9)), 2),
            )
            // A context's intake runs in its default log group's name, whose runtime tells the group's orphans.
            val intake = SubscriptionRuntime(
                GROUP,
                LogSource(log),
                checkpoints,
                ledger,
                listOf(Subscription("wow.commands", SubscriptionFilter("shop")) { }),
                testOptions(),
                tellsOrphans = false,
            )
            logged {
                intake.start(setOf(0))
                intake.stop()
            }.assert().isEmpty()
        }

    /**
     * The warnings of the subscription runtime while [block] runs. A coroutine of the stopping runtime may still be
     * logging when [block] returns, so the events are read under the appender's lock, which each append takes.
     */
    private suspend fun logged(block: suspend () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(SubscriptionRuntime::class.java.packageName) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        val events = synchronized(appender) { appender.list.toList() }
        return events.filter { it.level == Level.WARN && "no longer has" in it.formattedMessage }
            .map { it.formattedMessage }
    }

    private companion object {
        const val GROUP = "shop-group"
    }
}
