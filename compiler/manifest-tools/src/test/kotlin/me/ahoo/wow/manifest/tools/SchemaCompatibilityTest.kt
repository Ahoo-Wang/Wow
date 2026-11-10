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
        private val NUMBER = mapOf("type" to "number")
        private val ARRAY = mapOf("type" to "array")
        private val NULLABLE_INTEGER = mapOf("type" to listOf("integer", "null"))

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
            // A numeric format is a range of its type: an input that refuses a number old clients sent breaks.
            row(
                "numeric format imposed",
                mapOf("type" to "integer"),
                mapOf("type" to "integer", "format" to "int64"),
                "BREAKING s: format changed from any to int64",
            ),
            row(
                "Long narrowed to Int",
                mapOf("type" to "integer", "format" to "int64"),
                mapOf("type" to "integer", "format" to "int32"),
                "BREAKING s: format changed from int64 to int32",
            ),
            row(
                "Int widened to Long",
                mapOf("type" to "integer", "format" to "int32"),
                mapOf("type" to "integer", "format" to "int64"),
                "ADDITIVE s: format changed from int32 to int64",
            ),
            row(
                "numeric format lifted",
                mapOf("type" to "integer", "format" to "int64"),
                mapOf("type" to "integer"),
                "ADDITIVE s: format changed from int64 to any",
            ),
            row(
                "Double narrowed to Float",
                NUMBER,
                NUMBER + ("format" to "float"),
                "BREAKING s: format changed from any to float",
            ),
            row(
                "numeric formats neither holding the other",
                NUMBER + ("format" to "int64"),
                NUMBER + ("format" to "float"),
                "BREAKING s: format changed from int64 to float",
            ),
            row(
                "Long widened to BigDecimal",
                mapOf("type" to "integer", "format" to "int64"),
                mapOf("type" to "number", "format" to "decimal"),
                "ADDITIVE s: type widened from [integer] to [number]",
                "ADDITIVE s: format changed from int64 to decimal",
            ),
            // The Jakarta constraints of a command's field (decision D380): a tighter one is breaking for the
            // input old clients send, a looser one additive (D349 ③).
            row("minLength imposed", STRING, STRING + ("minLength" to 1), "BREAKING s: minLength 1 imposed"),
            row(
                "minLength raised",
                STRING + ("minLength" to 1),
                STRING + ("minLength" to 3),
                "BREAKING s: minLength raised from 1 to 3",
            ),
            row(
                "maxLength raised",
                STRING + ("maxLength" to 8),
                STRING + ("maxLength" to 16),
                "ADDITIVE s: maxLength raised from 8 to 16",
            ),
            row(
                "exclusiveMinimum imposed",
                NUMBER,
                NUMBER + ("exclusiveMinimum" to 0),
                "BREAKING s: exclusiveMinimum 0 imposed",
            ),
            row(
                "maximum lowered",
                NUMBER + ("maximum" to 10),
                NUMBER + ("maximum" to 9.5),
                "BREAKING s: maximum lowered from 10 to 9.5",
            ),
            row(
                "minimum lowered",
                NUMBER + ("minimum" to 1),
                NUMBER + ("minimum" to 0),
                "ADDITIVE s: minimum lowered from 1 to 0",
            ),
            row("minItems the same number", ARRAY + ("minItems" to 1), ARRAY + ("minItems" to 1.0)),
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
                "class and set ignored",
                mapOf("type" to "array", "x-wow-class" to "a.B"),
                mapOf("type" to "array", "x-wow-class" to "a.C", "uniqueItems" to true),
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
            row(
                "allOf member changed",
                mapOf("allOf" to listOf(obj("a" to STRING, "b" to STRING), obj("kind" to STRING))),
                mapOf("allOf" to listOf(obj("a" to STRING), obj("kind" to STRING))),
                "BREAKING s.allOf[0].b: field removed",
            ),
            row(
                "allOf member added",
                mapOf("allOf" to listOf(obj("a" to STRING))),
                mapOf("allOf" to listOf(obj("a" to STRING), obj("kind" to STRING, required = listOf("kind")))),
                "BREAKING s.allOf[1]: member added",
            ),
            row(
                "allOf member removed",
                mapOf("allOf" to listOf(obj("a" to STRING), obj("kind" to STRING))),
                mapOf("allOf" to listOf(obj("a" to STRING))),
                "ADDITIVE s.allOf[1]: member removed",
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
    fun `a nullable value should be no change from one of its two forms to the other`() {
        val map = mapOf("type" to "object", "additionalProperties" to mapOf("type" to "integer"))
        // A null alternative beside a reference, as a nullable class is written, and a type union.
        val alternative = obj("m" to mapOf("anyOf" to listOf(mapOf("type" to "null"), mapOf("\$ref" to "#/\$defs/M"))))
        val union = obj("m" to map + ("type" to listOf("object", "null")))
        SchemaDirection.entries.forEach { direction ->
            SchemaCompatibility(mapOf("t" to alternative, "M" to map), mapOf("t" to union))
                .compare("s", "#/\$defs/t", "#/\$defs/t", direction = direction).assert().isEmpty()
            SchemaCompatibility(mapOf("t" to union), mapOf("t" to alternative, "M" to map))
                .compare("s", "#/\$defs/t", "#/\$defs/t", direction = direction).assert().isEmpty()
        }
        // Either form, its value is still compared: here a map's values made nullable.
        val loosened = obj("m" to mapOf("type" to listOf("object", "null"), "additionalProperties" to NULLABLE_INTEGER))
        SchemaCompatibility(mapOf("t" to alternative, "M" to map), mapOf("t" to loosened))
            .compare("s", "#/\$defs/t", "#/\$defs/t", direction = SchemaDirection.OUTPUT)
            .map { it.toString() }.assert()
            .containsExactly("BREAKING s.m{}: type widened from [integer] to [integer, null]")
    }

    @Test
    fun `a change behind a reference of an allOf member should be compared, either way it flows`() {
        val subtype = mapOf("allOf" to listOf(obj("p" to mapOf("\$ref" to "#/\$defs/P")), obj("kind" to STRING)))
        val payload = obj("secret" to STRING, required = listOf("secret"))
        val relaxed = obj("secret" to STRING)
        changes(subtype, subtype, oldDefs = mapOf("P" to payload), newDefs = mapOf("P" to relaxed))
            .assert().containsExactly("ADDITIVE s.allOf[0].p.secret: field made optional")
        SchemaCompatibility(mapOf("t" to subtype, "P" to payload), mapOf("t" to subtype, "P" to relaxed))
            .compare("s", "#/\$defs/t", "#/\$defs/t", direction = SchemaDirection.OUTPUT)
            .map { it.toString() }.assert().containsExactly("BREAKING s.allOf[0].p.secret: field made optional")
        changes(subtype, subtype, oldDefs = mapOf("P" to payload), newDefs = mapOf("P" to obj()))
            .assert().containsExactly("BREAKING s.allOf[0].p.secret: field removed")
    }

    @Test
    fun `the properties beside a reference should hold with its definition's`() {
        // A recursive subtype: a reference to its class's schema beside its discriminator.
        val node = mapOf("\$ref" to "#/\$defs/N", "type" to "object", "properties" to mapOf("kind" to STRING))
        val children = obj("children" to ARRAY, required = listOf("children"))
        changes(node, node, oldDefs = mapOf("N" to children), newDefs = mapOf("N" to obj()))
            .assert().containsExactly("BREAKING s.children: field removed")
        changes(node, node, oldDefs = mapOf("N" to children), newDefs = mapOf("N" to children)).assert().isEmpty()
    }

    @Test
    fun `what a reference's definition requires should hold beside a required sibling`() {
        // The reference adds a required name and no property: the definition still requires its own.
        val node = mapOf("\$ref" to "#/\$defs/N", "required" to listOf("kind"))
        val strict = obj("a" to STRING, "kind" to STRING, required = listOf("a"))
        val relaxed = obj("a" to STRING, "kind" to STRING)
        fun compared(direction: SchemaDirection) =
            SchemaCompatibility(mapOf("t" to node, "N" to strict), mapOf("t" to node, "N" to relaxed))
                .compare("s", "#/\$defs/t", "#/\$defs/t", direction = direction).map { it.toString() }
        compared(SchemaDirection.OUTPUT).assert().containsExactly("BREAKING s.a: field made optional")
        compared(SchemaDirection.INPUT).assert().containsExactly("ADDITIVE s.a: field made optional")
    }

    @Test
    fun `a property both a reference's definition and its siblings declare should hold as both`() {
        // The class declares its discriminator too; the reference narrows it to its constant.
        val node = mapOf("\$ref" to "#/\$defs/N", "properties" to mapOf("kind" to mapOf("const" to "node")))
        val declared = obj("kind" to STRING, required = listOf("kind"))
        changes(node, node, oldDefs = mapOf("N" to declared), newDefs = mapOf("N" to declared)).assert().isEmpty()
        // The definition's own schema of it is still compared, not hidden behind the sibling's constant.
        val retyped = obj("kind" to NUMBER, required = listOf("kind"))
        changes(node, node, oldDefs = mapOf("N" to declared), newDefs = mapOf("N" to retyped))
            .assert().containsExactly("BREAKING s.kind.allOf[0]: type narrowed from [string] to [number]")
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
    fun `a numeric format narrowed should break what is read, widened what is written`() {
        val long = mapOf("type" to "integer", "format" to "int64")
        val int = mapOf("type" to "integer", "format" to "int32")
        fun compared(old: Any?, new: Any?, direction: SchemaDirection) =
            SchemaCompatibility(mapOf("t" to old), mapOf("t" to new))
                .compare("s", "#/\$defs/t", "#/\$defs/t", direction = direction)
                .map { it.toString() }
        // A stored 2^31 no longer reads back as an Int; a reader generated as an Int cannot hold a Long.
        compared(long, int, SchemaDirection.STORED).assert()
            .containsExactly("BREAKING s: format changed from int64 to int32")
        compared(long, int, SchemaDirection.OUTPUT).assert()
            .containsExactly("ADDITIVE s: format changed from int64 to int32")
        compared(int, long, SchemaDirection.STORED).assert()
            .containsExactly("ADDITIVE s: format changed from int32 to int64")
        compared(int, long, SchemaDirection.OUTPUT).assert()
            .containsExactly("BREAKING s: format changed from int32 to int64")
        // A format of no number keeps the general rule: a format imposed breaks an input.
        compared(STRING, STRING + ("format" to "uuid"), SchemaDirection.INPUT).assert()
            .containsExactly("BREAKING s: format uuid imposed")
    }

    @Test
    fun `an alternative of oneOf that may match a value another matches should break what is read`() {
        val integer = mapOf("type" to "integer")
        fun alternatives(keyword: String, vararg types: String) = mapOf(keyword to types.map { mapOf("type" to it) })
        // 7 matched [integer] alone; with number beside it, it matches two, and oneOf refuses it.
        changes(alternatives("oneOf", "integer"), alternatives("oneOf", "integer", "number")).assert()
            .containsExactlyInAnyOrder(
                "ADDITIVE s.oneOf[1]: alternative added",
                "BREAKING s.oneOf[1]: alternative overlaps alternative 0: a value both accept is refused",
            )
        // An alternative widened into another's type.
        changes(
            alternatives("oneOf", "integer", "string"),
            mapOf("oneOf" to listOf(integer, mapOf("type" to listOf("number", "string")))),
        ).assert().containsExactly(
            "BREAKING s.oneOf[1]: alternative overlaps alternative 0: a value both accept is refused",
        )
        // The neighbouring extensions keep every old value matching once: a disjoint oneOf alternative, a widened one
        // that stays disjoint, and any alternative of anyOf.
        changes(alternatives("oneOf", "integer"), alternatives("oneOf", "integer", "string")).assert()
            .containsExactly("ADDITIVE s.oneOf[1]: alternative added")
        changes(alternatives("oneOf", "integer", "string"), alternatives("oneOf", "number", "string")).assert()
            .isEmpty()
        changes(alternatives("anyOf", "integer"), alternatives("anyOf", "integer", "number")).assert()
            .containsExactly("ADDITIVE s.anyOf[1]: alternative added")
        // Two alternatives added that overlap each other: reported once, on the first. A number placed before the
        // integer it now matches too: the overlap read from either side.
        changes(alternatives("oneOf", "string"), alternatives("oneOf", "string", "integer", "number")).assert()
            .containsExactlyInAnyOrder(
                "ADDITIVE s.oneOf[1]: alternative added",
                "ADDITIVE s.oneOf[2]: alternative added",
                "BREAKING s.oneOf[1]: alternative overlaps alternative 2: a value both accept is refused",
            )
        changes(alternatives("oneOf", "integer"), alternatives("oneOf", "number", "integer")).assert()
            .contains("BREAKING s.oneOf[0]: alternative overlaps alternative 1: a value both accept is refused")
        // Two old alternatives collapsed into one, the second widened: nothing left for it to overlap.
        changes(
            mapOf("oneOf" to listOf(integer, integer + ("minimum" to 0))),
            mapOf("oneOf" to listOf(integer)),
        ).assert().isEmpty()
        // An alternative of no JSON type, an `enum` say, is not proven apart from another: either side.
        val one = mapOf("enum" to listOf(1))
        changes(mapOf("oneOf" to listOf(integer)), mapOf("oneOf" to listOf(integer, one))).assert()
            .contains("BREAKING s.oneOf[1]: alternative overlaps alternative 0: a value both accept is refused")
        changes(mapOf("oneOf" to listOf(one)), mapOf("oneOf" to listOf(one, STRING))).assert()
            .contains("BREAKING s.oneOf[1]: alternative overlaps alternative 0: a value both accept is refused")
        // A stored record is read back as an input is.
        SchemaCompatibility(
            mapOf("t" to alternatives("oneOf", "integer")),
            mapOf("t" to alternatives("oneOf", "integer", "number")),
        ).compare("s", "#/\$defs/t", "#/\$defs/t", direction = SchemaDirection.STORED).map { it.toString() }.assert()
            .contains("BREAKING s.oneOf[1]: alternative overlaps alternative 0: a value both accept is refused")
    }

    @Test
    fun `a subtype of a discriminated oneOf should be told apart by the constant both require`() {
        fun subtype(kind: Any?, required: Boolean = true, key: String = "const") = obj(
            "kind" to (if (kind == null) STRING else mapOf(key to kind)),
            "number" to STRING,
            required = if (required) listOf("kind") else emptyList(),
        )
        val payments = listOf(subtype("card"), subtype("bank"))
        fun added(subtype: Map<String, Any?>) = changes(
            mapOf("oneOf" to payments),
            mapOf("oneOf" to payments + subtype)
        )
        val overlaps = "BREAKING s.oneOf[2]: alternative overlaps alternative"
        // A new constant: no value matches two, the canonical compatible extension.
        added(subtype("wallet")).assert().containsExactly("ADDITIVE s.oneOf[2]: alternative added")
        // A constant another alternative has, no constant, or one not required by both: a value may match two.
        added(subtype("card")).assert().contains("$overlaps 0: a value both accept is refused")
        added(subtype(null)).assert().contains("$overlaps 0: a value both accept is refused")
            .contains("$overlaps 1: a value both accept is refused")
        added(subtype("wallet", required = false)).assert().contains("$overlaps 0: a value both accept is refused")
        val loose = listOf(subtype("card", required = false))
        changes(mapOf("oneOf" to loose), mapOf("oneOf" to loose + subtype("wallet"))).assert()
            .contains("BREAKING s.oneOf[1]: alternative overlaps alternative 0: a value both accept is refused")
        // An enum is a set of constants: apart when no value is in both.
        val tiers = listOf(subtype(listOf("gold", "silver"), key = "enum"))
        changes(mapOf("oneOf" to tiers), mapOf("oneOf" to tiers + subtype(listOf("bronze"), key = "enum")))
            .assert().containsExactly("ADDITIVE s.oneOf[1]: alternative added")
        changes(mapOf("oneOf" to tiers), mapOf("oneOf" to tiers + subtype(listOf("silver", "bronze"), key = "enum")))
            .assert().contains(
                "BREAKING s.oneOf[1]: alternative overlaps alternative 0: a value both accept is refused"
            )
        // Subtypes referred to by `$ref`: their definitions are read.
        val refs = listOf(mapOf("\$ref" to "#/\$defs/card"), mapOf("\$ref" to "#/\$defs/wallet"))
        changes(
            mapOf("oneOf" to refs.take(1)),
            mapOf("oneOf" to refs),
            oldDefs = mapOf("card" to subtype("card")),
            newDefs = mapOf("card" to subtype("card"), "wallet" to subtype("wallet")),
        ).assert().containsExactly("ADDITIVE s.oneOf[1]: alternative added")
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
        // A state's constraint tightened: it produces less, which old readers take.
        output(obj("a" to STRING), obj("a" to STRING + ("minLength" to 1)))
            .assert().containsExactly("ADDITIVE s.a: minLength 1 imposed")
        output(obj("a" to STRING + ("maxLength" to 8)), obj("a" to STRING + ("maxLength" to 16)))
            .assert().containsExactly("BREAKING s.a: maxLength raised from 8 to 16")
        output(
            obj("a" to mapOf("anyOf" to listOf(mapOf("type" to "string")))),
            obj("a" to mapOf("anyOf" to listOf(mapOf("type" to "string"), mapOf("type" to "integer")))),
        ).assert().containsExactly("BREAKING s.a.anyOf[1]: alternative added")
        // An input reads the same changes the other way.
        changes(obj("a" to STRING, required = listOf("a")), obj("a" to STRING))
            .assert().containsExactly("ADDITIVE s.a: field made optional")
    }

    @Test
    fun `stored records should be read back as an input whose constraints no record is checked against`() {
        fun stored(old: Any?, new: Any?) = SchemaCompatibility(mapOf("t" to old), mapOf("t" to new))
            .compare("s", "#/\$defs/t", "#/\$defs/t", direction = SchemaDirection.STORED)
            .map { it.toString() }
        // The structure is read back as an input's: an old record without a field the class now requires breaks.
        stored(obj("a" to STRING), obj("a" to STRING, "b" to STRING, required = listOf("b")))
            .assert().containsExactly("BREAKING s.b: required field added")
        stored(obj("a" to mapOf("enum" to listOf("x", "y"))), obj("a" to mapOf("enum" to listOf("x"))))
            .assert().containsExactly("BREAKING s.a: enum value y removed")
        // A format says how a value is decoded: changed, it is still breaking.
        stored(obj("a" to STRING + ("format" to "date-time")), obj("a" to STRING + ("format" to "date")))
            .assert().containsExactly("BREAKING s.a: format changed from date-time to date")
        // A constraint, whichever way it moves, is additive: no stored record is checked against it.
        stored(obj("a" to STRING), obj("a" to STRING + ("minLength" to 1)))
            .assert().containsExactly("ADDITIVE s.a: minLength 1 imposed")
        stored(obj("a" to STRING + ("maxLength" to 16)), obj("a" to STRING + ("maxLength" to 8)))
            .assert().containsExactly("ADDITIVE s.a: maxLength lowered from 16 to 8")
        stored(obj("a" to STRING + ("maxLength" to 8)), obj("a" to STRING + ("maxLength" to 16)))
            .assert().containsExactly("ADDITIVE s.a: maxLength raised from 8 to 16")
        stored(obj("a" to STRING + ("pattern" to "^a")), obj("a" to STRING + ("pattern" to "^b")))
            .assert().containsExactly("ADDITIVE s.a: pattern changed from ^a to ^b")
        stored(obj("a" to NUMBER + ("multipleOf" to 2)), obj("a" to NUMBER))
            .assert().containsExactly("ADDITIVE s.a: multipleOf 2 lifted")
    }
}
