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

package me.ahoo.wow.migrate

import com.mongodb.client.MongoDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.subscription.MongoCheckpointStore
import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.postgres.subscription.PostgresCheckpointStore
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.postgresql.ds.PGSimpleDataSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The import's cut-over end to end (X-38): the 9.x example's streams imported by the command line into a V10 log;
 * the example's V10 service on that log, started on standby and activated, its transport sink publishing its orders
 * to a topic; and a peer service whose saga and projection read that topic.
 *
 * The order's four imported streams reach the peer marked imported: its projection takes them, its saga passes over
 * them, since 9.x acted on them. The order's first V10 commit reaches both.
 */
class ImportCutoverChainTest {
    @TempDir
    lateinit var directory: Path

    private val output = ByteArrayOutputStream()
    private val nineX: MongoDatabase = NineXMongoStore.example()
    private val topics = InMemoryTopics(PARTITIONS).also { it.create(TOPIC, PARTITIONS) }
    private val channel = LoopbackClusterChannel()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()

    /** The versions of the order the peer's saga acted on, and those its projection took. */
    private val acted = CopyOnWriteArrayList<Int>()
    private val projected = CopyOnWriteArrayList<Int>()

    /** The peer's view of the published order state: the fields it reads, the others ignored. */
    data class OrderSeen(val status: String? = null)

    /** The peer's only aggregate: it reads the orders, and decides nothing of them. */
    class LedgerState(val id: String)

    @AfterEach
    fun stop(): Unit = runBlocking { nodes.forEach { it.stop() } }

    private fun config(target: String): Path {
        Files.writeString(directory.resolve("migration-map.json"), NineXExample.MAP_TEXT)
        Files.writeString(directory.resolve("manifest.json"), NineXExample.MANIFEST_TEXT)
        return directory.resolve("migrate-data.json").also {
            Files.writeString(
                it,
                """
                {
                  "map": "migration-map.json",
                  "manifests": ["manifest.json"],
                  "source": { "uri": "${WowContainers.mongo.replicaSetUrl}", "database": "${nineX.name}" },
                  "target": $target,
                  "partitions": { "${NineXExample.CONTEXT}": $PARTITIONS }
                }
                """.trimIndent(),
            )
        }
    }

    private fun run(vararg args: String): Int = MigrateData(PrintStream(output, true)).run(args.toList())

    @Test
    fun `on PostgreSQL, a peer's saga acts on V10's commits alone, its projection on the imported history too`() {
        val postgres = WowContainers.postgres
        val database = "wow_cutover_${databases.incrementAndGet()}_${System.nanoTime()}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use {
            it.createStatement().use { statement -> statement.execute("CREATE DATABASE $database") }
        }
        val url = postgres.jdbcUrl.replace("/${postgres.databaseName}", "/$database")
        val target = """{ "type": "postgres", "jdbcUrl": "$url", "username": "${postgres.username}", """ +
            """"password": "${postgres.password}" }"""
        val dataSource = PGSimpleDataSource().apply {
            setURL(url)
            user = postgres.username
            password = postgres.password
        }
        chain(
            target,
            PostgresEventLog(dataSource, NineXExample.CONTEXT, PARTITIONS),
            PostgresCheckpointStore(dataSource),
        )
    }

    @Test
    fun `on MongoDB, a peer's saga acts on V10's commits alone, its projection on the imported history too`() {
        val database = "wow_cutover_${databases.incrementAndGet()}"
        val target = """{ "type": "mongo", "uri": "${WowContainers.mongo.replicaSetUrl}", "database": "$database" }"""
        chain(
            target,
            MongoEventLog(NineXMongoStore.client, database, NineXExample.CONTEXT, PARTITIONS),
            MongoCheckpointStore(NineXMongoStore.client, database),
        )
    }

    private fun chain(target: String, log: EventLog, checkpoints: CheckpointStore) = runBlocking {
        run("import", "--config", config(target).toString()).assert().describedAs(output.toString())
            .isEqualTo(MigrateData.OK)
        output.toString().assert().contains("CUTOVER example: the imported history ends at the log's head in")
        val order = NineXExample.stream("order-stream-v1.json").aggregateId
        val ref = NineXExample.orderModel.type.ref(order.tenantId, order.id)
        val history = log.read(ref).toList()
        history.map { it.version }.assert().containsExactly(1, 2, 3, 4)

        val example = exampleNode(log, checkpoints)
        val peer = peerNode()
        example.start(standby = true)
        peer.start()
        peer.topicSubscriptions.getValue(TOPIC).awaitStarted()
        example.activate("test", "the import's cut-over")
        eventually { projected.size == history.size }
        projected.assert().containsExactly(1, 2, 3, 4)

        val paid = example.gateway.execute(
            ServerCommandRequest(
                context = NineXExample.CONTEXT,
                aggregate = "order",
                command = "PayOrder",
                body = CommandBody.of("""{"amount":10}"""),
                caller = Caller(order.tenantId, history.last().scope),
                aggregateId = order.id,
                wait = WaitPlan.COMMITTED,
            ),
        )
        eventually { acted.isNotEmpty() && projected.size == history.size + 1 }
        // The saga's first entry is V10's commit: one aggregate's records are handled in version order, so the
        // four imported ones were passed over by then.
        acted.assert().describedAs(paid.toString()).containsExactly(5)
        projected.assert().containsExactly(1, 2, 3, 4, 5)
    }

    /** The example's V10 service on the migrated [log] and [checkpoints], publishing its orders to [TOPIC]. */
    private fun exampleNode(log: EventLog, checkpoints: CheckpointStore): ServiceNode {
        val stores = ContextBackends(
            log = log,
            checkpoints = checkpoints,
            leases = InMemoryLeaseStore(),
            ledger = InMemoryFailureLedger(),
        )
        return node(
            EXAMPLE_SERVICE,
            listOf(NineXExample.orderModel(payable = true), NineXExample.cartModel),
            EXAMPLE,
            BackendFactory { stores },
            PublishedSinks.topics(EXAMPLE, topics, topicPrefix = PREFIX).factory,
            ContractDirectory(EXAMPLE),
            topics = null,
        )
    }

    /** The peer's service: a saga and a projection of the example's orders, its stores in memory. */
    private fun peerNode(): ServiceNode {
        val board = ProcessorBinding.project<OrderSeen?>(
            "Board",
            "onOrder",
            OrderSeen::class.java,
            aggregates = listOf(ORDER),
            nullableState = true,
        ) { event, _ -> projected += event.version }
        val payout = ProcessorBinding.saga<OrderSeen?>(
            "Payout",
            "onOrder",
            OrderSeen::class.java,
            aggregates = listOf(ORDER),
            nullableState = true,
        ) { event, _ ->
            acted += event.version
            emptyList()
        }
        val stores = ContextBackends(
            log = InMemoryEventLog(PARTITIONS),
            checkpoints = InMemoryCheckpointStore(),
            leases = InMemoryLeaseStore(),
            ledger = InMemoryFailureLedger(),
        )
        return node(
            PEER_SERVICE,
            listOf(aggregate<LedgerState>(PEER, "ledger") { initial { LedgerState(it.id) } }),
            PEER_MANIFEST,
            BackendFactory { stores },
            SubscriptionBindings(mapOf(PEER to listOf(board, payout))),
            ContractDirectory(PEER_MANIFEST, listOf(ManifestFragment(EXAMPLE_CONTRACT, Manifest(contexts = EXAMPLE.contexts)))),
            topics = SourceTopics(topics, PREFIX),
        )
    }

    @Suppress("LongParameterList") // A node's parts.
    private fun node(
        service: String,
        models: List<AggregateModel<*>>,
        manifest: Manifest,
        backends: BackendFactory,
        subscriptions: SubscriptionFactory,
        contracts: ContractDirectory,
        topics: SourceTopics?,
    ): ServiceNode = ServiceNode(
        service,
        "$service-1",
        "$service.internal:9090",
        models,
        manifest,
        backends,
        channel,
        options = OPTIONS,
        subscriptions = subscriptions,
        contracts = contracts,
        topics = topics,
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(30.seconds) {
            while (!condition()) {
                delay(20.milliseconds)
            }
        }
    }

    private companion object {
        const val PARTITIONS = 4
        const val PREFIX = "chain."
        const val EXAMPLE_SERVICE = "example-service"
        const val PEER_SERVICE = "billing-service"
        const val PEER = "billing"
        const val ORDER = "${NineXExample.CONTEXT}.order"
        const val EXAMPLE_CONTRACT = "example-api.jar!/META-INF/wow/contract.json"
        val TOPIC = PREFIX + PublishedManifest.defaultTopic(NineXExample.CONTEXT, "order")
        val databases = AtomicInteger()

        val OPTIONS = ServiceNodeOptions(
            cluster = ClusterOptions(
                leaseTtl = 1_500.milliseconds,
                renewInterval = 300.milliseconds,
                memberTtl = 1_500.milliseconds,
                heartbeatInterval = 300.milliseconds,
                reconcileInterval = 100.milliseconds,
                routeRefreshInterval = 100.milliseconds,
            ),
            subscription = SubscriptionOptions(checkpointInterval = 10.milliseconds),
        )

        private fun lifecycle(context: String, aggregate: String) =
            Lifecycle.COMMANDS.keys.map { SystemCommands.lifecycle(context, aggregate, it) }

        /** The example's Manifest as its service runs it: it pays orders, and publishes them. */
        val EXAMPLE: Manifest = NineXExample.manifest.contexts.single().let { context ->
            Manifest(
                service = ServiceInfo(EXAMPLE_SERVICE),
                contexts = listOf(
                    ContextManifest(
                        context.name,
                        aggregates = context.aggregates.map { aggregate ->
                            val decided = if (aggregate.name == "order") {
                                listOf(CommandManifest("PayOrder", schema = Manifest.schemaRef("example.PayOrder")))
                            } else {
                                emptyList()
                            }
                            AggregateManifest(
                                aggregate.name,
                                state = aggregate.state,
                                commands = decided + lifecycle(context.name, aggregate.name),
                                events = aggregate.events,
                            )
                        },
                        published = listOf(
                            PublishedManifest("order", PublishedManifest.defaultTopic(context.name, "order")),
                        ),
                    ),
                ),
            )
        }

        private fun subscription(name: String, kind: SubscriptionKind) = SubscriptionManifest(
            name = name,
            kind = kind,
            source = SubscriptionSource(NineXExample.CONTEXT, listOf("order"), nullableState = true),
            function = FunctionManifest(
                if (kind == SubscriptionKind.SAGA) FunctionKind.SAGA else FunctionKind.PROJECT,
                PEER,
                name.substringBefore('.'),
                name.substringAfter('.'),
            ),
        )

        val PEER_MANIFEST = Manifest(
            service = ServiceInfo(PEER_SERVICE),
            contexts = listOf(
                ContextManifest(
                    PEER,
                    aggregates = listOf(
                        AggregateManifest(
                            "ledger",
                            state = StateManifest(Manifest.schemaRef("billing.Ledger")),
                            commands = lifecycle(PEER, "ledger"),
                        ),
                    ),
                    subscriptions = listOf(
                        subscription("Board.onOrder", SubscriptionKind.PROJECTION),
                        subscription("Payout.onOrder", SubscriptionKind.SAGA),
                    ),
                ),
            ),
        )
    }
}
