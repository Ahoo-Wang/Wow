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
import me.ahoo.wow.query.dataset.QueryDatasetProvider
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.transport.TopicTransportOptions
import me.ahoo.wow.spi.transport.TransportProvider
import me.ahoo.wow.spring.boot.http.DnsPeerDiscovery
import me.ahoo.wow.spring.boot.observability.ObservabilityProperties
import me.ahoo.wow.spring.boot.query.QueryProperties
import me.ahoo.wow.spring.boot.transport.PortProviders
import org.junit.jupiter.api.Test
import org.springframework.beans.BeanWrapperImpl
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.convert.DurationStyle
import org.springframework.core.convert.TypeDescriptor
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.util.unit.DataSize
import java.util.ServiceLoader

/**
 * Every documented key ([DocumentedKeys]: the configuration metadata the reference is generated from and the keys
 * under its maps), set by name, reaches what reads it: a key of the host's the property its metadata names, a
 * provider's key the option it declares, as the host binds it. So a key renamed in the code but not in the reference,
 * or the other way round, fails here, and a key added later is checked without a test of its own. Its values the
 * integration tests' `DocumentedKeysGuardTest` holds: what the start does with one the key cannot take.
 */
class DocumentedKeysTest {
    private val providers: List<Pair<Class<*>, List<ProviderOption<*>>>> =
        (
            ServiceLoader.load(StoreProvider::class.java).map { it.javaClass to it.options } +
                ServiceLoader.load(TransportProvider::class.java).map { it.javaClass to TopicTransportOptions.of(it) } +
                ServiceLoader.load(SinkProvider::class.java).map { it.javaClass to it.options } +
                ServiceLoader.load(QueryDatasetProvider::class.java).map { it.javaClass to it.options }
            ) + (DnsPeerDiscovery::class.java to DnsPeerDiscovery().options)

    @Test
    fun `every documented key set by name should reach the property or the option it names`() {
        DocumentedKeys.all.size.assert().isGreaterThan(250)
        val unread = DocumentedKeys.all.filterNot { it.isSettingsMap }.mapNotNull { key ->
            runCatching { if (key.isProvider) providerRead(key) else hostRead(key) }
                .fold({ it }, { "${key.name}: ${it.message}" })
        }
        unread.assert().describedAs("Each key set by name, and why it was not read as set.").isEmpty()
    }

    /** Why [key], set by name to a sample of its type, is not read into its property; null when it is. */
    private fun hostRead(key: DocumentedKey): String? {
        val (root, prefix) = ROOTS.filter { key.key.startsWith(it.second + ".") }.maxBy { it.second.length }
        val sample = sampleOf(key.type, key.default) ?: return "no sample of ${key.type}: add one to sampleOf"
        val name = if (key.type == DocumentedKeys.STRING_MAP) "${key.key}.sample-entry" else key.key
        val settings = mapOf(name to sample)

        @Suppress("UNCHECKED_CAST")
        val type = root as Class<Any>
        val bound = Binder(MapConfigurationPropertySource(settings)).bind(prefix, type).orElseGet {
            type.getDeclaredConstructor().newInstance()
        }
        val path = beanPath(key.key.removePrefix("$prefix."))
        val wrapper = BeanWrapperImpl(bound)
        val value = wrapper.getPropertyValue(path)
        if (key.type == DocumentedKeys.STRING_MAP) {
            return "reads $value".takeUnless { (value as? Map<*, *>)?.get("sample-entry") == sample }
        }
        val target = wrapper.getPropertyTypeDescriptor(path) ?: return "no property at $path"
        val expected = ApplicationConversionService.getSharedInstance()
            .convert(sample, TypeDescriptor.valueOf(String::class.java), target)
        return "set to $sample, reads $value at $path".takeUnless { value == expected }
    }

    /** Why [key], set by name, is not read as the option its provider declares; null when it is. */
    private fun providerRead(key: DocumentedKey): String? {
        val options = providers.firstOrNull { it.first.name == key.sourceType }?.second
            ?: return "no provider ${key.sourceType} on the class path"
        val option = options.filter { key.key.endsWith(".${it.key}") }.maxByOrNull { it.key.length }
            ?: return "no option of ${key.sourceType} is its last elements"
        val prefix = key.key.removeSuffix(".${option.key}")
        val sample = providerSampleOf(option) ?: return "no sample of ${option.type}: add one to providerSampleOf"
        val name = if (option.isMap) "${key.key}.sample-entry" else key.key
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("documented", mapOf(name to sample)))
        }
        val bound = PortProviders.bound(environment, prefix, options, ProviderOption.Scope.entries)
        val read = if (option.isMap) bound["${option.key}.sample-entry"] else bound[option.key]
        if (read != sample) {
            return "set to $sample, the host reads $read under $prefix"
        }
        return "$sample reads as the default ${option.default}"
            .takeIf { !option.isMap && option.parse(read, name) == option.default }
    }

    private companion object {
        val ROOTS: List<Pair<Class<*>, String>> = listOf(
            WowProperties::class.java to WowProperties.PREFIX,
            QueryProperties::class.java to QueryProperties.PREFIX,
            ObservabilityProperties::class.java to ObservabilityProperties.PREFIX,
        )

        /** A value of [type] that is not [default], as a property source writes it; null for a type it has none of. */
        fun sampleOf(type: String, default: String?): String? = when (type) {
            // Twice the default: a power of two, as the log's partitions, stays one.
            "java.lang.Integer", "java.lang.Long" -> ((default?.toLongOrNull()?.takeIf { it > 0 } ?: 2) * 2).toString()
            "java.lang.Double" -> ((default?.toDoubleOrNull() ?: 0.25) + 0.5).toString()
            DocumentedKeys.DURATION ->
                "${(default?.let { DurationStyle.detectAndParse(it).toMillis() } ?: 2000) + 1000}ms"
            DocumentedKeys.DATA_SIZE -> "${(default?.let { DataSize.parse(it).toBytes() } ?: 2048) + 1024}B"
            else -> textSample(type, default)
        }

        /** A value of the textual or enumerated [type] that is not [default]; null for a type it has none of. */
        private fun textSample(type: String, default: String?): String? = when (type) {
            "java.lang.Boolean" -> (default?.toBooleanStrictOrNull() != true).toString()
            "java.lang.String", DocumentedKeys.STRING_MAP -> "sample-value"
            "java.net.URI" -> "http://sample.test/path"
            "java.util.Set<java.lang.String>", "java.util.List<java.lang.String>" -> "sample-a,sample-b"
            else -> enumSample(type, default)
        }

        /** A constant of the enumeration [type] (or of a set of one) that is not [default]. */
        private fun enumSample(type: String, default: String?): String? {
            val element = Regex("""java\.util\.Set<(.+)>""").matchEntire(type)?.groupValues?.get(1) ?: type
            val constants = runCatching { Class.forName(element) }.getOrNull()
                ?.takeIf { it.isEnum }?.enumConstants?.map { (it as Enum<*>).name } ?: return null
            fun uniform(name: String) = name.lowercase().filter { it.isLetterOrDigit() }
            return constants.firstOrNull { default == null || uniform(it) != uniform(default) }
                ?.lowercase()?.replace('_', '-')
        }

        /** A value of [option] that is not its default, as a source writes it; null for a type it has none of. */
        fun providerSampleOf(option: ProviderOption<*>): String? = when {
            option.isMap -> "sample-value"
            option.values.isNotEmpty() ->
                option.values.first { !it.equals(option.default?.toString(), ignoreCase = true) }
            option.type == kotlin.time.Duration::class.java ->
                "${((option.default as kotlin.time.Duration?)?.inWholeMilliseconds ?: 2000) + 1000}ms"
            option.type == Int::class.javaObjectType -> ((option.default as Int? ?: 2) + 1).toString()
            option.type == Boolean::class.javaObjectType -> (option.default != true).toString()
            option.type == String::class.java -> "sample-value"
            else -> null
        }

        /**
         * [relative], a key below its root's prefix, as a bean property path: kebab-case words in camel case, and each
         * entry of a map in brackets (`stores.main.query.max-concurrent` is `stores[main].query.maxConcurrent`).
         */
        fun beanPath(relative: String): String {
            val segments = Regex("""\[[^\]]*]|[^.\[\]]+""").findAll(relative).map { it.value }.toList()
            return buildString {
                segments.forEachIndexed { index, segment ->
                    when {
                        segment.startsWith("[") -> append(segment)
                        index == 1 && segments[0] in MAP_PROPERTIES -> append("[$segment]")
                        else -> {
                            if (isNotEmpty()) append('.')
                            append(camel(segment))
                        }
                    }
                }
            }
        }

        private fun camel(kebab: String): String = kebab.split('-').mapIndexed { index, word ->
            if (index == 0) word else word.replaceFirstChar(Char::uppercaseChar)
        }.joinToString("")

        /** The root's maps whose entry a key names without brackets: `wow.stores.<name>`, `wow.callers.<service>`. */
        val MAP_PROPERTIES: Set<String> = setOf("stores", "callers", "peers", "schedules")
    }
}
