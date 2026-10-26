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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import java.lang.reflect.AnnotatedElement
import kotlin.io.path.readText

/**
 * Design rule K1, one default per key: a condition on a `wow.*` key that matches when the key is not set
 * (`matchIfMissing`) must agree with the property's default, and one that does not must not match that default, so a
 * key left unset does what its documented default says.
 */
class ConditionalKeysTest {
    private val defaults: Map<String, String?> =
        WowJson.mapper.readTree(ConfigurationMetadata.FILE.readText())["properties"].associate { property ->
            property["name"].asString() to property["defaultValue"]?.takeIf { it.isValueNode }?.asString()
        }

    @Test
    fun `every condition on a wow key should agree with the key's default`() {
        val conditions = AUTO_CONFIGURATIONS.flatMap(::elementsOf).flatMap(::conditionsOf)
        conditions.map { it.first }.assert().isNotEmpty()
        val disagreeing = conditions.filter { (key, condition) ->
            val (havingValue, matchIfMissing) = condition
            key in defaults && matches(defaults[key], havingValue) != matchIfMissing
        }
        disagreeing.assert().describedAs("Make matchIfMissing what the property's default gives.").isEmpty()
    }

    /** Whether a key of [value] matches a condition of [havingValue]: equal to it, else anything but `false`. */
    private fun matches(value: String?, havingValue: String): Boolean = when {
        value == null -> false
        havingValue.isNotEmpty() -> value.equals(havingValue, ignoreCase = true)
        else -> !value.equals("false", ignoreCase = true)
    }

    /** [type], its nested classes and its methods. */
    private fun elementsOf(type: Class<*>): List<AnnotatedElement> =
        listOf(type) + type.declaredMethods + type.declaredClasses.flatMap(::elementsOf)

    /** Each `wow.*` key a condition of [element] reads, with its `havingValue` and `matchIfMissing`. */
    private fun conditionsOf(element: AnnotatedElement): List<Pair<String, Pair<String, Boolean>>> {
        val onProperty = element.getAnnotation(ConditionalOnProperty::class.java)?.let { condition ->
            (condition.name + condition.value).map { name ->
                keyOf(condition.prefix, name) to (condition.havingValue to condition.matchIfMissing)
            }
        }.orEmpty()
        val onBoolean = element.getAnnotation(ConditionalOnBooleanProperty::class.java)?.let { condition ->
            (condition.name + condition.value).map { name ->
                keyOf(condition.prefix, name) to (condition.havingValue.toString() to condition.matchIfMissing)
            }
        }.orEmpty()
        return (onProperty + onBoolean).filter { it.first.startsWith("wow.") }
    }

    private fun keyOf(prefix: String, name: String): String = if (prefix.isEmpty()) name else "$prefix.$name"

    private companion object {
        /** The host's auto-configurations, as its `AutoConfiguration.imports` lists them. */
        val AUTO_CONFIGURATIONS: List<Class<*>> = ConditionalKeysTest::class.java.classLoader
            .getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")!!
            .readText().lines().map { it.trim() }.filter { it.startsWith("me.ahoo.wow.spring.boot.") }
            .map { Class.forName(it) }
    }
}
