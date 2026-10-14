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

package me.ahoo.wow.example.eshop.e2e.soak

import me.ahoo.wow.example.eshop.e2e.EshopService
import me.ahoo.wow.example.eshop.e2e.EshopStack
import me.ahoo.wow.example.eshop.e2e.backendArguments
import me.ahoo.wow.tck.containers.WowContainers
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import java.io.File
import java.net.ServerSocket
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The eShop's four services of the order flow (basket, ordering, inventory, payment), each on as many nodes as
 * [SoakConfig.nodes] says, every node a JVM of its own as [EshopStack] starts them, on one PostgreSQL (a database per
 * context) and one Kafka. Unlike [EshopStack], the containers are bound to fixed host ports, so `docker restart` keeps
 * the address every node was given; and the nodes come and go: [kill] one and [start] another in its place.
 *
 * The catalog and ClickHouse are left out: no step of the order flow reads them.
 */
class SoakStack(private val config: SoakConfig, private val log: (String) -> Unit) : AutoCloseable {
    val postgres = WowContainers.newPostgres().withDatabaseName(ADMIN_DATABASE).apply {
        portBindings = listOf("${freePort()}:$POSTGRES_PORT")
    }
    val kafka = WowContainers.newKafka().apply {
        portBindings = listOf("${freePort()}:$KAFKA_PORT")
    }
    private val nodes = ConcurrentHashMap<String, CopyOnWriteArrayList<SoakNode>>()
    private val numbers = ConcurrentHashMap<String, AtomicInteger>()

    fun start() {
        postgres.start()
        kafka.start()
        DATABASES.values.forEach { database ->
            val created = postgres.execInContainer(
                "psql",
                "-U",
                postgres.username,
                "-d",
                ADMIN_DATABASE,
                "-c",
                "CREATE DATABASE $database"
            )
            check(created.exitCode == 0) { "CREATE DATABASE $database failed: ${created.stderr}" }
        }
        admin().use { admin ->
            admin.createTopics(
                listOf(
                    NewTopic("wow.eshop-ordering.commands", config.partitions, 1),
                    NewTopic("wow.eshop-inventory.commands", config.partitions, 1),
                    NewTopic("wow.eshop-basket.receipts", 1, 1),
                    NewTopic("wow.eshop-ordering.receipts", 1, 1),
                    NewTopic("wow.eshop-payment.receipts", 1, 1),
                    NewTopic("wow.eshop-basket.basket.state", 2, 1),
                    NewTopic("wow.eshop-ordering.order.state", 2, 1),
                    NewTopic("wow.eshop-inventory.stock.state", 2, 1),
                    NewTopic("wow.eshop-payment.payment.state", 2, 1),
                ),
            ).all().get()
        }
        // In the order EshopStack starts them: a saga's target context is up before the saga's.
        listOf("inventory", "ordering", "basket", "payment").forEach { context ->
            repeat(config.nodes.getValue(context)) { start(context) }
        }
    }

    /** The live nodes of [context]. */
    fun nodes(context: String): List<SoakNode> = nodes[context].orEmpty()

    fun allNodes(): List<SoakNode> = nodes.values.flatten()

    /** Starts a node of [context], named `<context>-<n>` with the next n, and waits until it serves. */
    fun start(context: String): SoakNode {
        val id = "$context-${numbers.computeIfAbsent(context) { AtomicInteger() }.incrementAndGet()}"
        val arguments = backendArguments(postgres, kafka, DATABASES.getValue(context), config.partitions) +
            "--wow.node.id=$id"
        val service = EshopService.start(context, MAINS.getValue(context), arguments, listOf("-Xmx${config.heap}"))
        return SoakNode(context, id, service).also { node ->
            nodes.computeIfAbsent(context) { CopyOnWriteArrayList() }.add(node)
            log("node $id started on port ${service.publicPort}")
        }
    }

    /** Kills [node] at once (`SIGKILL`), as a crash would. */
    fun kill(node: SoakNode) {
        nodes[node.context]?.remove(node)
        node.service.kill()
        log("node ${node.id} killed")
    }

    /**
     * A thread dump of every live node of [context] (`jcmd <pid> Thread.dump_to_file`), each written to [directory] as
     * `threads-<node>-<tag>.txt`; the files written.
     */
    fun threadDumps(context: String, directory: File, tag: String): List<File> {
        val jcmd = File(System.getProperty("java.home"), "bin/jcmd").path
        directory.mkdirs()
        return nodes(context).mapNotNull { node ->
            val process = ProcessHandle.current().descendants()
                .filter { handle -> handle.info().commandLine().orElse("").contains("--wow.node.id=${node.id}") }
                .findFirst().orElse(null) ?: return@mapNotNull null
            // Thread.dump_to_file holds the virtual threads too, which the services run their blocking calls on.
            val file = File(directory, "threads-${node.id}-$tag.txt").absoluteFile
            runCatching {
                ProcessBuilder(jcmd, process.pid().toString(), "Thread.dump_to_file", "-format=text", file.path)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                    .waitFor(DUMP_SECONDS, TimeUnit.SECONDS)
                file.takeIf { it.exists() }
            }.getOrNull()
        }
    }

    fun pausePostgres() {
        postgres.dockerClient.pauseContainerCmd(postgres.containerId).exec()
    }

    fun unpausePostgres() {
        postgres.dockerClient.unpauseContainerCmd(postgres.containerId).exec()
    }

    /** Restarts PostgreSQL: the image stops it with SIGINT, a fast shutdown that cuts every connection. */
    fun restartPostgres() {
        postgres.dockerClient.restartContainerCmd(postgres.containerId).withTimeout(RESTART_GRACE_SECONDS).exec()
    }

    fun restartKafka() {
        kafka.dockerClient.restartContainerCmd(kafka.containerId).withTimeout(RESTART_GRACE_SECONDS).exec()
    }

    /** Waits until PostgreSQL answers, Kafka has a broker and every node serves, for at most [deadline]. */
    fun awaitHealthy(deadline: Duration = HEALTH_DEADLINE): Boolean {
        val until = System.nanoTime() + deadline.toNanos()
        while (System.nanoTime() < until) {
            if (postgresUp() && kafkaUp() && allNodes().all { it.serves() }) return true
            Thread.sleep(POLL.toMillis())
        }
        return false
    }

    fun postgresUp(): Boolean = runCatching { connect(ADMIN_DATABASE).use { it.isValid(2) } }.getOrDefault(false)

    fun kafkaUp(): Boolean = runCatching {
        admin().use { it.describeCluster().nodes().get(5, TimeUnit.SECONDS).isNotEmpty() }
    }.getOrDefault(false)

    /** A connection to [database] that gives up on a statement after [SOCKET_TIMEOUT_SECONDS]. */
    fun connect(database: String): Connection = DriverManager.getConnection(
        "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$database" +
            "?connectTimeout=5&socketTimeout=$SOCKET_TIMEOUT_SECONDS&loginTimeout=5",
        postgres.username,
        postgres.password,
    )

    private fun admin(): Admin = Admin.create(
        mapOf<String, Any>(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG to 5_000,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG to 10_000,
        ),
    )

    override fun close() {
        allNodes().forEach { node -> runCatching { node.service.close() } }
        runCatching { unpausePostgres() }
        runCatching { kafka.stop() }
        runCatching { postgres.stop() }
    }

    companion object {
        const val ADMIN_DATABASE = "eshop_soak"
        const val POSTGRES_PORT = 5432
        const val KAFKA_PORT = 9092
        private const val RESTART_GRACE_SECONDS = 10
        private const val SOCKET_TIMEOUT_SECONDS = 30
        private const val DUMP_SECONDS = 30L
        private val HEALTH_DEADLINE: Duration = Duration.ofMinutes(3)
        private val POLL: Duration = Duration.ofSeconds(1)

        /** The database of each context. */
        val DATABASES: Map<String, String> = mapOf(
            "basket" to EshopStack.BASKET_DATABASE,
            "ordering" to EshopStack.ORDERING_DATABASE,
            "inventory" to EshopStack.INVENTORY_DATABASE,
            "payment" to EshopStack.PAYMENT_DATABASE,
        )

        private val MAINS: Map<String, String> = mapOf(
            "basket" to EshopStack.BASKET_MAIN,
            "ordering" to EshopStack.ORDERING_MAIN,
            "inventory" to EshopStack.INVENTORY_MAIN,
            "payment" to EshopStack.PAYMENT_MAIN,
        )

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}

/** A node of the eShop's [context]: its [id] (`wow.node.id`) and its JVM. */
class SoakNode(val context: String, val id: String, val service: EshopService) {
    /** Whether the node answers its Manifest. */
    fun serves(): Boolean = runCatching { service.get("/wow/manifest").status == OK }.getOrDefault(false)

    override fun toString(): String = id

    private companion object {
        const val OK = 200
    }
}
