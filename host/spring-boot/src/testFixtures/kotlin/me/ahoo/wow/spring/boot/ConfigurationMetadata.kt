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

import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.query.dataset.QueryDatasetProvider
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.peer.PeerDiscovery
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.transport.TopicTransportOptions
import me.ahoo.wow.spi.transport.TransportProvider
import java.beans.Introspector
import java.beans.PropertyDescriptor
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Spring configuration metadata (`META-INF/spring-configuration-metadata.json`) generated from properties classes and
 * their KDoc, without the configuration processor: the [roots], each a properties class and its prefix, walked to their
 * leaves, a property whose type is a class of a package starting with [groupPackage] (not an enum) being a group. The
 * KDoc of each class and property is read from the Kotlin sources under [sources]. The host's and the platform
 * services' metadata come from it, so every `wow.*` key has a description (design rule K8). The [providers] add the
 * `wow.<type>.<key>` options each declares for every instance of its type, described by the option itself: a store's,
 * a topic transport's, a Sink's or a dataset backend's. Providers of one type (the ClickHouse Sink's and dataset
 * backend's) share its group, and an option both declare is listed once.
 */
class ConfigurationMetadata(
    private val roots: List<Pair<Class<*>, String>>,
    private val sources: Path = Path.of("src/main/kotlin"),
    private val groupPackage: String = roots.first().first.packageName + ".",
    private val providers: List<ProviderOptions> = emptyList(),
) {
    /**
     * The options a provider of [type] declares ([source], the provider's class), and the description of their group
     * `wow.<type>`.
     */
    class ProviderOptions(
        val type: String,
        val source: Class<*>,
        val options: List<ProviderOption<*>>,
        val description: String,
    ) {
        companion object {
            /** A store provider's: the settings of every store of its type. */
            @JvmStatic
            fun of(provider: StoreProvider): ProviderOptions = ProviderOptions(
                provider.type,
                provider.javaClass,
                provider.options,
                "The settings of every ${provider.type} store; a store's own options override them.",
            )

            /** A topic transport provider's: its own options, and those every topic transport has. */
            @JvmStatic
            fun of(provider: TransportProvider): ProviderOptions = ProviderOptions(
                provider.type,
                provider.javaClass,
                TopicTransportOptions.of(provider),
                "The settings of the ${provider.type} topic transport.",
            )

            /** A Sink provider's. */
            @JvmStatic
            fun of(provider: SinkProvider): ProviderOptions = ProviderOptions(
                provider.type,
                provider.javaClass,
                provider.options,
                "The settings of the ${provider.type} Sink.",
            )

            /** A peer discovery's, under `wow.peer.<type>`. */
            @JvmStatic
            @OptIn(ExperimentalWowApi::class)
            fun of(provider: PeerDiscovery): ProviderOptions = ProviderOptions(
                "peer.${provider.type}",
                provider.javaClass,
                provider.options,
                "The settings of the ${provider.type} peer discovery.",
            )

            /** A dataset backend's. */
            @JvmStatic
            fun of(provider: QueryDatasetProvider): ProviderOptions = ProviderOptions(
                provider.type,
                provider.javaClass,
                provider.options,
                "The settings of the ${provider.type} dataset backend.",
            )
        }
    }

    /** The metadata document, as the committed file holds it. */
    fun document(): String {
        val docs = KDocIndex(sources)
        val groups = ArrayList<Map<String, Any?>>()
        val properties = ArrayList<Map<String, Any?>>()
        val hints = ArrayList<Map<String, Any?>>()
        roots.forEach { (type, prefix) ->
            groups += entry(prefix, type.name, null, docs.classDoc(type))
            walk(type, type.getDeclaredConstructor().newInstance(), prefix, docs, groups, properties)
        }
        providers.forEach { provider -> options(provider, groups, properties, hints) }
        val distinctGroups = groups.distinctBy { it["name"] }
        val distinctProperties = properties.distinctBy { it["name"] }
        val document = linkedMapOf(
            "groups" to distinctGroups.sortedBy { it["name"] as String },
            "properties" to distinctProperties.sortedBy { it["name"] as String },
        )
        if (hints.isNotEmpty()) {
            document["hints"] = hints.distinctBy { it["name"] }.sortedBy { it["name"] as String }
        }
        return WowJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n"
    }

    /**
     * What the committed [file] should be: [document]. With [UPDATE] set, the file is rewritten first, so a test that
     * compares it with the document passes and the change shows in the diff.
     */
    fun committed(file: Path = FILE): Pair<String, String> {
        val expected = document()
        if (System.getenv(UPDATE) != null) {
            file.parent?.createDirectories()
            file.writeText(expected)
        }
        return file.readText() to expected
    }

    /**
     * The leaf keys under each map of beans of [maps], which the metadata lists as one map key: each entry's settings
     * (`wow.stores.<name>.type`), walked as the roots are, described by their KDoc; and under the map of the stores,
     * [storesMap], each option a provider of [stores] declares, which a store sets beside the host's keys
     * (`wow.stores.<name>.data-source`). The configuration reference lists them below their maps.
     */
    fun mapsDocument(maps: List<MapKeys>, storesMap: String? = null, stores: List<ProviderOptions> = emptyList()): String {
        val docs = KDocIndex(sources)
        val properties = ArrayList<Map<String, Any?>>()
        maps.forEach { map ->
            val prefix = "${map.name}.${map.entry}"
            walk(map.type, map.type.getDeclaredConstructor().newInstance(), prefix, docs, ArrayList(), properties)
            if (map.name == storesMap) {
                // An option several store types declare (a `client`) is one key, described for each type.
                val declared = stores.flatMap { provider -> provider.options.map { provider to it } }
                declared.groupBy { (_, option) -> option.key }.forEach { (key, byType) ->
                    val (provider, option) = byType.first()
                    properties += entry(
                        "$prefix.$key",
                        OPTION_TYPES[option.type] ?: option.type.name,
                        provider.source.name,
                        byType.joinToString(" ") { (type, it) -> "On ${type.type}: ${it.description.removeSuffix(".")}." },
                    ) + defaultEntry(option)
                }
            }
        }
        val document = linkedMapOf("properties" to properties.distinctBy { it["name"] })
        return WowJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n"
    }

    /** What the committed [file] of the maps' keys should be: [mapsDocument], rewritten first with [UPDATE] set. */
    fun committedMaps(file: Path, document: String): Pair<String, String> {
        if (System.getenv(UPDATE) != null) {
            file.parent?.createDirectories()
            file.writeText(document)
        }
        return file.readText() to document
    }

    /** A map of beans: its [name], how one of its entries is written ([entry]), and its values' [type]. */
    class MapKeys(val name: String, val entry: String, val type: Class<*>)

    /** The keys without a description. */
    fun undocumented(): List<String> = WowJson.mapper.readTree(document())["properties"]
        .filter { it["description"]?.asString().isNullOrBlank() }
        .map { it["name"].asString() }

    /** The groups and keys whose description names a decision or review ID, which users cannot look up. */
    fun withIds(): List<String> {
        val document = WowJson.mapper.readTree(document())
        return (document["groups"] + document["properties"]).filter { entry ->
            val description = entry["description"]?.asString().orEmpty()
            INTERNAL_IDS.any { it.containsMatchIn(description) }
        }.map { it["name"].asString() }
    }

    /** The bean properties of [type] with a setter: Kotlin's `var`s, as Spring binds them. */
    private fun walk(
        type: Class<*>,
        instance: Any,
        prefix: String,
        docs: KDocIndex,
        groups: MutableList<Map<String, Any?>>,
        properties: MutableList<Map<String, Any?>>,
    ) {
        Introspector.getBeanInfo(type).propertyDescriptors.filter { it.writeMethod != null }.forEach { property ->
            val name = "$prefix.${kebab(property.name)}"
            val value = property.readMethod.invoke(instance)
            val classifier = property.propertyType
            // An inherited property's KDoc is on the class that declares it (QueryProperties.Budget's on Http's).
            val description = docs.propertyDoc(property.readMethod.declaringClass, property.name)
            if (isGroup(classifier)) {
                groups += entry(name, classifier.name, type.name, description ?: docs.classDoc(classifier))
                val nested = value ?: classifier.getDeclaredConstructor().newInstance()
                walk(classifier, nested, name, docs, groups, properties)
            } else {
                properties += entry(name, typeName(property), type.name, description) +
                    listOfNotNull(defaultOf(value)?.let { "defaultValue" to it })
            }
        }
    }

    /**
     * The group `wow.<type>` of [provider] and a key for each option it declares for every instance of its type, and
     * the values an [ProviderOption.enumeration] takes as its hints.
     */
    private fun options(
        provider: ProviderOptions,
        groups: MutableList<Map<String, Any?>>,
        properties: MutableList<Map<String, Any?>>,
        hints: MutableList<Map<String, Any?>>,
    ) {
        val typeOptions = provider.options.filter { it.scope == ProviderOption.Scope.TYPE }
        if (typeOptions.isEmpty()) return
        val prefix = "$ROOT${provider.type}"
        val source = provider.source.name
        groups += entry(prefix, source, null, provider.description)
        typeOptions.forEach { option ->
            val default = defaultEntry(option)
            if (option.values.isNotEmpty()) {
                hints += linkedMapOf(
                    "name" to "$prefix.${option.key}",
                    "values" to option.values.map { mapOf("value" to it) },
                )
            }
            properties += entry(
                "$prefix.${option.key}",
                OPTION_TYPES[option.type] ?: option.type.name,
                source,
                option.description.replaceFirstChar(Char::uppercase),
            ) + default
        }
    }

    private fun defaultEntry(option: ProviderOption<*>): Map<String, Any?> = when (val value = option.default) {
        null -> emptyMap()
        is kotlin.time.Duration -> mapOf("defaultValue" to value.toIsoString())
        is Enum<*> -> mapOf("defaultValue" to ProviderOption.valueOf(value))
        else -> mapOf("defaultValue" to value)
    }

    private fun entry(name: String, type: String, sourceType: String?, description: String?): Map<String, Any?> =
        linkedMapOf<String, Any?>("name" to name, "type" to type).apply {
            description?.let { put("description", it) }
            sourceType?.let { put("sourceType", it) }
        }

    private fun isGroup(type: Class<*>): Boolean = type.name.startsWith(groupPackage) && !type.isEnum

    private fun typeName(property: PropertyDescriptor): String =
        property.readMethod.genericReturnType.typeName.replace(" ", "").let { BOXED[it] ?: it }

    private fun defaultOf(value: Any?): Any? = when (value) {
        null -> null
        is Number, is Boolean, is String -> value
        is Duration -> value.toString()
        is Enum<*> -> value.name.lowercase().replace('_', '-')
        is Collection<*> -> value.takeIf { it.isNotEmpty() }?.map { defaultOf(it) }
        else -> null
    }

    private fun kebab(name: String): String = name.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()

    /**
     * The KDoc of the classes and properties of the host's sources, by nesting path (`WowProperties.Executor`): read by
     * indentation, as the official code style lays the classes out.
     */
    private class KDocIndex(sources: Path) {
        private val classes = HashMap<String, String>()
        private val properties = HashMap<String, String>()

        init {
            Files.walk(sources).use { paths ->
                paths.filter { it.extension == "kt" }.forEach { read(it.readLines()) }
            }
        }

        fun classDoc(type: Class<*>): String? = classes[path(type)]

        fun propertyDoc(type: Class<*>, property: String): String? = properties["${path(type)}#$property"]

        private fun path(type: Class<*>): String = type.name.substringAfterLast('.').replace('$', '.')

        private fun read(lines: List<String>) {
            val owners = ArrayDeque<Pair<String, Int>>()
            var doc: StringBuilder? = null
            var pending: String? = null
            for (line in lines) {
                val trimmed = line.trim()
                val indent = line.length - line.trimStart().length
                when {
                    doc != null -> {
                        doc.append(' ').append(trimmed.removeSuffix("*/").removePrefix("*").trim())
                        if (trimmed.endsWith("*/")) {
                            pending = clean(doc.toString())
                            doc = null
                        }
                    }

                    trimmed.startsWith("/**") -> {
                        val text = trimmed.removePrefix("/**")
                        if (text.endsWith("*/")) {
                            pending = clean(text.removeSuffix("*/"))
                        } else {
                            doc = StringBuilder(text)
                        }
                    }

                    trimmed.startsWith("@") || trimmed.isEmpty() -> Unit
                    else -> {
                        declaration(line, indent, owners, pending)
                        pending = null
                    }
                }
            }
        }

        /** Records [doc] for the class or the property [line] declares, if any, under its [owners]. */
        private fun declaration(line: String, indent: Int, owners: ArrayDeque<Pair<String, Int>>, doc: String?) {
            CLASS.find(line)?.let { match ->
                while (owners.isNotEmpty() && owners.last().second >= indent) owners.removeLast()
                owners.addLast(match.groupValues[1] to indent)
                doc?.let { classes[owners.joinToString(".") { it.first }] = it }
            }
            VAR.find(line)?.let { match ->
                while (owners.isNotEmpty() && owners.last().second >= indent) owners.removeLast()
                doc?.let { properties["${owners.joinToString(".") { it.first }}#${match.groupValues[1]}"] = it }
            }
        }

        private fun clean(text: String): String = text.trim()
            .replace(Regex("\\[([^\\]]+)]\\[[^\\]]+]"), "$1")
            .replace(Regex("(?<![.\\w])\\[([A-Za-z][\\w.]*)]"), "$1")
            .replace(Regex("\\s+"), " ")

        private companion object {
            val CLASS = Regex("^\\s*(?:[a-z]+ )*class (\\w+)")
            val VAR = Regex("^\\s*(?:[a-z]+ )*var (\\w+)\\s*:")
        }
    }

    companion object {
        /** The environment variable that makes a metadata test rewrite its committed file. */
        const val UPDATE: String = "WOW_UPDATE_SNAPSHOTS"

        private const val ROOT = "wow."

        /** The metadata type of each value type of a [ProviderOption]. */
        private val OPTION_TYPES: Map<Class<*>, String> = mapOf(
            String::class.java to "java.lang.String",
            kotlin.time.Duration::class.java to "java.time.Duration",
            Int::class.javaObjectType to "java.lang.Integer",
            Boolean::class.javaObjectType to "java.lang.Boolean",
            Map::class.java to "java.util.Map<java.lang.String,java.lang.String>",
        )

        /** Where a module's metadata is, relative to its directory. */
        @JvmField
        val FILE: Path = Path.of("src/main/resources/META-INF/spring-configuration-metadata.json")

        /** Decision IDs, review IDs and the circled numbers of a decision's items. */
        @JvmField
        val INTERNAL_IDS: List<Regex> = listOf(
            Regex("\\bD\\d{2,3}\\b"),
            Regex("\\b(R\\d(\\.\\d)?-\\d+|A\\d-\\d+|Q-\\d+|X-\\d+|M-\\d+)\\b"),
            Regex("[\u2460-\u2473]"),
        )

        private val BOXED = mapOf(
            "int" to "java.lang.Integer",
            "long" to "java.lang.Long",
            "boolean" to "java.lang.Boolean",
            "double" to "java.lang.Double",
        )
    }
}
