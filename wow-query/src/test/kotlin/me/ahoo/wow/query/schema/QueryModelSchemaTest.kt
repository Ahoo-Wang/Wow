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
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.tck.mock.MockCommandAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.module.kotlin.jsonMapper
import java.util.concurrent.TimeUnit
import kotlin.reflect.jvm.javaField

class QueryModelSchemaTest {
    private val jsonMapper = jsonMapper()

    @Test
    fun `exact field should win over a dynamic ancestor`() {
        val exact = fieldSchema(
            dynamicChildren = false,
            bindings = mapOf(QueryCapability.EXACT_MATCH to QueryFieldBinding("exact_name", null)),
        )
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            setOf(QueryCapability.EXACT_MATCH),
            mapOf(
                LogicalField("state") to fieldSchema(
                    dynamicChildren = true,
                    bindings = mapOf(QueryCapability.EXACT_MATCH to QueryFieldBinding("document", null)),
                ),
                LogicalField("state.name") to exact,
            ),
        )

        schema.resolve(LogicalField("state.name")).assert().isSameAs(exact)
    }

    @Test
    fun `nearest dynamic ancestor should append the relative logical suffix`() {
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            setOf(QueryCapability.EXACT_MATCH, QueryCapability.SORT),
            mapOf(
                LogicalField("state") to fieldSchema(
                    dynamicChildren = true,
                    bindings = mapOf(QueryCapability.EXACT_MATCH to QueryFieldBinding("document", null)),
                ),
                LogicalField("state.customer") to fieldSchema(
                    dynamicChildren = true,
                    bindings = mapOf(QueryCapability.SORT to QueryFieldBinding("customer_doc", QueryStorageType("keyword"))),
                    projectionPath = "customer_doc",
                ),
            ),
        )

        val resolved = schema.resolve(LogicalField("state.customer.address.city"))!!

        resolved.bindings.assert().isEqualTo(
            mapOf(
                QueryCapability.SORT to QueryFieldBinding(
                    "customer_doc.address.city",
                    QueryStorageType("keyword"),
                ),
            ),
        )
        resolved.projectionPath.assert().isEqualTo("customer_doc.address.city")
    }

    @Test
    fun `lookup should not invent a capability absent from a dynamic ancestor`() {
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            setOf(QueryCapability.EXACT_MATCH, QueryCapability.SORT),
            mapOf(
                LogicalField("state") to fieldSchema(
                    dynamicChildren = true,
                    bindings = mapOf(QueryCapability.EXACT_MATCH to QueryFieldBinding("document", null)),
                ),
            ),
        )

        schema.resolve(LogicalField("state.name"))!!.bindings.keys
            .assert().isEqualTo(setOf(QueryCapability.EXACT_MATCH))
    }

    @Test
    fun `dynamic suffix should not inherit element scope`() {
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(
                LogicalField("state.orders") to fieldSchema(
                    dynamicChildren = true,
                    bindings = mapOf(
                        QueryCapability.EXACT_MATCH to QueryFieldBinding("document.orders", null),
                        QueryCapability.ELEMENT_SCOPE to QueryFieldBinding("document.orders", null),
                    ),
                ),
            ),
        )

        schema.resolve(LogicalField("state.orders.items"))!!.bindings.keys.assert()
            .containsExactly(QueryCapability.EXACT_MATCH)
    }

    @Test
    fun `metadata projection should sort logical fields and omit binding details`() {
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            setOf(QueryCapability.EXACT_MATCH),
            linkedMapOf(
                LogicalField("state.z") to fieldSchema(
                    bindings = mapOf(
                        QueryCapability.EXACT_MATCH to QueryFieldBinding("private_z", QueryStorageType("keyword")),
                    ),
                    projectionPath = "private_source_z",
                ),
                LogicalField("state.a") to fieldSchema(title = "A"),
            ),
        )

        val metadata = schema.toMetadata()

        metadata.fields.map { it.field.value }.assert().containsExactly("state.a", "state.z")
        metadata.fields.last().capabilities.assert().isEqualTo(setOf(QueryCapability.EXACT_MATCH))
    }

    @Test
    fun `metadata should expose masked without executable mask details`() {
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(LogicalField("state.secret") to fieldSchema(maskRule = fullMaskRule())),
        )

        val metadata = schema.toMetadata()
        val json = jsonMapper.writeValueAsString(metadata)
        val directJson = jsonMapper.writeValueAsString(schema)

        metadata.fields.single().masked.assert().isTrue()
        json.contains("FullMaskStrategy").assert().isFalse()
        json.contains("maskRule").assert().isFalse()
        json.contains("annotation").assert().isFalse()
        directJson.contains("maskRule").assert().isFalse()
    }

    @Test
    fun `origin main constructor arities should remain unambiguous in Kotlin`() {
        val declaration = QueryFieldDeclaration(
            DeclarationValue.Unset,
            DeclarationValue.Unset,
            DeclarationValue.Unset,
            DeclarationValue.Unset,
            DeclarationValue.Unset,
            DeclarationValue.Unset,
            DeclarationValue.Unset,
            DeclarationValue.Unset,
            DeclarationValue.Unset,
        )
        val schema = QueryFieldSchema(
            null,
            null,
            null,
            setOf(QueryValueType.STRING),
            false,
            false,
            QueryCardinality.SINGLE,
            null,
            false,
            emptyMap(),
            null,
        )

        declaration.maskRule.assert().isEqualTo(DeclarationValue.Unset)
        schema.maskRule.assert().isNull()
    }

    @Test
    fun `mask cache should distinguish schemas with and without masked fields`() {
        val maskedField = LogicalField("state.secret")
        val maskedSchema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(
                maskedField to fieldSchema(maskRule = fullMaskRule()),
                LogicalField("state.name") to fieldSchema(),
            ),
        )
        val unmaskedSchema = maskedSchema.copy(
            fields = mapOf(LogicalField("state.name") to fieldSchema()),
        )

        maskedSchema.hasMaskedFields.assert().isTrue()
        maskedSchema.maskedFields.keys.assert().containsExactly(maskedField)
        unmaskedSchema.hasMaskedFields.assert().isFalse()
        unmaskedSchema.maskedFields.assert().isEmpty()
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
        }.build().fields.getValue(LogicalField("state.createdAt"))

        declaration.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
        declaration.semanticType.assert().isEqualTo(DeclarationValue.Set(Temporal.Epoch(TimeUnit.SECONDS)))
        declaration.title.assert().isEqualTo(DeclarationValue.Unset)
        declaration.nullable.assert().isEqualTo(DeclarationValue.Unset)
        declaration.cardinality.assert().isEqualTo(DeclarationValue.Unset)
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
                cardinality(QueryCardinality.MANY)
                semanticType(Temporal.Date)
                dynamicChildren()
            }
        }.build().fields.getValue(LogicalField("state.status"))

        declaration.assert().isEqualTo(
            QueryFieldDeclaration(
                title = DeclarationValue.Set("Status"),
                description = DeclarationValue.Set("Current status"),
                enumValues = DeclarationValue.Set(listOf(open)),
                valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
                nullable = DeclarationValue.Set(false),
                required = DeclarationValue.Set(true),
                cardinality = DeclarationValue.Set(QueryCardinality.MANY),
                semanticType = DeclarationValue.Set(Temporal.Date),
                dynamicChildren = DeclarationValue.Set(true),
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
        registration.declaration.fields.getValue(LogicalField("state.name")).title
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

    private fun fieldSchema(
        title: String? = null,
        dynamicChildren: Boolean = false,
        bindings: Map<QueryCapability, QueryFieldBinding> = emptyMap(),
        projectionPath: String? = null,
        maskRule: MaskRule? = null,
    ): QueryFieldSchema = QueryFieldSchema(
        title = title,
        description = null,
        enumValues = listOf(JsonNodeFactory.instance.stringNode("OPEN")),
        valueTypes = setOf(QueryValueType.STRING),
        nullable = false,
        required = true,
        cardinality = QueryCardinality.SINGLE,
        semanticType = null,
        dynamicChildren = dynamicChildren,
        maskRule = maskRule,
        bindings = bindings,
        projectionPath = projectionPath,
    )

    private fun fullMaskRule(): MaskRule {
        val annotation = Masked::secret.javaField!!.getAnnotation(Mask::class.java)
        return MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
    }

    private data class Masked(@field:Mask val secret: String)
}
