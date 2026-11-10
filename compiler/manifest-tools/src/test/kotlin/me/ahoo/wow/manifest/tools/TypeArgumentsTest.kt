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

package me.ahoo.wow.manifest.tools

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.spi.json.StateJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A type argument nullable where its type is used keeps its nullability in the schema ([TypeArguments]). */
class TypeArgumentsTest {
    data class Box<T>(val value: T)
    data class Pair<A, B>(val first: A, val second: B)
    data class Submit(
        val nullable: Box<String?>,
        val strict: Box<String>,
        val nested: Box<Box<Int?>>,
        val listed: Box<List<String?>>,
        val boxes: List<Box<Long?>?>,
        val mixed: Pair<String?, String>,
    )

    class State {
        var id: String = ""
        var box: Box<String?> = Box(null)
        var strict: Box<String> = Box("")
    }

    private val completed: Manifest = SchemaIntrospector(javaClass.classLoader).complete(
        Manifest(
            contexts = listOf(
                ContextManifest(
                    name = "audit",
                    aggregates = listOf(
                        AggregateManifest(
                            name = "item",
                            state = StateManifest(Manifest.schemaRef(State::class.java.name)),
                            commands = listOf(
                                CommandManifest("Submit", schema = Manifest.schemaRef(Submit::class.java.name))
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun key(type: Class<*>, vararg arguments: String) = type.name + arguments.joinToString(",", "(", ")")

    @Suppress("UNCHECKED_CAST")
    private fun schema(key: String): Map<String, Any?> = completed.defs.getValue(key) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun property(key: String, name: String): Any? = (schema(key)["properties"] as Map<String, Any?>)[name]

    private fun ref(key: String) = mapOf("\$ref" to Manifest.schemaRef(key))

    /** [node] itself, or the definition it refers to: a type used once is written in place. */
    @Suppress("UNCHECKED_CAST")
    private fun resolved(node: Any?): Map<String, Any?> {
        val map = node as Map<String, Any?>
        return (map["\$ref"] as? String)?.let { schema(Manifest.schemaKey(it)) } ?: map
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.property(name: String): Any? = (this["properties"] as Map<String, Any?>)[name]

    @Test
    fun `a nullable argument should be a definition of its own whose members accept null`() {
        // A legal command Wow's JSON writes and reads back with its nulls.
        val command =
            Submit(Box(null), Box("s"), Box(Box(null)), Box(listOf(null)), listOf(null, Box(null)), Pair(null, "x"))
        StateJson.mapper.readValue(
            StateJson.mapper.writeValueAsString(command),
            Submit::class.java
        ).assert().isEqualTo(command)

        val nullableBox = key(Box::class.java, "java.lang.String?")
        val strictBox = key(Box::class.java, "java.lang.String")
        property(Submit::class.java.name, "nullable").assert().isEqualTo(ref(nullableBox))
        property(Submit::class.java.name, "strict").assert().isEqualTo(ref(strictBox))
        property(nullableBox, "value").assert().isEqualTo(mapOf("type" to listOf("string", "null")))
        property(strictBox, "value").assert().isEqualTo(mapOf("type" to "string"))
        schema(nullableBox)["x-wow-class"].assert().isEqualTo(nullableBox)
        schema(strictBox)["x-wow-class"].assert().isEqualTo(strictBox)
    }

    @Test
    fun `nullability should compose through nested arguments and containers`() {
        // Box<Box<Int?>>: the inner box's argument, at depth.
        val inner = key(Box::class.java, "java.lang.Integer?")
        val nested = resolved(property(Submit::class.java.name, "nested"))
        nested["x-wow-class"].assert().isEqualTo(key(Box::class.java, inner))
        resolved(nested.property("value")).property("value").assert()
            .isEqualTo(mapOf("type" to listOf("integer", "null"), "format" to "int32"))
        // Box<List<String?>>: a container inside the class keeps its items' nullability.
        val listed = key(Box::class.java, key(List::class.java, "java.lang.String?"))
        resolved(property(Submit::class.java.name, "listed")).also { it["x-wow-class"].assert().isEqualTo(listed) }
            .property("value").assert()
            .isEqualTo(mapOf("type" to "array", "items" to mapOf("type" to listOf("string", "null"))))
        // List<Box<Long?>?>: a nullable item of a nullable argument.
        val longBox = key(Box::class.java, "java.lang.Long?")

        @Suppress("UNCHECKED_CAST")
        val items = (property(Submit::class.java.name, "boxes") as Map<String, Any?>)["items"] as Map<String, Any?>
        val alternatives = items["anyOf"] as List<*>
        alternatives.first().assert().isEqualTo(mapOf("type" to "null"))
        resolved(alternatives.last()).also { it["x-wow-class"].assert().isEqualTo(longBox) }.property("value").assert()
            .isEqualTo(mapOf("type" to listOf("integer", "null"), "format" to "int64"))
        // Each argument on its own.
        val pair = key(Pair::class.java, "java.lang.String?", "java.lang.String")
        val mixed = resolved(property(Submit::class.java.name, "mixed"))
        mixed["x-wow-class"].assert().isEqualTo(pair)
        mixed.property("first").assert().isEqualTo(mapOf("type" to listOf("string", "null")))
        mixed.property("second").assert().isEqualTo(mapOf("type" to "string"))
        completed.defs.toString().contains("Nullable").assert().isFalse()
    }

    @Test
    fun `a state view should name the nullable instantiation apart when it refuses it`() {
        val failure = assertThrows<IllegalStateException> {
            StateViewSources(completed.defs) { false }.of(completed.contexts.single())
        }
        failure.message.assert().contains(key(Box::class.java, "java.lang.String?"))
    }
}
