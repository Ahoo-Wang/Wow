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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.config.configCheck
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
 * application that no metadata describes (its `owned` prefixes), whose keys this check cannot tell. Names compare as Spring's
 * relaxed binding does (`laneCapacity` is `lane-capacity`); only property sources that list their names are read, so
 * environment variables are not checked.
 *
 * [check] warns of the unknown keys, and refuses the startup for an unknown key of [STRICT_PREFIXES]: a mistyped
 * `wow.cosec.authorizer.enabled` would otherwise leave the authorizer that allows all in place.
 *
 * A map of the security settings whose values are beans ([CHECKED_MAPS]: `wow.http.services`, `wow.calls.contexts`)
 * is checked to its leaves (review R2.5-3): a key under one of its entries is known only when it names a property of
 * the value, so `wow.http.services.basket.command` is unknown, and refused, instead of leaving `commands` empty, which
 * lets the service call every domain command (decision D226).
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

    /** Warns of the unknown keys of [environment]; refuses an unknown key of a strict prefix. */
    fun check(environment: ConfigurableEnvironment) {
        val unknown = unknown(environment)
        if (unknown.isEmpty()) {
            return
        }
        logger.warn { "Unknown Wow configuration keys, ignored: $unknown. Check their spelling." }
        val strict = unknown.filter { key -> STRICT_PREFIXES.any { key.startsWith(it) } }
        configCheck(strict.isEmpty(), strict.joinToString(), null, "Fix or remove them.") {
            "Unknown security configuration keys $strict: a mistyped key would leave its default in place."
        }
    }

    private fun isKnown(key: String): Boolean {
        val name = ConfigurationPropertyName.adapt(key, '.')
        CHECKED_MAPS.entries.firstOrNull { (map, _) -> map.isAncestorOf(name) }?.let { (map, leaves) ->
            return isLeafOf(map, leaves, name)
        }
        return known.any { it == name || it.isAncestorOf(name) } || owned.any { it == name || it.isAncestorOf(name) }
    }

    /** Whether [name] is a property, one of [leaves], of an entry of [map]: `<map>.<entry>.<leaf>`, or under it. */
    private fun isLeafOf(
        map: ConfigurationPropertyName,
        leaves: Set<String>,
        name: ConfigurationPropertyName
    ): Boolean {
        val leaf = map.numberOfElements + 1
        return name.numberOfElements > leaf && name.getElement(leaf, ConfigurationPropertyName.Form.UNIFORM) in leaves
    }

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val ROOT = "wow."

        /** The metadata Spring Boot's tooling reads, in the host's jar and any module's. */
        const val METADATA: String = "META-INF/spring-configuration-metadata.json"

        /**
         * The prefixes whose unknown keys refuse the startup: the authorization settings, the service identities and
         * the synchronous calls (review R2.5-3), and the cluster's tokens.
         */
        val STRICT_PREFIXES: List<String> = listOf("wow.cosec.", "wow.http.services.", "wow.calls.", "wow.internal.")

        /**
         * The maps of beans whose keys are checked to the value's properties (review R2.5-3), by the map's name: the
         * service identities of the internal port and the targets of the synchronous calls.
         */
        private val CHECKED_MAPS: Map<ConfigurationPropertyName, Set<String>> = mapOf(
            "wow.http.services" to WowProperties.Http.Service::class.java,
            "wow.calls.contexts" to WowProperties.Calls.CallTarget::class.java,
        ).entries.associate { (map, type) -> ConfigurationPropertyName.of(map) to leavesOf(type) }

        /** The properties of [type], as the uniform elements of their names (`connect-timeout` is `connecttimeout`). */
        private fun leavesOf(type: Class<*>): Set<String> = BeanUtils.getPropertyDescriptors(type)
            .filter { it.writeMethod != null }
            .mapTo(HashSet()) {
                ConfigurationPropertyName.adapt(it.name, '.').getElement(0, ConfigurationPropertyName.Form.UNIFORM)
            }

        /**
         * The keys of every [METADATA] resource of [classLoader], with the prefixes of the application's other
         * properties beans ([prefixes]) that no metadata describes.
         */
        fun of(classLoader: ClassLoader?, prefixes: Collection<String> = emptyList()): ConfigurationKeys {
            val resources = PathMatchingResourcePatternResolver(classLoader).getResources("classpath*:$METADATA")
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
