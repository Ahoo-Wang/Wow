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
            ledger.park(ParkedAggregate("Old.onAdded", cart("x1"), 0, 1, "failed", 0))
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
            repeat(3) { ledger.park(ParkedAggregate("Old.onAdded", cart("x$it"), 0, 1, "failed", 0)) }
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

    /** The warnings of the subscription runtime while [block] runs. */
    private suspend fun logged(block: suspend () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(SubscriptionRuntime::class.java.packageName) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN && "no longer has" in it.formattedMessage }
            .map { it.formattedMessage }
    }

    private companion object {
        const val GROUP = "shop-group"
    }
}
