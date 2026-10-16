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
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import java.beans.Introspector
import java.beans.PropertyDescriptor
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.extension
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The host's Spring configuration metadata (review A5-9), `META-INF/spring-configuration-metadata.json`, generated
 * from its properties classes and their KDoc without the configuration processor: this test fails when the committed
 * file is not what the classes give, and `WOW_UPDATE_SNAPSHOTS=1` rewrites it.
 */
class ConfigurationMetadataTest {
    @Test
    fun `the committed metadata should be what the properties classes give`() {
        val expected = metadata()
        if (System.getenv(UPDATE) != null) {
            METADATA_FILE.writeText(expected)
        }
        METADATA_FILE.readText().assert()
            .describedAs("Run with $UPDATE=1 to rewrite $METADATA_FILE.")
            .isEqualTo(expected)
    }

    @Test
    fun `the keys the modules read from the environment should be in the metadata`() {
        val names = WowJson.mapper.readTree(
            METADATA_FILE.readText()
        )["properties"].mapNotNull { it["name"]?.asString() }
        names.assert().contains(
            "wow.cosec.authorizer.enabled",
            "wow.cosec.cross-scope.roles",
            "wow.cosec.tenant-level.policies",
            "wow.cosec.service.roles",
            "wow.cosid.global-generator",
            "wow.tenant.default",
            "wow.executor.max-in-flight",
        )
    }

    private fun metadata(): String {
        val docs = KDocIndex(SOURCES)
        val groups = ArrayList<Map<String, Any?>>()
        val properties = ArrayList<Map<String, Any?>>()
        ROOTS.forEach { (type, prefix) ->
            groups += entry(prefix, type.name, null, docs.classDoc(type))
            walk(type, type.getDeclaredConstructor().newInstance(), prefix, docs, groups, properties)
        }
        val document = linkedMapOf(
            "groups" to groups.sortedBy { it["name"] as String },
            "properties" to properties.sortedBy { it["name"] as String },
        )
        return WowJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n"
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
            val description = docs.propertyDoc(type, property.name)
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

    private fun isGroup(type: Class<*>): Boolean = type.name.startsWith("me.ahoo.wow.spring.boot.") && !type.isEnum

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

    private companion object {
        const val UPDATE = "WOW_UPDATE_SNAPSHOTS"
        val SOURCES: Path = Path.of("src/main/kotlin")
        val METADATA_FILE: Path = Path.of("src/main/resources/${ConfigurationKeys.METADATA}")
        val ROOTS: List<Pair<Class<*>, String>> = listOf(
            WowProperties::class.java to WowProperties.PREFIX,
            QueryProperties::class.java to QueryProperties.PREFIX,
            KafkaProperties::class.java to KafkaProperties.PREFIX,
            ClickHouseProperties::class.java to ClickHouseProperties.PREFIX,
            ObservabilityProperties::class.java to ObservabilityProperties.PREFIX,
        )
        val BOXED = mapOf(
            "int" to "java.lang.Integer",
            "long" to "java.lang.Long",
            "boolean" to "java.lang.Boolean",
            "double" to "java.lang.Double",
        )
    }
}
