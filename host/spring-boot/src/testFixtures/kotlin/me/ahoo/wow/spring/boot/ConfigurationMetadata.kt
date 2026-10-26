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

import me.ahoo.wow.spi.json.WowJson
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
 * services' metadata come from it, so every `wow.*` key has a description (design rule K8).
 */
class ConfigurationMetadata(
    private val roots: List<Pair<Class<*>, String>>,
    private val sources: Path = Path.of("src/main/kotlin"),
    private val groupPackage: String = roots.first().first.packageName + ".",
) {
    /** The metadata document, as the committed file holds it. */
    fun document(): String {
        val docs = KDocIndex(sources)
        val groups = ArrayList<Map<String, Any?>>()
        val properties = ArrayList<Map<String, Any?>>()
        roots.forEach { (type, prefix) ->
            groups += entry(prefix, type.name, null, docs.classDoc(type))
            walk(type, type.getDeclaredConstructor().newInstance(), prefix, docs, groups, properties)
        }
        val document = linkedMapOf(
            "groups" to groups.sortedBy { it["name"] as String },
            "properties" to properties.sortedBy { it["name"] as String },
        )
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
