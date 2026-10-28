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
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.config.WowConfigurationException
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
    fun `a mistyped key refuses the startup, naming each`() {
        val environment = environment("wow.cluster.forward-timout" to "30s", "wow.clustr.router" to "local")
        keys.unknown(environment).assert().containsExactlyInAnyOrder("wow.cluster.forward-timout", "wow.clustr.router")
        val failure = assertThrows<WowConfigurationException> { keys.check(environment) }
        failure.key.assert().isIn("wow.cluster.forward-timout", "wow.clustr.router")
        failure.message.assert().contains("wow.cluster.forward-timout", "wow.clustr.router")
    }

    @Test
    fun `a key of an earlier version refuses the startup with what took its place`() {
        val environment = environment(
            "wow.calls.contexts.ordering.url" to "http://ordering:9090",
            "wow.clients.retry.backoff" to "1s",
            "wow.aggregates.[shop.cart].snapshot.store" to "search",
            "wow.query.in-process-timeout" to "5s",
            "wow.topics.in-memory" to "true",
            "wow.subscriptions.[shop.cart.projection].parked.min-backoff" to "1s",
        )
        val failure = assertThrows<WowConfigurationException> { keys.check(environment) }
        failure.message.assert().contains(
            "wow.calls.contexts.ordering.url (renamed wow.peers.<context>.url)",
            "wow.clients.retry.backoff (renamed wow.peer.retry.initial-backoff)",
            "(renamed wow.aggregates.[<context>.<aggregate>].snapshot.stores)",
            "wow.query.in-process-timeout (renamed wow.query.unbounded-timeout)",
            "wow.topics.in-memory (renamed wow.transport (memory))",
            "parked.min-backoff (renamed wow.subscriptions.[<subscription>].parked.initial-backoff)",
        )
        ConfigurationKeys.renamed("wow.query.inProcess.max-list-size").assert()
            .isEqualTo("renamed wow.query.budgets.in-process")
        ConfigurationKeys.renamed("wow.cluster.router").assert().isNull()
        ConfigurationKeys.renamed("wow.log.append-queue.max-batch").assert().startsWith("removed: appends are always")
        ConfigurationKeys.renamed("wow.log.handoff.enabled").assert().startsWith("removed: the hand-over")
        ConfigurationKeys.renamed("wow.stores.main.query.adaptive.enabled").assert().contains("max-concurrent")
        ConfigurationKeys.renamed("wow.query.adaptive.latency-factor").assert().contains("max-concurrent")
        ConfigurationKeys.renamed("wow.query.adaptive.ceiling").assert().isNull()
        ConfigurationKeys.renamed("wow.opentelemetry.sample-interval").assert().isEqualTo("renamed wow.observability")
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
            "WOW_COSEC_TRUST_INJECTED_APP" to "true",
            "WOW_LOG_MAX_POLL_INTERVAL" to "5s",
            "WOW_STATE_CACHE_CAPACITY" to "100",
            "WOW_PEERS_ESHOP_ORDERING_PUBLIC_URL" to "http://ordering",
            "WOW_CALLERS_BASKET_COMMANDS_0" to "shop.cart.Add",
        )
        keys.unknown(environment).assert().isEmpty()
        ConfigurationKeys.namesOf("WOW_COSEC_TRUST_INJECTED_APP").map { it.toString() }
            .assert().contains("wow.cosec.trust.injected.app", "wow.cosec.trust-injected-app")
        // An index ends a word, and the next element is a property of the indexed value.
        ConfigurationKeys.namesOf("WOW_VIEW_STORE_SYSTEM_VIEWS_0_ID").map { it.toString() }
            .assert().contains("wow.view-store.system-views[0].id")
        // Doubled underscores are not words, and a long variable is read in its canonical form alone.
        ConfigurationKeys.namesOf("WOW__STATE__CACHE").map { it.toString() }.assert().contains("wow.state-cache")
        val long = "WOW_A_B_C_D_E_F_G_H_I_J_K_L_M"
        ConfigurationKeys.namesOf(long).assert().containsExactly(ConfigurationKeys.nameOf(long))
        // A stale key in the legacy form is named with what took its place.
        assertThrows<WowConfigurationException> { keys.check(variables("WOW_KAFKA_COMMAND_INTAKE" to "true")) }
            .message.assert().contains("WOW_KAFKA_COMMAND_INTAKE (renamed wow.kafka.command-intake.enabled)")
    }

    @Test
    fun `the WOW_ variables of the shipped Kubernetes manifests should be known`() {
        // Finding F12: every variable a manifest of deploy/ sets, in a ConfigMap or a container's env, as the host reads it.
        val deploy = File("../../deploy").canonicalFile
        val variables = deploy.walkTopDown().filter { it.isFile && it.extension == "yaml" }.flatMap { manifest ->
            manifest.readLines().mapNotNull { manifestVariable.find(it)?.groupValues?.get(1) }
        }.toSet()
        variables.assert().contains("WOW_COSEC_TRUSTINJECTEDAPP", "WOW_KAFKA_BOOTSTRAPSERVERS", "WOW_INTERNAL_HOST")
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
            "WOW_KAFKA_TOPICS_DDL (renamed wow.kafka.ddl)",
            "WOW_KAFKA_COMMANDINTAKE (renamed wow.kafka.command-intake.enabled)",
            "WOW_SUBSCRIPTION_PARKED_MINBACKOFF (renamed wow.subscription.parked.initial-backoff)",
            "WOW_CALLERS_BASKET_COMMAND_0",
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
            ConfigurationKeys(emptyList()).check(variables("WOW_COSID_GLOBALGENERATOR" to "cosid"))
        }
        failure.message.assert().contains("WOW_COSID_GLOBALGENERATOR (needs wow-cosid on the class path)")
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
