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

internal class QuerySchemaMergerTest : QuerySchemaMergerFixtures() {
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
                extensionRoots = setOf("state"),
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
            extensionRoots = null,
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
