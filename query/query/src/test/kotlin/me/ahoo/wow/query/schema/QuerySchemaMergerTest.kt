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
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.query.error.QueryErrors
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

@Suppress("LargeClass")
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

        exception.errorCode.assert().isEqualTo(QueryErrors.FAILED)
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
        field.enumDescriptions.assert().isEmpty()
        field.valueTypes.assert().isEmpty()
        field.nullable.assert().isTrue()
        field.required.assert().isFalse()
        field.cardinality.assert().isNull()
        field.semanticType.assert().isNull()
        field.additionalProperties.assert().isNull()
        field.variant.assert().isNull()
        field.aliases.assert().isEmpty()
        field.deprecated.assert().isNull()
    }

    @Test
    fun `every declared leaf materializes into the logical value`() {
        val declaration = querySchema {
            field("state.status") {
                title("Status")
                description("Current status")
                types(QueryValueType.STRING)
                nullable(false)
                required(true)
                enumValue("OPEN", "Open")
                enumValue("CLOSED")
                aliases("state.phase")
                deprecated("Use state.stage.")
            }
        }
        val status = merger.merge(system(), listOf(PrioritizedQuerySchemaDeclaration(300, declaration)))
            .value(QueryField("state.status").toPathTemplate())!!

        status.title.assert().isEqualTo("Status")
        status.description.assert().isEqualTo("Current status")
        status.kind.assert().isEqualTo(QueryValueKind.SCALAR)
        status.nullable.assert().isFalse()
        status.required.assert().isTrue()
        status.enumValues!!.map { it.stringValue() }.assert().containsExactly("OPEN", "CLOSED")
        status.enumDescriptions.values.assert().containsExactly("Open")
        status.aliases.assert().containsExactly(QueryField("state.phase"))
        status.deprecated.assert().isEqualTo(QueryDeprecation("Use state.stage."))
    }

    @Test
    fun `snapshot state descendants should be allowed`() {
        merger.merge(
            system(),
            listOf(PrioritizedQuerySchemaDeclaration(300, title("Name"))),
        ).value(QueryField("state.name").toPathTemplate()).assert().isNotNull()
    }

    @Test
    fun `the payload root itself may be declared`() {
        val state = merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(
                    300,
                    QuerySchemaDeclaration(
                        mapOf(QueryField("state") to QueryFieldDeclaration(title = DeclarationValue.Set("State")))
                    ),
                ),
            ),
        ).value(QueryField("state").toPathTemplate())!!
        state.title.assert().isEqualTo("State")
        state.required.assert().isTrue()
        state.nullable.assert().isFalse()
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

        exception.errorCode.assert().isEqualTo(QueryErrors.FAILED)
        // A sibling whose name only starts with the payload root's is outside it.
        assertThrows<QuerySchemaConflictException> {
            merger.merge(
                system(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(
                        300,
                        QuerySchemaDeclaration(mapOf(QueryField("stateful") to QueryFieldDeclaration())),
                    ),
                ),
            )
        }
    }

    @Test
    fun `without a payload root extensions may declare fields anywhere`() {
        val schema = merger.merge(
            QuerySchemaDeclaration(emptyMap()),
            listOf(
                PrioritizedQuerySchemaDeclaration(
                    300,
                    QuerySchemaDeclaration(
                        mapOf(
                            QueryField("region") to QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING))),
                            QueryField("amount") to QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.DECIMAL))),
                        ),
                    ),
                ),
            ),
        )
        schema.root.properties.keys.assert().containsExactlyInAnyOrder("region", "amount")
        schema.value(
            QueryField("amount").toPathTemplate()
        )!!.valueTypes.assert().containsExactly(QueryValueType.DECIMAL)
        assertThrows<QuerySchemaConflictException> {
            merger.merge(
                system(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(
                        300,
                        QuerySchemaDeclaration(mapOf(QueryField("region") to QueryFieldDeclaration())),
                    ),
                ),
                extensionRoot = "state",
            )
        }
        merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(
                    300,
                    QuerySchemaDeclaration(
                        mapOf(
                            QueryField("tags.region") to QueryFieldDeclaration(title = DeclarationValue.Set("Region"))
                        )
                    ),
                ),
            ),
            extensionRoot = null,
        ).value(QueryField("tags.region").toPathTemplate())!!.title.assert().isEqualTo("Region")
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
    fun `array and map nodes retain independent value nullability`() {
        val schema = mergeTrees(
            QueryFieldDeclaration(
                additionalProperties = DeclarationValue.Set(
                    QueryFieldDeclaration(
                        nullable = DeclarationValue.Set(true),
                        items = DeclarationValue.Set(
                            QueryFieldDeclaration(
                                nullable = DeclarationValue.Set(false),
                                properties = DeclarationValue.Set(mapOf("secret" to string())),
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
        item.properties.getValue("secret").valueTypes.assert().containsExactly(QueryValueType.STRING)
    }

    @Test
    fun `items and map values are never required, whatever they declare`() {
        val schema = mergeTrees(
            QueryFieldDeclaration(
                additionalProperties = DeclarationValue.Set(
                    QueryFieldDeclaration(
                        required = DeclarationValue.Set(true),
                        items = DeclarationValue.Set(string().copy(required = DeclarationValue.Set(true))),
                    ),
                ),
            ),
        )
        val array = schema.root.properties.getValue("state").properties.getValue("values").additionalProperties!!
        array.required.assert().isFalse()
        array.items!!.required.assert().isFalse()
    }

    @Test
    fun `structure replacement replaces the whole value of a map`() {
        val lower = QueryFieldDeclaration(
            additionalProperties = DeclarationValue.Set(
                QueryFieldDeclaration(
                    properties = DeclarationValue.Set(
                        mapOf("secret" to string(), "old" to QueryFieldDeclaration())
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
        value.properties.getValue("secret").valueTypes.assert().containsExactly(QueryValueType.STRING)
    }

    @Test
    fun `named properties merge by name across priorities`() {
        val lower = QueryFieldDeclaration(
            properties = DeclarationValue.Set(mapOf("a" to string(), "b" to QueryFieldDeclaration())),
        )
        val higher = QueryFieldDeclaration(
            properties = DeclarationValue.Set(mapOf("a" to QueryFieldDeclaration(title = DeclarationValue.Set("A")))),
        )
        val values = mergeTrees(lower, higher).root.properties.getValue("state").properties.getValue("values")
        values.properties.keys.assert().containsExactly("a", "b")
        values.properties.getValue("a").title.assert().isEqualTo("A")
        values.properties.getValue("a").valueTypes.assert().containsExactly(QueryValueType.STRING)
    }

    @Test
    fun `union alternatives preserve distinct shapes`() {
        val array =
            QueryFieldDeclaration(
                items = DeclarationValue.Set(
                    QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)))
                )
            )
        val lower = QueryFieldDeclaration(alternatives = DeclarationValue.Set(listOf(string(), array)))
        val schema = mergeTrees(lower)
        val union = schema.root.properties.getValue("state").properties.getValue("values")
        union.kind.assert().isEqualTo(QueryValueKind.UNION)
        union.cardinality.assert().isNull()
        union.alternatives.map { it.kind }.assert().isEqualTo(listOf(QueryValueKind.SCALAR, QueryValueKind.ARRAY))
    }

    @Test
    fun `a non-null union rejects nullable alternatives and an invalid structure is a conflict`() {
        assertThrows<QuerySchemaConflictException> {
            mergeTrees(
                QueryFieldDeclaration(
                    nullable = DeclarationValue.Set(false),
                    alternatives = DeclarationValue.Set(
                        listOf(string(), QueryFieldDeclaration(kind = DeclarationValue.Set(QueryValueKind.NULL)))
                    ),
                ),
            )
        }
        // An array cannot have properties.
        assertThrows<QuerySchemaConflictException> {
            mergeTrees(
                QueryFieldDeclaration(
                    kind = DeclarationValue.Set(QueryValueKind.ARRAY),
                    items = DeclarationValue.Set(string()),
                    properties = DeclarationValue.Set(mapOf("a" to string())),
                ),
            )
        }.cause.assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `dotted patches reach through arrays and stop at scalars`() {
        val schema = merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(
                    100,
                    QuerySchemaDeclaration(
                        mapOf(
                            QueryField("state.lines") to QueryFieldDeclaration(
                                items = DeclarationValue.Set(
                                    QueryFieldDeclaration(properties = DeclarationValue.Set(mapOf("sku" to string()))),
                                ),
                            ),
                        ),
                    ),
                ),
                PrioritizedQuerySchemaDeclaration(
                    300,
                    QuerySchemaDeclaration(
                        mapOf(
                            QueryField("state.lines.sku") to QueryFieldDeclaration(title = DeclarationValue.Set("SKU"))
                        ),
                    ),
                ),
            ),
        )
        val sku = schema.root.properties.getValue(
            "state"
        ).properties.getValue("lines").items!!.properties.getValue("sku")
        sku.title.assert().isEqualTo("SKU")
        sku.valueTypes.assert().containsExactly(QueryValueType.STRING)

        assertThrows<QuerySchemaConflictException> {
            merger.merge(
                system(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(
                        100,
                        QuerySchemaDeclaration(mapOf(QueryField("state.name") to string())),
                    ),
                    PrioritizedQuerySchemaDeclaration(
                        300,
                        QuerySchemaDeclaration(
                            mapOf(
                                QueryField("state.name.first") to QueryFieldDeclaration(title = DeclarationValue.Set("First"))
                            ),
                        ),
                    ),
                ),
            )
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

    /** An opaque value, as the Manifest states a JSON-typed property: an unknown kind and no value type. */
    private val opaque = QueryFieldDeclaration(
        kind = DeclarationValue.Set(QueryValueKind.UNKNOWN),
        valueTypes = DeclarationValue.Set(emptySet()),
    )

    private fun manifestAndDeclared(manifest: QueryFieldDeclaration, declared: QuerySchemaDeclaration) = merger.merge(
        system(),
        listOf(
            PrioritizedQuerySchemaDeclaration(
                QuerySchemaSourcePriority.MANIFEST,
                QuerySchemaDeclaration(mapOf(QueryField("state.config") to manifest)),
            ),
            PrioritizedQuerySchemaDeclaration(QuerySchemaSourcePriority.BEAN, declared),
        ),
    )

    @Test
    fun `a declaration of kind OBJECT opens an opaque value as an object`() {
        val declared = QuerySchemaDeclarationBuilder().apply {
            field("state.config") {
                kind(QueryValueKind.OBJECT)
                property("kind") { types(QueryValueType.STRING) }
            }
        }.build()
        val config = manifestAndDeclared(
            opaque,
            declared
        ).root.properties.getValue("state").properties.getValue("config")
        config.kind.assert().isEqualTo(QueryValueKind.OBJECT)
        config.valueTypes.assert().isEqualTo(setOf(QueryValueType.OBJECT))
        config.properties.getValue("kind").valueTypes.assert().isEqualTo(setOf(QueryValueType.STRING))
    }

    @Test
    fun `opening an opaque value compiles as stating its object value type did`() {
        val declared = QuerySchemaDeclarationBuilder().apply {
            field("state.config") {
                kind(QueryValueKind.OBJECT)
                property("kind") { types(QueryValueType.STRING) }
            }
        }.build()
        // How a declaration had to open one before 9.3.0: edit the value types of the built declaration by hand.
        val handEdited = QuerySchemaDeclaration(
            declared.fields.mapValues { (_, field) ->
                field.copy(valueTypes = DeclarationValue.Set(setOf(QueryValueType.OBJECT)))
            }
        )
        val opened = manifestAndDeclared(opaque, declared)
        val edited = manifestAndDeclared(opaque, handEdited)
        opened.values.keys.assert().isEqualTo(edited.values.keys)
        opened.values.forEach { (path, value) ->
            val reference = edited.values.getValue(path)
            value.kind.assert().isEqualTo(reference.kind)
            value.valueTypes.assert().isEqualTo(reference.valueTypes)
            value.nullable.assert().isEqualTo(reference.nullable)
            value.properties.keys.assert().isEqualTo(reference.properties.keys)
        }
    }

    @Test
    fun `a declaration of kind OBJECT still cannot turn a scalar into an object`() {
        val declared = QuerySchemaDeclarationBuilder().apply {
            field("state.config") {
                kind(QueryValueKind.OBJECT)
                property("kind") { types(QueryValueType.STRING) }
            }
        }.build()
        val scalar = QueryFieldDeclaration(
            kind = DeclarationValue.Set(QueryValueKind.SCALAR),
            valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
        )
        assertThrows<QuerySchemaConflictException> { manifestAndDeclared(scalar, declared) }
    }

    private fun string() = QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)))

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

    private fun declaration(
        field: String,
        valueTypes: Set<QueryValueType>? = null,
        semanticType: QuerySemanticType? = null,
    ): QuerySchemaDeclaration =
        QuerySchemaDeclaration(
            mapOf(
                QueryField(field) to QueryFieldDeclaration(
                    valueTypes = valueTypes?.let { DeclarationValue.Set(it) } ?: DeclarationValue.Unset,
                    semanticType = semanticType?.let { DeclarationValue.Set(it) } ?: DeclarationValue.Unset,
                ),
            ),
        )

    private fun title(value: String): QuerySchemaDeclaration =
        QuerySchemaDeclaration(
            mapOf(QueryField("state.name") to QueryFieldDeclaration(title = DeclarationValue.Set(value))),
        )
}
