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
import me.ahoo.wow.spring.boot.http.DnsPeerDiscovery
import tools.jackson.databind.JsonNode
import java.nio.file.Path

/** A documented key, as the configuration metadata or the maps document writes it. */
class DocumentedKey(val name: String, val type: String, val sourceType: String, node: JsonNode) {
    /** Its default as the documentation writes it; null for none. */
    val default: String? = node["defaultValue"]?.takeUnless { it.isNull }
        ?.let { if (it.isString) it.asString() else it.toString() }
    val description: String = node["description"]?.asString().orEmpty()

    /** [name] with each placeholder of a map's entry named as the test host serves it. */
    val key: String = DocumentedKeys.ENTRIES.entries.fold(name) { key, (placeholder, entry) ->
        key.replace(placeholder, entry)
    }

    /** A provider's option, which its provider reads, not a property of the host's. */
    val isProvider: Boolean = !sourceType.startsWith(WowProperties::class.java.packageName + ".") ||
        sourceType == DnsPeerDiscovery::class.java.name

    /** A number or a duration (or a size), whose impossible values the guard sets. */
    val isNumeric: Boolean = type in DocumentedKeys.NUMERIC

    /** A map of settings, whose entries' keys the maps document lists one by one. */
    val isSettingsMap: Boolean = type.startsWith("java.util.Map<") && type != DocumentedKeys.STRING_MAP
}

/** The documented keys: the configuration metadata the reference is generated from, and the keys under its maps. */
object DocumentedKeys {
    val METADATA: Path = Path.of("src/main/resources/META-INF/spring-configuration-metadata.json")
    val MAPS: Path = Path.of("src/test/resources/configuration-maps.json")

    /** The entries the test host serves, for the placeholders of the maps' keys. */
    val ENTRIES: Map<String, String> = mapOf(
        "[<context>.<aggregate>]" to "[bank.account]",
        "[<context>.<name>]" to "[bank.wow.snapshot]",
        "[<code>]" to "[$FROZEN]",
        "<name>" to "main",
        "<service>" to "teller",
        "<context>" to "bank",
    )

    const val DURATION = "java.time.Duration"
    const val DATA_SIZE = "org.springframework.util.unit.DataSize"
    const val STRING_MAP = "java.util.Map<java.lang.String,java.lang.String>"
    val NUMERIC = setOf("java.lang.Integer", "java.lang.Long", "java.lang.Double", DURATION, DATA_SIZE)

    /** A zero the description gives a meaning: `0` on its own, not a digit of another number. */
    private val ZERO = Regex("""(?<![\w.])`?0`?(?![\w.%])""")

    /** Every documented key. */
    val all: List<DocumentedKey> by lazy {
        listOf(METADATA, MAPS).flatMap { path ->
            WowJson.mapper.readTree(path.toFile())["properties"].toList().map { node ->
                DocumentedKey(node["name"].asString(), node["type"].asString(), node["sourceType"].asString(), node)
            }
        }
    }

    /** The values [key] cannot take: a negative one, and zero unless its description gives zero a meaning. */
    fun impossibleValues(key: DocumentedKey): List<String> {
        val unit = if (key.type == DURATION) "s" else if (key.type == DATA_SIZE) "B" else ""
        return listOfNotNull("-1$unit", "0$unit".takeUnless { ZERO.containsMatchIn(key.description) })
    }
}
