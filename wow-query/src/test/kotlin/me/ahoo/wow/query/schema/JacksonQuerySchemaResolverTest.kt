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

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.PropertyNamingStrategies
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class JacksonQuerySchemaResolverTest {
    private val namedAggregate = "sales.order".toNamedAggregate()
    private val snapshotTarget = QueryTarget(namedAggregate, QueryDocumentKind.SNAPSHOT)
    private val eventStreamTarget = QueryTarget(namedAggregate, QueryDocumentKind.EVENT_STREAM)

    @Test
    fun `derives snapshot logical fields from the injected Jackson serialization model`() {
        val resolver = resolver(ExampleState::class.java)

        val schema = resolver.resolve(snapshotTarget).block()!!

        schema.field("state.display_name")!!.valueKind.assert().isEqualTo(QueryFieldValueKind.STRING)
        schema.field("state.snake_case_value")!!.valueKind.assert().isEqualTo(QueryFieldValueKind.STRING)
        schema.field("state.inherited_value")!!.valueKind.assert().isEqualTo(QueryFieldValueKind.STRING)
        schema.field("state.nullable_note")!!.nullable.assert().isTrue()
        schema.field("state.status")!!.valueKind.assert().isEqualTo(QueryFieldValueKind.ENUM)
        schema.field("state.primary_address.city")!!.valueKind.assert().isEqualTo(QueryFieldValueKind.STRING)
        schema.field("state.secondary_address.city")!!.valueKind.assert().isEqualTo(QueryFieldValueKind.STRING)
        schema.field("state.tags")!!.collectionKind.assert().isEqualTo(QueryCollectionKind.SCALAR)
        schema.field("state.tags")!!.operators.assert().contains(PortableOperator.ALL_IN)
        schema.field("state.lines")!!.collectionKind.assert().isEqualTo(QueryCollectionKind.OBJECT)
        schema.field("state.lines")!!.elementMatchEnabled.assert().isFalse()
        schema.field("state.lines.sku")!!.valueKind.assert().isEqualTo(QueryFieldValueKind.STRING)
        schema.field("state.attributes")!!.valueKind.assert().isEqualTo(QueryFieldValueKind.MAP)
        schema.field("state.attributes")!!.queryable.assert().isFalse()
        schema.field("state.attributes.any_key").assert().isNull()
        schema.field("state.originalName").assert().isNull()
        schema.field("state.ignored").assert().isNull()
    }

    @Test
    fun `declares target-specific immutable system fields without inferring event payload`() {
        val resolver = resolver(ExampleState::class.java)

        val snapshot = resolver.resolve(snapshotTarget).block()!!
        val eventStream = resolver.resolve(eventStreamTarget).block()!!

        snapshot.field("id")!!.system.assert().isTrue()
        snapshot.field("deleted")!!.valueKind.assert().isEqualTo(QueryFieldValueKind.BOOLEAN)
        snapshot.field("state.display_name").assert().isNotNull()
        snapshot.field("aggregateId").assert().isNull()

        eventStream.field("id")!!.system.assert().isTrue()
        eventStream.field("aggregateId")!!.system.assert().isTrue()
        eventStream.field("body")!!.collectionKind.assert().isEqualTo(QueryCollectionKind.OBJECT)
        eventStream.field("body.id")!!.system.assert().isTrue()
        eventStream.field("body.name")!!.system.assert().isTrue()
        eventStream.field("body.revision")!!.system.assert().isTrue()
        eventStream.field("body.bodyType")!!.system.assert().isTrue()
        eventStream.fields.keys.none { it.value.startsWith("state.") }.assert().isTrue()
        eventStream.fields.keys.none { it.value.startsWith("body.body.") }.assert().isTrue()

        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT) as MutableList<QueryFieldSchema>)[0] =
                QueryFieldSchema.string(LogicalField("tampered"), nullable = false)
        }
    }

    @Test
    fun `declares conservative default capabilities`() {
        val schema = resolver(ExampleState::class.java).resolve(snapshotTarget).block()!!

        schema.field("state.display_name")!!.let { field ->
            field.operators.assert().contains(PortableOperator.EQ)
            field.operators.assert().contains(PortableOperator.CONTAINS)
            field.capabilities.assert().isEmpty()
            field.stringOptions!!.comparisonModes.assert().isEqualTo(StringComparisonMode.entries.toSet())
            field.stringOptions.collation.assert().isNull()
        }
        schema.field("state.score")!!.let { field ->
            field.sortable.assert().isTrue()
            field.operators.assert().contains(PortableOperator.BETWEEN)
        }
        schema.field("state.created_at")!!.let { field ->
            field.valueKind.assert().isEqualTo(QueryFieldValueKind.TIME)
            field.sortable.assert().isTrue()
        }
    }

    @Test
    fun `fails deterministically on direct and indirect recursive types without rejecting shared DAG`() {
        resolver(ExampleState::class.java).resolve(snapshotTarget).block()!!.field("state.secondary_address.city")
            .assert().isNotNull()

        listOf(DirectCycle::class.java, IndirectCycleA::class.java).forEach { recursiveType ->
            val error = assertThrows<QuerySchemaException> {
                resolver(recursiveType).resolve(snapshotTarget).block()
            }
            error.reason.assert().isEqualTo(QuerySchemaErrorReason.RECURSIVE_TYPE)
            error.message.assert().isEqualTo("Query schema resolution failed: RECURSIVE_TYPE.")
        }
    }

    @Test
    fun `caches only successful immutable views by target and metadata identity`() {
        var attempts = 0
        val transientCustomizer = QuerySchemaCustomizer { context ->
            attempts++
            if (attempts == 1) {
                throw QuerySchemaException(QuerySchemaErrorReason.CUSTOMIZATION_FAILED)
            }
            context.baseSchema
        }
        val resolver = resolver(ExampleState::class.java, listOf(transientCustomizer))

        assertThrows<QuerySchemaException> { resolver.resolve(snapshotTarget).block() }
        val first = resolver.resolve(snapshotTarget).block()!!
        val second = resolver.resolve(snapshotTarget).block()!!

        attempts.assert().isEqualTo(2)
        (first === second).assert().isTrue()
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (first.fields as MutableMap<LogicalField, QueryFieldSchema>).clear()
        }
    }

    @Test
    fun `concurrent subscriptions materialize one schema instance`() {
        val materializations = AtomicInteger()
        val resolver = resolver(
            ExampleState::class.java,
            listOf(
                QuerySchemaCustomizer { context ->
                    materializations.incrementAndGet()
                    context.baseSchema
                }
            ),
        )

        val views = Flux.range(0, 32)
            .flatMap { resolver.resolve(snapshotTarget).subscribeOn(Schedulers.parallel()) }
            .collectList()
            .block()!!

        materializations.get().assert().isOne()
        views.all { it === views.first() }.assert().isTrue()
    }

    private fun resolver(
        stateType: Class<*>,
        customizers: List<QuerySchemaCustomizer> = emptyList()
    ): JacksonQuerySchemaResolver {
        val state = mockk<StateAggregateMetadata<Any>>()
        every { state.aggregateType } returns stateType.castClass()
        val metadata = mockk<AggregateMetadata<Any, Any>>()
        every { metadata.namedAggregate } returns namedAggregate
        every { metadata.state } returns state
        val mapper = JsonSerializer.rebuild()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build()
        return JacksonQuerySchemaResolver(mapper, listOf(metadata), customizers)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Class<*>.castClass(): Class<Any> = this as Class<Any>

    open class BaseState(
        val inheritedValue: String
    )

    class ExampleState(
        @field:JsonProperty("display_name")
        val originalName: String,
        @field:JsonIgnore
        val ignored: String,
        val nullableNote: String?,
        val status: Status,
        val score: Int,
        val createdAt: Instant,
        val primaryAddress: Address,
        val secondaryAddress: Address,
        val tags: List<String>,
        val lines: List<Line>,
        val attributes: Map<String, String>,
        val snakeCaseValue: String
    ) : BaseState("inherited")

    data class Address(val city: String)

    data class Line(val sku: String, val quantity: Int)

    enum class Status {
        CREATED,
        COMPLETED
    }

    data class DirectCycle(val child: DirectCycle?)

    data class IndirectCycleA(val child: IndirectCycleB)

    data class IndirectCycleB(val parent: IndirectCycleA?)
}
