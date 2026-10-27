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

package me.ahoo.wow.runtime.cluster

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.subscription.eventually
import org.junit.jupiter.api.Test
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The greetings of the other members (finding C-20): a greeting that fails, as one does while a new member's address
 * does not resolve yet, is tried again on the next read of the routes, and is a warning only once it keeps failing.
 */
class PeerGreetingsTest {
    @Test
    fun `a greeting is a warning only once it has failed for longer than the member TTL`() {
        val time = TestTimeSource()
        val greetings = PeerGreetings(time, 10.seconds)
        greetings.failed("node-2@a").assert().isFalse()
        time += 5.seconds
        greetings.failed("node-2@a").assert().isFalse()
        greetings.failingFor("node-2@a").assert().isEqualTo(5.seconds)
        time += 6.seconds
        greetings.failed("node-2@a").assert().isTrue()
        greetings.failed("node-3@b").assert().isFalse()
        // Greeted, or gone: it starts over.
        greetings.greeted("node-2@a")
        greetings.failingFor("node-2@a").assert().isEqualTo(kotlin.time.Duration.ZERO)
        greetings.failed("node-2@a").assert().isFalse()
        time += 11.seconds
        greetings.retain(emptySet())
        greetings.failed("node-3@b").assert().isFalse()
    }

    @Test
    fun `a member whose greeting fails is greeted again on the next round`(): Unit = runBlocking {
        val leases = InMemoryLeaseStore()
        val calls = AtomicInteger()
        val greeter = PartitionOwnership(
            GROUP,
            "node-1",
            "node-1:9090",
            PARTITIONS,
            leases,
            fastOptions(),
            context = Dispatchers.Default,
            peers = { member ->
                if (member.node == "node-2" && calls.incrementAndGet() < 3) throw UnresolvedAddressException()
            },
        )
        val other = PartitionOwnership(GROUP, "node-2", "node-2:9090", PARTITIONS, leases, fastOptions())
        greeter.start()
        other.start()
        try {
            eventually { calls.get() >= 3 }
            eventually { "node-2@node-2:9090" in greeter.knownPeers }
        } finally {
            greeter.stop()
            other.stop()
        }
    }

    @Test
    fun `a greeting that keeps failing past the member TTL is still tried each round`(): Unit = runBlocking {
        val leases = InMemoryLeaseStore()
        val calls = AtomicInteger()
        val greeter = PartitionOwnership(
            GROUP,
            "node-1",
            "node-1:9090",
            PARTITIONS,
            leases,
            fastOptions(),
            context = Dispatchers.Default,
            peers = { member ->
                if (member.node == "node-2") {
                    calls.incrementAndGet()
                    throw UnresolvedAddressException()
                }
            },
        )
        val other = PartitionOwnership(GROUP, "node-2", "node-2:9090", PARTITIONS, leases, fastOptions())
        greeter.start()
        other.start()
        try {
            // The member TTL of fastOptions is 1.5 s: the rounds after it warn, and go on trying.
            val started = System.nanoTime()
            eventually { System.nanoTime() - started > 2_000_000_000L && calls.get() > 10 }
            ("node-2@node-2:9090" in greeter.knownPeers).assert().isFalse()
        } finally {
            greeter.stop()
            other.stop()
        }
    }

    private companion object {
        val GROUP = LeaseGroup("greet-service", "greet")
        const val PARTITIONS = 4
    }
}
