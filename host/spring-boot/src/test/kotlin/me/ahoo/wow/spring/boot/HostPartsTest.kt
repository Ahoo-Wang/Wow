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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.MissingDependencyException
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.node.Routing
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackendSelectionTest {
    @Test
    fun `auto should pick the backend whose bean the application has`() {
        BackendSelection.select(
            BackendKind.AUTO,
            postgres = true,
            mongo = false
        ).assert().isEqualTo(BackendKind.POSTGRES)
        BackendSelection.select(BackendKind.AUTO, postgres = false, mongo = true).assert().isEqualTo(BackendKind.MONGO)
        BackendSelection.select(
            BackendKind.AUTO,
            postgres = false,
            mongo = false
        ).assert().isEqualTo(BackendKind.MEMORY)
    }

    @Test
    fun `auto should refuse two backends`() {
        assertThrows<IllegalStateException> { BackendSelection.select(BackendKind.AUTO, postgres = true, mongo = true) }
    }

    @Test
    fun `a configured backend should be taken when it is available`() {
        BackendSelection.select(
            BackendKind.POSTGRES,
            postgres = true,
            mongo = true
        ).assert().isEqualTo(BackendKind.POSTGRES)
        BackendSelection.select(BackendKind.MONGO, postgres = true, mongo = true).assert().isEqualTo(BackendKind.MONGO)
        BackendSelection.select(
            BackendKind.MEMORY,
            postgres = true,
            mongo = true
        ).assert().isEqualTo(BackendKind.MEMORY)
    }

    @Test
    fun `the router should follow the backend`() {
        val properties = WowProperties()
        WowAutoConfiguration.routing(properties, BackendKind.MEMORY).assert().isEqualTo(Routing.LOCAL)
        WowAutoConfiguration.routing(properties, BackendKind.POSTGRES).assert().isEqualTo(Routing.PARTITIONED)
        WowAutoConfiguration.routing(properties, null).assert().isEqualTo(Routing.PARTITIONED)
        properties.cluster.router = Routing.LOCAL
        WowAutoConfiguration.routing(properties, BackendKind.MONGO).assert().isEqualTo(Routing.LOCAL)
        WowAutoConfiguration.routing(properties, BackendKind.MEMORY).assert().isEqualTo(Routing.LOCAL)
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
        backends.kind.assert().isNull()
        backends.appendQueue.assert().isEqualTo(AppendQueueOptions())
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
        properties.backend.assert().isEqualTo(BackendKind.AUTO)
        properties.internal.port.assert().isEqualTo(9090)
        properties.log.partitions.assert().isEqualTo(64)
        properties.cluster.options().assert().isEqualTo(ClusterOptions())
        properties.subscription.options().assert().isEqualTo(SubscriptionOptions())
        properties.log.appendQueue.options(AppendQueueOptions.POSTGRES).assert().isEqualTo(AppendQueueOptions.POSTGRES)
        properties.log.appendQueue.options(AppendQueueOptions.MONGO).assert().isEqualTo(AppendQueueOptions.MONGO)
        properties.tenant.default.assert().isEqualTo("(0)")
        properties.wait.defaultTimeout.assert().isEqualTo(Duration.ofSeconds(10))
        properties.wait.maxTimeout.assert().isEqualTo(Duration.ofSeconds(60))
        properties.wait.maxHops.assert().isEqualTo(4)
        properties.http.manifest.enabled.assert().isTrue()
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
        properties.backend.assert().isEqualTo(BackendKind.MONGO)
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
