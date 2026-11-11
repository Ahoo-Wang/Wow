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

package me.ahoo.wow.test.server

import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.spi.json.WowJson
import java.io.InputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/** The ports of the services of a [WowLocal]: each its own fixed ones, or every one a port the system chooses. */
enum class LocalPorts {
    FIXED,
    RANDOM,
    ;

    companion object {
        /** The system property that chooses the ports: `fixed` (the default) or `random`. */
        const val PROPERTY: String = "wow-local.ports"

        /** The ports [value] names, `fixed` or `random` whatever its case; [FIXED] when it is null or blank. */
        @JvmStatic
        fun of(value: String?): LocalPorts = when (value?.trim()?.lowercase()) {
            null, "", "fixed" -> FIXED
            "random" -> RANDOM
            else -> throw IllegalArgumentException("$PROPERTY is fixed or random, not $value.")
        }
    }
}

/**
 * Starts several Wow services in this JVM, on the memory backend and without a broker, for a local run: development,
 * a front end against its back ends, a demo. Each service serves its own contexts alone (`wow.contexts.include`) in a
 * development profile (`dev`), so a request names its caller in the development identity headers, and they share one
 * [InMemoryTopics] of [partitions] partitions, their logs' too: each one's sagas send other contexts' commands
 * through it, its published streams are written to it, and it executes the commands sent to its own command topics,
 * so a flow across the services runs as it does on Kafka.
 *
 * The services start in the order [services] lists them, and each one calls the services listed before it: its
 * `wow.peers.<context>.url` names the internal port of each context they serve. So list a called service before its
 * callers. On [ports] the system chooses ([LocalPorts.RANDOM]), or each service's own ([LocalPorts.FIXED], the
 * default, which `wow-local.ports` changes); a service that names no port then fails to start. A service that fails
 * to start closes the ones started before it. Close the [WowLocal] to stop them all, the last started first.
 *
 * ```kotlin
 * wowLocal {
 *     service<OrderingServer>("eshop-ordering-service", listOf("eshop-ordering"), 8083, 9093)
 *     service<BasketServer>("eshop-basket-service", listOf("eshop-basket"), 8082, 9092)
 * }.use { shop -> println(shop.publicPorts) }
 * ```
 */
fun wowLocal(
    ports: LocalPorts = LocalPorts.of(System.getProperty(LocalPorts.PROPERTY)),
    partitions: Int = WowLocal.DEFAULT_PARTITIONS,
    services: WowLocal.Builder.() -> Unit,
): WowLocal = WowLocal.Builder(ports, partitions).apply(services).start()

/**
 * Runs [services] as [wowLocal] starts them, from a `main` function: it prints [WowLocal.readyLine] once every service
 * listens, and stops them when the JVM exits. With the system property `wow-local.exit-on-stdin-eof` set to `true`, the
 * JVM exits when its standard input closes, so a process that started it, a test of a web front end say, never leaves
 * it running. A service that fails to start makes it print why and exit with status 1.
 *
 * ```kotlin
 * fun main() = runWowLocal {
 *     service<OrderingServer>("eshop-ordering-service", listOf("eshop-ordering"), 8083, 9093)
 *     service<BasketServer>("eshop-basket-service", listOf("eshop-basket"), 8082, 9092)
 * }
 * ```
 */
fun runWowLocal(
    ports: LocalPorts = LocalPorts.of(System.getProperty(LocalPorts.PROPERTY)),
    partitions: Int = WowLocal.DEFAULT_PARTITIONS,
    services: WowLocal.Builder.() -> Unit,
) {
    val exitOnEof = System.getProperty(WowLocal.EXIT_ON_STDIN_EOF).toBoolean()
    runWowLocal(ports, partitions, services, exitOnEof, System.`in`, System.out, System.err, ::exitProcess)
}

/** [runWowLocal] on the given streams, exiting through [exit]. */
internal fun runWowLocal(
    ports: LocalPorts,
    partitions: Int,
    services: WowLocal.Builder.() -> Unit,
    exitOnEof: Boolean,
    input: InputStream,
    output: PrintStream,
    errors: PrintStream,
    exit: (Int) -> Unit,
): WowLocal? {
    val local = try {
        wowLocal(ports, partitions, services)
    } catch (failed: RuntimeException) {
        errors.println("wow-local failed to start: $failed")
        exit(1)
        return null
    }
    Runtime.getRuntime().addShutdownHook(Thread(local::close, "wow-local-stop"))
    output.println(local.readyLine)
    if (exitOnEof) {
        while (input.read() != -1) {
            // Reads until the process that started this one closes the pipe.
        }
        exit(0)
    }
    return local
}

/**
 * Wow services started in this JVM ([wowLocal]): its [servers] by their names, in the order they started, and their
 * [publicPorts]. Closing it closes them, the last started first.
 */
class WowLocal private constructor(
    /** The servers by their services' names, in the order they started. */
    val servers: Map<String, WowServer>,
) : AutoCloseable {
    /** The public port of each service, by its name. */
    val publicPorts: Map<String, Int> = servers.mapValues { (_, server) -> server.publicPort }

    /** The line that says every service listens, with their public ports: `wow-local ready {"bank-service":8080}`. */
    val readyLine: String = "$READY ${WowJson.mapper.writeValueAsString(publicPorts)}"

    override fun close() {
        closeAll(servers.values.toList())
    }

    /** What a [WowLocal] runs: its services, each added with [service], in the order they start. */
    class Builder internal constructor(private val ports: LocalPorts, private val partitions: Int) {
        private val services = mutableListOf<LocalService>()

        init {
            require(partitions > 0) { "The topics and the logs have one partition at least." }
        }

        /**
         * The service named [name] (`spring.application.name`), the application [application] serving [contexts]
         * alone, on its [publicPort] and [internalPort] when the ports are fixed, with [properties] over its own
         * configuration.
         */
        @JvmOverloads
        fun service(
            application: Class<*>,
            name: String,
            contexts: List<String>,
            publicPort: Int = 0,
            internalPort: Int = 0,
            properties: Map<String, Any?> = emptyMap(),
        ): Builder = apply {
            require(name.isNotBlank()) { "A local service is named." }
            require(contexts.isNotEmpty()) { "The local service $name serves a context at least." }
            require(services.none { it.name == name }) { "Two local services are named $name." }
            services += LocalService(application, name, contexts.toList(), publicPort, internalPort, properties)
        }

        /** The service of [App], as [service] of its class adds it. */
        inline fun <reified App : Any> service(
            name: String,
            contexts: List<String>,
            publicPort: Int = 0,
            internalPort: Int = 0,
            properties: Map<String, Any?> = emptyMap(),
        ): Builder = service(App::class.java, name, contexts, publicPort, internalPort, properties)

        internal fun start(): WowLocal {
            require(services.isNotEmpty()) { "A local run has a service at least." }
            if (ports == LocalPorts.FIXED) {
                services.filter { it.publicPort <= 0 || it.internalPort <= 0 }.forEach { service ->
                    throw IllegalArgumentException(
                        "The local service ${service.name} names no public or internal port, which fixed ports " +
                            "(${LocalPorts.PROPERTY}) need: give it both, or run with ${LocalPorts.PROPERTY}=random.",
                    )
                }
            }
            val topics = InMemoryTopics(partitions)
            val started = LinkedHashMap<LocalService, WowServer>()
            try {
                services.forEach { service -> started[service] = service.start(ports, partitions, topics, started) }
            } catch (failed: RuntimeException) {
                closeAll(started.values.toList())
                throw failed
            }
            return WowLocal(started.mapKeys { (service, _) -> service.name })
        }
    }

    /** A service a [WowLocal] runs. */
    internal class LocalService(
        val application: Class<*>,
        val name: String,
        val contexts: List<String>,
        val publicPort: Int,
        val internalPort: Int,
        val properties: Map<String, Any?>,
    ) {
        /** This service started on [ports], sharing [topics], calling the services started [before] it. */
        fun start(
            ports: LocalPorts,
            partitions: Int,
            topics: InMemoryTopics,
            before: Map<LocalService, WowServer>,
        ): WowServer {
            val fixed = ports == LocalPorts.FIXED
            val own = mapOf(
                "spring.application.name" to name,
                "wow.contexts.include" to contexts.joinToString(","),
                "wow.log.partitions" to partitions,
                "server.port" to if (fixed) publicPort else 0,
                "wow.internal.port" to if (fixed) internalPort else 0,
            )
            return wowServer(
                application,
                profiles = listOf(PROFILE),
                properties = peersOf(before, contexts) + own + properties,
                topics = topics,
            )
        }
    }

    companion object {
        /** The partitions of the shared topics and of every service's logs, by default. */
        const val DEFAULT_PARTITIONS: Int = 4

        /** The system property that makes [runWowLocal] exit when its standard input closes. */
        const val EXIT_ON_STDIN_EOF: String = "wow-local.exit-on-stdin-eof"

        private const val READY = "wow-local ready"
        private const val PROFILE = "dev"

        /**
         * The `wow.peers.<context>.url` of each context the [started] services serve but [contexts]: the internal port
         * of the server that serves it.
         */
        internal fun peersOf(started: Map<LocalService, WowServer>, contexts: List<String>): Map<String, Any?> =
            started.flatMap { (service, server) ->
                service.contexts.filter { it !in contexts }.map { context ->
                    "wow.peers.$context.url" to "http://$LOOPBACK:${server.internalPort}"
                }
            }.toMap()

        private fun closeAll(servers: List<WowServer>) {
            servers.reversed().forEach { runCatching { it.close() } }
        }

        private const val LOOPBACK = "127.0.0.1"
    }
}
