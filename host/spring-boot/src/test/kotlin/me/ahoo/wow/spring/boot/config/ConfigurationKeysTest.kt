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

package me.ahoo.wow.spring.boot.config

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.peer.PeerDiscovery
import me.ahoo.wow.spi.peer.PeerDiscoveryConfig
import me.ahoo.wow.spi.peer.PeerTargets
import me.ahoo.wow.spring.boot.http.PeerDiscoveries
import me.ahoo.wow.spring.boot.startHost
import me.ahoo.wow.spring.boot.store.ProviderOptionKeys
import me.ahoo.wow.spring.boot.store.StoreProviders
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.mock.env.MockEnvironment
import java.io.File

/**
 * The `wow.*` keys no property names (review A5-9): every one refuses the startup (decision D302), a key of an earlier
 * version with what took its place.
 */
class ConfigurationKeysTest {
    private val manifestVariable = Regex("""^\s*(?:- name:\s*)?(WOW_[A-Z0-9_]+)\s*:?""")

    private val keys = ConfigurationKeys.of(javaClass.classLoader, listOf("wow.view-store", "wow", "spring.foo"))

    private fun environment(vararg properties: Pair<String, String>) = MockEnvironment().apply {
        properties.forEach { (key, value) -> setProperty(key, value) }
    }

    /** An environment whose [variables] come as the operating system's do. */
    private fun variables(vararg variables: Pair<String, String>) = MockEnvironment().apply {
        propertySources.addLast(SystemEnvironmentPropertySource("systemEnvironment", mapOf<String, Any>(*variables)))
    }

    @Test
    fun `the keys of the metadata, relaxed, mapped, listed or owned by another bean are known`() {
        val environment = environment(
            "wow.executor.max-in-flight" to "8",
            "wow.executor.laneCapacity" to "8",
            "wow.aggregates.[shop.cart].tenant" to "acme",
            "wow.cosec.cross-scope.roles[0]" to "admin",
            "wow.cosec.authorizer.enabled" to "true",
            "wow.query.memory.enabled" to "true",
            "wow.view-store.system-views[0].id" to "v",
            "spring.application.name" to "shop",
        )
        keys.unknown(environment).assert().isEmpty()
        keys.check(environment)
    }

    @Test
    fun `the options the providers declare for their types are known at runtime, any other key under a type refused`() {
        val providers = StoreProviders.load(javaClass.classLoader).stores.values.map { it.type to it.options }
        val keys = ConfigurationKeys.of(javaClass.classLoader, declared = ProviderOptionKeys.of(providers))
        keys.unknown(environment("wow.mongo.append-timeout" to "5s", "wow.acme.region" to "eu-1")).assert().isEmpty()
        keys.unknown(environment("wow.mongo.nonsense" to "x", "wow.acme.colour" to "red"))
            .assert().containsExactlyInAnyOrder("wow.mongo.nonsense", "wow.acme.colour")
        // A third party's type (the test's `acme`, on the class path) has no metadata: its options are known from its
        // provider alone.
        ConfigurationKeys.of(javaClass.classLoader).unknown(environment("wow.acme.region" to "eu-1"))
            .assert().containsExactly("wow.acme.region")
    }

    @Test
    fun `a store's provider options sit beside the host's keys, and no other key under a store is known`() {
        val stores = StoreProviders.load(javaClass.classLoader).stores.values
        val keys = ConfigurationKeys.of(javaClass.classLoader, storeOptions = stores.flatMap { it.options })
        keys.unknown(
            environment(
                "wow.stores.main.type" to "postgres",
                "wow.stores.main.data-source" to "writes",
                "wow.stores.main.query-data-source" to "reads",
                "wow.stores.main.query.max-concurrent" to "4",
                "wow.stores.docs.client" to "mongo",
                "wow.stores.docs.database" to "shop",
                "wow.stores.acme.region" to "eu-1",
            ),
        ).assert().isEmpty()
        keys.unknown(environment("wow.stores.main.options.data-source" to "writes", "wow.stores.main.colour" to "red"))
            .assert().containsExactlyInAnyOrder("wow.stores.main.options.data-source", "wow.stores.main.colour")
        keys.unknown(variables("WOW_STORES_MAIN_DATASOURCE" to "writes", "WOW_STORES_MAIN_COLOUR" to "red"))
            .assert().containsExactly("WOW_STORES_MAIN_COLOUR")
    }

    @Test
    fun `a peer discovery's declared options are known under wow peer, any other key there refused`() {
        val discoveries = PeerDiscoveries.load(javaClass.classLoader)
        val keys = ConfigurationKeys.of(javaClass.classLoader, declared = ProviderOptionKeys.of(discoveries.declared))
        keys.unknown(environment("wow.peer.dns.name-template" to "_x._tcp.{service}")).assert().isEmpty()
        keys.unknown(
            environment(
                "wow.peer.dns.namespace" to "shop",
                "wow.peer.dns.namespaces.eshop-payment-service" to "payments",
            ),
        ).assert().isEmpty()
        keys.unknown(variables("WOW_PEER_DNS_NAMESPACE" to "shop")).assert().isEmpty()
        keys.unknown(environment("wow.peer.dns.colour" to "red")).assert().containsExactly("wow.peer.dns.colour")
        // A third party's discovery (an application's bean) has no metadata: its options are known from it alone.
        val consul = object : PeerDiscovery {
            override val type: String = "consul"
            override val options: List<ProviderOption<*>> = listOf(ProviderOption.string("address", "the agent."))

            override fun open(config: PeerDiscoveryConfig): PeerTargets = error("not opened")
        }
        val withBean = PeerDiscoveries.load(javaClass.classLoader, listOf(consul)).declared
        ConfigurationKeys.of(javaClass.classLoader, declared = ProviderOptionKeys.of(withBean))
            .unknown(environment("wow.peer.consul.address" to "http://c:8500", "wow.peer.consul.zone" to "a"))
            .assert().containsExactly("wow.peer.consul.zone")
    }

    @Test
    fun `any sub-key of a declared map option is known, and nothing beside it`() {
        val labels = ProviderOption.map("labels", "the labels of every acme resource.")
        val declared = ProviderOptionKeys.of(listOf("acme" to listOf(labels)))
        val keys = ConfigurationKeys.of(javaClass.classLoader, declared = declared)
        keys.unknown(
            environment("wow.acme.labels.team" to "a", "wow.acme.labels.cost.center" to "b")
        ).assert().isEmpty()
        keys.unknown(environment("wow.acme.labelz.team" to "a", "wow.acme.colour" to "red"))
            .assert().containsExactlyInAnyOrder("wow.acme.labelz.team", "wow.acme.colour")
        // Wow's own transports and Sinks declare theirs in the metadata: Kafka's client properties pass as they are.
        val passed = environment(
            "wow.kafka.properties.linger.ms" to "5",
            "wow.clickhouse.query.settings.max_threads" to "2",
        )
        keys.unknown(passed).assert().isEmpty()
        keys.unknown(environment("wow.kafka.propertiez.linger.ms" to "5", "wow.kafka.linger" to "5"))
            .assert().containsExactlyInAnyOrder("wow.kafka.propertiez.linger.ms", "wow.kafka.linger")
    }

    @Test
    fun `the in-memory topics' retention should be in the in-memory provider's group, wow memory (X-33)`() {
        keys.unknown(environment("wow.memory.topic-retention" to "50")).assert().isEmpty()
        keys.unknown(variables("WOW_MEMORY_TOPIC_RETENTION" to "50")).assert().isEmpty()
        keys.unknown(environment("wow.memory-topic-retention" to "50")).assert()
            .containsExactly("wow.memory-topic-retention")
    }

    @Test
    fun `a mistyped key refuses the startup, naming each`() {
        val environment = environment("wow.cluster.forward-timout" to "30s", "wow.clustr.router" to "local")
        keys.unknown(environment).assert().containsExactlyInAnyOrder("wow.cluster.forward-timout", "wow.clustr.router")
        val failure = assertThrows<WowConfigurationException> { keys.check(environment) }
        failure.key.assert().isIn("wow.cluster.forward-timout", "wow.clustr.router")
        failure.message.assert().contains("wow.cluster.forward-timout", "wow.clustr.router")
    }

    @Test
    fun `a key of an earlier version refuses the startup, pointing at the migration guide`() {
        val environment = environment(
            "wow.cluster.router" to "local",
            "wow.ops.enabled" to "true",
            "wow.query.abac.enabled" to "true",
        )
        val failure = assertThrows<WowConfigurationException> { keys.check(environment) }
        failure.message.assert()
            .contains("wow.cluster.router", "wow.ops.enabled", "wow.query.abac.enabled", ConfigurationKeys.MIGRATION)
    }

    @Test
    fun `the WOW_ environment variables are checked as Spring maps them, others are left alone`() {
        val environment = variables(
            "WOW_EXECUTOR_MAXINFLIGHT" to "8",
            "WOW_KAFKA_COMMANDINTAKE_ENABLED" to "true",
            "WOW_CALLERS_BASKET_COMMANDS_0" to "shop.cart.Add",
            "WOW_PEERS_ESHOPORDERING_URL" to "http://ordering:9090",
            "WOW_AGGREGATES_SHOP_CART_TENANT" to "acme",
            "WOW_COSEC_CROSSSCOPE_ROLES_0" to "admin",
            "WOWZA_ANYTHING" to "x",
            "JAVA_HOME" to "/jdk",
            // Outside Wow's groups: a tool's or the application's own variables.
            "WOW_BENCH_PG_PRELOAD" to "pg_stat_statements",
            "WOW_UPDATE_SNAPSHOTS" to "1",
            "WOW_PG_PASSWORD" to "secret",
        )
        keys.unknown(environment).assert().isEmpty()
        keys.check(environment)
    }

    @Test
    fun `a WOW_ variable in Spring's legacy form, dashes as underscores, should be known as Spring binds it`() {
        val environment = variables(
            "WOW_COSEC_TENANT_LEVEL_ROLES" to "employee",
            "WOW_LOG_MAX_POLL_INTERVAL" to "5s",
            "WOW_STATE_CACHE_CAPACITY" to "100",
            "WOW_PEERS_ESHOP_ORDERING_PUBLIC_URL" to "http://ordering",
            "WOW_CALLERS_BASKET_COMMANDS_0" to "shop.cart.Add",
        )
        keys.unknown(environment).assert().isEmpty()
        ConfigurationKeys.namesOf("WOW_COSEC_TENANT_LEVEL_ROLES").map { it.toString() }
            .assert().contains("wow.cosec.tenant.level.roles", "wow.cosec.tenant-level.roles")
        // An index ends a word, and the next element is a property of the indexed value.
        ConfigurationKeys.namesOf("WOW_VIEW_STORE_SYSTEM_VIEWS_0_ID").map { it.toString() }
            .assert().contains("wow.view-store.system-views[0].id")
        // Doubled underscores are not words, and a long variable is read in its canonical form alone.
        ConfigurationKeys.namesOf("WOW__STATE__CACHE").map { it.toString() }.assert().contains("wow.state-cache")
        val long = "WOW_A_B_C_D_E_F_G_H_I_J_K_L_M"
        ConfigurationKeys.namesOf(long).assert().containsExactly(ConfigurationKeys.nameOf(long))
        // A stale key in the legacy form is refused, pointing at the migration guide.
        assertThrows<WowConfigurationException> { keys.check(variables("WOW_KAFKA_COMMAND_INTAKE" to "true")) }
            .message.assert().contains("WOW_KAFKA_COMMAND_INTAKE", ConfigurationKeys.MIGRATION)
    }

    @Test
    fun `a WOW_ variable whose first word is not a Wow group is left alone, whatever its later words (#6404)`() {
        // The legacy form reads an underscore as a key's dash, but never the one after WOW: no key starts `wow-`, so
        // WOW_BENCH_MONGO_CACHE_GB is not `wow-bench.mongo.cache.gb`, under the group `mongo`. Each shape of key the
        // legacy form serves still maps.
        val mapped = mapOf(
            // A kebab key under a group, one of three words, a kebab group, an indexed kebab key, a map's entry.
            "WOW_COSEC_TENANT_LEVEL_ROLES" to "wow.cosec.tenant-level.roles",
            "WOW_LOG_MAX_POLL_INTERVAL" to "wow.log.max-poll-interval",
            "WOW_STATE_CACHE_CAPACITY" to "wow.state-cache.capacity",
            "WOW_VIEW_STORE_SYSTEM_VIEWS_0_ID" to "wow.view-store.system-views[0].id",
            "WOW_PEERS_ESHOP_ORDERING_PUBLIC_URL" to "wow.peers.eshop-ordering.public-url",
        )
        mapped.forEach { (variable, key) ->
            ConfigurationKeys.namesOf(variable).map { it.toString() }.assert().contains(key)
        }
        keys.unknown(variables(*mapped.keys.map { it to "x" }.toTypedArray())).assert().isEmpty()
        // A tool's, an application's and a bare variable: their first word is no group, though a later one is.
        val alone = listOf("WOW_BENCH_MONGO_CACHE_GB", "WOW_BENCH_NODE_STOP_SECONDS", "WOW_APP_KAFKA_URL", "WOW_HOME")
        (mapped.keys + alone).flatMap { ConfigurationKeys.namesOf(it) }.map { it.toString() }
            .filter { it.startsWith("wow-") }.assert().isEmpty()
        val tools = variables(*alone.map { it to "x" }.toTypedArray())
        keys.unknown(tools).assert().isEmpty()
        keys.check(tools)
        // A misspelt key under a real group is refused as before, in either form.
        val misspelt = listOf("WOW_KAFKA_BOOTSTRAP_SERVERZ", "WOW_NODE_STOP_SECONDS", "WOW_STATE_CACHE_CAPACITEE")
        keys.unknown(variables(*misspelt.map { it to "x" }.toTypedArray())).assert()
            .containsExactlyInAnyOrderElementsOf(misspelt)
    }

    /**
     * Every variable a manifest of deploy/ sets, in a ConfigMap or a container's env, as the host reads it (finding
     * F12). A chart's helm-unittest suites and their values, the `tests/` beside its `Chart.yaml`, are not shipped: the
     * scan leaves them out (#6983).
     */
    @Test
    fun `the WOW_ variables of the shipped Kubernetes manifests should be known`() {
        val deploy = File("../../deploy").canonicalFile
        val shipped = deploy.walkTopDown().onEnter { dir ->
            !(dir.name == "tests" && File(dir.parentFile, "Chart.yaml").isFile)
        }
        val variables = shipped.filter { it.isFile && it.extension == "yaml" }.flatMap { manifest ->
            manifest.readLines().mapNotNull { manifestVariable.find(it)?.groupValues?.get(1) }
        }.toSet()
        variables.assert().contains(
            "WOW_KAFKA_COMMANDINTAKE_ENABLED",
            "WOW_KAFKA_BOOTSTRAPSERVERS",
            "WOW_INTERNAL_HOST"
        )
        keys.unknown(variables(*variables.map { it to "x" }.toTypedArray())).assert().isEmpty()
        // The shipped files teach one form, the canonical one, dashes dropped: the legacy one reads two ways
        // (WOW_LOG_MAX_POLL_INTERVAL could be max.poll.interval).
        variables.filter { variable ->
            keys.unknown(environment(ConfigurationKeys.nameOf(variable).toString() to "x")).isNotEmpty()
        }.assert().describedAs("Write a shipped variable in the canonical form, the key's dashes dropped.").isEmpty()
    }

    @Test
    fun `a stale or mistyped WOW_ environment variable refuses the startup, naming its new key`() {
        val environment = variables(
            "WOW_KAFKA_TOPICS_DDL" to "auto",
            "WOW_KAFKA_COMMANDINTAKE" to "true",
            "WOW_SUBSCRIPTION_PARKED_MINBACKOFF" to "1s",
            "WOW_CALLERS_BASKET_COMMAND_0" to "shop.cart.Add",
            "WOW_CLUSTER_FORWARDTIMOUT" to "30s",
        )
        keys.unknown(environment).assert().containsExactlyInAnyOrder(
            "WOW_KAFKA_TOPICS_DDL",
            "WOW_KAFKA_COMMANDINTAKE",
            "WOW_SUBSCRIPTION_PARKED_MINBACKOFF",
            "WOW_CALLERS_BASKET_COMMAND_0",
            "WOW_CLUSTER_FORWARDTIMOUT",
        )
        val failure = assertThrows<WowConfigurationException> { keys.check(environment) }
        failure.message.assert().contains(
            "WOW_KAFKA_TOPICS_DDL",
            "WOW_KAFKA_COMMANDINTAKE",
            "WOW_SUBSCRIPTION_PARKED_MINBACKOFF",
            "WOW_CALLERS_BASKET_COMMAND_0",
            ConfigurationKeys.MIGRATION,
        )
        failure.message.assert().contains(ConfigurationKeys.VARIABLES)
        ConfigurationKeys.nameOf("WOW_CALLERS_BASKET_COMMANDS_0").toString()
            .assert().isEqualTo("wow.callers.basket.commands[0]")
    }

    @Test
    fun `a variable of the application's own under a Wow group is refused, saying to rename it`() {
        val failure = assertThrows<WowConfigurationException> { keys.check(variables("WOW_KAFKA_PASSWORD" to "s")) }
        failure.message.assert().contains(
            "WOW_KAFKA_PASSWORD",
            "An environment variable under a Wow group is read as configuration; rename a variable of your own " +
                "outside these groups",
        )
        assertThrows<WowConfigurationException> { keys.check(environment("wow.kafka.pasword" to "s")) }
            .message.assert().doesNotContain(ConfigurationKeys.VARIABLES)
    }

    @Test
    fun `a WOW_ environment variable of an absent module names the module`() {
        val failure = assertThrows<WowConfigurationException> {
            ConfigurationKeys(emptyList()).check(variables("WOW_COSID_AGGREGATEGENERATOR" to "cosid"))
        }
        failure.message.assert().contains("WOW_COSID_AGGREGATEGENERATOR (needs wow-cosid on the class path)")
    }

    @Test
    fun `a mistyped security key refuses the startup`() {
        val environment = environment("wow.cosec.authorizer.enable" to "true")
        val failure = assertThrows<IllegalStateException> { keys.check(environment) }
        failure.message.assert().contains("wow.cosec.authorizer.enable")
    }

    @Test
    fun `the keys of a calling service and a call target are checked to their properties (R2_5-3)`() {
        val known = environment(
            "wow.callers.basket.commands[0]" to "shop.order.SubmitOrder",
            "wow.callers.[ordering.v2].commands" to "Checkout",
            "wow.callers.ordering.reads" to "shop.order.snapshot",
            "wow.peers.ordering.url" to "http://ordering:9090",
        )
        keys.unknown(known).assert().isEmpty()
        keys.check(known)
        // The map holds the entry, but its value has no such property: commands stays empty, which opens every
        // domain command to the service (D226), so the typo refuses the startup.
        val mistyped = environment(
            "wow.callers.basket.command[0]" to "shop.order.SubmitOrder",
            "wow.callers.basket" to "t",
            "wow.peers.ordering.uri" to "http://ordering:9090",
            // The tokens D300 removed are unknown keys now: a deployment that still sets one does not start.
            "wow.callers.ordering.tokens" to "a,b",
            "wow.calls.token" to "t",
            "wow.internal.token" to "t",
            "wow.cosec.service.roles" to "service",
        )
        keys.unknown(mistyped).assert().containsExactlyInAnyOrder(
            "wow.callers.basket.command[0]",
            "wow.callers.basket",
            "wow.peers.ordering.uri",
            "wow.callers.ordering.tokens",
            "wow.calls.token",
            "wow.internal.token",
            "wow.cosec.service.roles",
        )
        val failure = assertThrows<IllegalStateException> { keys.check(mistyped) }
        failure.message.assert().contains("wow.callers.basket.command[0]")
    }

    @Test
    fun `the CoSec policy keys of earlier pre-releases refuse the startup, written as keys or variables`() {
        // CoSec 8.0.0-beta.1 tells an application no principal's policies: a key that named callers by one would
        // otherwise be dropped in silence, and change who is a tenant-level or cross-scope caller.
        val removed = environment(
            "wow.cosec.cross-scope.policies" to "p1",
            "wow.cosec.tenant-level.policies[0]" to "p2",
        )
        keys.unknown(removed).assert().containsExactlyInAnyOrder(
            "wow.cosec.cross-scope.policies",
            "wow.cosec.tenant-level.policies[0]",
        )
        val variables = variables("WOW_COSEC_CROSS_SCOPE_POLICIES" to "p1", "WOW_COSEC_TENANTLEVEL_POLICIES" to "p2")
        keys.unknown(variables).assert().containsExactlyInAnyOrder(
            "WOW_COSEC_CROSS_SCOPE_POLICIES",
            "WOW_COSEC_TENANTLEVEL_POLICIES",
        )
        // Their roles are still read.
        keys.unknown(environment("wow.cosec.tenant-level.roles" to "employee")).assert().isEmpty()
    }

    @Test
    fun `a host that still sets a CoSec policy key does not start`() {
        listOf("wow.cosec.cross-scope.policies", "wow.cosec.tenant-level.policies").forEach { key ->
            val failure = runCatching { startHost(key to "staff").close() }.exceptionOrNull()
            generateSequence(failure) { it.cause }.filterIsInstance<WowConfigurationException>().first().message
                .assert().contains("Unknown Wow configuration keys: $key.")
        }
    }

    @Test
    fun `a host whose security key is mistyped does not start under lazy initialization either (#6851)`() {
        val failure = runCatching {
            startHost("spring.main.lazy-initialization" to true, "wow.cosec.authorizer.enable" to true).close()
        }.exceptionOrNull()
        generateSequence(failure) { it.cause }.filterIsInstance<WowConfigurationException>().first().message
            .assert().contains("Unknown Wow configuration keys: wow.cosec.authorizer.enable.")
    }

    @Test
    fun `a host whose security key is mistyped does not start`() {
        val failure = runCatching { startHost("wow.cosec.authorizer.enable" to true).close() }.exceptionOrNull()
        generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("wow.cosec.authorizer.enable") }
            .assert().isTrue()
    }

    @Test
    fun `a host whose service's commands are mistyped does not start`() {
        val key = "wow.callers.basket.command[0]"
        val failure = runCatching { startHost(key to "shop.order.SubmitOrder").close() }.exceptionOrNull()
        generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains(key) }.assert().isTrue()
    }
}
