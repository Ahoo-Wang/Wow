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
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
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
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.Routing
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.snapshot.SnapshotMaintenance
import me.ahoo.wow.runtime.snapshot.SnapshotOptions
import me.ahoo.wow.runtime.store.StoreLayout
import me.ahoo.wow.runtime.store.StoreTypes
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.spi.Closing
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotTable
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spring.boot.store.HostBackends
import me.ahoo.wow.spring.boot.store.MemoryStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
/**
 * Snapshot rebuild, repair and backfill (decision D172, roadmap 6.2e) end to end, on the nodes of a service over the
 * snapshot stores a backend gives ([store]: `a`, which holds the log too, and `b`): an in-place rebuild after the state
 * revision is raised; a rolling upgrade, where a node of the old revision keeps serving without overwriting the new
 * one, and its rollback; a store that replaces a type's own, and a copy for its queries, each filled from the log
 * (D423); and a rebuild request that repairs a snapshot. Each backend runs it: memory here, PostgreSQL and MongoDB in the integration tests.
 *
 * The snapshot maintenance runs only when a test drives it ([maintain]): its interval is an hour.
 */
abstract class SnapshotRebuildSpec {
    /** A new store named [name] of the backend; `a` holds the logs too. */
    protected abstract fun store(name: String): Store

    private val context = "rebuild-" + UUID.randomUUID().toString().take(ID_LENGTH)
    private val type = AggregateType(context, COUNTER)

    /** The stores the nodes share, which the spec opened, so closes: after its nodes stopped. */
    private val opened = lazy { listOf(A, B).associateWith(::store) }
    private val stores: Map<String, Store> by opened
    private val channel = LoopbackClusterChannel()
    private val nodes = ConcurrentHashMap<String, ServiceNode>()

    @AfterEach
    fun stopNodes(): Unit = runBlocking {
        nodes.values.forEach { it.stop() }
        if (opened.isInitialized()) Closing.inReverse(stores.values)
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
    fun `a store that replaces a type's own is filled from the log, its queries rebuilding meanwhile (MS-3)`(): Unit =
        runBlocking {
            val first = start("node-1", revision = 1)
            IDS.forEach { add(first, it, 3) }
            eventually { IDS.all { snapshot(A, it)?.version == 1 } }
            first.stop()

            // `b` replaces `a` as the default store: `wow.snapshot`, `b`'s now, is at the log's head, so it has not
            // written `b` from the beginning, and a subscription of its own fills `b` from the log.
            val moved = start("node-2", revision = 1, snapshot = B)
            moved.context(context).subscriptions.subscriptionIds.assert()
                .contains(SubscriptionId.of(context, "wow.snapshot#$COUNTER"))
            moved.snapshotStatus.rebuilding(type).assert().isTrue()
            eventually {
                maintain(moved)
                table(B).backfilled
            }
            IDS.forEach { snapshot(B, it)!!.assertCounter(revision = 1, version = 1, total = 3, adds = 0) }
            moved.snapshotStatus.rebuilding(type).assert().isFalse()
            add(moved, IDS.first(), 1, wait = null).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            snapshot(B, IDS.first())!!.version.assert().isEqualTo(2)
        }

    @Test
    fun `a copy for a type's queries is filled from the log by its own subscription, the queries rebuilding till then`():
        Unit = runBlocking {
        val first = start("node-1", revision = 1)
        IDS.forEach { add(first, it, 3) }
        eventually {
            maintain(first)
            IDS.all { snapshot(A, it)?.version == 1 } && table(A).backfilled
        }
        first.stop()

        val copied = start("node-2", revision = 1, copy = B)
        copied.context(context).snapshotSubscriptions.map { it.name }.assert()
            .containsExactly("wow.snapshot", "wow.snapshot:$B")
        // The type's own store is caught up; its queries read the copy, which is not yet (MS-1).
        copied.snapshotStatus.rebuilding(type).assert().isTrue()
        eventually {
            maintain(copied)
            table(B).backfilled
        }
        IDS.forEach { snapshot(B, it)!!.assertCounter(revision = 1, version = 1, total = 3, adds = 0) }
        copied.snapshotStatus.rebuilding(type).assert().isFalse()
        // The default wait is answered by the type's own store's subscription; the copy follows on its own.
        add(copied, IDS.first(), 1, wait = null).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        snapshot(A, IDS.first())!!.version.assert().isEqualTo(2)
        copied.context(context).backends.primarySnapshots!!.load(type.ref(TENANT, IDS.first()))!!.version
            .assert().isEqualTo(2)
        eventually { snapshot(B, IDS.first())?.version == 2 }
    }

    @Test
    fun `a copy keeps its index and its checkpoints across a restart, with nothing read again`(): Unit = runBlocking {
        // As a deployment of the list shape (`[a, b]`, `a` primary) left it: `b` caught up under `wow.snapshot:b`,
        // the subscription the copy keeps under the new keys.
        val first = start("node-1", revision = 1, copy = B)
        IDS.forEach { add(first, it, 3) }
        eventually {
            maintain(first)
            IDS.all { snapshot(B, it)?.version == 1 } && table(B).backfilled
        }
        first.stop()
        val log = stores.getValue(A).checkpoints
        val group = first.context(context).subscriptions.group
        val kept = log.list(group, "wow.snapshot:$B")
        kept.assert().isNotEmpty()

        val restarted = start("node-2", revision = 1, copy = B)
        // No backfill and no checkpoint read from the start: the copy goes on from where it stood.
        restarted.context(context).subscriptions.subscriptionIds.assert()
            .doesNotContain(SubscriptionId.of(context, "wow.snapshot:$B#$COUNTER"))
        log.list(group, "wow.snapshot:$B").assert().containsExactlyInAnyOrderElementsOf(kept)
        restarted.snapshotStatus.rebuilding(type).assert().isFalse()
        add(restarted, IDS.first(), 1, wait = null).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        eventually { snapshot(B, IDS.first())?.version == 2 }
    }

    @Test
    fun `a default store changed while the old one keeps a type reads the log again under the old one's name, by version`():
        Unit = runBlocking {
        val first = start("node-1", revision = 1)
        IDS.forEach { add(first, it, 3) }
        eventually {
            maintain(first)
            IDS.all { snapshot(A, it)?.version == 1 } && table(A).backfilled
        }
        first.stop()

        // `b` becomes the default store while the counter stays on `a`, whose subscription is now `wow.snapshot:a`:
        // it has no checkpoint, so it reads the log from its start, writing nothing back, as `a` is caught up.
        val changed = start("node-2", revision = 1, snapshot = B, own = A)
        changed.context(context).snapshotMaintenance!!.targets.primarySubscriptionOf(type).assert()
            .isEqualTo("wow.snapshot:$A")
        val log = stores.getValue(A).checkpoints
        val group = changed.context(context).subscriptions.group
        eventually { log.list(group, "wow.snapshot:$A").size == changed.context(context).log.partitions }
        table(A).backfilled.assert().isTrue()
        changed.snapshotStatus.rebuilding(type).assert().isFalse()
        IDS.forEach { snapshot(A, it)!!.assertCounter(revision = 1, version = 1, total = 3, adds = 0) }
        add(changed, IDS.first(), 1, wait = null).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        snapshot(A, IDS.first())!!.version.assert().isEqualTo(2)
    }

    @Test
    fun `a rebuild request repairs snapshots in place`(): Unit = runBlocking {
        val node = start("node-1", revision = 1)
        IDS.forEach { add(node, it, 2) }
        eventually { IDS.all { snapshot(A, it)?.version == 1 } }
        val broken = snapshot(A, IDS.first())!!.copyWith(state = """{"total":999,"adds":0}""")
        snapshots(A).rebuilds!!.rewrite(broken).assert().isTrue()

        rebuild(node, COUNTER, IDS.first(), "missing").status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        eventually { snapshot(A, IDS.first())?.state?.replace(" ", "")?.contains("\"total\":2") == true }
        snapshot(A, "missing").assert().isNull()

        val refused = rebuild(node, "nothing", IDS.first())
        refused.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        refused.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_INVALID)
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
            eventually { snapshots(A).rebuilds!!.pending(type, 2, limit = 10).contains(type.ref(TENANT, IDS.first())) }
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

    private suspend fun table(store: String): SnapshotTable = checkNotNull(snapshots(store).rebuilds!!.table(type))

    private suspend fun maintain(node: ServiceNode) {
        maintenanceOf(node).maintain()
    }

    private fun maintenanceOf(node: ServiceNode): SnapshotMaintenance =
        checkNotNull(node.context(context).snapshotMaintenance)

    private suspend fun start(
        name: String,
        revision: Int,
        snapshot: String = A,
        own: String? = null,
        copy: String? = null,
    ): ServiceNode {
        val layout = StoreLayout(
            A,
            snapshot,
            snapshots = own?.let { mapOf(type to it) }.orEmpty(),
            copies = copy?.let { mapOf(type to it) }.orEmpty(),
        )
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
            ServerCommandRequest(
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
            ServerCommandRequest(
                context = context,
                aggregate = SnapshotRebuilds.AGGREGATE,
                command = SnapshotRebuilds.REBUILD_SNAPSHOT,
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
                                    creation = Creation.ALLOWED,
                                ),
                            ) + Lifecycle.COMMANDS.keys.map { SystemCommands.lifecycle(context, COUNTER, it) } +
                                SystemCommands.rebuild(context, COUNTER),
                            events = listOf(
                                EventManifest("Added", schema = Manifest.schemaRef(Added::class.java.name))
                            ),
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
    override fun store(name: String): Store = memoryStore(name)
}

/**
 * [SnapshotRebuildSpec] in memory with the writes of store `a` delayed: a subscription of `a` is behind the others,
 * as on a loaded machine, so no assertion may rely on `a` keeping up with `b` or with a wait it does not answer.
 */
class SlowStoreSnapshotRebuildTest : SnapshotRebuildSpec() {
    override fun store(name: String): Store = if (name != A) {
        memoryStore(name)
    } else {
        MemoryStore(StoreConfig(name, StoreTypes.MEMORY), snapshotsOf = { SlowSaves(InMemorySnapshotStore()) })
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
