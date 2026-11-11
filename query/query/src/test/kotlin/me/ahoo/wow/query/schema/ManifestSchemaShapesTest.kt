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

package me.ahoo.wow.query.schema

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.query.schema.ManifestFixtures.schemaOf
import me.ahoo.wow.query.schema.ManifestFixtures.stateManifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/** How the Manifest schema reader carries keywords through arrays, unions and maps, and what it refuses. */
class ManifestSchemaShapesTest {
    private suspend fun schema(properties: Map<String, Any?>, extra: Map<String, Any?> = emptyMap()) =
        schemaOf(ManifestQuerySchemaSource(stateManifest(properties, extra)))

    private fun QueryModelSchema.value(path: String): QueryValueSchema = checkNotNull(field(QueryField(path))).value

    private val display = mapOf("level" to "display")

    @Test
    fun `semantic keywords reach every non-null leaf of arrays and unions`() {
        runTest {
            val schema = schema(
                mapOf(
                    "times" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "integer"),
                        ManifestQueryKeywords.TEMPORAL to mapOf("unit" to "seconds"),
                    ),
                    "either" to mapOf(
                        "anyOf" to listOf(
                            mapOf("type" to "integer"),
                            mapOf("type" to "array", "items" to mapOf("type" to "integer")),
                            mapOf("type" to "null"),
                        ),
                        ManifestQueryKeywords.TEMPORAL to mapOf("unit" to "milliseconds"),
                    ),
                    "amounts" to mapOf(
                        "anyOf" to listOf(
                            mapOf("type" to "number"),
                            mapOf("type" to "array", "items" to mapOf("type" to "number")),
                            mapOf("type" to "null"),
                        ),
                        ManifestQueryKeywords.DECIMAL to mapOf("scale" to 2),
                    ),
                ),
            )
            schema.value("state.times").items?.semanticType.assert().isEqualTo(Temporal.Epoch(TimeUnit.SECONDS))
            schema.value("state.either").run {
                kind.assert().isEqualTo(QueryValueKind.UNION)
                nullable.assert().isTrue()
                alternatives.first().semanticType.assert().isEqualTo(Temporal.Epoch(TimeUnit.MILLISECONDS))
            }
            schema.value("state.amounts").alternatives.first().semanticType.assert()
                .isEqualTo(NumericFormat.Decimal(2))
        }
    }

    @Test
    fun `sensitivity reaches each alternative of a union that is not all strings`() {
        runTest {
            val schema = schema(
                mapOf(
                    "contact" to mapOf(
                        "anyOf" to listOf(
                            mapOf("type" to "string"),
                            mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string")),
                        ),
                        ManifestQueryKeywords.SENSITIVE to display,
                    ),
                ),
            )
            schema.value("state.contact").alternatives.run {
                first().maskRule?.level.assert().isEqualTo(SensitivityLevel.DISPLAY)
                last().additionalProperties?.maskRule?.level.assert().isEqualTo(SensitivityLevel.DISPLAY)
            }
        }
    }

    /** A subtype of a sealed value as the Manifest writes it: its own properties, then its discriminator's value. */
    private fun subtype(name: String, extra: Map<String, Any?>): Map<String, Any?> = mapOf(
        "allOf" to listOf(
            mapOf(
                "type" to "object",
                "properties" to mapOf("type" to mapOf("type" to "string")) + extra,
                "required" to listOf("type"),
            ),
            mapOf("type" to "object", "properties" to mapOf("type" to mapOf("const" to name))),
        ),
    )

    @Test
    fun `a sealed value's discriminator, a string each subtype narrows to its const, should read as a string`() {
        runTest {
            val holder = mapOf("oneOf" to listOf(mapOf("\$ref" to "#/\$defs/Rack"), mapOf("\$ref" to "#/\$defs/Crate")))
            val schema = schema(
                mapOf("holder" to holder),
                mapOf(
                    "Rack" to subtype("Rack", mapOf("levels" to mapOf("type" to "integer"))),
                    "Crate" to subtype("Crate", mapOf("lidded" to mapOf("type" to "boolean"))),
                ),
            )
            val (rack, crate) = schema.value("state.holder").alternatives
            listOf(rack to "Rack", crate to "Crate").forEach { (branch, name) ->
                branch.properties.getValue("type").run {
                    kind.assert().isEqualTo(QueryValueKind.SCALAR)
                    valueTypes.assert().containsExactly(QueryValueType.STRING)
                    enumValues!!.map { it.asString() }.assert().containsExactly(name)
                }
            }
            rack.properties.getValue("levels").valueTypes.assert().containsExactly(QueryValueType.INTEGER)
            schema.field(QueryField("state.holder.type")).assert().isNotNull()
        }
    }

    @Test
    fun `an enum or a numeric const should narrow the type beside it alike, and name one only where none is`() {
        runTest {
            fun narrowed(type: String, values: Map<String, Any?>) =
                mapOf("allOf" to listOf(mapOf("type" to type), values))
            val schema = schema(
                mapOf(
                    "grade" to narrowed("string", mapOf("enum" to listOf("A", "B"))),
                    "ratio" to narrowed("number", mapOf("const" to 3)),
                    "count" to narrowed("integer", mapOf("const" to 3)),
                    "flag" to narrowed("boolean", mapOf("const" to true)),
                    "tag" to mapOf("const" to "fixed"),
                    "levels" to mapOf("enum" to listOf(1, 2.5, null)),
                    "whole" to mapOf("const" to 3.0),
                    "shapes" to mapOf("enum" to listOf(mapOf("a" to 1))),
                ),
            )
            schema.value("state.grade").valueTypes.assert().containsExactly(QueryValueType.STRING)
            schema.value("state.ratio").valueTypes.assert().containsExactly(QueryValueType.DECIMAL)
            schema.value("state.count").valueTypes.assert().containsExactly(QueryValueType.INTEGER)
            schema.value("state.flag").valueTypes.assert().containsExactly(QueryValueType.BOOLEAN)
            schema.value("state.tag").run {
                kind.assert().isEqualTo(QueryValueKind.SCALAR)
                valueTypes.assert().containsExactly(QueryValueType.STRING)
            }
            schema.value("state.levels").valueTypes.assert()
                .containsExactly(QueryValueType.INTEGER, QueryValueType.DECIMAL)
            schema.value("state.whole").valueTypes.assert().containsExactly(QueryValueType.INTEGER)
            // An enum of objects narrows no scalar.
            schema.value("state.shapes").kind.assert().isEqualTo(QueryValueKind.UNKNOWN)
        }
    }

    @Test
    fun `a value of another type than the one named should still be a conflict`() {
        runTest {
            listOf(
                mapOf("allOf" to listOf(mapOf("type" to "string"), mapOf("type" to "integer"))),
                mapOf("allOf" to listOf(mapOf("type" to "string"), mapOf("const" to 3))),
                mapOf("type" to "integer", "const" to 2.5),
            ).forEach { field ->
                assertThrows<QuerySchemaConflictException> { schema(mapOf("odd" to field)) }.message.assert()
                    .describedAs(field.toString()).startsWith("Conflicting query schema declaration: [state.odd.")
            }
        }
    }

    @Test
    fun `type lists, unions and arrays read their edge shapes`() {
        runTest {
            val schema = schema(
                mapOf(
                    "mixed" to mapOf("type" to listOf("string", "object", "null")),
                    "bare" to mapOf("type" to "array"),
                    "none" to mapOf("anyOf" to listOf(mapOf("type" to "null"))),
                    "single" to mapOf("anyOf" to listOf(mapOf("type" to listOf("string", "null")))),
                    "listed" to mapOf(
                        "type" to "object",
                        "required" to listOf(1, "a"),
                        "properties" to mapOf("a" to mapOf("type" to "string")),
                    ),
                    "same" to mapOf(
                        "anyOf" to listOf(
                            mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "s" to mapOf("type" to "string", ManifestQueryKeywords.SENSITIVE to display),
                                ),
                                "additionalProperties" to mapOf("type" to "integer"),
                            ),
                            mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "s" to mapOf("type" to "string", ManifestQueryKeywords.SENSITIVE to display),
                                    "t" to mapOf("type" to "integer"),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            schema.value("state.mixed").run {
                kind.assert().isEqualTo(QueryValueKind.UNION)
                nullable.assert().isTrue()
            }
            schema.value("state.bare").items?.kind.assert().isEqualTo(QueryValueKind.UNKNOWN)
            schema.value("state.none").kind.assert().isEqualTo(QueryValueKind.NULL)
            schema.value("state.single").nullable.assert().isTrue()
            schema.value("state.listed.a").required.assert().isTrue()
            schema.field(QueryField("state.same.s"))?.protection.assert().isEqualTo(SensitivityLevel.DISPLAY)
        }
    }

    @Test
    fun `a hidden sensitive value is found through lists and references, and fails`() {
        runTest {
            val secret = mapOf("type" to "string", ManifestQueryKeywords.SENSITIVE to display)
            val hidden = listOf(
                mapOf("anyOf" to listOf(mapOf("type" to "null"), secret)),
                mapOf("\$ref" to "#/\$defs/Secret"),
            )
            hidden.forEach { property ->
                assertThrows<QuerySchemaConflictException>("$property") {
                    schema(mapOf("bad name" to property), extra = mapOf("Secret" to secret))
                }
            }
            // A hidden property without a sensitive value is left out, a recursive or foreign reference included.
            val schema = schema(
                mapOf(
                    "bad name" to mapOf("\$ref" to "#/\$defs/Node"),
                    "other name" to mapOf("\$ref" to "#/other", "items" to listOf(1)),
                    "ok" to mapOf("type" to "string"),
                ),
                extra = mapOf(
                    "Node" to mapOf(
                        "type" to "object",
                        "properties" to mapOf("next" to mapOf("\$ref" to "#/\$defs/Node")),
                    ),
                ),
            )
            schema.field(QueryField("state.ok")).assert().isNotNull()
        }
    }

    @Test
    fun `malformed shapes and keywords fail`() {
        runTest {
            val invalid = listOf(
                mapOf("type" to mapOf("a" to 1)),
                mapOf("type" to listOf("string", 1)),
                mapOf("allOf" to listOf("x")),
                mapOf("type" to "integer", ManifestQueryKeywords.TEMPORAL to emptyMap<String, Any?>()),
                mapOf("type" to "integer", ManifestQueryKeywords.TEMPORAL to "SECONDS"),
                mapOf(
                    "type" to "string",
                    ManifestQueryKeywords.SENSITIVE to display,
                    ManifestQueryKeywords.MASK to mapOf("strategy" to "x", "keepSuffix" to 1),
                ),
                mapOf(
                    "type" to "object",
                    "properties" to mapOf("a" to mapOf("type" to "string")),
                    "additionalProperties" to mapOf("type" to "string"),
                    ManifestQueryKeywords.SENSITIVE to display,
                ),
                mapOf(
                    "anyOf" to listOf(mapOf("type" to "string"), mapOf("type" to "integer")),
                    ManifestQueryKeywords.SENSITIVE to display,
                ),
            )
            invalid.forEach { property ->
                assertThrows<QuerySchemaConflictException>("$property") { schema(mapOf("value" to property)) }
            }
        }
    }
}
