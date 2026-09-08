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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.mask.CompiledMask
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.KeepMask
import me.ahoo.wow.api.query.mask.KeepMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.JsonNodeFactory
import java.util.concurrent.TimeUnit
import kotlin.reflect.jvm.javaField

class QuerySchemaMergerTest {
    private val merger = QuerySchemaMerger()

    @Test
    fun `dotted metadata patch preserves nullable union branches`() {
        val objectBranch = QueryFieldDeclaration(
            properties = DeclarationValue.Set(
                mapOf(
                    "zip" to QueryFieldDeclaration(
                        valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
                    )
                )
            ),
        )
        val nullable = QueryFieldDeclaration(
            alternatives = DeclarationValue.Set(
                listOf(objectBranch, QueryFieldDeclaration(kind = DeclarationValue.Set(QueryValueKind.NULL)))
            ),
        )
        val result = merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(
                    100,
                    QuerySchemaDeclaration(mapOf(QueryField("state.address") to nullable))
                ),
                PrioritizedQuerySchemaDeclaration(
                    300,
                    QuerySchemaDeclaration(
                        mapOf(
                            QueryField("state.address.zip") to QueryFieldDeclaration(title = DeclarationValue.Set("ZIP label")),
                        )
                    )
                ),
            ),
        )
        val address = result.root.properties.getValue("state").properties.getValue("address")
        address.kind.assert().isEqualTo(QueryValueKind.UNION)
        address.alternatives.map { it.kind }.assert().isEqualTo(listOf(QueryValueKind.OBJECT, QueryValueKind.NULL))
        val zip = address.alternatives.first().properties.getValue("zip")
        zip.title.assert().isEqualTo("ZIP label")
        zip.valueTypes.assert().isEqualTo(setOf(QueryValueType.STRING))
        address.alternatives.last().properties.assert().isEmpty()
    }

    @Test
    fun `dotted patch does not turn scalar union alternatives into objects`() {
        assertThrows<QuerySchemaConflictException> {
            merger.merge(
                system(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(
                        100,
                        QuerySchemaDeclaration(
                            mapOf(
                                QueryField("state.address") to QueryFieldDeclaration(
                                    alternatives = DeclarationValue.Set(
                                        listOf(
                                            QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING))),
                                            QueryFieldDeclaration(kind = DeclarationValue.Set(QueryValueKind.NULL)),
                                        )
                                    )
                                ),
                            )
                        )
                    ),
                    PrioritizedQuerySchemaDeclaration(
                        300,
                        QuerySchemaDeclaration(
                            mapOf(
                                QueryField("state.address.zip") to QueryFieldDeclaration(title = DeclarationValue.Set("ZIP label")),
                            )
                        )
                    ),
                ),
            )
        }
    }

    @Test
    fun `equal length declaration paths retain distinct keys`() {
        val result = merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(
                    100,
                    QuerySchemaDeclaration(
                        mapOf(
                            QueryField("state.first") to QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING))),
                            QueryField("state.other") to QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.INTEGER))),
                        ),
                    )
                )
            )
        )
        result.value(
            QueryField("state.first").toPathTemplate()
        )!!.valueTypes.assert().isEqualTo(setOf(QueryValueType.STRING))
        result.value(
            QueryField("state.other").toPathTemplate()
        )!!.valueTypes.assert().isEqualTo(setOf(QueryValueType.INTEGER))
    }

    @Test
    fun `higher priority should merge by leaf without erasing lower values`() {
        val json = declaration("state.createdAt", valueTypes = setOf(QueryValueType.INTEGER))
        val bean = declaration("state.createdAt", semanticType = Temporal.Epoch(TimeUnit.MILLISECONDS))

        val field = merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(100, json),
                PrioritizedQuerySchemaDeclaration(300, bean),
            ),
        ).value(QueryField("state.createdAt").toPathTemplate())!!

        field.valueTypes.assert().isEqualTo(setOf(QueryValueType.INTEGER))
        field.semanticType.assert().isEqualTo(Temporal.Epoch(TimeUnit.MILLISECONDS))
    }

    @Test
    fun `higher priority should override the same extension leaf`() {
        val result = merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(100, title("JSON")),
                PrioritizedQuerySchemaDeclaration(300, title("Bean")),
            ),
        )

        result.value(QueryField("state.name").toPathTemplate())!!.title.assert().isEqualTo("Bean")
    }

    @Test
    fun `same priority different leaf values should conflict`() {
        val exception = assertThrows<QuerySchemaConflictException> {
            merger.merge(
                system(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(300, title("A")),
                    PrioritizedQuerySchemaDeclaration(300, title("B")),
                ),
            )
        }

        exception.errorCode.assert().isEqualTo(QuerySchemaConflictException.ERROR_CODE)
    }

    @Test
    fun `same priority equal leaf values should merge deterministically`() {
        val declaration = title("Name")

        merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(300, declaration),
                PrioritizedQuerySchemaDeclaration(300, declaration),
            ),
        ).value(QueryField("state.name").toPathTemplate())!!.title.assert().isEqualTo("Name")
    }

    @Test
    fun `unset extension leaves should materialize defaults`() {
        val field = merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(
                    300,
                    QuerySchemaDeclaration(
                        mapOf(
                            QueryField("state.name") to QueryFieldDeclaration(),
                        )
                    )
                )
            ),
        ).value(QueryField("state.name").toPathTemplate())!!

        field.title.assert().isNull()
        field.description.assert().isNull()
        field.enumValues.assert().isNull()
        field.valueTypes.assert().isEmpty()
        field.nullable.assert().isTrue()
        field.required.assert().isFalse()
        field.cardinality.assert().isNull()
        field.semanticType.assert().isNull()
        field.additionalProperties.assert().isNull()
    }

    @Test
    fun `snapshot state descendants should be allowed`() {
        merger.merge(
            system(),
            listOf(PrioritizedQuerySchemaDeclaration(300, title("Name"))),
        ).value(QueryField("state.name").toPathTemplate()).assert().isNotNull()
    }

    @Test
    fun `event stream payload descendants should be allowed`() {
        val payload = declaration("body.body.data", valueTypes = setOf(QueryValueType.STRING))

        merger.merge(
            SystemQuerySchemaSource.declaration(QueryModel.EVENT_STREAM),
            listOf(PrioritizedQuerySchemaDeclaration(300, payload)),
        ).value(QueryField("body.body.data").toPathTemplate()).assert().isNotNull()
    }

    @Test
    fun `event stream body type enum should enrich system discriminator`() {
        val field = merger.merge(
            eventStreamSystem(),
            listOf(
                PrioritizedQuerySchemaDeclaration(
                    300,
                    bodyType(QueryFieldDeclaration(enumValues = DeclarationValue.Set(enumValues("example.Event")))),
                ),
            ),
        ).value(QueryField("body.bodyType").toPathTemplate())!!

        field.enumValues.assert().isEqualTo(listOf(JsonNodeFactory.instance.stringNode("example.Event")))
        field.valueTypes.assert().isEqualTo(setOf(QueryValueType.STRING))
        field.required.assert().isTrue()
    }

    @Test
    fun `event stream body type should reject every non enum leaf`() {
        listOf(
            QueryFieldDeclaration(title = DeclarationValue.Set("Body type")),
            QueryFieldDeclaration(description = DeclarationValue.Set("Event type discriminator")),
            QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.INTEGER))),
            QueryFieldDeclaration(nullable = DeclarationValue.Set(true)),
            QueryFieldDeclaration(required = DeclarationValue.Set(false)),
            QueryFieldDeclaration(kind = DeclarationValue.Set(QueryValueKind.ARRAY)),
            QueryFieldDeclaration(semanticType = DeclarationValue.Set(Temporal.Epoch(TimeUnit.MILLISECONDS))),
            QueryFieldDeclaration(additionalProperties = DeclarationValue.Set(QueryFieldDeclaration())),
            QueryFieldDeclaration(maskRule = DeclarationValue.Set(fullMaskRule())),
        ).forEach { extension ->
            assertThrows<QuerySchemaConflictException> {
                merger.merge(eventStreamSystem(), listOf(PrioritizedQuerySchemaDeclaration(300, bodyType(extension))))
            }
        }
    }

    @Test
    fun `event stream body type should reject invalid enum enrichment`() {
        listOf(
            QueryFieldDeclaration(enumValues = DeclarationValue.Set(null)),
            QueryFieldDeclaration(enumValues = DeclarationValue.Set(emptyList())),
            QueryFieldDeclaration(enumValues = DeclarationValue.Set(listOf(JsonNodeFactory.instance.numberNode(1)))),
            QueryFieldDeclaration(enumValues = DeclarationValue.Set(enumValues("duplicate", "duplicate"))),
        ).forEach { extension ->
            assertThrows<QuerySchemaConflictException> {
                merger.merge(eventStreamSystem(), listOf(PrioritizedQuerySchemaDeclaration(300, bodyType(extension))))
            }
        }
    }

    @Test
    fun `event stream body type invalid enum should not clear lower priority enum`() {
        assertThrows<QuerySchemaConflictException> {
            merger.merge(
                eventStreamSystem(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(
                        100,
                        bodyType(QueryFieldDeclaration(enumValues = DeclarationValue.Set(enumValues("example.Event")))),
                    ),
                    PrioritizedQuerySchemaDeclaration(
                        300,
                        bodyType(QueryFieldDeclaration(enumValues = DeclarationValue.Set(emptyList()))),
                    ),
                ),
            )
        }
    }

    @Test
    fun `event stream payload outside system field should conflict`() {
        assertThrows<QuerySchemaConflictException> {
            merger.merge(
                eventStreamSystem(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(
                        300,
                        QuerySchemaDeclaration(
                            mapOf(
                                QueryField("body.name") to QueryFieldDeclaration(
                                    enumValues = DeclarationValue.Set(listOf(JsonNodeFactory.instance.stringNode("event"))),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `arbitrary snapshot top level extension should conflict`() {
        val exception = assertThrows<QuerySchemaConflictException> {
            merger.merge(
                system(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(
                        300,
                        QuerySchemaDeclaration(
                            mapOf(
                                QueryField("custom") to QueryFieldDeclaration(title = DeclarationValue.Set("Custom")),
                            )
                        )
                    )
                ),
            )
        }

        exception.errorCode.assert().isEqualTo(QuerySchemaConflictException.ERROR_CODE)
    }

    @Test
    fun `extension should not overwrite a system leaf`() {
        assertThrows<QuerySchemaConflictException> {
            merger.merge(
                system(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(
                        300,
                        QuerySchemaDeclaration(
                            mapOf(
                                QueryField("state") to QueryFieldDeclaration(
                                    nullable = DeclarationValue.Set(true),
                                ),
                            )
                        )
                    )
                ),
            )
        }
    }

    @Test
    fun `mask rules should merge only when equal`() {
        val rule = fullMaskRule()
        val masked = declaration("state.secret", setOf(QueryValueType.STRING), maskRule = rule)
        val sameRule = MaskRule(rule.strategyType, rule.annotation, CompiledMask { "different" })

        sameRule.assert().isEqualTo(rule)

        merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(100, declaration("state.secret")),
                PrioritizedQuerySchemaDeclaration(100, masked),
            ),
        ).value(QueryField("state.secret").toPathTemplate())!!.maskRule.assert().isEqualTo(rule)

        merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(100, masked),
                PrioritizedQuerySchemaDeclaration(
                    100,
                    declaration("state.secret", setOf(QueryValueType.STRING), maskRule = sameRule),
                ),
            ),
        ).value(QueryField("state.secret").toPathTemplate())!!.maskRule.assert().isEqualTo(rule)

        assertThrows<QuerySchemaConflictException> {
            merger.merge(
                system(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(100, masked),
                    PrioritizedQuerySchemaDeclaration(200, declaration("state.secret", maskRule = keepMaskRule())),
                ),
            )
        }
    }

    @Test
    fun `materialized masked fields should remain string typed`() {
        val masked = declaration("state.secret", setOf(QueryValueType.STRING), maskRule = fullMaskRule())

        assertThrows<QuerySchemaConflictException> {
            merger.merge(
                system(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(100, masked),
                    PrioritizedQuerySchemaDeclaration(
                        200,
                        declaration("state.secret", setOf(QueryValueType.INTEGER)),
                    ),
                ),
            )
        }
    }

    @Test
    fun `array and map nodes retain independent value nullability`() {
        val schema = mergeTrees(
            QueryFieldDeclaration(
                additionalProperties = DeclarationValue.Set(
                    QueryFieldDeclaration(
                        nullable = DeclarationValue.Set(true),
                        items = DeclarationValue.Set(
                            QueryFieldDeclaration(
                                nullable = DeclarationValue.Set(false),
                                properties = DeclarationValue.Set(mapOf("secret" to stringMask())),
                            )
                        ),
                    )
                ),
                nullable = DeclarationValue.Set(false),
            )
        )
        val map = schema.root.properties.getValue("state").properties.getValue("values")
        map.kind.assert().isEqualTo(QueryValueKind.OBJECT)
        map.nullable.assert().isFalse()
        val array = checkNotNull(map.additionalProperties)
        array.kind.assert().isEqualTo(QueryValueKind.ARRAY)
        array.nullable.assert().isTrue()
        val item = checkNotNull(array.items)
        item.nullable.assert().isFalse()
        item.properties.getValue("secret").maskRule.assert().isEqualTo(fullMaskRule())
    }

    @Test
    fun `structure replacement retains compatible masked descendants and replaces ordinary leaves`() {
        val lower = QueryFieldDeclaration(
            additionalProperties = DeclarationValue.Set(
                QueryFieldDeclaration(
                    properties = DeclarationValue.Set(
                        mapOf("secret" to stringMask(), "old" to QueryFieldDeclaration())
                    ),
                )
            )
        )
        val higher = QueryFieldDeclaration(
            additionalProperties = DeclarationValue.Set(
                QueryFieldDeclaration(
                    properties = DeclarationValue.Set(
                        mapOf(
                            "secret" to QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)))
                        )
                    ),
                )
            )
        )
        val value = mergeTrees(
            lower,
            higher
        ).root.properties.getValue("state").properties.getValue("values").additionalProperties!!
        value.properties.keys.assert().isEqualTo(setOf("secret"))
        value.properties.getValue("secret").maskRule.assert().isEqualTo(fullMaskRule())
    }

    @Test
    fun `structure replacement rejects loss of a masked dynamic descendant`() {
        val lower = QueryFieldDeclaration(
            additionalProperties = DeclarationValue.Set(
                QueryFieldDeclaration(
                    properties = DeclarationValue.Set(mapOf("secret" to stringMask())),
                )
            )
        )
        listOf(
            QueryFieldDeclaration(additionalProperties = DeclarationValue.Set(null)),
            QueryFieldDeclaration(
                additionalProperties = DeclarationValue.Set(
                    QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
                )
            ),
        ).forEach { higher -> assertThrows<QuerySchemaConflictException> { mergeTrees(lower, higher) } }
    }

    @Test
    fun `union alternatives preserve distinct shapes and protected replacement must be unambiguous`() {
        val string = stringMask()
        val array =
            QueryFieldDeclaration(
                items = DeclarationValue.Set(
                    QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)))
                )
            )
        val lower = QueryFieldDeclaration(alternatives = DeclarationValue.Set(listOf(string, array)))
        val schema = mergeTrees(lower)
        val union = schema.root.properties.getValue("state").properties.getValue("values")
        union.kind.assert().isEqualTo(QueryValueKind.UNION)
        union.cardinality.assert().isNull()
        union.alternatives.map { it.kind }.assert().isEqualTo(listOf(QueryValueKind.SCALAR, QueryValueKind.ARRAY))
        assertThrows<QuerySchemaConflictException> {
            mergeTrees(lower, QueryFieldDeclaration(alternatives = DeclarationValue.Set(listOf(array, array))))
        }
    }

    @Test
    fun `same priority nested property and dotted patch conflicts are detected`() {
        assertThrows<QuerySchemaConflictException> {
            merger.merge(
                system(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(
                        100,
                        QuerySchemaDeclaration(
                            mapOf(
                                QueryField("state.values") to QueryFieldDeclaration(properties = DeclarationValue.Set(mapOf("name" to QueryFieldDeclaration(title = DeclarationValue.Set("A"))))),
                                QueryField("state.values.name") to QueryFieldDeclaration(title = DeclarationValue.Set("B")),
                            )
                        )
                    )
                )
            )
        }
    }

    private fun stringMask() = QueryFieldDeclaration(
        valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
        maskRule = DeclarationValue.Set(fullMaskRule()),
    )

    private fun mergeTrees(vararg declarations: QueryFieldDeclaration): LogicalQuerySchema = merger.merge(
        system(),
        declarations.mapIndexed { index, declaration ->
            PrioritizedQuerySchemaDeclaration(
                index,
                QuerySchemaDeclaration(mapOf(QueryField("state.values") to declaration))
            )
        }
    )

    private fun system(): QuerySchemaDeclaration =
        SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT)

    private fun eventStreamSystem(): QuerySchemaDeclaration =
        SystemQuerySchemaSource.declaration(QueryModel.EVENT_STREAM)

    private fun declaration(
        field: String,
        valueTypes: Set<QueryValueType>? = null,
        semanticType: QuerySemanticType? = null,
        maskRule: MaskRule? = null,
    ): QuerySchemaDeclaration =
        QuerySchemaDeclaration(
            mapOf(
                QueryField(field) to QueryFieldDeclaration(
                    valueTypes = valueTypes?.let { DeclarationValue.Set(it) } ?: DeclarationValue.Unset,
                    semanticType = semanticType?.let { DeclarationValue.Set(it) } ?: DeclarationValue.Unset,
                    maskRule = maskRule?.let { DeclarationValue.Set(it) } ?: DeclarationValue.Unset,
                ),
            ),
        )

    private fun fullMaskRule(): MaskRule {
        val annotation = Masked::secret.javaField!!.getAnnotation(Mask::class.java)
        return MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
    }

    private fun keepMaskRule(): MaskRule {
        val annotation = Masked::keep.javaField!!.getAnnotation(KeepMask::class.java)
        return MaskRule(KeepMaskStrategy::class, annotation, KeepMaskStrategy.compile(annotation))
    }

    private fun title(value: String): QuerySchemaDeclaration =
        QuerySchemaDeclaration(
            mapOf(QueryField("state.name") to QueryFieldDeclaration(title = DeclarationValue.Set(value))),
        )

    private fun bodyType(declaration: QueryFieldDeclaration): QuerySchemaDeclaration = QuerySchemaDeclaration(
        mapOf(
            QueryField("body.bodyType") to declaration,
        ),
    )

    private fun enumValues(vararg values: String) = values.map(JsonNodeFactory.instance::stringNode)

    private data class Masked(
        @field:Mask val secret: String,
        @field:KeepMask val keep: String,
    )
}
