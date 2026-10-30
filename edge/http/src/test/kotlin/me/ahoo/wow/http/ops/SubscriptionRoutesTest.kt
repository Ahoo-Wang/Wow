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

package me.ahoo.wow.http.ops

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.http.Answer
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.http.TENANT
import me.ahoo.wow.http.answer
import me.ahoo.wow.http.cartManifest
import me.ahoo.wow.http.cartModel
import me.ahoo.wow.http.headerCallers
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.RouteAction
import me.ahoo.wow.runtime.admission.ServiceRouteAuthorizer
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.runtime.node.PartitionView
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SubscriptionView
import me.ahoo.wow.runtime.node.SubscriptionsView
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.runtime.subscription.OrphanCheckpoints
import me.ahoo.wow.runtime.subscription.PausedPartition
import me.ahoo.wow.runtime.subscription.SubscriptionId
import me.ahoo.wow.runtime.subscription.ThrottledPartition
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.concurrent.CopyOnWriteArrayList

/** The operator's read of a node's subscriptions (decision D372 S7): who may read it, and what it writes. */
class SubscriptionRoutesTest {
    private val checkpoints = InMemoryCheckpointStore()
    private val ledger = InMemoryFailureLedger()

    /** While set, the checkpoint store is down. */
    @Volatile
    private var down = false

    private val store = object : CheckpointStore by checkpoints {
        override suspend fun list(group: String): List<Checkpoint> {
            if (down) throw StorageUnavailableException("The checkpoints are unavailable.")
            return checkpoints.list(group)
        }
    }

    /** A node of the cart, not started: it runs no partition, so each subscription stands at its checkpoints. */
    private val node = ServiceNode(
        "shop-service",
        "node-1",
        "node-1.internal:9090",
        listOf(cartModel),
        cartManifest(),
        BackendFactory {
            ContextBackends(
                log = InMemoryEventLog(partitions = 2),
                checkpoints = store,
                leases = InMemoryLeaseStore(),
                ledger = ledger,
                snapshots = SnapshotTargets.single(InMemorySnapshotStore()),
            )
        },
        ClusterChannel { _, _ -> error("A single node forwards nothing.") },
    )
    private val actions = CopyOnWriteArrayList<RouteAction>()
    private val authorizer = ServiceRouteAuthorizer { caller, action ->
        actions += action
        if (caller.subject == "mallory") Authorization.Denied("Mallory may not.") else Authorization.Allowed
    }
    private val client = WebTestClient
        .bindToRouterFunction(subscriptionRoutes(NodeSampler(node), cartManifest(), authorizer, headerCallers))
        .build()

    private fun read(user: String? = "root", admin: Boolean = true): Answer = client.get().uri(OpsPaths.SUBSCRIPTIONS)
        .headers {
            if (user != null) it.set("X-User", user)
            if (admin) it.set("X-Admin", "true")
        }.answer()

    @Test
    fun `an operator reads each subscription where it stands`(): Unit =
        runBlocking {
            checkpoints.save(GROUP, "wow.snapshot", 1, 3)
            checkpoints.save(GROUP, "Old.onAdded", 0, 5)

            val answer = read()

            answer.status.assert().isEqualTo(200)
            answer.contentType.assert().contains("application/json")
            val body = answer.body!!
            body["node"].asString().assert().isEqualTo("node-1")
            val snapshot = body["subscriptions"].toList().single { it["subscription"].asString() == "shop.wow.snapshot" }
            snapshot["group"].asString().assert().isEqualTo(GROUP)
            snapshot["source"].asString().assert().isEqualTo("log")
            // The node has not sampled: its parked entries are not counted yet.
            snapshot["parked"].isNull.assert().isTrue()
            // A partition another node runs: where its checkpoint stands, nothing more.
            snapshot["partitions"].toList().map { it.toString() }.assert().containsExactly(
                """{"partition":1,"position":3}"""
            )
            // A node tells the checkpoints left under old names once it runs and has its subscriptions.
            body["orphans"].toList().assert().isEmpty()
            actions.single().assert().isEqualTo(
                RouteAction("wow", "ops.subscriptions", "GET", OpsPaths.SUBSCRIPTIONS, TENANT, Scope("root")),
            )
        }

    @Test
    fun `only an authenticated caller who reaches every tenant, and whom the authorizer allows, reads them`() {
        read(user = null).let {
            it.status.assert().isEqualTo(401)
            it.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_UNAUTHENTICATED)
        }
        // A caller confined to its tenant: the view is the whole service's.
        read(user = "alice", admin = false).let {
            it.status.assert().isEqualTo(403)
            it.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
            it.body!!["detail"].asString().assert().contains("confined to tenant $TENANT")
        }
        actions.assert().isEmpty()
        read(user = "mallory").let {
            it.status.assert().isEqualTo(403)
            it.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
            it.body!!["detail"].asString().assert().isEqualTo("Mallory may not.")
        }
        client.get().uri(OpsPaths.SUBSCRIPTIONS).header("X-User", "root").header("X-Admin", "true")
            .header("Accept", "text/html").answer().status.assert().isEqualTo(406)
    }

    @Test
    fun `a checkpoint store that is unavailable is a problem the client retries after a while`() {
        down = true
        val answer = read()
        answer.status.assert().isEqualTo(503)
        answer.contentType.assert().contains("problem+json")
        answer.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.STORAGE_UNAVAILABLE)
        answer.headers.getFirst("Retry-After").assert().isEqualTo("1")
    }

    @Test
    fun `a partition this node runs shows its head and lag and why it is held back`() {
        val partition = PartitionView(
            0,
            4,
            runs = true,
            head = 9,
            paused = PausedPartition(GROUP, "Board.onAdded", 0, 1_000, "StorageUnavailableException"),
            throttled = ThrottledPartition(GROUP, "Board.onAdded", 0, 2, 2_000),
        )
        val view = SubscriptionsView(
            "node-1",
            listOf(
                SubscriptionView(
                    SubscriptionId("shop", "Board.onAdded"),
                    GROUP,
                    SubscriptionView.Source.LOG,
                    7,
                    listOf(partition)
                ),
                SubscriptionView(
                    SubscriptionId("shop", "wow.commands"),
                    GROUP,
                    SubscriptionView.Source.TOPIC,
                    0,
                    emptyList()
                ),
            ),
            listOf(OrphanCheckpoints(GROUP, "Old.onAdded", listOf(Checkpoint.of("Old.onAdded", 1, 2)), 3)),
        )
        json(viewOf(view)).assert().isEqualTo(
            """{"node":"node-1","subscriptions":[""" +
                """{"subscription":"shop.Board.onAdded","group":"$GROUP","source":"log","parked":7,"partitions":[""" +
                """{"partition":0,"position":4,"runs":true,"head":9,"lag":5,""" +
                """"paused":{"since":1000,"reason":"StorageUnavailableException"},"throttled":{"limit":2,"since":2000}}]},""" +
                """{"subscription":"shop.wow.commands","group":"$GROUP","source":"topic","parked":0,"partitions":[]}],""" +
                """"orphans":[{"group":"$GROUP","name":"Old.onAdded","partitions":[{"partition":1,"position":2}],"parked":3}]}""",
        )
    }

    private fun json(value: Any): String = HttpJson.mapper.writeValueAsString(value)

    private companion object {
        const val GROUP = "shop-service/shop"
    }
}
