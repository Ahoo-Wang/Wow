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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.json.WowJson
import org.springframework.beans.BeanUtils
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * The `wow.*` keys of the environment that no property names (review A5-9): Spring binds what it knows and ignores the
 * rest, so `wow.cluster.forward-timout` would be a silent no-op. A key is known when a property of the configuration
 * metadata on the class path ([METADATA], the host's and any module's) is the key or holds it (a map or list
 * property, such as `wow.aggregates`), or when it is under the prefix of another `@ConfigurationProperties` bean of the
 * application that no metadata describes (its `owned` prefixes), whose keys this check cannot tell. Names compare as
 * Spring's relaxed binding does (`laneCapacity` is `lane-capacity`); only property sources that list their names are
 * read, so environment variables are not checked.
 *
 * [check] refuses the startup for every unknown key: a mistyped `wow.cosec.authorizer.enabled` would otherwise leave
 * the authorizer that allows all in place, and a key of an earlier version would do nothing. A key that was renamed or
 * removed ([RENAMED]) is refused with its new name or the reason it is gone: a table of messages, not an alias.
 *
 * A map whose values are beans ([CHECKED_MAPS]: `wow.callers`, `wow.peers`, `wow.aggregates`, `wow.stores`, ...) is
 * checked to its leaves (review R2.5-3): a key under one of its entries is known only when it names a property of the
 * value, down its nested settings, so `wow.callers.basket.command` is unknown, and refused, instead of leaving
 * `commands` empty, which lets the service call every domain command (decision D226), and so is an aggregate's
 * `snapshot.store` of an earlier version.
 */
@InternalWowApi
class ConfigurationKeys(
    private val known: List<ConfigurationPropertyName>,
    private val owned: List<ConfigurationPropertyName> = emptyList(),
) {
    /** The `wow.*` keys of [environment] no property names, in the order the environment lists them. */
    fun unknown(environment: ConfigurableEnvironment): List<String> = environment.propertySources
        .filterIsInstance<EnumerablePropertySource<*>>()
        .flatMap { it.propertyNames.asList() }
        .filter { it.startsWith(ROOT) }
        .distinct()
        .filterNot { key -> isKnown(key) }

    /** Refuses the startup when [environment] has an unknown key, naming the new key of one that was renamed. */
    fun check(environment: ConfigurableEnvironment) {
        val unknown = unknown(environment)
        if (unknown.isEmpty()) {
            return
        }
        val lines = unknown.map { key -> (renamed(key) ?: moduleOf(key))?.let { "$key ($it)" } ?: key }
        throw WowConfigurationException(
            unknown.first(),
            null,
            "Unknown Wow configuration keys: ${lines.joinToString("; ")}. Nothing reads them.",
            "Fix their spelling, or use the new key each one names; the configuration reference lists every key.",
        )
    }

    private fun isKnown(key: String): Boolean {
        val name = ConfigurationPropertyName.adapt(key, '.')
        CHECKED_MAPS.entries.firstOrNull { (map, _) -> map.isAncestorOf(name) }?.let { (map, type) ->
            return isLeafOf(map, type, name)
        }
        return known.any { it == name || it.isAncestorOf(name) } || owned.any { it == name || it.isAncestorOf(name) }
    }

    /**
     * Whether [name] is a property of an entry of [map], whose values are of [type]: `<map>.<entry>.<property>`, a
     * nested setting's down to its leaf, or a key under a leaf that is itself a map or a list.
     */
    private fun isLeafOf(map: ConfigurationPropertyName, type: Class<*>, name: ConfigurationPropertyName): Boolean {
        val elements = (map.numberOfElements + 1 until name.numberOfElements).map {
            name.getElement(it, ConfigurationPropertyName.Form.UNIFORM)
        }
        return isPropertyOf(type, elements)
    }

    private fun isPropertyOf(type: Class<*>, elements: List<String>): Boolean {
        val property = elements.firstOrNull()?.let { propertiesOf(type)[it] } ?: return false
        return !isSettings(property) || isPropertyOf(property, elements.drop(1))
    }

    @InternalWowApi
    companion object {
        private const val ROOT = "wow."

        /** The metadata Spring Boot's tooling reads, in the host's jar and any module's. */
        const val METADATA: String = "META-INF/spring-configuration-metadata.json"

        /** The metadata a module adds by hand to what its properties classes give. */
        const val ADDITIONAL_METADATA: String = "META-INF/additional-spring-configuration-metadata.json"

        /**
         * The modules whose own metadata declares the keys under a prefix: a key there is unknown when the module is
         * not on the class path, and the refusal names it.
         */
        val MODULES: Map<String, String> = mapOf(
            "wow.cosid." to "wow-cosid",
        )

        /** The module that declares [key], when its prefix is one of [MODULES]. */
        fun moduleOf(key: String): String? =
            MODULES.entries.firstOrNull { key.startsWith(it.key) }?.let { "needs ${it.value} on the class path" }

        /**
         * The maps of beans whose keys are checked to the value's properties (review R2.5-3), by the map's name: the
         * permits of the calling services, the peers this one calls, and each aggregate's, store's, subscription's,
         * schedule's and error code's settings.
         */
        private val CHECKED_MAPS: Map<ConfigurationPropertyName, Class<*>> = mapOf(
            "wow.callers" to WowProperties.CallerPermits::class.java,
            "wow.peers" to WowProperties.PeerTarget::class.java,
            "wow.aggregates" to WowProperties.Aggregate::class.java,
            "wow.stores" to WowProperties.Store::class.java,
            "wow.subscriptions" to WowProperties.SubscriptionSettings::class.java,
            "wow.schedules" to WowProperties.Schedule::class.java,
            "wow.http.errors" to WowProperties.Http.Error::class.java,
        ).entries.associate { (map, type) -> ConfigurationPropertyName.of(map) to type }

        /**
         * The keys of earlier versions, each with what took its place: a pattern whose `*` stands for one element (a
         * context, a store, a service or an aggregate's `[<context>.<aggregate>]`), a key under it matching too. Only
         * the refusal's message reads it.
         */
        val RENAMED: List<Pair<String, String>> = listOf(
            "wow.query.index.ddl" to "renamed: each query index follows the ddl of its store, wow.ddl or " +
                "wow.stores.<store>.ddl",
            "wow.kafka.topics.ddl" to "renamed wow.kafka.ddl",
            "wow.kafka.topics.dev-profiles" to "renamed wow.dev-profiles",
            "wow.kafka.command-intake" to "renamed wow.kafka.command-intake.enabled",
            "wow.clickhouse.sink.ddl" to "renamed wow.clickhouse.ddl",
            "wow.clickhouse.sink.aggregates" to "removed: use wow.aggregates.[<context>.<aggregate>].clickhouse" +
                ".history",
            "wow.internal.dev-profiles" to "renamed wow.dev-profiles",
            "wow.internal.token" to "removed: the internal port's network admits the platform's pods alone",
            "wow.calls.token" to "removed: a service names itself with the Wow-Service header",
            "wow.calls.contexts.*.url" to "renamed wow.peers.<context>.url",
            "wow.calls.timeout" to "renamed wow.peer.timeout",
            "wow.calls.connect-timeout" to "renamed wow.peer.connect-timeout",
            "wow.clients.contexts.*.internal-url" to "renamed wow.peers.<context>.url",
            "wow.clients.contexts.*.url" to "renamed wow.peers.<context>.public-url",
            "wow.clients.contexts.*.mode" to "renamed wow.peers.<context>.mode",
            "wow.clients.contexts.*.contract" to "renamed wow.peers.<context>.contract",
            "wow.clients.mode" to "renamed wow.peer.mode",
            "wow.clients.timeout" to "renamed wow.peer.timeout",
            "wow.clients.connect-timeout" to "renamed wow.peer.connect-timeout",
            "wow.clients.forwarded-headers" to "renamed wow.peer.forwarded-headers",
            "wow.clients.retry.backoff" to "renamed wow.peer.retry.initial-backoff",
            "wow.clients.retry" to "renamed wow.peer.retry",
            "wow.http.services.*.tokens" to "removed: a service names itself with the Wow-Service header",
            "wow.http.services" to "renamed wow.callers.<service>.commands and wow.callers.<service>.reads",
            "wow.cosec.service" to "removed: a calling service is named by its Wow-Service header",
            "wow.http.client-facts" to "renamed wow.http.client-facts.enabled",
            "wow.http.global-errors" to "renamed wow.http.global-errors.enabled",
            "wow.standby" to "renamed wow.node.standby",
            "wow.query.http" to "renamed wow.query.budgets.http",
            "wow.query.in-process-timeout" to "renamed wow.query.unbounded-timeout",
            "wow.query.in-process" to "renamed wow.query.budgets.in-process",
            "wow.query.events.mongo-read-preference" to "renamed wow.mongo.query-read-preference",
            "wow.stores.*.query.read-preference" to "renamed wow.stores.<store>.options.query-read-preference",
            "wow.query.count-cache.events-enabled" to "renamed wow.query.count-cache.events.enabled",
            "wow.snapshot.store" to "renamed wow.snapshot.stores",
            "wow.snapshot.targets" to "renamed wow.snapshot.stores",
            "wow.aggregates.*.snapshot.store" to "renamed wow.aggregates.[<context>.<aggregate>].snapshot.stores",
            "wow.subscription.parked.min-backoff" to "renamed wow.subscription.parked.initial-backoff",
            "wow.subscriptions.*.parked.min-backoff" to "renamed wow.subscriptions.[<subscription>].parked" +
                ".initial-backoff",
            "wow.topics.in-memory" to "renamed wow.transport (memory)",
            "wow.analytics.catalog.watch" to "renamed wow.analytics.catalog.watch.enabled",
        )

        private val RENAMED_NAMES: List<Pair<List<String>, String>> = RENAMED.map { (pattern, message) ->
            pattern.split('.').map { if (it == "*") it else uniform(it) } to message
        }

        /** What took the place of [key], a key of an earlier version, or null for a key that never was one. */
        fun renamed(key: String): String? {
            val name = ConfigurationPropertyName.adapt(key, '.')
            val elements = (0 until name.numberOfElements).map {
                name.getElement(it, ConfigurationPropertyName.Form.UNIFORM)
            }
            return RENAMED_NAMES.firstOrNull { (pattern, _) ->
                elements.size >= pattern.size &&
                    pattern.indices.all { pattern[it] == "*" || pattern[it] == elements[it] }
            }?.second
        }

        private fun uniform(element: String): String =
            ConfigurationPropertyName.adapt(element, '.').getElement(0, ConfigurationPropertyName.Form.UNIFORM)

        /**
         * The settable properties of [type] by the uniform element of their names (`connect-timeout` is
         * `connecttimeout`), each with its type.
         */
        private fun propertiesOf(type: Class<*>): Map<String, Class<*>> = BeanUtils.getPropertyDescriptors(type)
            .filter { it.writeMethod != null }
            .associate { uniform(it.name) to it.propertyType }

        /** Whether [type] is a class of nested settings, walked to its leaves; a value, a map or a list is a leaf. */
        private fun isSettings(type: Class<*>): Boolean =
            type.name.startsWith(WowProperties::class.java.packageName + ".") && !type.isEnum

        /**
         * The keys of every [METADATA] and [ADDITIONAL_METADATA] resource of [classLoader], each module's, with the
         * prefixes of the application's other properties beans ([prefixes]) that no metadata describes.
         */
        fun of(classLoader: ClassLoader?, prefixes: Collection<String> = emptyList()): ConfigurationKeys {
            val resolver = PathMatchingResourcePatternResolver(classLoader)
            val resources = resolver.getResources("classpath*:$METADATA") +
                resolver.getResources("classpath*:$ADDITIONAL_METADATA")
            val names = resources.flatMap { resource ->
                resource.inputStream.use { WowJson.mapper.readTree(it) }["properties"]
                    ?.mapNotNull { it["name"]?.asString() }
                    .orEmpty()
            }
            val known = names.map { ConfigurationPropertyName.adapt(it, '.') }.filterNot { it.isEmpty }
            val owned = prefixes
                .filter { it.startsWith(ROOT) }
                .map { ConfigurationPropertyName.adapt(it, '.') }
                .filterNot { prefix -> known.any { prefix == it || prefix.isAncestorOf(it) } }
            return ConfigurationKeys(known, owned)
        }
    }
}
