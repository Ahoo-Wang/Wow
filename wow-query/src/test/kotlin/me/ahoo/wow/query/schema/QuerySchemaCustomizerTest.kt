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
                    bindings = mapOf(
                        "search" to QueryCapabilityBinding("search", "exact-v1", mapOf("mode" to "exact"))
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
        schema.field("body.name")!!.bindings["search"]!!.bindingId.assert().isEqualTo("exact-v1")
    }

    @Test
    fun `rejects incompatible field types and bindings instead of last wins`() {
        val typeConflict = listOf(
            addPayload(QueryFieldValueKind.STRING),
            addPayload(QueryFieldValueKind.INTEGER)
        )
        assertConflict(typeConflict)

        val firstBinding = bindBodyName("exact-v1")
        val secondBinding = bindBodyName("exact-v2")
        assertConflict(listOf(firstBinding, secondBinding))
    }

    @Test
    fun `rejects authority driver objects and capability widening outside controlled descriptors`() {
        val base = QuerySchema(
            target = eventTarget,
            fields = listOf(QueryFieldSchema.string(LogicalField("field"), nullable = false))
        )
        assertThrows<IllegalArgumentException> {
            QueryCapabilityBinding("", "binding")
        }
        assertThrows<IllegalArgumentException> {
            QueryCapabilityBinding("search", "binding", mapOf("authority" to "admin"))
        }
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (base.field("field")!!.capabilities as MutableSet<QueryCapabilityId>).add(QueryCapabilityId("driver"))
        }

        val portableWidening = QuerySchemaCustomizer { context ->
            val field = context.baseSchema.field("body.name")!!
            context.baseSchema.withField(
                field.copy(operators = field.operators + PortableOperator.BETWEEN)
            )
        }
        val error = assertThrows<QuerySchemaException> {
            resolver(listOf(portableWidening)).resolve(eventTarget).block()
        }
        error.reason.assert().isEqualTo(QuerySchemaErrorReason.INVALID_CUSTOMIZATION)
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

    private fun bindBodyName(bindingId: String): QuerySchemaCustomizer = QuerySchemaCustomizer { context ->
        val field = context.baseSchema.field("body.name")!!
        context.baseSchema.withField(
            field.copy(bindings = mapOf("search" to QueryCapabilityBinding("search", bindingId)))
        )
    }

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
