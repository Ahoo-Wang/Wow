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

package me.ahoo.wow.runtime.health

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A sample that cannot read a subject names it among what it could not read, so its readers keep what they last read
 * of a subject that is still there (#6858), and the sampler says so twice: a warning as the read starts failing, a line
 * as it reads again.
 */
class NodeSamplerOutageTest : NodeHealthFixtures() {
    /** While set, the ledger fails to count, as one that is unreachable. */
    @Volatile
    private var ledgerDown = false

    private val ledger = InMemoryFailureLedger()

    private val flaky = BackendFactory {
        ContextBackends(
            log = log,
            checkpoints = InMemoryCheckpointStore(),
            leases = leases,
            ledger = object : FailureLedger by ledger {
                override suspend fun count(subscription: SubscriptionId, max: Int): Int {
                    check(!ledgerDown) { "The ledger is down." }
                    return ledger.count(subscription, max)
                }
            },
            snapshots = SnapshotTargets.single(InMemorySnapshotStore()),
        )
    }

    /** What [block] logs at INFO and above, the sampler's lines. */
    private fun said(block: () -> Unit): List<ILoggingEvent> {
        val lines = ListAppender<ILoggingEvent>().also { it.start() }
        val logger = LoggerFactory.getLogger(NodeSampler::class.java) as Logger
        logger.addAppender(lines)
        try {
            block()
        } finally {
            logger.detachAppender(lines)
        }
        return lines.list.filter { it.level.isGreaterOrEqual(Level.INFO) }
    }

    @Test
    fun `a read that starts failing should be named in its samples, warned of once, and said when it reads again`() {
        val node = node(backends = flaky)
        var now = 0L
        val sampler = NodeSampler(node, 10.seconds, readTimeout = 1.seconds, nanos = { now })
        val said = said {
            runBlocking {
                node.start()
                eventually { node.context(BANK).ownership.owned.size == PARTITIONS }
                val group = node.context(BANK).logGroups.single().subscriptions.group
                val subscriptions = node.parked.groupedSubscriptions.toSet()
                subscriptions.assert().isNotEmpty()
                val read = sampler.sample()
                read.unreadHeads.assert().isEmpty()
                read.unreadParked.assert().isEmpty()
                read.parked.keys.assert().isEqualTo(subscriptions)

                log.down = true
                ledgerDown = true
                now = 1.seconds.inWholeNanoseconds
                val failed = sampler.sample()
                failed.heads.assert().isEmpty()
                failed.parked.assert().isEmpty()
                failed.unreadHeads.assert().containsExactly(group)
                failed.unreadParked.assert().isEqualTo(subscriptions)
                // Failing again only says so at debug.
                now = 2.seconds.inWholeNanoseconds
                sampler.sample().unreadHeads.assert().containsExactly(group)

                log.down = false
                ledgerDown = false
                now = 4.seconds.inWholeNanoseconds
                val again = sampler.sample()
                again.unreadHeads.assert().isEmpty()
                again.unreadParked.assert().isEmpty()
                again.parked.keys.assert().isEqualTo(subscriptions)
                // A read that reads on is not said again.
                sampler.sample()
            }
        }
        val warnings = said.filter { it.level == Level.WARN }
        warnings.size.assert().isEqualTo(1 + node.parked.groupedSubscriptions.size)
        warnings.first().formattedMessage.assert()
            .startsWith("Node node-1 fails to read the heads of ")
            .contains("tries again every 10s", "the node's health leaves it out and its gauges keep their last values")
        warnings.first().throwableProxy.message.assert().isEqualTo("The log is down.")
        warnings.drop(1).forEach {
            it.formattedMessage.assert().startsWith("Node node-1 fails to count the parked entries of ")
            it.throwableProxy.message.assert().isEqualTo("The ledger is down.")
        }
        val recovered = said.filter { it.level == Level.INFO }
        recovered.size.assert().isEqualTo(warnings.size)
        recovered.forEach { it.formattedMessage.assert().startsWith("Node node-1 can ").endsWith(" again, after 3s.") }
        sampler.close()
    }

    @Test
    fun `a read slower than the read timeout should be named in its sample and warned of without a failure`() {
        val node = node(backends = flaky)
        val sampler = NodeSampler(node, 10.seconds, readTimeout = 100.milliseconds)
        val said = said {
            runBlocking {
                node.start()
                eventually { node.context(BANK).ownership.owned.size == PARTITIONS }
                val group = node.context(BANK).logGroups.single().subscriptions.group
                log.hang = CompletableDeferred()
                sampler.sample().unreadHeads.assert().containsExactly(group)
                log.hang?.complete(Unit)
                log.hang = null
                sampler.sample().unreadHeads.assert().isEmpty()
            }
        }
        val warning = said.single { it.level == Level.WARN }
        warning.formattedMessage.assert().startsWith("Node node-1 takes longer than 100ms to read the heads of ")
        warning.throwableProxy.assert().isNull()
        said.single { it.level == Level.INFO }.formattedMessage.assert()
            .startsWith("Node node-1 can read the heads of ")
        sampler.close()
    }

    @Test
    fun `a sample cancelled while it reads should end, its read not counted as failing`() {
        val node = node(backends = flaky)
        val sampler = NodeSampler(node, 10.seconds, readTimeout = 10.seconds)
        val said = said {
            runBlocking {
                node.start()
                eventually { node.context(BANK).ownership.owned.size == PARTITIONS }
                log.hang = CompletableDeferred()
                // One thread: the sample runs until its read of the heads waits, then the delay ends and cancels it.
                val sampling = launch { sampler.sample() }
                delay(100.milliseconds)
                sampling.cancelAndJoin()
                sampling.isCancelled.assert().isTrue()
                log.hang?.complete(Unit)
                log.hang = null
                sampler.sample().unreadHeads.assert().isEmpty()
            }
        }
        said.assert().isEmpty()
        sampler.close()
    }
}
