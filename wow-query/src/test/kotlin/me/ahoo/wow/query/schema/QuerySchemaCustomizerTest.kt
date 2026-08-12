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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QuerySchemaCustomizerTest {
    private val namedAggregate = "sales.order".toNamedAggregate()
    private val eventTarget = QueryTarget(namedAggregate, QueryDocumentKind.EVENT_STREAM)

    @Test
    fun `event payload fields can only be explicitly added by the single customizer SPI`() {
        val customizer = QuerySchemaCustomizer { context ->
            context.baseSchema.withField(
                QueryFieldSchema.string(
                    path = LogicalField("body.body.sku"),
                    nullable = false
                )
            )
        }

        val schema = resolver(listOf(customizer)).resolve(eventTarget).block()!!

        schema.field("body.body.sku")!!.valueKind.assert().isEqualTo(QueryFieldValueKind.STRING)
    }

    @Test
    fun `all customizers see the same base and compatible changes are centrally merged`() {
        val seenBases = mutableListOf<QuerySchema>()
        val fullText = QueryCapabilityId("full-text")
        val first = QuerySchemaCustomizer { context ->
            seenBases += context.baseSchema
            context.baseSchema.withField(
                context.baseSchema.field("body.name")!!.copy(
                    capabilities = setOf(fullText),
                    bindings = setOf(
                        QueryCapabilityBinding(
                            backendId = QueryBackendId("elasticsearch"),
                            usage = QueryFieldUsage.SEARCH,
                            field = QueryBackendFieldPath("body.name")
                        )
                    )
                )
            )
        }
        val second = QuerySchemaCustomizer { context ->
            seenBases += context.baseSchema
            context.baseSchema.withField(
                context.baseSchema.field("body.name")!!.copy(projectable = false)
            )
        }

        val schema = resolver(listOf(first, second)).resolve(eventTarget).block()!!

        seenBases.size.assert().isEqualTo(2)
        (seenBases[0] === seenBases[1]).assert().isTrue()
        schema.field("body.name")!!.capabilities.assert().contains(fullText)
        schema.field("body.name")!!.projectable.assert().isFalse()
        schema.field("body.name")!!.bindings.single().usage.assert().isEqualTo(QueryFieldUsage.SEARCH)
    }

    @Test
    fun `rejects incompatible field types and bindings instead of last wins`() {
        val typeConflict = listOf(
            addPayload(QueryFieldValueKind.STRING),
            addPayload(QueryFieldValueKind.INTEGER)
        )
        assertConflict(typeConflict)

        val firstBinding = bindBodyName("body.name.keyword")
        val secondBinding = bindBodyName("body.name.raw")
        assertConflict(listOf(firstBinding, secondBinding))
    }

    @Test
    fun `compatible additions on the same path merge by identity and binding key`() {
        val elasticsearch = addPayloadBinding("elasticsearch", "body.sku")
        val mongo = QuerySchemaCustomizer { context ->
            context.baseSchema.withField(
                QueryFieldSchema.string(LogicalField("body.body.sku"), nullable = false).copy(
                    projectable = false,
                    bindings = setOf(
                        QueryCapabilityBinding(
                            QueryBackendId("mongo"),
                            QueryFieldUsage.EXACT,
                            QueryBackendFieldPath("body.sku")
                        )
                    )
                )
            )
        }

        val schema = resolver(listOf(elasticsearch, mongo)).resolve(eventTarget).block()!!

        schema.field("body.body.sku")!!.run {
            projectable.assert().isFalse()
            bindings.map { it.backendId.value }.toSet().assert().isEqualTo(setOf("elasticsearch", "mongo"))
        }

        assertConflict(
            listOf(
                addPayloadBinding("elasticsearch", "body.sku.keyword"),
                addPayloadBinding("elasticsearch", "body.sku.raw")
            )
        )
    }

    @Test
    fun `existing field deltas preserve removals merge additions and reject remove modify conflicts`() {
        val fullText = QueryCapabilityId("full-text")
        val extension = QueryCapabilityId("x-acme:prefix")
        val elasticsearch = binding("elasticsearch", QueryFieldUsage.SEARCH, "body.name")
        val mongo = binding("mongo", QueryFieldUsage.EXACT, "body.name")
        val opensearch = binding("opensearch", QueryFieldUsage.EXACT, "body.name.keyword")
        val baseField = QueryFieldSchema.string(LogicalField("body.name"), nullable = false).copy(
            capabilities = setOf(fullText),
            bindings = setOf(elasticsearch, mongo)
        )
        val base = QuerySchema(eventTarget, listOf(baseField))
        val removing = base.withField(
            baseField.copy(
                projectable = false,
                operators = baseField.operators - PortableOperator.CONTAINS,
                capabilities = emptySet(),
                bindings = setOf(mongo)
            )
        )
        val adding = base.withField(
            baseField.copy(
                capabilities = baseField.capabilities + extension,
                bindings = baseField.bindings + opensearch
            )
        )

        val merged = QuerySchemaCustomizationMerger.merge(base, listOf(removing, adding))
        merged.field("body.name")!!.run {
            projectable.assert().isFalse()
            operators.assert().doesNotContain(PortableOperator.CONTAINS)
            capabilities.assert().doesNotContain(fullText)
            capabilities.assert().contains(extension)
            bindings.assert().doesNotContain(elasticsearch)
            bindings.assert().contains(mongo, opensearch)
        }

        val modifyingRemovedBinding = base.withField(
            baseField.copy(
                bindings = setOf(
                    binding("elasticsearch", QueryFieldUsage.SEARCH, "body.name.raw"),
                    mongo
                )
            )
        )
        val conflict = assertThrows<QuerySchemaException> {
            QuerySchemaCustomizationMerger.merge(base, listOf(removing, modifyingRemovedBinding))
        }
        conflict.reason.assert().isEqualTo(QuerySchemaErrorReason.CUSTOMIZER_CONFLICT)
    }

    @Test
    fun `rejects authority driver objects outside controlled descriptors`() {
        val base = QuerySchema(
            target = eventTarget,
            fields = listOf(QueryFieldSchema.string(LogicalField("field"), nullable = false))
        )
        assertThrows<IllegalArgumentException> { QueryBackendId("driver") }
        assertThrows<IllegalArgumentException> { QueryBackendId("Elasticsearch") }
        assertThrows<IllegalArgumentException> { QueryBackendFieldPath("\$where") }
        assertThrows<IllegalArgumentException> {
            QueryFieldSchema.string(LogicalField("field"), nullable = false).copy(
                capabilities = setOf(QueryCapabilityId("driver"))
            )
        }
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (base.field("field")!!.capabilities as MutableSet<QueryCapabilityId>).add(QueryCapabilityId("driver"))
        }
    }

    @Test
    fun `validates field type collection binding and system invariants at construction`() {
        assertThrows<IllegalArgumentException> {
            QueryFieldSchema.string(LogicalField("field"), nullable = false).copy(
                operators = setOf(PortableOperator.BETWEEN)
            )
        }
        assertThrows<IllegalArgumentException> {
            QueryFieldSchema(
                path = LogicalField("score"),
                valueKind = QueryFieldValueKind.INTEGER,
                nullable = false,
                capabilities = setOf(QueryCapabilityId("full-text"))
            )
        }
        assertThrows<IllegalArgumentException> {
            QueryFieldSchema(
                path = LogicalField("score"),
                valueKind = QueryFieldValueKind.INTEGER,
                nullable = false,
                bindings = setOf(
                    binding("elasticsearch", QueryFieldUsage.SEARCH, "score")
                )
            )
        }
        assertThrows<IllegalArgumentException> {
            QueryFieldSchema.string(LogicalField("field"), nullable = false).copy(nested = true)
        }
        assertThrows<IllegalArgumentException> {
            QueryFieldSchema(
                path = LogicalField("field"),
                valueKind = QueryFieldValueKind.STRING,
                nullable = false,
                collectionKind = QueryCollectionKind.OBJECT
            )
        }
        assertThrows<IllegalArgumentException> {
            QuerySchema(
                eventTarget,
                QuerySystemFields.fields(QueryDocumentKind.EVENT_STREAM) + QueryFieldSchema(
                    path = LogicalField("body.body.systemPayload"),
                    valueKind = QueryFieldValueKind.STRING,
                    nullable = false,
                    system = true
                )
            )
        }
        assertThrows<IllegalArgumentException> {
            QuerySchema(
                eventTarget,
                listOf(
                    QueryFieldSchema.string(LogicalField("payload"), nullable = false),
                    QueryFieldSchema.string(LogicalField("payload.child"), nullable = false)
                )
            )
        }
    }

    @Test
    fun `rejects independently added child below a scalar parent during central merge`() {
        assertConflict(
            listOf(
                addPayload(QueryFieldValueKind.STRING),
                QuerySchemaCustomizer { context ->
                    context.baseSchema.withField(
                        QueryFieldSchema.string(LogicalField("body.body.sku.value"), nullable = false)
                    )
                }
            )
        )
    }

    private fun addPayload(kind: QueryFieldValueKind): QuerySchemaCustomizer = QuerySchemaCustomizer { context ->
        context.baseSchema.withField(
            QueryFieldSchema(
                path = LogicalField("body.body.sku"),
                valueKind = kind,
                nullable = false
            )
        )
    }

    private fun bindBodyName(fieldPath: String): QuerySchemaCustomizer = QuerySchemaCustomizer { context ->
        val field = context.baseSchema.field("body.name")!!
        context.baseSchema.withField(
            field.copy(
                capabilities = field.capabilities + QueryCapabilityId("full-text"),
                bindings = setOf(
                    QueryCapabilityBinding(
                        QueryBackendId("elasticsearch"),
                        QueryFieldUsage.SEARCH,
                        QueryBackendFieldPath(fieldPath)
                    )
                )
            )
        )
    }

    private fun addPayloadBinding(backendId: String, fieldPath: String): QuerySchemaCustomizer =
        QuerySchemaCustomizer { context ->
            context.baseSchema.withField(
                QueryFieldSchema.string(LogicalField("body.body.sku"), nullable = false).copy(
                    bindings = setOf(
                        QueryCapabilityBinding(
                            QueryBackendId(backendId),
                            QueryFieldUsage.EXACT,
                            QueryBackendFieldPath(fieldPath)
                        )
                    )
                )
            )
        }

    private fun binding(
        backendId: String,
        usage: QueryFieldUsage,
        fieldPath: String
    ): QueryCapabilityBinding = QueryCapabilityBinding(
        QueryBackendId(backendId),
        usage,
        QueryBackendFieldPath(fieldPath)
    )

    private fun assertConflict(customizers: List<QuerySchemaCustomizer>) {
        val error = assertThrows<QuerySchemaException> {
            resolver(customizers).resolve(eventTarget).block()
        }
        error.reason.assert().isEqualTo(QuerySchemaErrorReason.CUSTOMIZER_CONFLICT)
    }

    private fun resolver(customizers: List<QuerySchemaCustomizer>): JacksonQuerySchemaResolver {
        val state = mockk<StateAggregateMetadata<Any>>()
        @Suppress("UNCHECKED_CAST")
        every { state.aggregateType } returns EmptyState::class.java as Class<Any>
        val metadata = mockk<AggregateMetadata<Any, Any>>()
        every { metadata.namedAggregate } returns namedAggregate
        every { metadata.state } returns state
        return JacksonQuerySchemaResolver(JsonSerializer, listOf(metadata), customizers)
    }

    class EmptyState
}
