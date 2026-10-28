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

package me.ahoo.wow.kernel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** The bound of a read-only replay ([Kernel.stateAt], [Kernel.trace]; review R4-Q, Q-1). */
class KernelReplayLimitTest {
    private class CountingLog(private val delegate: EventLog = InMemoryEventLog()) : EventLog by delegate {
        var streamsRead = 0

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
            delegate.read(ref, fromVersion, toVersion).onEach { streamsRead++ }
    }

    private val codec = EventCodec()
    private val log = CountingLog()
    private var now = 0L
    private val kernel = Kernel(log, codec, clock = { now })
    private val ref = cartModel.type.ref("tenant", "cart-1")
    private var sequence = 0

    private suspend fun send(body: Any, at: Long): CommandOutcome.Committed {
        now = at
        return kernel.execute(
            cartModel,
            Command("c-${++sequence}", "r-$sequence", ref, body)
        ) as CommandOutcome.Committed
    }

    /** Versions 1 to 4 committed at 1000, 2000, 3000 and 4000 ms; returns the snapshot of version 2. */
    private suspend fun history(): SnapshotRecord {
        send(CreateCart("ahoo"), 1000)
        val second = send(AddItem("apple", 1), 2000)
        send(AddItem("pear", 2), 3000)
        send(AddItem("apple", 3), 4000)
        log.streamsRead = 0
        return codec.snapshotOf(cartModel, ref, second.state as CartState, 2, Scope.NONE, 1000, 2000)
    }

    private suspend fun refused(read: suspend () -> Any?): ReplayLimitExceededException =
        runCatching { read() }.exceptionOrNull() as ReplayLimitExceededException

    @Test
    fun `a state at a version further than the bound is refused before the log is read`(): Unit = runBlocking {
        history()
        val refused = refused { kernel.stateAt(cartModel, ref, 4, maxReplay = 3) }
        refused.ref.assert().isEqualTo(ref)
        refused.limit.assert().isEqualTo(3)
        refused.message.assert().contains("more than 3 streams")
        log.streamsRead.assert().isZero()
        kernel.stateAt(cartModel, ref, 3, maxReplay = 3)!!.version.assert().isEqualTo(3)
        log.streamsRead.assert().isEqualTo(3)
    }

    @Test
    fun `a read that names no bound takes the kernel's, finite by default`(): Unit = runBlocking {
        // Finding R5.A-6: the kernel's options bound every read that does not name its own.
        KernelOptions().maxReplay.assert().isEqualTo(KernelOptions.DEFAULT_MAX_REPLAY).isEqualTo(10_000)
        history()
        val bounded = Kernel(log, codec, options = KernelOptions(maxReplay = 2))
        val refused = refused { bounded.stateAt(cartModel, ref, 4) }
        refused.limit.assert().isEqualTo(2)
        refused.message.assert().contains(ref.toString()).contains("snapshots of its type")
        refused { bounded.stateAt(cartModel, ref, Instant.ofEpochMilli(4000)) }.limit.assert().isEqualTo(2)
        // A trace from version 4 replays the state at version 3 first.
        refused { bounded.trace(cartModel, ref, 4, 4, 10) }.limit.assert().isEqualTo(2)
        bounded.stateAt(cartModel, ref, 4, maxReplay = Int.MAX_VALUE)!!.version.assert().isEqualTo(4)
    }

    @Test
    fun `the kernel's options are values, and refuse what makes no sense`() {
        val options = KernelOptions(maxAttempts = 2, verifyDecidePurity = true, maxReplay = 5)
        options.assert().isEqualTo(KernelOptions(maxAttempts = 2, verifyDecidePurity = true, maxReplay = 5))
        options.hashCode().assert().isEqualTo(
            KernelOptions(maxAttempts = 2, verifyDecidePurity = true, maxReplay = 5).hashCode()
        )
        options.assert().isNotEqualTo(KernelOptions())
        options.toString().assert().contains("maxAttempts=2").contains("maxReplay=5")
        listOf(
            { KernelOptions(maxAttempts = 0) },
            { KernelOptions(recentRequests = -1) },
            { KernelOptions(maxEarlyWait = (-1).seconds) },
            { KernelOptions(maxReplay = 0) },
        ).forEach { make ->
            runCatching { make() }.exceptionOrNull().assert().isInstanceOf(
                IllegalArgumentException::class.java
            )
        }
    }

    @Test
    fun `a state at a version is counted from the snapshot when it fits`(): Unit = runBlocking {
        val snapshot = history()
        // Two streams after the snapshot of version 2.
        kernel.stateAt(cartModel, ref, 4, snapshot, maxReplay = 2)!!.version.assert().isEqualTo(4)
        log.streamsRead = 0
        refused { kernel.stateAt(cartModel, ref, 4, snapshot, maxReplay = 1) }.limit.assert().isEqualTo(1)
        log.streamsRead.assert().isZero()
        // A snapshot that does not fit leaves the replay from version 1, which the bound refuses.
        val unfit = snapshot.copyWith(schemaFingerprint = "another")
        refused { kernel.stateAt(cartModel, ref, 4, unfit, maxReplay = 2) }.limit.assert().isEqualTo(2)
        kernel.stateAt(cartModel, ref, 2, unfit, maxReplay = 2)!!.version.assert().isEqualTo(2)
    }

    @Test
    fun `a state as of a time is refused once it would fold one stream more`(): Unit = runBlocking {
        val snapshot = history()
        refused { kernel.stateAt(cartModel, ref, Instant.ofEpochMilli(5000), maxReplay = 2) }
        // Folded as read: the two streams the bound allows, and the third it refuses; never the fourth.
        log.streamsRead.assert().isEqualTo(3)
        // The stream that ends the read is not folded.
        kernel.stateAt(cartModel, ref, Instant.ofEpochMilli(2500), maxReplay = 2)!!.version.assert().isEqualTo(2)
        // From the snapshot, its tail is what counts.
        kernel.stateAt(cartModel, ref, Instant.ofEpochMilli(5000), snapshot, maxReplay = 2)!!
            .version.assert().isEqualTo(4)
        refused { kernel.stateAt(cartModel, ref, Instant.ofEpochMilli(5000), snapshot, maxReplay = 1) }
            .limit.assert().isEqualTo(1)
    }

    @Test
    fun `a trace reaches its first version within the bound`(): Unit = runBlocking {
        val snapshot = history()
        refused { kernel.trace(cartModel, ref, 4, 4, 1, maxReplay = 2) }.limit.assert().isEqualTo(2)
        log.streamsRead.assert().isZero()
        kernel.trace(cartModel, ref, 4, 4, 1, snapshot, maxReplay = 2).single().version.assert().isEqualTo(4)
        // A trace from version 1 replays nothing before it.
        kernel.trace(cartModel, ref, 1, 4, 4, maxReplay = 1).assert().hasSize(4)
    }

    @Test
    fun `a bound below one stream is not asked for`(): Unit = runBlocking {
        history()
        listOf<suspend () -> Any?>(
            { kernel.stateAt(cartModel, ref, 1, maxReplay = 0) },
            { kernel.stateAt(cartModel, ref, Instant.MAX, maxReplay = 0) },
        ).forEach { read ->
            runCatching { read() }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
