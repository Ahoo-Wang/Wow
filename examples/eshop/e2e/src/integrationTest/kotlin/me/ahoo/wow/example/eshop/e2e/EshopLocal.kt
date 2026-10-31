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

package me.ahoo.wow.example.eshop.e2e

import me.ahoo.wow.example.eshop.basket.server.BasketServer
import me.ahoo.wow.example.eshop.catalog.server.CatalogServer
import me.ahoo.wow.example.eshop.inventory.server.InventoryServer
import me.ahoo.wow.example.eshop.ordering.server.OrderingServer
import me.ahoo.wow.example.eshop.payment.server.PaymentServer
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.http.internal.InternalPort
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import kotlin.system.exitProcess

/**
 * The whole eShop in this one JVM, on the memory backend and without Kafka, for the web UI (work package E.7) and for
 * trying the shop out: `./gradlew :eshop-e2e:eshopLocal`. The five services share one class path, each
 * serving its own context (`wow.contexts.include`, as [EshopSharedJvmTest] runs them), and one [InMemoryTopics] in
 * place of Kafka, which `wow.transport` wires into each (DX gap G27): each one's sagas send other contexts'
 * commands to it, its published streams are written to it, and it executes the commands sent to its own command
 * topic. So the checkout's whole chain runs, to the payment, as it does on Kafka.
 *
 * The system property `eshop.local.ports` is `fixed` (the default: the README's ports, 8081 to 8085 public and 9091 to
 * 9095 internal) or `random` (every port bound to 0). Once every service listens, it prints one line,
 * `eshop-local ready {"catalog":8081,...}`, with the public ports. With `eshop.local.exit-on-stdin-eof=true` it stops
 * when its standard input closes, so a test that started it never leaves it running.
 */
fun main() {
    val random = System.getProperty("eshop.local.ports", "fixed") == "random"
    val shop = EshopLocal(random)
    Runtime.getRuntime().addShutdownHook(Thread(shop::close, "eshop-local-stop"))
    try {
        shop.start()
    } catch (failed: Exception) {
        System.err.println("eshop-local failed to start: $failed")
        shop.close()
        exitProcess(1)
    }
    println("eshop-local ready ${shop.publicPorts.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }}")
    if (System.getProperty("eshop.local.exit-on-stdin-eof").toBoolean()) {
        while (System.`in`.read() != -1) {
            // Reads until the parent closes the pipe.
        }
        exitProcess(0)
    }
}

/**
 * The CosId setting of the eShop service of [context] in a JVM that runs several services on one class path, where
 * Spring Boot reads one `application.yaml` for all of them: the ordering imports its order numbers' configuration
 * (decision D373); the others find CosId on the ordering's class path, and run without it.
 */
fun cosIdOf(context: String): Pair<String, String> =
    if (context == "ordering") {
        "spring.config.import" to "classpath:eshop-ordering-ids.yaml"
    } else {
        "cosid.enabled" to "false"
    }

/** The five eShop services in this JVM, on the memory backend, their topics in memory; on [randomPorts] or fixed ones. */
class EshopLocal(private val randomPorts: Boolean) : AutoCloseable {
    private val topics = InMemoryTopics(PARTITIONS)
    private val started = mutableListOf<ConfigurableApplicationContext>()

    /** The public port of each service, by its short name, once [start]ed. */
    val publicPorts: MutableMap<String, Int> = linkedMapOf()

    /**
     * Starts the services, each topic's owner before its readers and writers: the inventory and the ordering create
     * their command topics, and the ordering its published order stream, before the services that send to or read
     * them start (an in-memory topic read first would be created with its default partitions).
     */
    fun start() {
        start(InventoryServer::class.java, "inventory")
        val ordering = start(
            OrderingServer::class.java,
            "ordering",
            "--eshop.ordering.payment-grace-period=30m",
        )
        start(PaymentServer::class.java, "payment", "--eshop.payment.gateway.limit=1000.00")
        val orderingGateway = ordering.getBean(InternalPort::class.java).boundPort
        start(
            BasketServer::class.java,
            "basket",
            "--wow.peers.eshop-ordering.url=http://127.0.0.1:$orderingGateway",
        )
        start(CatalogServer::class.java, "catalog")
    }

    private fun start(application: Class<*>, name: String, vararg arguments: String): ConfigurableApplicationContext {
        val offset = SERVICES.indexOf(name) + 1
        // A development profile: the requests name their callers in the Wow-* headers.
        val context = SpringApplicationBuilder(application)
            .profiles("dev")
            .initializers({ it.beanFactory.registerSingleton(TOPICS_BEAN, topics) })
            .run(
                *arguments,
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN",
                "--spring.application.name=eshop-$name-service",
                "--server.address=127.0.0.1",
                "--server.port=${if (randomPorts) 0 else PUBLIC_PORT_BASE + offset}",
                "--wow.log.partitions=$PARTITIONS",
                "--wow.internal.host=127.0.0.1",
                "--wow.internal.port=${if (randomPorts) 0 else INTERNAL_PORT_BASE + offset}",
                "--wow.contexts.include=eshop-$name",
                cosIdOf(name).let { (key, value) -> "--$key=$value" },
                // The services share one class path, so one application.yaml: each is given its own secrets.
            )
        started += context
        publicPorts[name] = context.environment.getRequiredProperty("local.server.port", Int::class.java)
        return context
    }

    override fun close() {
        synchronized(started) {
            started.reversed().forEach { runCatching { it.close() } }
            started.clear()
        }
    }

    companion object {
        /** The services, in the order of their ports: catalog 8081 and 9091, ..., payment 8085 and 9095. */
        val SERVICES = listOf("catalog", "basket", "ordering", "inventory", "payment")
        const val PUBLIC_PORT_BASE = 8080
        const val INTERNAL_PORT_BASE = 9090
        const val PARTITIONS = 4
        const val TOPICS_BEAN = "eshopLocalTopics"
    }
}

