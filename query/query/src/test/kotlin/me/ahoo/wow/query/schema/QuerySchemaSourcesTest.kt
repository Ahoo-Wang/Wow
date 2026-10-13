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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryField
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.JsonNodeFactory
import java.util.concurrent.TimeUnit

class QuerySchemaSourcesTest {
    @Test
    fun `bean source should not leak registrations across contexts`() {
        runTest {
            val orderDeclaration = title("Order")
            val cartDeclaration = title("Cart")
            val otherModelDeclaration = title("Other model")
            val source = BeanQuerySchemaSource(
                listOf(
                    QuerySchemaRegistration(ORDER_CONTEXT, orderDeclaration),
                    QuerySchemaRegistration(CART_CONTEXT, cartDeclaration),
                    QuerySchemaRegistration(ORDER_CONTEXT.copy(model = QueryModel("CUSTOM")), otherModelDeclaration),
                ),
            )

            source.priority.assert().isEqualTo(QuerySchemaSourcePriority.BEAN)
            source.load(ORDER_CONTEXT).assert().containsExactly(orderDeclaration)
            source.refresh(CART_CONTEXT).assert().containsExactly(cartDeclaration)
            source.load(ORDER_CONTEXT.copy(aggregateType = AggregateType("test-context", "other"))).assert().isEmpty()
        }
    }

    @Test
    fun `bean source snapshots its registrations`() {
        runTest {
            val registrations = mutableListOf(QuerySchemaRegistration(ORDER_CONTEXT, title("Order")))
            val source = BeanQuerySchemaSource(registrations)
            registrations.clear()

            source.load(ORDER_CONTEXT).assert().hasSize(1)
        }
    }

    @Test
    fun `the manifest sits below the beans`() {
        (QuerySchemaSourcePriority.MANIFEST < QuerySchemaSourcePriority.BEAN).assert().isTrue()
    }

    @Test
    fun `bean declarations at one priority merge when equal and conflict when different`() {
        runTest {
            val equal = DefaultQueryModelSchemaProvider(
                ORDER_CONTEXT,
                listOf(
                    BeanQuerySchemaSource(listOf(QuerySchemaRegistration(ORDER_CONTEXT, title("Name")))),
                    BeanQuerySchemaSource(listOf(QuerySchemaRegistration(ORDER_CONTEXT, title("Name")))),
                ),
                FIXTURE_ADAPTER,
            ).schema()
            equal.root.properties.getValue("state").properties.getValue("name").title.assert().isEqualTo("Name")
            equal.field(QueryField("state.name")).assert().isNotNull()

            val conflicting = DefaultQueryModelSchemaProvider(
                ORDER_CONTEXT,
                listOf(
                    BeanQuerySchemaSource(
                        listOf(
                            QuerySchemaRegistration(ORDER_CONTEXT, title("A")),
                            QuerySchemaRegistration(ORDER_CONTEXT, title("B")),
                        ),
                    ),
                ),
                FIXTURE_ADAPTER,
            )
            assertThrows<QuerySchemaConflictException> { conflicting.schema() }
        }
    }

    @Test
    fun `Kotlin DSL expresses named array and dynamic value structure`() {
        val declaration = QuerySchemaDeclarationBuilder().apply {
            field("state.addresses") {
                property("home") {
                    items {
                        types(QueryValueType.STRING)
                        nullable(false)
                    }
                }
                values { items { types(QueryValueType.STRING) } }
            }
            field("state.status") {
                types(QueryValueType.STRING)
                enumValue("PAID", "Paid")
                enumValue("SHIPPED")
            }
            field("state.placedOn") {
                types(QueryValueType.STRING)
                temporalFormatted("yyyy-MM-dd")
            }
            field("state.paidAt") {
                types(QueryValueType.INTEGER)
                temporalEpoch()
            }
        }.build()
        val value = declaration.fields.getValue(QueryField("state.addresses"))
        value.properties.valueOr(
            emptyMap()
        ).getValue("home").items.valueOr(null)!!.nullable.assert().isEqualTo(DeclarationValue.Set(false))
        value.additionalProperties.valueOr(
            null
        )!!.items.valueOr(null)!!.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
        val status = declaration.fields.getValue(QueryField("state.status"))
        status.enumValues.valueOr(null)!!.map { it.stringValue() }.assert().containsExactly("PAID", "SHIPPED")
        status.enumDescriptions.valueOr(emptyMap()).values.assert().containsExactly("Paid")
        declaration.fields.getValue(QueryField("state.placedOn")).semanticType.assert()
            .isEqualTo(DeclarationValue.Set(Temporal.Formatted("yyyy-MM-dd")))
        declaration.fields.getValue(QueryField("state.paidAt")).semanticType.assert()
            .isEqualTo(DeclarationValue.Set(Temporal.Epoch(TimeUnit.MILLISECONDS)))
    }

    @Test
    fun `the querySchema builder declares title, required, aliases and deprecation`() {
        val declaration = querySchema {
            field("state.amount") {
                title("Amount")
                required(true)
                aliases("state.total", "state.sum")
                deprecated("Use state.price.")
            }
            field("state.legacy") { deprecated() }
        }
        val amount = declaration.fields.getValue(QueryField("state.amount"))
        amount.title.assert().isEqualTo(DeclarationValue.Set("Amount"))
        amount.required.assert().isEqualTo(DeclarationValue.Set(true))
        amount.aliases.assert().isEqualTo(
            DeclarationValue.Set(setOf(QueryField("state.total"), QueryField("state.sum")))
        )
        amount.deprecated.assert().isEqualTo(DeclarationValue.Set(QueryDeprecation("Use state.price.")))
        declaration.fields.getValue(QueryField("state.legacy")).deprecated.assert()
            .isEqualTo(DeclarationValue.Set(QueryDeprecation()))
    }

    @Test
    fun `the querySchema builder takes built item and map value declarations`() {
        val item = QueryFieldDeclarationBuilder().apply { types(QueryValueType.STRING) }.build()
        val declaration = querySchema {
            field("state.tags") { items(item) }
            field("state.labels") { values(item) }
        }
        declaration.fields.getValue(QueryField("state.tags")).items.assert().isEqualTo(DeclarationValue.Set(item))
        declaration.fields.getValue(QueryField("state.labels")).additionalProperties.assert()
            .isEqualTo(DeclarationValue.Set(item))
    }

    @Suppress("LongMethod")
    @Test
    fun `repeated blocks merge when they agree and conflict when they do not`() {
        val declaration = querySchema {
            field("state.address") { property("city") { types(QueryValueType.STRING) } }
            field("state.address") {
                property("city") { title("City") }
                property("zip") { types(QueryValueType.STRING) }
            }
            field("state.name") {
                title("Name")
                title("Name")
            }
            field("state.code") {
                property("value") { types(QueryValueType.STRING) }
                property("value") { title("Code") }
            }
        }
        val address = declaration.fields.getValue(QueryField("state.address")).properties.valueOr(emptyMap())
        address.keys.assert().containsExactly("city", "zip")
        address.getValue("city").title.assert().isEqualTo(DeclarationValue.Set("City"))
        address.getValue("city").valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
        declaration.fields.getValue(QueryField("state.code")).properties.valueOr(emptyMap()).getValue("value")
            .title.assert().isEqualTo(DeclarationValue.Set("Code"))

        listOf<QueryFieldDeclarationBuilder.() -> Unit>(
            {
                title("A")
                title("B")
            },
            {
                required(true)
                required(false)
            },
            {
                nullable(true)
                nullable(false)
            },
            {
                description("A")
                description("B")
            },
            {
                aliases("state.a")
                aliases("state.b")
            },
            {
                deprecated("A")
                deprecated("B")
            },
            {
                kind(QueryValueKind.OBJECT)
                kind(QueryValueKind.ARRAY)
            },
            {
                types(QueryValueType.STRING)
                types(QueryValueType.INTEGER)
            },
            {
                items { types(QueryValueType.STRING) }
                items { types(QueryValueType.INTEGER) }
            },
            {
                values { types(QueryValueType.STRING) }
                values { types(QueryValueType.INTEGER) }
            },
            {
                property("a") { title("A") }
                property("a") { title("B") }
            },
        ).forEach { block ->
            assertThrows<QuerySchemaConflictException> { querySchema { field("state.value", block) } }
        }
    }

    @Test
    fun `the builder rejects what a declaration cannot state`() {
        assertThrows<IllegalArgumentException> { QueryFieldDeclarationBuilder().types() }
        assertThrows<IllegalArgumentException> { QueryFieldDeclarationBuilder().types(QueryValueType.OBJECT) }
        assertThrows<IllegalArgumentException> { QueryFieldDeclarationBuilder().kind(QueryValueKind.NULL) }
        assertThrows<IllegalArgumentException> { QueryFieldDeclarationBuilder().property("a.b") {} }
        assertThrows<IllegalArgumentException> {
            QueryFieldDeclarationBuilder().apply {
                enumValue("A")
                enumValue("A", "again")
            }
        }
        assertThrows<IllegalArgumentException> { querySchema { field("state..name") {} } }
    }

    @Test
    fun `enum values are declared as JSON values, with their descriptions`() {
        val declaration = QueryFieldDeclarationBuilder().apply {
            enumValue(1, "One")
            enumValue(true)
            enumValue(null, "None")
        }.build()
        val nodes = JsonNodeFactory.instance
        declaration.enumValues.assert()
            .isEqualTo(DeclarationValue.Set(listOf(nodes.numberNode(1), nodes.booleanNode(true), nodes.nullNode())))
        declaration.enumDescriptions.assert()
            .isEqualTo(DeclarationValue.Set(mapOf(nodes.numberNode(1) to "One", nodes.nullNode() to "None")))
    }

    @Test
    fun `a declaration value is set or left unset`() {
        DeclarationValue.Set("a").valueOr("b").assert().isEqualTo("a")
        DeclarationValue.Unset.valueOr("b").assert().isEqualTo("b")
        DeclarationValue.Set<String?>(null).valueOr("b").assert().isNull()
    }

    private fun title(value: String): QuerySchemaDeclaration =
        QuerySchemaDeclaration(
            mapOf(QueryField("state.name") to QueryFieldDeclaration(title = DeclarationValue.Set(value))),
        )

    companion object {
        private val ORDER_CONTEXT = QuerySchemaContext(
            AggregateType("test-context", "order"),
            QueryModel.SNAPSHOT,
        )
        private val CART_CONTEXT = QuerySchemaContext(
            AggregateType("test-context", "cart"),
            QueryModel.SNAPSHOT,
        )

        /** Grants every path of the logical schema the fixture's capabilities. */
        private val FIXTURE_ADAPTER = object : QueryStorageAdapter {
            override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts {
                val bound = boundSchemaFixture(logicalSchema.root)
                return QueryStorageFacts(bound.bindings, bound.capabilities)
            }
        }
    }
}
