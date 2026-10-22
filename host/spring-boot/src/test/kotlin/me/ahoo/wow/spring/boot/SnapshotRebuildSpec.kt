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

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.snapshot.SnapshotRebuilds
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.SnapshotManifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandRequest
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.Routing
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.snapshot.SnapshotMaintenance
import me.ahoo.wow.runtime.snapshot.SnapshotOptions
import me.ahoo.wow.runtime.snapshot.SnapshotPlacement
import me.ahoo.wow.runtime.store.StoreLayout
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotTable
import me.ahoo.wow.spi.store.Store
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import me.ahoo.wow.manifest.Creation as ManifestCreation

/**
 * Snapshot rebuild, repair and backfill (decision D172, roadmap 6.2e) end to end, on the nodes of a service over the
 * snapshot stores a backend gives ([store]: `a`, which holds the log too, and `b`): an in-place rebuild after the state
 * revision is raised; a rolling upgrade, where a node of the old revision keeps serving without overwriting the new
 * one, and its rollback; a target added and backfilled, made primary, the old one dropped; and a rebuild request that
 * repairs a snapshot. Each backend runs it: memory here, PostgreSQL and MongoDB in the integration tests.
 *
 * The snapshot maintenance runs only when a test drives it ([maintain]): its interval is an hour.
 */
abstract class SnapshotRebuildSpec {
    /** A new store named [name] of the backend; `a` holds the logs too. */
    protected abstract fun store(name: String): Store

    private val context = "rebuild-" + UUID.randomUUID().toString().take(ID_LENGTH)
    private val type = AggregateType(context, COUNTER)
    private val stores: Map<String, Store> by lazy { listOf(A, B).associateWith(::store) }
    private val channel = LoopbackClusterChannel()
    private val nodes = ConcurrentHashMap<String, ServiceNode>()

    @AfterEach
    fun stopNodes(): Unit = runBlocking {
        nodes.values.forEach { it.stop() }
    }

    @Test
    fun `a raised state revision rebuilds the snapshots in place`(): Unit = runBlocking {
        val first = start("node-1", revision = 1)
        IDS.forEach { id -> repeat(2) { add(first, id, 2) } }
        eventually { IDS.all { snapshot(A, it)?.version == 2 } }
        IDS.forEach { snapshot(A, it)!!.assertCounter(revision = 1, version = 2, total = 4, adds = 0) }
        first.stop()

        val second = start("node-2", revision = 2)
        table(A).assert().isEqualTo(SnapshotTable(2, rebuilding = true, backfilled = false))
        second.snapshotStatus.rebuilding(type).assert().isTrue()
        // The write side does not read a snapshot of another revision back: it replays the log.
        add(second, IDS.first(), 1)
        // Rebuilt, and caught up with the log: the table was first claimed by node-1, which never ran a round to mark it
        // backfilled, so a round of node-2 marks it once its `snapshot` subscription passed the log's head.
        eventually {
            maintain(second)
            !table(A).catchingUp
        }
        table(A).assert().isEqualTo(SnapshotTable(2))
        snapshot(A, IDS.first())!!.assertCounter(revision = 2, version = 3, total = 5, adds = 3)
        IDS.drop(1).forEach { snapshot(A, it)!!.assertCounter(revision = 2, version = 2, total = 4, adds = 2) }
        second.snapshotStatus.rebuilding(type).assert().isFalse()
        second.snapshotStatus.rebuilding(AggregateType(context, "unknown")).assert().isFalse()
    }

    @Test
    fun `a rolling upgrade keeps the old node serving without overwriting the new revision, and rolls back`(): Unit =
        runBlocking {
            val old = start("old-1", revision = 1)
            IDS.forEach { add(old, it, 1) }
            eventually { IDS.all { snapshot(A, it)?.version == 1 } }

            val new = start("new-1", revision = 2)
            table(A).revision.assert().isEqualTo(2)
            eventually {
                maintain(new)
                IDS.all { snapshot(A, it)?.stateRevision == 2 }
            }
            // The old node still takes commands; whichever node writes their snapshots, none goes back to revision 1.
            IDS.forEach { add(old, it, 1) }
            maintain(old)
            table(A).assert().isEqualTo(table(A).copyWith(revision = 2, rebuilding = true))
            eventually {
                // The old node keeps the table rebuilding on each of its runs: its refused saves leave stale marks.
                maintain(old)
                maintain(new)
                IDS.all { snapshot(A, it)?.let { s -> s.version == 2 && s.stateRevision == 2 } == true }
            }
            IDS.forEach { snapshot(A, it)!!.assertCounter(revision = 2, version = 2, total = 2, adds = 2) }
            old.stop()
            eventually {
                maintain(new)
                !new.snapshotStatus.rebuilding(type)
            }
            table(A).assert().isEqualTo(SnapshotTable(2))

            // The rollback: the new revision is gone, a node of the old one starts and rebuilds back.
            new.stop()
            val back = start("old-2", revision = 1)
            table(A).assert().isEqualTo(table(A).copyWith(revision = 1, rebuilding = true))
            eventually {
                maintain(back)
                !table(A).rebuilding
            }
            IDS.forEach { snapshot(A, it)!!.assertCounter(revision = 1, version = 2, total = 2, adds = 0) }
        }

    @Test
    fun `a target added is backfilled, made primary, and the old one dropped`(): Unit = runBlocking {
        val first = start("node-1", revision = 1)
        IDS.forEach { add(first, it, 3) }
        eventually { IDS.all { snapshot(A, it)?.version == 1 } }
        first.stop()

        // `b`'s subscription starts from the beginning of the log; stopped before it caught up, its store is past the
        // beginning but not backfilled: the next node fills it with a subscription of its own.
        val started = start("node-2", revision = 1, targets = listOf(A, B))
        started.context(context).snapshotSubscriptions.map { it.name }.assert()
            .containsExactly("snapshot", "snapshot:$B")
        started.snapshotStatus.rebuilding(type).assert().isTrue()
        eventually { snapshot(B, IDS.first()) != null }
        started.stop()
        table(B).backfilled.assert().isFalse()
        val both = start("node-3", revision = 1, targets = listOf(A, B))
        both.context(context).subscriptions.subscriptionNames.assert().contains("snapshot:$B#$COUNTER")
        eventually {
            maintain(both)
            table(A).backfilled && table(B).backfilled
        }
        IDS.forEach { snapshot(B, it)!!.assertCounter(revision = 1, version = 1, total = 3, adds = 0) }
        both.snapshotStatus.rebuilding(type).assert().isFalse()
        // Caught up, the backfill of `b` stops writing; its store's subscription goes on.
        add(both, IDS.last(), 1)
        eventually { snapshot(A, IDS.last())?.version == 2 && snapshot(B, IDS.last())?.version == 2 }
        both.stop()

        val switched = start("node-4", revision = 1, targets = listOf(A, B), primary = B)
        // The default wait is answered by the primary target's subscription.
        add(switched, IDS.first(), 1, wait = null).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        snapshot(B, IDS.first())!!.version.assert().isEqualTo(2)
        switched.context(context).backends.primarySnapshots!!.load(type.ref(TENANT, IDS.first()))!!.version
            .assert().isEqualTo(2)
        // `a` is still a target, written by its own subscription, which the wait does not follow.
        eventually { snapshot(A, IDS.first())?.version == 2 }
        switched.stop()

        val dropped = start("node-5", revision = 1, targets = listOf(B))
        dropped.context(context).snapshotSubscriptions.map { it.name }.assert().containsExactly("snapshot:$B")
        add(dropped, IDS.first(), 1, wait = null).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        snapshot(B, IDS.first())!!.assertCounter(revision = 1, version = 3, total = 5, adds = 0)
        snapshot(A, IDS.first())!!.version.assert().isEqualTo(2)
    }

    @Test
    fun `a rebuild request repairs snapshots in place`(): Unit = runBlocking {
        val node = start("node-1", revision = 1)
        IDS.forEach { add(node, it, 2) }
        eventually { IDS.all { snapshot(A, it)?.version == 1 } }
        val broken = snapshot(A, IDS.first())!!.copyWith(state = """{"total":999,"adds":0}""")
        snapshots(A).rewrite(broken).assert().isTrue()

        rebuild(node, COUNTER, IDS.first(), "missing").status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        eventually { snapshot(A, IDS.first())?.state?.replace(" ", "")?.contains("\"total\":2") == true }
        snapshot(A, "missing").assert().isNull()

        val refused = rebuild(node, "nothing", IDS.first())
        refused.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        refused.error!!.code.assert().isEqualTo(FrameworkCodes.INVALID)
    }

    @Test
    fun `a rebuild request on a node of another revision leaves the snapshot to the claimed one`(): Unit =
        runBlocking {
            val new = start("new-1", revision = 2)
            IDS.forEach { add(new, it, 2) }
            eventually { IDS.all { snapshot(A, it)?.stateRevision == 2 } }
            new.stop()
            // An old node starts while the new revision's claim stands: a member of it may be back any moment.
            val membership = stores.getValue(A).leases
            membership.heartbeat("$SERVICE/$context#snapshot/$COUNTER@2", "new-2", "new-2", 1.hours)
            val old = start("old-1", revision = 1)
            table(A).revision.assert().isEqualTo(2)
            rebuild(old, COUNTER, IDS.first()).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            // The request marks the snapshot stale, then the table rebuilding, in that order.
            eventually { snapshots(A).pending(type, 2, limit = 10).contains(type.ref(TENANT, IDS.first())) }
            eventually { table(A).rebuilding }
            snapshot(A, IDS.first())!!.stateRevision.assert().isEqualTo(2)
        }

    private fun SnapshotRecord.assertCounter(revision: Int, version: Int, total: Int, adds: Int) {
        stateRevision.assert().describedAs("$ref's state revision").isEqualTo(revision)
        this.version.assert().describedAs("$ref's version").isEqualTo(version)
        state.replace(" ", "").assert().contains("\"total\":$total").contains("\"adds\":$adds")
    }

    private fun snapshots(store: String) = stores.getValue(store).snapshots(context)

    private suspend fun snapshot(store: String, id: String): SnapshotRecord? =
        snapshots(store).load(type.ref(TENANT, id))

    private suspend fun table(store: String): SnapshotTable = checkNotNull(snapshots(store).table(type))

    private suspend fun maintain(node: ServiceNode) {
        maintenanceOf(node).maintain()
    }

    private fun maintenanceOf(node: ServiceNode): SnapshotMaintenance =
        checkNotNull(node.context(context).snapshotMaintenance)

    private suspend fun start(
        name: String,
        revision: Int,
        targets: List<String> = listOf(A),
        primary: String = targets.first(),
    ): ServiceNode {
        val layout = StoreLayout(A, A, snapshotDefault = SnapshotPlacement(targets, primary))
        val backends = HostBackends.of(stores, layout, PARTITIONS)
        val node = ServiceNode(
            SERVICE,
            name,
            "$name.internal:9090",
            listOf(counterModel(context, revision)),
            manifest(context, revision),
            backends.factory,
            channel,
            remoteSignals = { origin, signal, _ -> nodes[origin.node]?.waits?.signal(signal) },
            options = OPTIONS,
        )
        nodes[name] = node
        channel.bind(node.address, node.forwardHandler)
        backends.createSchemas()
        node.start()
        return node
    }

    private suspend fun add(node: ServiceNode, id: String, amount: Int, wait: WaitPlan? = WaitPlan.COMMITTED) =
        node.gateway.execute(
            CommandRequest(
                context = context,
                aggregate = COUNTER,
                command = "Add",
                body = CommandBody.of("""{"amount":$amount}"""),
                caller = Caller(TENANT),
                aggregateId = id,
                wait = wait,
            ),
        ).also { it.status.assert().describedAs("Add to $id: $it").isEqualTo(CommandResult.Status.SUCCEEDED) }

    private suspend fun rebuild(node: ServiceNode, aggregate: String, vararg ids: String): CommandResult =
        node.gateway.execute(
            CommandRequest(
                context = context,
                aggregate = SnapshotRebuilds.AGGREGATE,
                command = SnapshotRebuilds.REBUILD,
                body = CommandBody.of(ids.joinToString(",", """{"ids":[""", "]}") { "\"$it\"" }),
                caller = Caller(TENANT),
                aggregateId = aggregate,
                wait = WaitPlan.COMMITTED,
            ),
        )

    private suspend fun eventually(timeout: Duration = 20.seconds, condition: suspend () -> Boolean) {
        withTimeout(timeout) {
            while (!condition()) {
                delay(POLL)
            }
        }
    }

    /** A counter whose state revision 2 counts its additions, which revision 1 does not. */
    class Counter {
        var total: Int = 0
        var adds: Int = 0
    }

    data class Add(val amount: Int)

    data class Added(val amount: Int)

    companion object {
        const val A = "a"
        const val B = "b"
        const val COUNTER = "counter"
        const val SERVICE = "rebuild-service"
        const val TENANT = "tenant"
        const val PARTITIONS = 4
        private const val ID_LENGTH = 8
        private val POLL = 20.milliseconds
        val IDS = (0 until 5).map { "c-$it" }

        fun counterModel(context: String, revision: Int): AggregateModel<Counter> =
            aggregate<Counter>(context, COUNTER) {
                stateRevision(revision)
                initial { Counter() }
                decide<Add>(Creation.ALLOWED) { _, body -> Decision.events(Added(body.amount)) }
                evolve<Added> {
                    total += it.amount
                    if (revision >= 2) {
                        adds++
                    }
                }
            }

        fun manifest(context: String, revision: Int) = Manifest(
            service = ServiceInfo(SERVICE),
            contexts = listOf(
                ContextManifest(
                    context,
                    aggregates = listOf(
                        AggregateManifest(
                            COUNTER,
                            state = StateManifest(Manifest.schemaRef(Counter::class.java.name)),
                            snapshot = SnapshotManifest(stateRevision = revision),
                            commands = listOf(
                                CommandManifest(
                                    "Add",
                                    schema = Manifest.schemaRef(Add::class.java.name),
                                    creation = ManifestCreation.ALLOWED,
                                ),
                            ),
                            events = listOf(
                                EventManifest("Added", schema = Manifest.schemaRef(Added::class.java.name))
                            ),
                            lifecycle = Lifecycle.COMMANDS.keys.toList(),
                        ),
                    ),
                ),
            ),
        )

        val OPTIONS = ServiceNodeOptions(
            cluster = ClusterOptions(
                leaseTtl = 3.seconds,
                renewInterval = 200.milliseconds,
                memberTtl = 3.seconds,
                heartbeatInterval = 200.milliseconds,
                reconcileInterval = 100.milliseconds,
                routeRefreshInterval = 100.milliseconds,
                drainTimeout = 1.seconds,
            ),
            routing = Routing.LOCAL,
            subscription = SubscriptionOptions(
                retry = RetryPolicy(maxAttempts = 3, initialBackoff = 1.milliseconds, maxBackoff = 5.milliseconds),
                checkpointInterval = 20.milliseconds,
            ),
            snapshots = SnapshotOptions(
                interval = 1.hours,
                batch = 2,
                pages = 2,
                leaseTtl = 2.hours,
                memberTtl = 2.hours
            ),
        )
    }
}

/** [SnapshotRebuildSpec] in memory. */
class MemorySnapshotRebuildTest : SnapshotRebuildSpec() {
    override fun store(name: String): Store = MemoryStore(name)
}

/**
 * [SnapshotRebuildSpec] in memory with the writes of store `a` delayed: a subscription of `a` is behind the others,
 * as on a loaded machine, so no assertion may rely on `a` keeping up with `b` or with a wait it does not answer.
 */
class SlowStoreSnapshotRebuildTest : SnapshotRebuildSpec() {
    override fun store(name: String): Store = if (name != A) {
        MemoryStore(name)
    } else {
        MemoryStore(name) { SlowSaves(InMemorySnapshotStore()) }
    }

    /** Saves to [delegate] after [SLOW_WRITE]. */
    private class SlowSaves(private val delegate: SnapshotStore) : SnapshotStore by delegate {
        override suspend fun save(snapshot: SnapshotRecord): Boolean {
            delay(SLOW_WRITE)
            return delegate.save(snapshot)
        }

        override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> {
            delay(SLOW_WRITE)
            return delegate.saveAll(snapshots)
        }
    }

    private companion object {
        val SLOW_WRITE = 50.milliseconds
    }
}
