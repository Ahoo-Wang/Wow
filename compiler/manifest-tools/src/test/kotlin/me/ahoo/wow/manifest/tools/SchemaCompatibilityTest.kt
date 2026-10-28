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
import me.ahoo.wow.manifest.tools.ChangeKind.ADDITIVE
import me.ahoo.wow.manifest.tools.ChangeKind.BREAKING
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** The classification table of [SchemaCompatibility]: each schema change kind, additive or breaking. */
class SchemaCompatibilityTest {
    companion object {
        private val STRING = mapOf("type" to "string")

        private fun obj(vararg properties: Pair<String, Any?>, required: List<String> = emptyList()): Map<String, Any?> =
            mapOf("type" to "object", "properties" to mapOf(*properties), "required" to required)

        private fun row(name: String, old: Any?, new: Any?, vararg expected: String): Arguments =
            Arguments.of(name, old, new, expected.toList())

        /** Each row: the baseline schema, the current one, and the expected changes as `KIND path: description`. */
        @JvmStatic
        @Suppress("LongMethod") // The table.
        fun table(): List<Arguments> = listOf(
            row("no change", obj("a" to STRING), obj("a" to STRING)),
            row("field removed", obj("a" to STRING, "b" to STRING), obj("a" to STRING), "BREAKING s.b: field removed"),
            row(
                "optional field added",
                obj("a" to STRING),
                obj("a" to STRING, "b" to STRING),
                "ADDITIVE s.b: optional field added",
            ),
            row(
                "required field added",
                obj("a" to STRING),
                obj("a" to STRING, "b" to STRING, required = listOf("b")),
                "BREAKING s.b: required field added",
            ),
            row(
                "field made required",
                obj("a" to STRING),
                obj("a" to STRING, required = listOf("a")),
                "BREAKING s.a: field made required",
            ),
            row(
                "field made optional",
                obj("a" to STRING, required = listOf("a")),
                obj("a" to STRING),
                "ADDITIVE s.a: field made optional",
            ),
            row(
                "nullability removed",
                mapOf("type" to listOf("string", "null")),
                STRING,
                "BREAKING s: type narrowed from [null, string] to [string]",
            ),
            row(
                "nullability added",
                STRING,
                mapOf("type" to listOf("string", "null")),
                "ADDITIVE s: type widened from [string] to [null, string]",
            ),
            row(
                "number narrowed to integer",
                mapOf("type" to "number"),
                mapOf("type" to "integer"),
                "BREAKING s: type narrowed from [number] to [integer]",
            ),
            row(
                "integer widened to number",
                mapOf("type" to "integer"),
                mapOf("type" to "number"),
                "ADDITIVE s: type widened from [integer] to [number]",
            ),
            row(
                "type changed",
                STRING,
                mapOf("type" to "boolean"),
                "BREAKING s: type narrowed from [string] to [boolean]"
            ),
            row("any narrowed", emptyMap<String, Any?>(), STRING, "BREAKING s: type narrowed from any to [string]"),
            row("any widened", STRING, emptyMap<String, Any?>(), "ADDITIVE s: type widened from [string] to any"),
            row(
                "enum value removed",
                STRING + ("enum" to listOf("A", "B")),
                STRING + ("enum" to listOf("A")),
                "BREAKING s: enum value B removed",
            ),
            row(
                "enum value added",
                STRING + ("enum" to listOf("A")),
                STRING + ("enum" to listOf("A", "B")),
                "ADDITIVE s: enum value B added",
            ),
            row(
                "enum imposed",
                STRING,
                STRING + ("enum" to listOf("A")),
                "BREAKING s: values restricted to the enum [A]"
            ),
            row("enum lifted", STRING + ("enum" to listOf("A")), STRING, "ADDITIVE s: enum lifted"),
            row(
                "array item narrowed",
                mapOf("type" to "array", "items" to mapOf("type" to "number")),
                mapOf("type" to "array", "items" to mapOf("type" to "integer")),
                "BREAKING s[]: type narrowed from [number] to [integer]",
            ),
            row(
                "map value field removed",
                mapOf("type" to "object", "additionalProperties" to obj("a" to STRING)),
                mapOf("type" to "object", "additionalProperties" to obj()),
                "BREAKING s{}.a: field removed",
            ),
            row(
                "additional properties refused",
                mapOf("type" to "object"),
                mapOf("type" to "object", "additionalProperties" to false),
                "BREAKING s{}: no value accepted any more",
            ),
            row(
                "additional properties accepted",
                mapOf("type" to "object", "additionalProperties" to false),
                mapOf("type" to "object"),
                "ADDITIVE s{}: values accepted",
            ),
            row(
                "alternative removed",
                mapOf("anyOf" to listOf(STRING, mapOf("type" to "integer"))),
                mapOf("anyOf" to listOf(STRING)),
                "BREAKING s.anyOf[1]: alternative no longer accepted",
            ),
            row(
                "alternative added",
                mapOf("oneOf" to listOf(STRING)),
                mapOf("oneOf" to listOf(STRING, mapOf("type" to "integer"))),
                "ADDITIVE s.oneOf[1]: alternative added",
            ),
            row(
                "alternatives imposed",
                emptyMap<String, Any?>(),
                mapOf("anyOf" to listOf(STRING)),
                "BREAKING s.anyOf[0]: alternative no longer accepted",
                "ADDITIVE s.anyOf[0]: alternative added",
            ),
            row("format imposed", STRING, STRING + ("format" to "date"), "BREAKING s: format date imposed"),
            row("format lifted", STRING + ("format" to "date"), STRING, "ADDITIVE s: format date lifted"),
            // A wire format says how a client reads the number, not which numbers are valid (review R4-Q, Q-9).
            row("wire format added", mapOf("type" to "integer"), mapOf("type" to "integer", "format" to "int64")),
            row(
                "wire format swapped",
                mapOf("type" to "integer", "format" to "int64"),
                mapOf("type" to "number", "format" to "decimal"),
                "ADDITIVE s: type widened from [integer] to [number]",
            ),
            row(
                "pattern changed",
                STRING + ("pattern" to "a"),
                STRING + ("pattern" to "b"),
                "BREAKING s: pattern changed from a to b",
            ),
            row(
                "query semantics changed",
                STRING + ("x-wow-temporal" to mapOf("unit" to "SECONDS")),
                STRING,
                "BREAKING s: x-wow-temporal changed from {unit=SECONDS} to null",
            ),
            row(
                "annotations ignored",
                STRING + ("description" to "a") + ("x-wow-index" to listOf("i")),
                STRING + ("description" to "b"),
            ),
            row(
                "query alias removed",
                obj("a" to STRING + ("x-wow-alias" to listOf("state.x"))),
                obj("a" to STRING),
                "BREAKING s.a: query alias state.x removed",
            ),
            row(
                "query alias added",
                obj("a" to STRING),
                obj("a" to STRING + ("x-wow-alias" to listOf("state.x"))),
                "ADDITIVE s.a: query alias state.x added",
            ),
            row("no value accepted", STRING, false, "BREAKING s: no value accepted any more"),
            row("still no value accepted", false, false),
        )

        private fun changes(
            old: Any?,
            new: Any?,
            queryRoot: Boolean = false,
            oldDefs: Map<String, Any?> = emptyMap(),
            newDefs: Map<String, Any?> = emptyMap(),
        ): List<String> = SchemaCompatibility(oldDefs + ("t" to old), newDefs + ("t" to new))
            .compare("s", "#/\$defs/t", "#/\$defs/t", queryRoot)
            .map { it.toString() }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    fun `each schema change should be classified`(name: String, old: Any?, new: Any?, expected: List<String>) {
        changes(old, new).assert().describedAs(name).containsExactlyInAnyOrderElementsOf(expected)
    }

    @Test
    fun `a renamed definition of the same shape should be no change`() {
        val item = obj("a" to STRING)
        SchemaCompatibility(
            mapOf("t" to obj("item" to mapOf("\$ref" to "#/\$defs/old.Item")), "old.Item" to item),
            mapOf("t" to obj("item" to mapOf("\$ref" to "#/\$defs/new.Item")), "new.Item" to item),
        ).compare("s", "#/\$defs/t", "#/\$defs/t").assert().isEmpty()
    }

    @Test
    fun `a definition that is gone should be read as any value`() {
        changes(obj("a" to STRING), obj("a" to mapOf("\$ref" to "#/\$defs/missing")))
            .assert().containsExactly("ADDITIVE s.a: type widened from [string] to any")
    }

    @Test
    fun `a recursive definition should be compared once per path`() {
        val node = obj("next" to mapOf("\$ref" to "#/\$defs/t"), "value" to STRING)
        val narrowed = obj("next" to mapOf("\$ref" to "#/\$defs/t"), "value" to mapOf("type" to "integer"))
        changes(node, narrowed).assert().containsExactly("BREAKING s.value: type narrowed from [string] to [integer]")
    }

    @Test
    fun `a state field renamed with its old query path as an alias should be additive`() {
        val old = obj("name" to STRING, required = listOf("name"))
        val renamed = obj("title" to STRING + ("x-wow-alias" to listOf("state.name")))
        changes(old, renamed, queryRoot = true).assert().containsExactlyInAnyOrder(
            "ADDITIVE s.name: field renamed to title, which keeps state.name as a query alias",
            "ADDITIVE s.title: field made optional",
            "ADDITIVE s.title: query alias state.name added",
        )
    }

    @Test
    fun `a nested state field should be aliased by its full query path`() {
        val old = obj("address" to obj("city" to STRING))
        val renamed = obj(
            "address" to obj(
                "town" to STRING + ("x-wow-alias" to listOf("state.address.city")),
                required = listOf("town"),
            ),
        )
        changes(old, renamed, queryRoot = true).assert().containsExactlyInAnyOrder(
            "ADDITIVE s.address.city: field renamed to town, which keeps state.address.city as a query alias",
            "BREAKING s.address.town: field made required",
            "ADDITIVE s.address.town: query alias state.address.city added",
        )
    }

    @Test
    fun `an alias outside a state should not keep a removed field`() {
        val renamed = obj("title" to STRING + ("x-wow-alias" to listOf("state.name")))
        changes(obj("name" to STRING), renamed).assert().containsExactlyInAnyOrder(
            "BREAKING s.name: field removed",
            "ADDITIVE s.title: optional field added",
        )
    }

    @Test
    fun `a change should name its kind`() {
        ManifestChange(ADDITIVE, "p", "d").toString().assert().isEqualTo("ADDITIVE p: d")
        ManifestChange(BREAKING, "p", "d").kind.assert().isEqualTo(BREAKING)
    }

    @Test
    fun `an output should only produce less`() {
        fun output(old: Any?, new: Any?) = SchemaCompatibility(mapOf("t" to old), mapOf("t" to new))
            .compare("s", "#/\$defs/t", "#/\$defs/t", direction = SchemaDirection.OUTPUT)
            .map { it.toString() }
        output(obj("a" to STRING, required = listOf("a")), obj("a" to STRING))
            .assert().containsExactly("BREAKING s.a: field made optional")
        output(obj("a" to STRING), obj("a" to STRING, "b" to STRING, required = listOf("b")))
            .assert().containsExactly("ADDITIVE s.b: required field added")
        output(obj("a" to STRING, "b" to STRING), obj("a" to STRING))
            .assert().containsExactly("ADDITIVE s.b: optional field removed")
        output(obj("a" to STRING, "b" to STRING, required = listOf("b")), obj("a" to STRING))
            .assert().containsExactly("BREAKING s.b: field removed")
        output(obj("a" to mapOf("type" to "string")), obj("a" to mapOf("type" to listOf("string", "null"))))
            .assert().containsExactly("BREAKING s.a: type widened from [string] to [null, string]")
        output(obj("a" to mapOf("enum" to listOf("x"))), obj("a" to mapOf("enum" to listOf("x", "y"))))
            .assert().containsExactly("BREAKING s.a: enum value y added")
        output(obj("a" to mapOf("enum" to listOf("x", "y"))), obj("a" to mapOf("enum" to listOf("x"))))
            .assert().containsExactly("ADDITIVE s.a: enum value y removed")
        output(
            obj("a" to mapOf("anyOf" to listOf(mapOf("type" to "string")))),
            obj("a" to mapOf("anyOf" to listOf(mapOf("type" to "string"), mapOf("type" to "integer")))),
        ).assert().containsExactly("BREAKING s.a.anyOf[1]: alternative added")
        // An input reads the same changes the other way.
        changes(obj("a" to STRING, required = listOf("a")), obj("a" to STRING))
            .assert().containsExactly("ADDITIVE s.a: field made optional")
    }
}
