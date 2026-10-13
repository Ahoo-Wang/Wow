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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.QueryBindingError
import me.ahoo.wow.query.QueryErrors
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
    fun `union alternatives bound to one physical field share it and merge their storage types`() {
        val union = QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(
                objectFixture("code" to scalarFixture(QueryValueType.STRING)),
                objectFixture("code" to scalarFixture(QueryValueType.INTEGER)),
            ),
            nullable = true,
        )
        val definition = LogicalQuerySchema(objectFixture("address" to union))
        val physical = QueryPathTemplate(listOf(QueryPathSegment.Property("code")))
        val keyword = QueryStorageType("keyword")
        val long = QueryStorageType("long")
        val bindings = mapOf(
            QueryField("address.code").toPathTemplate() to QueryValueBindings(
                mapOf(QueryCapability.EXACT_MATCH to QueryFieldBindingTemplate(physical, setOf(keyword))),
                projectionPath = physical,
            ),
        )
        val code = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), definition, bindings)
            .field(QueryField("address.code"))!!
        code.value.kind.assert().isEqualTo(QueryValueKind.UNION)
        code.binding(QueryCapability.EXACT_MATCH)!!.physicalField.assert().isEqualTo(QueryField("code"))
        code.binding(QueryCapability.EXACT_MATCH)!!.storageTypes.assert().containsExactly(keyword)
        code.elementAncestors.assert().isEqualTo(emptyList<QueryField>())
        code.projectionField.assert().isEqualTo(QueryField("code"))
        code.responseField.assert().isNull()

        val untyped = QueryModelSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            definition,
            mapOf(
                QueryField("address.code").toPathTemplate() to QueryValueBindings(
                    mapOf(QueryCapability.EXACT_MATCH to QueryFieldBindingTemplate(physical, null)),
                ),
            ),
        ).field(QueryField("address.code"))!!
        untyped.binding(QueryCapability.EXACT_MATCH)!!.storageTypes.assert().isNull()

        val typed = QueryFieldBinding(QueryField("code"), setOf(keyword, long))
        typed.storageTypes.assert().containsExactly(keyword, long)
        typed.assert().isEqualTo(QueryFieldBinding(QueryField("code"), setOf(keyword, long)))
        typed.hashCode().assert().isEqualTo(QueryFieldBinding(QueryField("code"), setOf(keyword, long)).hashCode())
        typed.assert().isNotEqualTo(QueryFieldBinding(QueryField("code"), null, QueryField("scope")))
        assertThrows<IllegalArgumentException> { QueryFieldBinding(QueryField("code"), emptySet()) }
        assertThrows<IllegalArgumentException> { QueryFieldBindingTemplate(physical, emptySet()) }
        QueryFieldBindingTemplate(physical, setOf(keyword)).assert()
            .isEqualTo(QueryFieldBindingTemplate(physical, setOf(keyword)))
        QueryFieldBindingTemplate(physical, setOf(keyword)).hashCode().assert()
            .isEqualTo(QueryFieldBindingTemplate(physical, setOf(keyword)).hashCode())
        QueryFieldBindingTemplate(physical, null).assert().isNotEqualTo(physical)
    }

    @Test
    fun `a field reached both inside and outside an element has no single element scope`() {
        val child = objectFixture("c" to scalarFixture())
        val union = QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(arrayFixture(child), child))
        val schema = boundSchemaFixture(objectFixture("x" to union))
        val field = schema.field(QueryField("x.c"))!!
        field.elementAncestors.assert().isNull()
        field.binding(QueryCapability.EXACT_MATCH)!!.physicalField.assert().isEqualTo(QueryField("native.x.c"))
        field.effective.cursorSortable.assert().isFalse()
    }

    @Test
    fun `native fields are explicit and public logical paths are never native aliases`() {
        val schema = boundSchemaFixture(objectFixture("name" to scalarFixture()))
        schema.physicalField(
            QueryField("name"),
            QueryCapability.EXACT_MATCH
        ).assert().isEqualTo(QueryField("native.name"))
        schema.projectionField(QueryField("name")).assert().isEqualTo(QueryField("native.name"))
        schema.field(QueryField("name"))!!.responseField.assert().isEqualTo(QueryField("name"))
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
        absoluteLogicalField(QueryField("price"), QueryField("orders")).assert()
            .isEqualTo(QueryField("orders.price"))
        absoluteLogicalField(QueryField("price"), null).assert().isEqualTo(QueryField("price"))
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
    fun `a dynamic field is resolved once per key`() {
        val schema = boundSchemaFixture(
            objectFixture(
                "counts" to QueryValueSchema(
                    QueryValueKind.OBJECT,
                    additionalProperties = scalarFixture(QueryValueType.INTEGER),
                ),
            ),
        )
        schema.field(QueryField("counts.work")).assert().isSameAs(schema.field(QueryField("counts.work")))
        schema.field(QueryField("counts.work.extra")).assert().isNull()
        schema.field(QueryField("counts.work"))!!.effective.assert()
            .isSameAs(schema.field(QueryField("counts.work"))!!.effective)
    }

    @Test
    fun `the dynamic field cache starts over when callers send more keys than it holds`() {
        val schema = boundSchemaFixture(
            objectFixture(
                "counts" to QueryValueSchema(
                    QueryValueKind.OBJECT,
                    additionalProperties = scalarFixture(QueryValueType.INTEGER),
                ),
            ),
        )
        val first = schema.field(QueryField("counts.k0"))!!
        schema.field(QueryField("counts.k0")).assert().isSameAs(first)

        (1..2048).forEach { index ->
            val field = QueryField("counts.k$index")
            schema.field(field)!!.logicalField.assert().isEqualTo(field)
        }

        val again = schema.field(QueryField("counts.k0"))!!
        again.assert().isNotSameAs(first)
        again.logicalField.assert().isEqualTo(first.logicalField)
        again.capabilities.assert().isEqualTo(first.capabilities)
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
    fun `native paths keep every logical map key and only some metrics are approximate`() {
        val definition = LogicalQuerySchema(
            objectFixture("counts" to mapFixture(scalarFixture(QueryValueType.INTEGER)))
        )
        val keyed = QueryPathTemplate(listOf(QueryPathSegment.Property("counts"), QueryPathSegment.Key(0)))
        val unkeyed = QueryPathTemplate(listOf(QueryPathSegment.Property("counts")))
        assertThrows<IllegalArgumentException> {
            QueryModelSchema(
                QueryModel.SNAPSHOT,
                emptySet(),
                definition,
                mapOf(keyed to QueryValueBindings(projectionPath = unkeyed)),
            )
        }
        assertThrows<IllegalArgumentException> {
            QueryModelSchema(
                QueryModel.SNAPSHOT,
                emptySet(),
                definition,
                mapOf(keyed to QueryValueBindings(responsePath = unkeyed)),
            )
        }
        assertThrows<IllegalArgumentException> {
            QueryModelSchema(
                QueryModel.SNAPSHOT,
                emptySet(),
                definition,
                mapOf(
                    keyed to QueryValueBindings(
                        mapOf(QueryCapability.EXACT_MATCH to QueryFieldBindingTemplate(unkeyed, null)),
                    ),
                ),
            )
        }
        assertThrows<IllegalArgumentException> {
            QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), definition, emptyMap(), approximateMetrics = setOf("SUM"))
        }
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            setOf(QueryCapability.FULL_TEXT_TERMS),
            definition,
            mapOf(keyed to QueryValueBindings(projectionPath = keyed, responsePath = keyed)),
            approximateMetrics = setOf("PERCENTILE"),
        )
        schema.approximateMetrics.assert().containsExactly("PERCENTILE")
        schema.supports(QueryCapability.FULL_TEXT_TERMS).assert().isTrue()
        schema.supports(QueryCapability.FULL_TEXT_PHRASE).assert().isFalse()
        schema.hasIncomparableFields.assert().isFalse()
        schema.hasAliases.assert().isFalse()
        schema.root.assert().isSameAs(definition.root)
        schema.profile.assert().isEqualTo(SnapshotQueryModelProfile)
        schema.field(QueryField("counts.home"))!!.projectionField.assert().isEqualTo(QueryField("counts.home"))
        schema.field(QueryField("counts.home"))!!.responseField.assert().isEqualTo(QueryField("counts.home"))
    }

    @Test
    fun `a field is never protected until sensitivity joins the logical definition`() {
        val field = boundSchemaFixture(objectFixture("name" to scalarFixture())).field(QueryField("name"))!!
        field.protection.assert().isNull()
        field.protected.assert().isFalse()
        field.comparable.assert().isTrue()
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
                types(QueryValueType.INTEGER)
                temporalEpoch(TimeUnit.SECONDS)
            }
        }.build().fields.getValue(QueryField("state.createdAt"))

        declaration.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
        declaration.semanticType.assert().isEqualTo(DeclarationValue.Set(Temporal.Epoch(TimeUnit.SECONDS)))
        declaration.title.assert().isEqualTo(DeclarationValue.Unset)
        declaration.nullable.assert().isEqualTo(DeclarationValue.Unset)
        declaration.kind.assert().isEqualTo(DeclarationValue.Unset)
        declaration.required.assert().isEqualTo(DeclarationValue.Unset)
        declaration.aliases.assert().isEqualTo(DeclarationValue.Unset)
        declaration.deprecated.assert().isEqualTo(DeclarationValue.Unset)
        declaration.enumValues.assert().isEqualTo(DeclarationValue.Unset)
        declaration.enumDescriptions.assert().isEqualTo(DeclarationValue.Unset)
    }

    @Test
    fun `field DSL should declare every explicitly called leaf`() {
        val open = JsonNodeFactory.instance.stringNode("OPEN")
        val declaration = QuerySchemaDeclarationBuilder().apply {
            field("state.status") {
                description("Current status")
                enumValue("OPEN", "Open")
                types(QueryValueType.STRING)
                nullable(false)
                kind(QueryValueKind.SCALAR)
                semantic(Temporal.Date)
            }
        }.build().fields.getValue(QueryField("state.status"))

        declaration.assert().isEqualTo(
            QueryFieldDeclaration(
                description = DeclarationValue.Set("Current status"),
                enumValues = DeclarationValue.Set(listOf(open)),
                enumDescriptions = DeclarationValue.Set(mapOf(open to "Open")),
                valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
                nullable = DeclarationValue.Set(false),
                kind = DeclarationValue.Set(QueryValueKind.SCALAR),
                semanticType = DeclarationValue.Set(Temporal.Date),
            ),
        )
        assertThrows<IllegalArgumentException> {
            QueryFieldDeclarationBuilder().kind(QueryValueKind.UNION)
        }
    }

    @Test
    fun `duplicate field blocks should reject different values for one leaf`() {
        val exception = assertThrows<QuerySchemaConflictException> {
            QuerySchemaDeclarationBuilder().apply {
                field("state.name") { description("Name") }
                field("state.name") { description("Display name") }
            }.build()
        }

        exception.errorCode.assert().isEqualTo(QueryErrors.FAILED)
    }

    @Test
    fun `registration DSL should materialize aggregate context`() {
        val aggregateType = AggregateType("example", "order")
        val registration = querySchemaRegistration(aggregateType, QueryModel.SNAPSHOT) {
            field("state.name") { description("Name") }
        }

        registration.context.model.assert().isEqualTo(QueryModel.SNAPSHOT)
        registration.context.aggregateType.assert().isEqualTo(aggregateType)
        registration.declaration.fields.getValue(QueryField("state.name")).description
            .assert().isEqualTo(DeclarationValue.Set("Name"))
    }

    @Test
    fun `query schema exceptions should preserve causes and exact error codes`() {
        val cause = IllegalStateException("cause")

        listOf(
            QuerySchemaValidationException("validation", cause) to QueryErrors.FAILED,
            QuerySchemaConflictException("conflict", cause) to QueryErrors.FAILED,
            QuerySchemaUnavailableException("unavailable", cause) to QueryErrors.FAILED,
        ).forEach { (exception, errorCode) ->
            exception.errorCode.assert().isEqualTo(errorCode)
            exception.errorMsg.assert().isEqualTo(exception.message)
            exception.cause.assert().isSameAs(cause)
            exception.bindingErrors.assert().isEmpty()
        }
    }

    @Test
    fun `a validation exception with a violation is a rejection naming the violated rule`() {
        val violation = QueryViolation.UnknownField(QueryField("state.missing"))
        val exception = QuerySchemaValidationException(violation)
        exception.errorCode.assert().isEqualTo(QueryErrors.INVALID)
        exception.violation.assert().isSameAs(violation)
        exception.message.assert().isEqualTo(violation.message)
        exception.bindingErrors.assert().containsExactly(
            QueryBindingError("state.missing", violation.message, QueryErrorCodes.UNKNOWN_FIELD),
        )
    }
}
