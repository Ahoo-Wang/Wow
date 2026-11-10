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

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/** What an ownership knows of the other members, on its own: its routes, and whom it greets, and when it does not. */
class PeerRoutesTest {
    private val other = Member(GROUP.service, "node-2", "node-2:9090", Long.MAX_VALUE, setOf(GROUP.name))
    private val membership = mockk<ServiceMembership>(relaxed = true) {
        every { membersOf(GROUP.name) } returns listOf(other)
    }
    private val leases = mockk<LeaseStore> { coEvery { owners(GROUP.name) } returns emptyList() }
    private val greeted = mutableListOf<Member>()
    private var woken = 0

    private fun routes(peers: suspend (Member) -> Unit = { greeted += it }) =
        PeerRoutes(GROUP, NODE, membership, leases, peers, PeerGreetings(TestTimeSource(), 1.minutes)) { woken++ }

    @Test
    fun `a read without the ownership's scope reads the routes and greets nobody`(): Unit = runBlocking {
        val routes = routes()
        routes.refresh(steppedOut = false, upkeep = null)
        routes.routes.members.assert().containsExactly(other)
        routes.knownPeers.assert().isEmpty()
        greeted.assert().isEmpty()
        woken.assert().isEqualTo(1)
    }

    @Test
    fun `a greeting cancelled is not a failure, so the member stays greeted and is not tried again`(): Unit = runBlocking {
        val routes = routes { throw CancellationException("The node stops.") }
        val upkeep = CoroutineScope(coroutineContext + Job())
        routes.refresh(steppedOut = false, upkeep = upkeep)
        upkeep.coroutineContext.job.children.toList().joinAll()
        routes.knownPeers.assert().containsExactly("node-2@node-2:9090")
        upkeep.coroutineContext.job.cancel()
    }

    @Test
    fun `an ownership that has not started reads its routes and greets nobody`(): Unit = runBlocking {
        val ownership =
            PartitionOwnership(GROUP, NODE, "$NODE.internal:7070", 1, InMemoryLeaseStore(), peers = { greeted += it })
        ownership.refreshRoutes()
        ownership.knownPeers.assert().isEmpty()
        greeted.assert().isEmpty()
    }

    private companion object {
        const val NODE = "node-1"
        val GROUP = LeaseGroup("routing-service", "routing")
    }
}
