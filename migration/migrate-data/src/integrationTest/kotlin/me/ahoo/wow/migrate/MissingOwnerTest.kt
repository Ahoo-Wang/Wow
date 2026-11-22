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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.scopeOf
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.migrate.example.CartItemAdded
import me.ahoo.wow.migrate.example.CartItemRemoved
import me.ahoo.wow.migrate.example.CartQuantityChanged
import me.ahoo.wow.migrate.example.CartState
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.subscription.MongoCheckpointStore
import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.postgres.subscription.PostgresCheckpointStore
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.postgresql.ds.PGSimpleDataSource
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/** Empties a cart: the one command of the V10 cart the owner sends below. */
internal class ClearCart

/**
 * A 9.x cart whose streams and snapshot record no owner, of an aggregate one per owner in V10 (`idSource = OWNER`),
 * migrated by a map that fills a missing owner with the id (`missingOwner`): `check` warns of it, the import and the
 * tail fill its streams' owner as the kernel records it, `compare` finds no difference, and on a node of the V10
 * service its owner reaches it while another owner does not.
 */
class MissingOwnerTest {
    @TempDir
    lateinit var directory: Path

    private val output = ByteArrayOutputStream()
    private val nineX = NineXMongoStore.example()
    private val channel = LoopbackClusterChannel()
    private val nodes = mutableListOf<ServiceNode>()

    @AfterEach
    fun stop(): Unit = runBlocking { nodes.forEach { it.stop() } }

    /** The example's cart stream [name] as one of the cart [CART] that records no owner. */
    private fun unowned(name: String, createTime: Long? = null): String {
        val stream = WowJson.mapper.readTree(NineXExample.resource(name)) as ObjectNode
        stream.put("id", "${stream.get("id").asString()}-$CART").put("aggregateId", CART).remove("ownerId")
        createTime?.let { stream.put("createTime", it) }
        return WowJson.mapper.writeValueAsString(stream)
    }

    private fun config(target: String, createIndex: Boolean = false): Path {
        Files.writeString(directory.resolve("migration-map.json"), NineXExample.fillingMapText())
        Files.writeString(
            directory.resolve("manifest.json"),
            NineXExample.manifestText(NineXExample.ownedCartManifest),
        )
        return directory.resolve("migrate-data.json").also {
            Files.writeString(
                it,
                """
                {
                  "map": "migration-map.json",
                  "manifests": ["manifest.json"],
                  "source": { "uri": "${WowContainers.mongoUrl}", "database": "${nineX.name}",
                              "createCommitTimeIndex": $createIndex },
                  "target": $target,
                  "partitions": { "${NineXExample.CONTEXT}": $PARTITIONS }
                }
                """.trimIndent(),
            )
        }
    }

    private fun run(vararg args: String): Int = MigrateData(PrintStream(output, true)).run(args.toList())

    @Test
    fun `on PostgreSQL, a cart with no owner takes its id, reaches its owner, and compares equal`() {
        val postgres = WowContainers.postgres
        val database = "wow_missing_owner_${databases.incrementAndGet()}_${System.nanoTime()}"
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
        migrate(
            target,
            PostgresEventLog(dataSource, NineXExample.CONTEXT, PARTITIONS),
            PostgresCheckpointStore(dataSource),
        )
    }

    @Test
    fun `on MongoDB, a cart with no owner takes its id, reaches its owner, and compares equal`() {
        val database = "wow_missing_owner_${databases.incrementAndGet()}"
        val target = """{ "type": "mongo", "uri": "${WowContainers.mongoUrl}", "database": "$database" }"""
        migrate(
            target,
            MongoEventLog(NineXMongoStore.client, database, NineXExample.CONTEXT, PARTITIONS),
            MongoCheckpointStore(NineXMongoStore.client, database),
        )
    }

    @Suppress("LongMethod") // The tool's commands in the order an operator runs them, then the node.
    private fun migrate(target: String, log: EventLog, checkpoints: CheckpointStore) = runBlocking {
        // Its first three streams before the import; its snapshot at the fourth, which records no owner either.
        NineXExample.CART_STREAMS.take(3).forEach { NineXMongoStore.insert(nineX, unowned(it)) }
        val snapshot = WowJson.mapper.readTree(NineXExample.resource("cart-snapshot.json")) as ObjectNode
        snapshot.put("aggregateId", CART).remove("ownerId")
        (snapshot.get("state") as ObjectNode).put("id", CART)
        NineXMongoStore.saveSnapshot(nineX, WowJson.mapper.writeValueAsString(snapshot))
        val config = config(target)

        run("check", "--config", config.toString()).assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        output.toString().lines().single { it.startsWith("WARNING example-service.cart: missingOwner fills") }.assert()
            .contains("the owner of 3 streams of 1 9.x instances")
            .endsWith("check each instance whose owner is inferred: \"$CART\" (no owner: 3 streams of the tenants [(0)])")
        run("import", "--config", config.toString()).assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        output.toString().assert().contains("3 owners filled with their id")

        // The tail fills a stream 9.x commits after the import alike.
        NineXMongoStore.insert(nineX, unowned(NineXExample.CART_STREAMS[3], System.currentTimeMillis()))
        run("tail", "--config", config(target, createIndex = true).toString(), "--once").assert()
            .describedAs(output.toString()).isEqualTo(MigrateData.OK)
        val ref = NineXExample.cartModel.type.ref("(0)", CART)
        val imported = log.read(ref).toList()
        imported.map { it.version }.assert().containsExactly(1, 2, 3, 4)
        // Each the scope the kernel records for such an aggregate, never a copy of the rule, and marked filled.
        imported.forEach { record ->
            record.scope.assert().isEqualTo(AggregateIdSource.OWNER.scopeOf(CART, Scope.NONE))
            record.headers[FILLED_OWNER_HEADER].assert().isEqualTo("aggregate-id")
        }

        // The 9.x snapshot's owner, filled as the import filled it, is the rebuilt state's.
        run("compare", "--config", config(target).toString()).assert().describedAs(output.toString())
            .isEqualTo(MigrateData.OK)
        output.toString().assert().doesNotContain("DIFFERS").contains("1 compared, 0 differ")

        // On a node of the V10 service, the cart is its owner's own; another owner's command finds no such cart.
        val node = node(log, checkpoints)
        node.start(standby = true)
        node.activate("test", "the import's cut-over")
        suspend fun clear(owner: String, aggregateId: String?) = node.gateway.execute(
            ServerCommandRequest(
                context = NineXExample.CONTEXT,
                aggregate = "cart",
                command = "ClearCart",
                body = CommandBody.of("{}"),
                caller = Caller("(0)", Scope(ownerId = owner)),
                aggregateId = aggregateId,
                wait = WaitPlan.COMMITTED,
            ),
        )
        clear("mallory", CART).error?.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_NOT_FOUND)
        val own = clear(CART, null)
        own.error.assert().describedAs(own.toString()).isNull()
        own.version.assert().isEqualTo(5)
        log.read(ref, 5, 5).toList().single().scope.assert().isEqualTo(imported.last().scope)
    }

    /** The V10 service over the migrated [log]: its cart one per owner, which its owner empties. */
    private fun node(log: EventLog, checkpoints: CheckpointStore): ServiceNode {
        val cart = aggregate<CartState>(NineXExample.CONTEXT, "cart") {
            id(AggregateIdSource.OWNER)
            initial { CartState(it.id) }
            evolve<CartItemAdded> { items = items + it.added }
            evolve<CartQuantityChanged> { changed ->
                items = items.map { if (it.productId == changed.changed.productId) changed.changed else it }
            }
            evolve<CartItemRemoved> { removed -> items = items.filterNot { it.productId in removed.productIds } }
            decide<ClearCart> { state, _ -> Decision.events(CartItemRemoved(state.items.map { it.productId }.toSet())) }
        }
        val manifest = NineXExample.ownedCartManifest.let { owned ->
            owned.copyWith(
                service = ServiceInfo(SERVICE),
                contexts = owned.contexts.map { context ->
                    context.copyWith(
                        aggregates = context.aggregates.filter { it.name == "cart" }.map { aggregate ->
                            aggregate.copyWith(
                                commands = listOf(CommandManifest("ClearCart", schema = Manifest.schemaRef("example.ClearCart"))) +
                                    cart.lifecycleCommands.map { command ->
                                        SystemCommands.lifecycle(
                                            context.name,
                                            aggregate.name,
                                            me.ahoo.wow.api.lifecycle.Lifecycle.COMMANDS.entries.single { it.value == command }.key,
                                        )
                                    },
                            )
                        },
                    )
                },
            )
        }
        val stores = ContextBackends(
            log = log,
            checkpoints = checkpoints,
            leases = InMemoryLeaseStore(),
            ledger = InMemoryFailureLedger(),
        )
        return ServiceNode(
            SERVICE,
            "$SERVICE-1",
            "$SERVICE.internal:9090",
            listOf(cart),
            manifest,
            BackendFactory { stores },
            channel,
            subscriptions = SubscriptionBindings(emptyMap()),
            contracts = ContractDirectory(manifest),
        ).also {
            nodes += it
            channel.bind(it.address, it.forwardHandler)
        }
    }

    private companion object {
        const val PARTITIONS = 4
        const val CART = "cart-n"
        const val SERVICE = "example-service"
        val databases = AtomicInteger()
    }
}
