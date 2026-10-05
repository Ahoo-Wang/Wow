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

package me.ahoo.wow.it.mixed

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * One example-server node of the mixed-version cluster.
 *
 * [name] doubles as the Kafka consumer `client.id`, which is how the test tells which node owns a partition.
 */
sealed interface ExampleServerNode : AutoCloseable {
    val name: String
    val baseUrl: String

    fun start()

    /** The last lines the node logged, for a failure message. */
    fun logTail(): String

    /**
     * The Spring arguments every node gets: Kafka buses, Mongo stores, no local-first shortcut unless [localFirst] (so
     * a command or event can only reach a processor through the shared broker), and a distinct CosId machine id (wait
     * ids are routed back to the waiting node by machine id).
     */
    fun clusterArguments(
        machineId: Int,
        mongoUri: String,
        kafkaBootstrapServers: String,
        localFirst: Boolean,
    ): List<String> = listOf(
        "--spring.autoconfigure.exclude=$ELASTICSEARCH_AUTOCONFIGURATIONS",
        "--spring.mongodb.uri=$mongoUri",
        "--cosid.machine.distributor.manual.machine-id=$machineId",
        "--wow.kafka.enabled=true",
        "--wow.kafka.bootstrap-servers=$kafkaBootstrapServers",
        "--wow.kafka.consumer[client.id]=$name",
        "--wow.command.bus.type=kafka",
        "--wow.command.bus.local-first.enabled=$localFirst",
        "--wow.event.bus.type=kafka",
        "--wow.event.bus.local-first.enabled=$localFirst",
        "--wow.eventsourcing.state.bus.type=kafka",
        "--wow.eventsourcing.state.bus.local-first.enabled=$localFirst",
        "--wow.eventsourcing.store.storage=mongo",
        "--wow.eventsourcing.snapshot.storage=mongo",
        "--logging.level.me.ahoo.wow=info",
    )

    companion object {
        private const val ELASTICSEARCH_AUTOCONFIGURATIONS =
            "org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration," +
                "org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration"
        const val JAVA_OPTS = "-Xms256m -Xmx768m -XX:MaxMetaspaceSize=256m"
        val STARTUP_TIMEOUT: Duration = Duration.ofMinutes(5)
    }
}

/** A node run from a published image, on the cluster network (the released version in CI). */
class ImageNode(
    override val name: String,
    image: String,
    network: Network,
    machineId: Int,
    localFirst: Boolean,
) : ExampleServerNode {
    private val container: GenericContainer<*> = GenericContainer(DockerImageName.parse(image))
        .withNetwork(network)
        .withNetworkAliases(name)
        .withExposedPorts(HTTP_PORT)
        .withEnv("JAVA_OPTS", ExampleServerNode.JAVA_OPTS)
        .withCommand(
            *clusterArguments(
                machineId = machineId,
                mongoUri = "mongodb://${MixedVersionCluster.MONGO_ALIAS}:27017/${MixedVersionCluster.DATABASE}?directConnection=true",
                kafkaBootstrapServers = MixedVersionCluster.KAFKA_NETWORK_BOOTSTRAP,
                localFirst = localFirst,
            ).toTypedArray(),
        )
        .waitingFor(
            Wait.forHttp("/actuator/health")
                .forPort(HTTP_PORT)
                .forStatusCode(200)
                .withStartupTimeout(ExampleServerNode.STARTUP_TIMEOUT),
        )

    override val baseUrl: String
        get() = "http://${container.host}:${container.getMappedPort(HTTP_PORT)}"

    override fun start() {
        container.start()
    }

    override fun logTail(): String = container.logs.lines().takeLast(LOG_TAIL_LINES).joinToString("\n")

    override fun close() {
        container.stop()
    }

    companion object {
        private const val HTTP_PORT = 8080
        private const val LOG_TAIL_LINES = 200
    }
}

/**
 * A node run from an `installDist` directory as a local process (the build under test in CI).
 *
 * The process reaches Kafka and Mongo through their mapped ports; image nodes reach it through the host address its
 * command wait endpoint advertises.
 */
class ProcessNode(
    override val name: String,
    private val installHome: Path,
    private val machineId: Int,
    private val mongoUri: String,
    private val kafkaBootstrapServers: String,
    private val localFirst: Boolean,
) : ExampleServerNode {
    private val port = freePort()
    private val workDir: Path = Files.createTempDirectory("wow-mixed-$name")
    private val stdout: Path = workDir.resolve("stdout.log")
    private var process: Process? = null

    override val baseUrl: String = "http://localhost:$port"

    override fun start() {
        // bin/example-server reads ./config/ and writes ./logs and ./data, so each node gets its own working directory.
        Files.createDirectories(workDir.resolve("config"))
        Files.createDirectories(workDir.resolve("logs"))
        Files.createDirectories(workDir.resolve("data"))
        Files.copy(
            installHome.resolve("config/application.yaml"),
            workDir.resolve("config/application.yaml"),
        )
        val command = listOf(installHome.resolve("bin/example-server").toString()) +
            "--server.port=$port" +
            clusterArguments(machineId, mongoUri, kafkaBootstrapServers, localFirst)
        val builder = ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(stdout.toFile())
        builder.environment()["JAVA_OPTS"] = ExampleServerNode.JAVA_OPTS
        // The distribution pins the JMX port; two nodes on one host must not share it.
        builder.environment()["EXAMPLE_SERVER_OPTS"] = "-Dcom.sun.management.jmxremote.port=${freePort()}"
        val started = builder.start()
        process = started
        awaitHealthy(started)
    }

    private fun awaitHealthy(started: Process) {
        val client = HttpClient.newHttpClient()
        val deadline = System.nanoTime() + ExampleServerNode.STARTUP_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            check(started.isAlive) { "Node [$name] exited during startup:\n${logTail()}" }
            val healthy = runCatching {
                client.send(
                    HttpRequest.newBuilder(URI.create("$baseUrl/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode() == 200
            }.getOrDefault(false)
            if (healthy) {
                return
            }
            TimeUnit.SECONDS.sleep(2)
        }
        error("Node [$name] was not healthy after ${ExampleServerNode.STARTUP_TIMEOUT}:\n${logTail()}")
    }

    override fun logTail(): String =
        if (Files.exists(stdout)) Files.readAllLines(stdout).takeLast(LOG_TAIL_LINES).joinToString("\n") else ""

    override fun close() {
        process?.let {
            it.destroy()
            if (!it.waitFor(30, TimeUnit.SECONDS)) {
                it.destroyForcibly()
            }
        }
    }

    companion object {
        private const val LOG_TAIL_LINES = 200

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
