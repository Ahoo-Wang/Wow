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
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryCompatibilityLevel
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
    @Suppress("LongMethod")
    fun `none rewrite model should preserve identity and enforce admission`() {
        val valid = QueryField("state.name")
        val missingCapability = QueryField("state.missingCapability")
        val integer = QueryField("state.integer")
        val single = QueryField("state.single")
        val masked = QueryField("state.masked")
        val unstableAlias = QueryField("state.rank")
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(
                valid to fieldSchema(
                    bindings = mapOf(
                        QueryCapability.EXACT_MATCH to QueryFieldBinding(valid, valid, null),
                        QueryCapability.SORT to QueryFieldBinding(valid, valid, null),
                    ),
                    projectionField = valid,
                ),
                missingCapability to fieldSchema(),
                integer to fieldSchema(
                    bindings = mapOf(
                        QueryCapability.EXACT_MATCH to QueryFieldBinding(integer, integer, null),
                    ),
                    valueTypes = setOf(QueryValueType.INTEGER),
                ),
                single to fieldSchema(
                    bindings = mapOf(
                        QueryCapability.PRESENCE to QueryFieldBinding(single, single, null),
                    ),
                ),
                masked to fieldSchema(
                    bindings = mapOf(
                        QueryCapability.SORT to QueryFieldBinding(masked, masked, null),
                    ),
                    maskRule = fullMaskRule(),
                ),
                unstableAlias to fieldSchema(
                    bindings = mapOf(
                        QueryCapability.SORT to QueryFieldBinding(
                            QueryField("_score"),
                            QueryField("_score"),
                            null,
                        ),
                    ),
                ),
            ),
        )
        val query = ListQuery(
            filter = AndFilter(listOf(EqualFilter(valid, JsonNodeFactory.instance.stringNode("A")))),
            projection = Projection(include = listOf(valid)),
            sort = listOf(Sort(valid, Sort.Direction.ASC)),
            limit = 10,
        )

        schema.rewriteMode.assert().isEqualTo(QueryRewriteMode.NONE)
        val resolution = schema.resolve(query)

        resolution.value.assert().isSameAs(query)
        resolution.value.filter.assert().isSameAs(query.filter)
        resolution.value.projection.assert().isSameAs(query.projection)
        resolution.value.sort.assert().isSameAs(query.sort)
        listOf(
            schema.resolve(EqualFilter(missingCapability, JsonNodeFactory.instance.stringNode("A"))),
            schema.resolve(EqualFilter(integer, JsonNodeFactory.instance.stringNode("not-an-integer"))),
            schema.resolve(IsEmptyFilter(single)),
            schema.resolve(CursorQuery(MatchAllFilter, sort = listOf(Sort(masked, Sort.Direction.ASC)))),
            schema.resolve(CursorQuery(MatchAllFilter, sort = listOf(Sort(unstableAlias, Sort.Direction.ASC)))),
        ).map { it.compatibility }.assert().containsExactly(
            QueryCompatibilityLevel.INCOMPATIBLE,
            QueryCompatibilityLevel.INCOMPATIBLE,
            QueryCompatibilityLevel.INCOMPATIBLE,
            QueryCompatibilityLevel.INCOMPATIBLE,
            QueryCompatibilityLevel.INCOMPATIBLE,
        )
    }

    @Test
    fun `projection binding should validate without rewriting the public projection`() {
        val field = QueryField("state.name")
        val projection = Projection(include = listOf(field))
        val query = ListQuery(MatchAllFilter, projection = projection)
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(
                field to fieldSchema(
                    projectionField = QueryField("document.name"),
                    rewriteMode = QueryRewriteMode.NONE,
                ),
            ),
        )

        val resolution = schema.resolve(query)

        resolution.value.assert().isSameAs(query)
        resolution.value.projection.assert().isSameAs(projection)
        resolution.compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
    }

    @Test
    fun `none rewrite model should preserve model search fallback filter`() {
        val filter = SearchFilter("name", setOf(QueryField("state.unknown")))
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            setOf(QueryCapability.FULL_TEXT_TERMS),
            emptyMap(),
        )

        val resolution = schema.resolve(filter)

        resolution.value.assert().isSameAs(filter)
        resolution.compatibility.assert().isEqualTo(QueryCompatibilityLevel.COMPATIBLE)
    }

    @Test
    fun `exact field should win over a dynamic ancestor`() {
        val exact = fieldSchema(
            dynamicChildren = false,
            bindings = mapOf(
                QueryCapability.EXACT_MATCH to QueryFieldBinding(
                    QueryField("exact_name"),
                    QueryField("exact_name"),
                    null,
                ),
            ),
        )
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            setOf(QueryCapability.EXACT_MATCH),
            mapOf(
                QueryField("state") to fieldSchema(
                    dynamicChildren = true,
                    bindings = mapOf(
                        QueryCapability.EXACT_MATCH to QueryFieldBinding(
                            QueryField("document"),
                            QueryField("document"),
                            null,
                        ),
                    ),
                ),
                QueryField("state.name") to exact,
            ),
        )

        schema.field(QueryField("state.name")).assert().isSameAs(exact)
    }

    @Test
    fun `nearest dynamic ancestor should append the relative logical suffix`() {
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            setOf(QueryCapability.EXACT_MATCH, QueryCapability.SORT),
            mapOf(
                QueryField("state") to fieldSchema(
                    dynamicChildren = true,
                    bindings = mapOf(
                        QueryCapability.EXACT_MATCH to QueryFieldBinding(
                            QueryField("document"),
                            QueryField("document"),
                            null,
                        ),
                    ),
                ),
                QueryField("state.customer") to fieldSchema(
                    dynamicChildren = true,
                    bindings = mapOf(
                        QueryCapability.SORT to QueryFieldBinding(
                            QueryField("customer_doc"),
                            QueryField("customer_doc"),
                            QueryStorageType("keyword"),
                        ),
                    ),
                    projectionField = QueryField("customer_doc"),
                ),
            ),
        )

        val resolved = schema.field(QueryField("state.customer.address.city"))!!

        resolved.bindings.assert().isEqualTo(
            mapOf(
                QueryCapability.SORT to QueryFieldBinding(
                    QueryField("customer_doc.address.city"),
                    QueryField("customer_doc.address.city"),
                    QueryStorageType("keyword"),
                ),
            ),
        )
        resolved.projectionField.assert().isEqualTo(QueryField("customer_doc.address.city"))
    }

    @Test
    fun `lookup should not invent a capability absent from a dynamic ancestor`() {
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            setOf(QueryCapability.EXACT_MATCH, QueryCapability.SORT),
            mapOf(
                QueryField("state") to fieldSchema(
                    dynamicChildren = true,
                    bindings = mapOf(
                        QueryCapability.EXACT_MATCH to QueryFieldBinding(
                            QueryField("document"),
                            QueryField("document"),
                            null,
                        ),
                    ),
                ),
            ),
        )

        schema.field(QueryField("state.name"))!!.bindings.keys
            .assert().isEqualTo(setOf(QueryCapability.EXACT_MATCH))
    }

    @Test
    fun `dynamic suffix should not inherit element scope`() {
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(
                QueryField("state.orders") to fieldSchema(
                    dynamicChildren = true,
                    bindings = mapOf(
                        QueryCapability.EXACT_MATCH to QueryFieldBinding(
                            QueryField("document.orders"),
                            QueryField("document.orders"),
                            null,
                        ),
                        QueryCapability.ELEMENT_SCOPE to QueryFieldBinding(
                            QueryField("document.orders"),
                            QueryField("document.orders"),
                            null,
                        ),
                    ),
                ),
            ),
        )

        schema.field(QueryField("state.orders.items"))!!.bindings.keys.assert()
            .containsExactly(QueryCapability.EXACT_MATCH)
    }

    @Test
    fun `identity dynamic child should keep none rewrite mode`() {
        val parent = QueryField("state.dynamic")
        val child = QueryField("state.dynamic.code")
        val fieldSchema = fieldSchema(
            dynamicChildren = true,
            bindings = mapOf(
                QueryCapability.EXACT_MATCH to QueryFieldBinding(parent, parent, null),
            ),
            projectionField = parent,
            rewriteMode = QueryRewriteMode.NONE,
        )
        val schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), mapOf(parent to fieldSchema))

        schema.field(child)!!.binding(QueryCapability.EXACT_MATCH)!!.resolvedField.assert().isEqualTo(child)
        schema.field(child)!!.rewriteMode.assert().isEqualTo(QueryRewriteMode.NONE)
    }

    @Test
    fun `nearest dynamic ancestor should derive every field and drop element scope`() {
        val root = QueryField("state")
        val customer = QueryField("state.customer")
        val requested = QueryField("state.customer.name")
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(
                root to fieldSchema(
                    dynamicChildren = true,
                    bindings = mapOf(
                        QueryCapability.EXACT_MATCH to QueryFieldBinding(
                            QueryField("root"),
                            QueryField("root"),
                            null,
                        ),
                    ),
                    projectionField = QueryField("root"),
                    rewriteMode = QueryRewriteMode.REQUIRED,
                ),
                customer to fieldSchema(
                    dynamicChildren = true,
                    bindings = mapOf(
                        QueryCapability.EXACT_MATCH to QueryFieldBinding(
                            customer,
                            QueryField("document.customer"),
                            null,
                        ),
                        QueryCapability.ELEMENT_SCOPE to QueryFieldBinding(
                            customer,
                            QueryField("document.customer"),
                            null,
                        ),
                    ),
                    projectionField = QueryField("source.customer"),
                    rewriteMode = QueryRewriteMode.INFER,
                ),
            ),
        )

        val resolved = schema.field(requested)!!
        resolved.binding(QueryCapability.EXACT_MATCH)!!.resolvedField.assert()
            .isEqualTo(QueryField("state.customer.name"))
        resolved.binding(QueryCapability.EXACT_MATCH)!!.physicalField.assert()
            .isEqualTo(QueryField("document.customer.name"))
        resolved.projectionField.assert().isEqualTo(QueryField("source.customer.name"))
        resolved.bindings.assert().doesNotContainKey(QueryCapability.ELEMENT_SCOPE)
    }

    @Test
    fun `metadata projection should sort logical fields and omit binding details`() {
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            setOf(QueryCapability.EXACT_MATCH),
            linkedMapOf(
                QueryField("state.z") to fieldSchema(
                    bindings = mapOf(
                        QueryCapability.EXACT_MATCH to QueryFieldBinding(
                            QueryField("private_z"),
                            QueryField("private_z"),
                            QueryStorageType("keyword"),
                        ),
                    ),
                    projectionField = QueryField("private_source_z"),
                ),
                QueryField("state.a") to fieldSchema(title = "A"),
            ),
        )

        val metadata = schema.toMetadata()

        metadata.fields.map { it.field.path }.assert().containsExactly("state.a", "state.z")
        metadata.fields.last().capabilities.assert().isEqualTo(setOf(QueryCapability.EXACT_MATCH))
    }

    @Test
    fun `metadata should expose masked without executable mask details`() {
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(QueryField("state.secret") to fieldSchema(maskRule = fullMaskRule())),
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
            QueryRewriteMode.NONE,
        )

        declaration.maskRule.assert().isEqualTo(DeclarationValue.Unset)
        schema.maskRule.assert().isNull()
    }

    @Test
    fun `mask cache should distinguish schemas with and without masked fields`() {
        val maskedField = QueryField("state.secret")
        val maskedSchema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            emptySet(),
            mapOf(
                maskedField to fieldSchema(maskRule = fullMaskRule()),
                QueryField("state.name") to fieldSchema(),
            ),
        )
        val unmaskedSchema = maskedSchema.copy(
            fields = mapOf(QueryField("state.name") to fieldSchema()),
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
        }.build().fields.getValue(QueryField("state.createdAt"))

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
        }.build().fields.getValue(QueryField("state.status"))

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

    private fun fieldSchema(
        title: String? = null,
        dynamicChildren: Boolean = false,
        bindings: Map<QueryCapability, QueryFieldBinding> = emptyMap(),
        projectionField: QueryField? = null,
        rewriteMode: QueryRewriteMode = QueryRewriteMode.NONE,
        maskRule: MaskRule? = null,
        valueTypes: Set<QueryValueType> = setOf(QueryValueType.STRING),
    ): QueryFieldSchema = QueryFieldSchema(
        title = title,
        description = null,
        enumValues = listOf(JsonNodeFactory.instance.stringNode("OPEN")),
        valueTypes = valueTypes,
        nullable = false,
        required = true,
        cardinality = QueryCardinality.SINGLE,
        semanticType = null,
        dynamicChildren = dynamicChildren,
        maskRule = maskRule,
        bindings = bindings,
        projectionField = projectionField,
        rewriteMode = rewriteMode,
    )

    private fun fullMaskRule(): MaskRule {
        val annotation = Masked::secret.javaField!!.getAnnotation(Mask::class.java)
        return MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
    }

    private data class Masked(@field:Mask val secret: String)
}
