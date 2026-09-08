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
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.tck.mock.MockCommandAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.JsonNodeFactory
import java.util.concurrent.TimeUnit

class QueryModelSchemaTest {
    @Test
    fun `indexed static fields preserve array container and every unnamed item scope`() {
        val leaf = scalarFixture()
        val outer = arrayFixture(arrayFixture(objectFixture("city" to leaf)))
        val schema = boundSchemaFixture(objectFixture("addresses" to outer))
        schema.field(QueryField("addresses"))!!.value.assert().isSameAs(outer)
        val city = schema.field(QueryField("addresses.city"))!!
        city.value.assert().isSameAs(leaf)
        city.elementAncestors.assert().isEqualTo(listOf(QueryField("addresses"), QueryField("addresses")))
        city.binding(
            QueryCapability.EXACT_MATCH
        )!!.physicalField.assert().isEqualTo(QueryField("native.addresses.city"))
    }

    @Test
    fun `indexed union descendants keep incomplete unknown witnesses`() {
        val known = objectFixture("city" to scalarFixture())
        val union = QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(known, QueryValueSchema(QueryValueKind.UNKNOWN)),
        )
        val schema = boundSchemaFixture(objectFixture("address" to union))
        val city = schema.field(QueryField("address.city"))!!
        city.value.kind.assert().isEqualTo(QueryValueKind.UNION)
        city.value.alternatives.map { it.kind }.assert().contains(QueryValueKind.UNKNOWN)
        city.bindings.assert().isEmpty()
        schema.field(QueryField("address.unknown")).assert().isNull()
    }

    @Test
    fun `native fields are explicit and public logical paths are never native aliases`() {
        val schema = boundSchemaFixture(objectFixture("name" to scalarFixture()))
        schema.physicalField(
            QueryField("name"),
            QueryCapability.EXACT_MATCH
        ).assert().isEqualTo(QueryField("native.name"))
        schema.projectionField(QueryField("name")).assert().isEqualTo(QueryField("native.name"))
        listOf("missing", "native.name").forEach { path ->
            assertThrows<QuerySchemaValidationException> {
                schema.physicalField(
                    QueryField(path),
                    QueryCapability.EXACT_MATCH
                )
            }
            assertThrows<QuerySchemaValidationException> { schema.projectionField(QueryField(path)) }
        }
    }

    @Test
    fun `element lookups return absolute native bindings and require the declared logical scope`() {
        val schema = boundSchemaFixture(
            objectFixture("orders" to arrayFixture(objectFixture("price" to scalarFixture(QueryValueType.INTEGER))))
        )
        schema.physicalField(QueryField("price"), QueryCapability.RANGE, QueryField("orders")).assert()
            .isEqualTo(QueryField("native.orders.price"))
        schema.projectionField(QueryField("orders.price")).assert().isEqualTo(QueryField("native.orders.price"))
        assertThrows<QuerySchemaValidationException> {
            schema.physicalField(
                QueryField("orders.price"),
                QueryCapability.RANGE
            )
        }
        assertThrows<QuerySchemaValidationException> {
            schema.physicalField(
                QueryField("orders.price"),
                QueryCapability.RANGE,
                QueryField("orders")
            )
        }
    }

    @Test
    fun `dynamic values have their own definition and exact key bindings override a map default`() {
        val root = objectFixture(
            "counts" to QueryValueSchema(QueryValueKind.OBJECT, additionalProperties = scalarFixture(QueryValueType.INTEGER))
        )
        val base = boundSchemaFixture(root)
        val special = QueryPathTemplate(listOf(QueryPathSegment.Property("counts"), QueryPathSegment.Property("home")))
        val schema = QueryModelSchema(
            base.model,
            base.capabilities,
            base.definition,
            base.bindings + (special to QueryValueBindings())
        )
        schema.field(QueryField("counts.work"))!!.value.valueTypes.assert().containsExactly(QueryValueType.INTEGER)
        schema.physicalField(
            QueryField("counts.work"),
            QueryCapability.EXACT_MATCH
        ).assert().isEqualTo(QueryField("native.counts.work"))
        schema.field(QueryField("counts.work.extra")).assert().isNull()
        assertThrows<QuerySchemaValidationException> {
            schema.physicalField(
                QueryField("counts.home"),
                QueryCapability.EXACT_MATCH
            )
        }
    }

    @Test
    fun `binding snapshot preserves shared values and cannot reference undeclared paths`() {
        val definition = LogicalQuerySchema(objectFixture("name" to scalarFixture()))
        val native = linkedMapOf<QueryPathTemplate, QueryValueBindings>()
        val schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), definition, native)
        native[QueryField("missing").toPathTemplate()] = QueryValueBindings()
        schema.definition.assert().isSameAs(definition)
        schema.bindings.containsKey(QueryField("missing").toPathTemplate()).assert().isFalse()
        assertThrows<IllegalArgumentException> { QueryModelSchema(schema.model, emptySet(), definition, native) }
    }

    @Test
    fun `storage type should reject unsafe identifiers`() {
        assertThrows<IllegalArgumentException> { QueryStorageType("keyword.raw") }
        QueryStorageType("keyword-raw").value.assert().isEqualTo("keyword-raw")
    }

    @Test
    fun `field DSL should set only explicitly called leaves`() {
        val declaration = QuerySchemaDeclarationBuilder().apply {
            field("state.createdAt") {
                valueTypes(QueryValueType.INTEGER)
                temporalEpoch(TimeUnit.SECONDS)
            }
        }.build().fields.getValue(QueryField("state.createdAt"))

        declaration.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
        declaration.semanticType.assert().isEqualTo(DeclarationValue.Set(Temporal.Epoch(TimeUnit.SECONDS)))
        declaration.title.assert().isEqualTo(DeclarationValue.Unset)
        declaration.nullable.assert().isEqualTo(DeclarationValue.Unset)
        declaration.kind.assert().isEqualTo(DeclarationValue.Unset)
    }

    @Test
    fun `field DSL should declare every explicitly called leaf`() {
        val open = JsonNodeFactory.instance.stringNode("OPEN")
        val declaration = QuerySchemaDeclarationBuilder().apply {
            field("state.status") {
                title("Status")
                description("Current status")
                enumValues(listOf(open))
                valueTypes(QueryValueType.STRING)
                nullable(false)
                required(true)
                kind(QueryValueKind.SCALAR)
                semanticType(Temporal.Date)
            }
        }.build().fields.getValue(QueryField("state.status"))

        declaration.assert().isEqualTo(
            QueryFieldDeclaration(
                title = DeclarationValue.Set("Status"),
                description = DeclarationValue.Set("Current status"),
                enumValues = DeclarationValue.Set(listOf(open)),
                valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
                nullable = DeclarationValue.Set(false),
                required = DeclarationValue.Set(true),
                kind = DeclarationValue.Set(QueryValueKind.SCALAR),
                semanticType = DeclarationValue.Set(Temporal.Date),
            ),
        )
    }

    @Test
    fun `duplicate field blocks should reject different values for one leaf`() {
        val exception = assertThrows<QuerySchemaConflictException> {
            QuerySchemaDeclarationBuilder().apply {
                field("state.name") { title("Name") }
                field("state.name") { title("Display name") }
            }.build()
        }

        exception.errorCode.assert().isEqualTo(QuerySchemaConflictException.ERROR_CODE)
    }

    @Test
    fun `registration DSL should materialize aggregate context`() {
        val registration = querySchemaRegistration(MockCommandAggregate::class, QueryModel.SNAPSHOT) {
            field("state.name") { title("Name") }
        }

        registration.context.model.assert().isEqualTo(QueryModel.SNAPSHOT)
        registration.context.namedAggregate.aggregateName.assert().isEqualTo("mock_aggregate")
        registration.declaration.fields.getValue(QueryField("state.name")).title
            .assert().isEqualTo(DeclarationValue.Set("Name"))
    }

    @Test
    fun `query schema exceptions should preserve causes and exact error codes`() {
        val cause = IllegalStateException("cause")

        listOf(
            QuerySchemaValidationException("validation", cause) to "QuerySchemaValidation",
            QuerySchemaConflictException("conflict", cause) to "QuerySchemaConflict",
            QuerySchemaUnavailableException("unavailable", cause) to "QuerySchemaUnavailable",
        ).forEach { (exception, errorCode) ->
            exception.errorCode.assert().isEqualTo(errorCode)
            exception.cause.assert().isSameAs(cause)
        }
    }
}
