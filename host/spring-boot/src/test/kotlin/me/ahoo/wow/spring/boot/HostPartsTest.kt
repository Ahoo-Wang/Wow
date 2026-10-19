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

import com.mongodb.client.MongoClient
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.MissingDependencyException
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.node.ContextNode
import me.ahoo.wow.runtime.node.Routing
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.runtime.wait.UnknownContexts
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.AggregateIdGeneratorSource
import me.ahoo.wow.spi.store.StoreResources
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BackendSelectionTest {
    /** The backend [configured] selects when a PostgreSQL ([postgres]) or MongoDB ([mongo]) bean is there. */
    private fun select(configured: String, postgres: Boolean, mongo: Boolean, memoryOptIn: String? = null): String {
        val resources = object : StoreResources {
            override fun has(type: Class<*>): Boolean =
                postgres && type == DataSource::class.java || mongo && type == MongoClient::class.java

            override fun <T : Any> resource(type: Class<T>, name: String?, key: String): T = error("not opened")
        }
        return BackendSelection.select(configured, providers, resources, memoryOptIn)
    }

    /** Memory's, and PostgreSQL's and MongoDB's from the classpath. */
    private val providers = StoreProviders.load(javaClass.classLoader)

    @Test
    fun `auto should pick the backend whose bean the application has`() {
        select("auto", postgres = true, mongo = false).assert().isEqualTo("postgres")
        select("auto", postgres = false, mongo = true).assert().isEqualTo("mongo")
        select("auto", postgres = false, mongo = false, memoryOptIn = "profile dev").assert().isEqualTo("memory")
    }

    @Test
    fun `auto without a durable backend should fail unless a development node opts in to memory`() {
        // Review R3.5-1: auto fell back to memory with a warning, and lost every command on a restart.
        assertThrows<IllegalStateException> {
            select("auto", postgres = false, mongo = false)
        }.message.assert().isEqualTo(BackendSelection.NO_DURABLE_BACKEND).contains("wow.backend=memory")
        select("memory", postgres = false, mongo = false).assert().isEqualTo("memory")
    }

    @Test
    fun `a development profile or a loopback internal port should opt in to memory`() {
        val dev = setOf("dev", "test")
        BackendSelection.memoryOptIn(listOf("prod", "test"), dev, "10.0.0.1").assert().isEqualTo("profile test")
        BackendSelection.memoryOptIn(listOf("prod"), dev, "127.0.0.1").assert()
            .isEqualTo("internal port on loopback 127.0.0.1")
        BackendSelection.memoryOptIn(emptyList(), dev, "::1").assert().isNotNull()
        BackendSelection.memoryOptIn(listOf("prod"), dev, "10.0.0.1").assert().isNull()
        BackendSelection.memoryOptIn(listOf("prod"), dev, "0.0.0.0").assert().isNull()
        BackendSelection.memoryOptIn(emptyList(), dev, null).assert().isNull()
        BackendSelection.memoryOptIn(emptyList(), dev, "no-such-host.invalid").assert().isNull()
    }

    @Test
    fun `auto should refuse two backends`() {
        assertThrows<IllegalStateException> { select("auto", postgres = true, mongo = true) }
            .message.assert().contains("postgres", "mongo")
    }

    @Test
    fun `a configured backend should be taken when it is available`() {
        select("postgres", postgres = true, mongo = true).assert().isEqualTo("postgres")
        select("MONGO", postgres = true, mongo = true).assert().isEqualTo("mongo")
        select("memory", postgres = true, mongo = true).assert().isEqualTo("memory")
    }

    @Test
    fun `a configured backend should fail when it is not available`() {
        assertThrows<IllegalStateException> { select("postgres", postgres = false, mongo = true) }
            .message.assert().isEqualTo(
                "wow.backend=postgres needs wow-postgres on the classpath and a DataSource bean."
            )
        assertThrows<IllegalStateException> { select("mongo", postgres = true, mongo = false) }
            .message.assert().contains("a MongoClient bean")
        assertThrows<IllegalStateException> { select("acme", postgres = true, mongo = false) }
            .message.assert().contains("wow.backend is acme, which no store provider offers")
        assertThrows<IllegalStateException> {
            BackendSelection.select(
                "postgres",
                StoreProviders(mapOf("memory" to MemoryStoreProvider())),
                StoreResources.NONE
            )
        }.message.assert().isEqualTo("wow.backend is postgres, which needs wow-postgres on the classpath.")
    }

    @Test
    fun `the router should follow the backend`() {
        val properties = WowProperties()
        WowAutoConfiguration.routing(properties, local = true).assert().isEqualTo(Routing.LOCAL)
        WowAutoConfiguration.routing(properties, local = false).assert().isEqualTo(Routing.PARTITIONED)
        properties.cluster.router = Routing.LOCAL
        WowAutoConfiguration.routing(properties, local = false).assert().isEqualTo(Routing.LOCAL)
        WowAutoConfiguration.routing(properties, local = true).assert().isEqualTo(Routing.LOCAL)
        properties.cluster.router = Routing.PARTITIONED
        assertThrows<IllegalStateException> { WowAutoConfiguration.routing(properties, local = true) }
    }

    @Test
    fun `the memory backends should open each context once`() {
        val backends = HostBackends.memory(4)
        val bank = backends.factory.backends("bank")
        backends.factory.backends("bank").assert().isSameAs(bank)
        val ledger = backends.factory.backends("ledger")
        ledger.log.assert().isNotSameAs(bank.log)
        ledger.leases.assert().isSameAs(bank.leases)
        bank.log.partitions.assert().isEqualTo(4)
        backends.contexts.keys.assert().containsExactlyInAnyOrder("bank", "ledger")
        backends.createSchemas()
    }

    @Test
    fun `the application's backend factory should be opened once per context`() {
        var opened = 0
        val memory = HostBackends.memory(2)
        val backends = HostBackends.of { context ->
            opened++
            memory.factory.backends(context)
        }
        backends.factory.backends("bank")
        backends.factory.backends("bank")
        opened.assert().isEqualTo(1)
        backends.type.assert().isNull()
        backends.local.assert().isFalse()
        backends.appendQueue.assert().isEqualTo(AppendQueueOptions())
    }

    @Test
    fun `the processors on the class path should be registered beside the application's subscriptions`() {
        val models = BankModels().models(Dependencies.of(DecidedCommands()))
        val providers = ModelProvider.load()
        val discovered = WowAutoConfiguration.bindings(providers, Dependencies.NONE, emptyList())
        discovered.assert().isInstanceOf(SubscriptionBindings::class.java)
        discovered.viewTypes(BANK, models).assert().isEmpty()
        val application = object : SubscriptionFactory {
            override fun subscriptions(context: ContextNode) = emptyList<Subscription>()

            override fun viewTypes(context: String, models: List<AggregateModel<*>>) = models.map { it.type }.toSet()
        }
        val both = WowAutoConfiguration.bindings(providers, Dependencies.NONE, listOf(application, application))
        both.assert().isNotInstanceOf(SubscriptionBindings::class.java)
        both.viewTypes(BANK, models).assert().containsExactly(AggregateType(BANK, "account"))
    }
}

class BeanFactoryDependenciesTest {
    private val beanFactory = DefaultListableBeanFactory()
    private val dependencies = BeanFactoryDependencies(beanFactory)

    @Test
    fun `a dependency should be the only bean of its type`() {
        beanFactory.registerSingleton("clock", java.time.Clock.systemUTC())
        dependencies.get(java.time.Clock::class.java).assert().isSameAs(java.time.Clock.systemUTC())
        dependencies.find(StringBuilder::class.java, null).assert().isNull()
        assertThrows<MissingDependencyException> { dependencies.get(StringBuilder::class.java) }
    }

    @Test
    fun `a qualified dependency should be the bean of that name and type`() {
        beanFactory.registerSingleton("first", StringBuilder("1"))
        beanFactory.registerSingleton("second", StringBuilder("2"))
        dependencies.get(StringBuilder::class.java, "second").toString().assert().isEqualTo("2")
        dependencies.find(StringBuilder::class.java, "third").assert().isNull()
        dependencies.find(Integer::class.java, "first").assert().isNull()
    }

    @Test
    fun `several beans of a type should need a qualifier unless one is primary`() {
        beanFactory.registerSingleton("first", StringBuilder("1"))
        beanFactory.registerSingleton("second", StringBuilder("2"))
        assertThrows<IllegalStateException> { dependencies.find(StringBuilder::class.java, null) }
        beanFactory.registerBeanDefinition(
            "primary",
            RootBeanDefinition(StringBuilder::class.java) { StringBuilder("p") }.apply { isPrimary = true },
        )
        dependencies.get(StringBuilder::class.java).toString().assert().isEqualTo("p")
    }
}

class WowPropertiesTest {
    private fun bind(vararg properties: Pair<String, String>): WowProperties =
        Binder(MapConfigurationPropertySource(properties.toMap())).bind("wow", WowProperties::class.java)
            .orElse(null) ?: WowProperties()

    @Test
    fun `the defaults should be the runtime's`() {
        val properties = WowProperties()
        properties.backend.assert().isEqualTo("auto")
        properties.internal.port.assert().isEqualTo(9090)
        properties.log.partitions.assert().isEqualTo(64)
        properties.cluster.options().assert().isEqualTo(ClusterOptions())
        properties.subscription.options().assert().isEqualTo(SubscriptionOptions())
        properties.log.appendQueue.options(AppendQueueOptions.POSTGRES).assert().isEqualTo(AppendQueueOptions.POSTGRES)
        properties.log.appendQueue.options(AppendQueueOptions.MONGO).assert().isEqualTo(AppendQueueOptions.MONGO)
        properties.tenant.default.assert().isEqualTo("(0)")
        properties.wait.defaultTimeout.assert().isEqualTo(Duration.ofSeconds(10))
        properties.wait.maxTimeout.assert().isEqualTo(Duration.ofSeconds(60))
        properties.wait.maxHops.assert().isEqualTo(16)
        properties.wait.unknownContexts.assert().isEqualTo(UnknownContexts.ALLOW)
        properties.http.manifest.enabled.assert().isTrue()
        properties.http.openapi.enabled.assert().isTrue()
        properties.mongo.database.assert().isEqualTo("wow")
    }

    @Test
    fun `the wow namespace should bind`() {
        val properties = bind(
            "wow.service" to "svc",
            "wow.node.id" to "n1",
            "wow.node.address" to "10.0.0.1:9191",
            "wow.internal.port" to "9191",
            "wow.backend" to "mongo",
            "wow.mongo.database" to "shop",
            "wow.log.partitions" to "16",
            "wow.log.append-queue.enabled" to "false",
            "wow.cluster.router" to "local",
            "wow.cluster.lease-ttl" to "20s",
            "wow.cluster.drain-timeout" to "500ms",
            "wow.subscription.read-retry.max-attempts" to "3",
            "wow.subscription.read-retry.max-backoff" to "1s",
            "wow.http.errors.[shop.cart.full].status" to "409",
            "wow.http.request-facts[0]" to "X-App",
            "wow.aggregates.[shop.cart].tenant" to "t0",
        )
        properties.service.assert().isEqualTo("svc")
        properties.node.id.assert().isEqualTo("n1")
        properties.node.address.assert().isEqualTo("10.0.0.1:9191")
        properties.internal.port.assert().isEqualTo(9191)
        properties.backend.assert().isEqualTo("mongo")
        properties.mongo.database.assert().isEqualTo("shop")
        properties.log.partitions.assert().isEqualTo(16)
        properties.log.appendQueue.options(AppendQueueOptions.MONGO).enabled.assert().isFalse()
        properties.cluster.router.assert().isEqualTo(Routing.LOCAL)
        properties.cluster.options().leaseTtl.assert().isEqualTo(20.seconds)
        properties.cluster.options().drainTimeout.assert().isEqualTo(500.milliseconds)
        properties.subscription.options().readRetry.maxAttempts.assert().isEqualTo(3)
        properties.subscription.options().readRetry.maxBackoff.assert().isEqualTo(1.seconds)
        properties.http.errors.getValue("shop.cart.full").status.assert().isEqualTo(409)
        properties.http.requestFacts.assert().containsExactly("X-App")
        properties.aggregates.getValue("shop.cart").tenant.assert().isEqualTo("t0")
    }

    @Test
    fun `a subscription's start should bind by its name (D154)`() {
        val properties = bind(
            "wow.subscriptions.[CartSaga.onOrderCreated].start" to "beginning",
            "wow.subscriptions.[Audit.onEvent].start" to "2026-10-01T00:00:00Z",
            "wow.subscriptions.[Mail.send].start" to "end",
            "wow.subscriptions.[View.onEvent].start" to "",
        )
        properties.subscriptions.keys.assert()
            .containsExactlyInAnyOrder("CartSaga.onOrderCreated", "Audit.onEvent", "Mail.send", "View.onEvent")
        properties.subscription.options(properties.subscriptions).starts.assert().isEqualTo(
            mapOf(
                "CartSaga.onOrderCreated" to SubscriptionStart.Beginning,
                "Audit.onEvent" to SubscriptionStart.At(Instant.parse("2026-10-01T00:00:00Z")),
                "Mail.send" to SubscriptionStart.End,
            ),
        )
        properties.subscription.options().starts.assert().isEmpty()
        val invalid = bind("wow.subscriptions.[Mail.send].start" to "soon")
        assertThrows<IllegalArgumentException> { invalid.subscription.options(invalid.subscriptions) }
            .message.assert().contains("soon")
    }

    @Test
    fun `a subscription's retry budget should bind by its name, over the global one (P3)`() {
        val properties = bind(
            "wow.subscription.retry.max-attempts" to "4",
            "wow.subscription.retry.timeout" to "30s",
            "wow.subscriptions.[Mail.send].retry.max-attempts" to "2",
            "wow.subscriptions.[Mail.send].retry.timeout" to "500ms",
            "wow.subscriptions.[Audit.onEvent].retry.initial-backoff" to "1s",
            "wow.subscriptions.[Audit.onEvent].retry.max-backoff" to "1m",
            "wow.subscriptions.[Audit.onEvent].retry.multiplier" to "3.0",
            "wow.subscriptions.[View.onEvent].start" to "end",
        )
        val options = properties.subscription.options(properties.subscriptions)
        options.retry.maxAttempts.assert().isEqualTo(4)
        options.retry.timeout.assert().isEqualTo(30.seconds)
        options.retries.assert().isEqualTo(
            mapOf(
                "Mail.send" to HandlerRetry(maxAttempts = 2, timeout = 500.milliseconds),
                "Audit.onEvent" to HandlerRetry(initialBackoff = 1.seconds, maxBackoff = 1.minutes, multiplier = 3.0),
            ),
        )
        WowProperties().subscription.retry.timeout.assert().isNull()
        WowProperties.Retry.of(options.retry).timeout.assert().isEqualTo(Duration.ofSeconds(30))
    }

    @Test
    fun `a fixed tenant should be of an aggregate type the node serves (D116)`() {
        val models = BankModels().models(Dependencies.of(DecidedCommands()))
        val account = AggregateType(BANK, "account")
        WowAutoConfiguration.tenants(WowProperties(), models).fixedTenant(account).assert().isNull()
        val fixed = bind("wow.aggregates.[bank.account].tenant" to "t0")
        WowAutoConfiguration.tenants(fixed, models).fixedTenant(account).assert().isEqualTo("t0")
        val unknown = bind("wow.aggregates.[shop.cart].tenant" to "t0")
        assertThrows<IllegalStateException> { WowAutoConfiguration.tenants(unknown, models) }
            .message.assert().contains("shop.cart")
        val blank = WowProperties().apply { aggregates["bank.account"] = WowProperties.Aggregate().apply { tenant = " " } }
        assertThrows<IllegalStateException> { WowAutoConfiguration.tenants(blank, models) }
            .message.assert().contains("blank")
    }

    @Test
    fun `an aggregate type's id generator should be the one its id-generator names (D171)`() {
        val models = BankModels().models(Dependencies.of(DecidedCommands()))
        val account = AggregateType(BANK, "account")
        val default = AggregateIdGenerator { "default" }
        val named = AggregateIdGenerator { "named" }
        val lookup = { name: String -> named.takeIf { name == "accounts" } }
        WowAutoConfiguration.aggregateIdGenerators(WowProperties(), models, default, lookup).assert().isSameAs(default)
        val configured = bind("wow.aggregates.[bank.account].id-generator" to "accounts")
        WowAutoConfiguration.aggregateIdGenerators(configured, models, default, lookup).next(account)
            .assert().isEqualTo("named")
        val tenantOnly = bind("wow.aggregates.[bank.account].tenant" to "t0")
        WowAutoConfiguration.aggregateIdGenerators(tenantOnly, models, default, lookup).assert().isSameAs(default)
        val unknownName = bind("wow.aggregates.[bank.account].id-generator" to "missing")
        assertThrows<IllegalStateException> {
            WowAutoConfiguration.aggregateIdGenerators(unknownName, models, default, lookup)
        }.message.assert().contains("names missing")
        val unknownType = bind("wow.aggregates.[shop.cart].id-generator" to "accounts")
        assertThrows<IllegalStateException> {
            WowAutoConfiguration.aggregateIdGenerators(unknownType, models, default, lookup)
        }.message.assert().contains("shop.cart")
        val blank = bind("wow.aggregates.[bank.account].id-generator" to " ")
        assertThrows<IllegalStateException> {
            WowAutoConfiguration.aggregateIdGenerators(blank, models, default, lookup)
        }.message.assert().contains("blank")
    }

    @Test
    fun `a generator's name should find its bean first, then a source's generator (D171)`() {
        val bean = AggregateIdGenerator { "bean" }
        val sourced = AggregateIdGenerator { "sourced" }
        val beans = DefaultListableBeanFactory().apply {
            registerSingleton("accounts", bean)
            registerSingleton("notAGenerator", "text")
        }
        val sources = listOf(
            AggregateIdGeneratorSource { null },
            AggregateIdGeneratorSource { name -> sourced.takeIf { name == "cosid-accounts" } },
        )
        val lookup = WowAutoConfiguration.idGeneratorLookup(beans, sources)
        lookup("accounts").assert().isSameAs(bean)
        lookup("cosid-accounts").assert().isSameAs(sourced)
        lookup("notAGenerator").assert().isNull()
        WowAutoConfiguration.idGeneratorLookup(beans, emptyList())("cosid-accounts").assert().isNull()
    }

    @Test
    fun `the node identity should default to the service name and this host`() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(MapPropertySource("test", mapOf("spring.application.name" to "app")))
        val properties = WowProperties().apply { internal.port = 9300 }
        val identity = NodeIdentity.of(properties, environment)
        identity.service.assert().isEqualTo("app")
        identity.node.assert().isNotBlank()
        identity.address.assert().endsWith(":9300")
        NodeIdentity.of(properties, environment).node.assert().isNotEqualTo(identity.node)
        properties.internal.host = "127.0.0.1"
        NodeIdentity.of(properties, environment).address.assert().isEqualTo("127.0.0.1:9300")
    }

    @Test
    fun `the internal port should bind the node's member host unless wow internal host says otherwise`() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(MapPropertySource("test", mapOf("spring.application.name" to "app")))
        // Decision D195: by default, the host the node advertises, never every interface.
        val properties = WowProperties()
        val identity = NodeIdentity.of(properties, environment)
        identity.bindHost.assert().isEqualTo(identity.host).isNotEqualTo("0.0.0.0")
        properties.node.address = "10.0.0.1:9191"
        NodeIdentity.of(properties, environment).bindHost.assert().isEqualTo("10.0.0.1")
        properties.node.address = "[fd00::1]:9191"
        NodeIdentity.of(properties, environment).bindHost.assert().isEqualTo("fd00::1")
        properties.node.address = "node-1.shop"
        NodeIdentity.of(properties, environment).bindHost.assert().isEqualTo("node-1.shop")
        // A blank wow.internal.host is not set.
        properties.internal.host = " "
        NodeIdentity.of(properties, environment).bindHost.assert().isEqualTo("node-1.shop")
        properties.node.address = " "
        NodeIdentity.of(properties, environment).let { it.bindHost.assert().isEqualTo(it.host) }
        properties.internal.host = "0.0.0.0"
        NodeIdentity.of(properties, environment).bindHost.assert().isEqualTo("0.0.0.0")
        properties.internal.host = "127.0.0.1"
        NodeIdentity.of(properties, environment).bindHost.assert().isEqualTo("127.0.0.1")
    }

    @Test
    fun `a node on a port the system chooses should learn its address once bound`() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(MapPropertySource("test", mapOf("spring.application.name" to "app")))
        val properties = WowProperties().apply {
            internal.port = 0
            internal.host = "127.0.0.1"
        }
        val identity = NodeIdentity.of(properties, environment)
        identity.address.assert().isNull()
        identity.host.assert().isEqualTo("127.0.0.1")
        identity.addressAt(41234).assert().isEqualTo("127.0.0.1:41234")
        properties.node.address = "10.0.0.1:9191"
        NodeIdentity.of(properties, environment).addressAt(41234).assert().isEqualTo("10.0.0.1:9191")
    }
}

class ShutdownTimeoutEnvironmentPostProcessorTest {
    private fun environment(vararg properties: Pair<String, Any>): StandardEnvironment = StandardEnvironment().apply {
        propertySources.addFirst(MapPropertySource("test", properties.toMap()))
    }

    private fun timeout(environment: StandardEnvironment): Duration? {
        ShutdownTimeoutEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())
        return ShutdownTimeoutEnvironmentPostProcessor.configured(environment)
    }

    @Test
    fun `the shutdown phase should allow the longest wait and the drain`() {
        timeout(environment()).assert().isEqualTo(Duration.ofSeconds(70))
        timeout(environment("wow.wait.max-timeout" to "20s", "wow.cluster.drain-timeout" to "5s"))
            .assert().isEqualTo(Duration.ofSeconds(25))
    }

    @Test
    fun `a shutdown timeout the application sets should be kept`() {
        timeout(environment("spring.lifecycle.timeout-per-shutdown-phase" to "5s"))
            .assert().isEqualTo(Duration.ofSeconds(5))
    }
}
