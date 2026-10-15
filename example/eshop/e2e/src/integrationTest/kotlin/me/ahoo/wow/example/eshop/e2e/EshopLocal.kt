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
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.TopicDdl
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.spring.boot.InternalServer
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.system.exitProcess

/**
 * The whole eShop in this one JVM, on the memory backend and without Kafka, for the web UI (work package E.7) and for
 * trying the shop out: `./gradlew -Pwow.scope=v10 :eshop-e2e:eshopLocal`. The five services share one class path, each
 * serving its own context (`wow.contexts.include`, as [EshopSharedJvmTest] runs them), and one [InMemoryTopics] in
 * place of Kafka ([LocalTopicsBeans]): each one's sagas send other contexts' commands to it, its published streams are
 * written to it, and it executes the commands sent to its own command topic. So the checkout's whole chain runs, to
 * the payment, as it does on Kafka (DX gap G27).
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
            "--wow.http.services.eshop-basket-service.tokens[0]=$SERVICE_TOKEN",
        )
        start(PaymentServer::class.java, "payment", "--eshop.payment.gateway.limit=1000.00")
        val orderingGateway = ordering.getBean(InternalServer::class.java).boundPort
        start(
            BasketServer::class.java,
            "basket",
            "--wow.calls.contexts.eshop-ordering.url=http://127.0.0.1:$orderingGateway",
            "--wow.calls.token=$SERVICE_TOKEN",
        )
        start(CatalogServer::class.java, "catalog")
    }

    private fun start(application: Class<*>, name: String, vararg arguments: String): ConfigurableApplicationContext {
        val offset = SERVICES.indexOf(name) + 1
        val context = SpringApplicationBuilder(application, LocalTopicsBeans::class.java)
            .initializers({ it.beanFactory.registerSingleton(TOPICS_BEAN, topics) })
            .run(
                *arguments,
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN",
                "--spring.application.name=eshop-$name-service",
                "--server.address=127.0.0.1",
                "--server.port=${if (randomPorts) 0 else PUBLIC_PORT_BASE + offset}",
                "--wow.service=eshop-$name-service",
                "--wow.backend=memory",
                "--wow.log.partitions=$PARTITIONS",
                // The storefront's and the orders' snapshot queries, from memory (decision D209).
                "--wow.query.memory.enabled=true",
                "--wow.internal.host=127.0.0.1",
                "--wow.internal.port=${if (randomPorts) 0 else INTERNAL_PORT_BASE + offset}",
                "--wow.contexts.include=eshop-$name",
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

        /** The basket's credential at the ordering's internal port (decision D193), for this one machine. */
        const val SERVICE_TOKEN = "eshop-local-service-token-not-a-secret"
    }
}

/**
 * The transport of one service on the shared [InMemoryTopics], as wow-kafka gives it on Kafka: the topics it reads and
 * sends to, with its command intake and the topics it owns created at its start, and the Sink of its published streams.
 */
@Configuration(proxyBeanMethods = false)
class LocalTopicsBeans {
    @Bean
    fun localSourceTopics(topics: InMemoryTopics): SourceTopics =
        SourceTopics(topics, producer = topics, intake = true, admin = topics, ddl = TopicDdl.AUTO)

    @Bean
    fun localPublishedSinks(manifest: Manifest, topics: InMemoryTopics): PublishedSinks =
        PublishedSinks.topics(manifest, topics)
}
