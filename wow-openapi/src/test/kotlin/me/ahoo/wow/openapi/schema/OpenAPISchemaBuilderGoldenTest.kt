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

package me.ahoo.wow.openapi.schema

import com.github.victools.jsonschema.generator.Option
import io.swagger.v3.core.util.ObjectMapperFactory
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.OrderStatus
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import me.ahoo.wow.schema.typed.AggregatedDomainEventStream
import me.ahoo.wow.schema.web.ServerSentEventNonNullData
import org.junit.jupiter.api.Test
import org.springframework.http.codec.ServerSentEvent
import tools.jackson.databind.JsonNode
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.Path

/**
 * Snapshots the references and components [OpenAPISchemaBuilder] produces for the framework types and the example
 * domain. Regenerate with `WOW_OPENAPI_SCHEMA_GOLDEN_UPDATE=true` and review the diff.
 */
class OpenAPISchemaBuilderGoldenTest {
    private class Case(val name: String, val type: Type, vararg val typeParameters: Type)

    private abstract class TypeRef<T> {
        val type: Type = (javaClass.genericSuperclass as ParameterizedType).actualTypeArguments[0]
    }

    private val cases = listOf(
        Case("IntRange", IntRange::class.java),
        Case("CharRange", CharRange::class.java),
        Case("LongRange", LongRange::class.java),
        Case("AggregateId", AggregateId::class.java),
        Case("OrderStatus", OrderStatus::class.java),
        Case("JsonNode", JsonNode::class.java),
        Case("FilterExpression", FilterExpression::class.java),
        Case("ListQuery", ListQuery::class.java),
        Case("PagedQuery", PagedQuery::class.java),
        Case(
            "OrderStateMaterializedSnapshotPagedList",
            object : TypeRef<PagedList<MaterializedSnapshot<OrderState>>>() {}.type,
        ),
        Case("ServerSentEvent", ServerSentEvent::class.java, OrderState::class.java),
        Case("ServerSentEventNonNullData", ServerSentEventNonNullData::class.java, OrderState::class.java),
        Case("CommandMessage", CommandMessage::class.java),
        Case("CreateOrderCommandMessage", CommandMessage::class.java, CreateOrder::class.java),
        Case("DomainEvent", DomainEvent::class.java),
        Case("OrderCreatedDomainEvent", DomainEvent::class.java, OrderCreated::class.java),
        Case("DomainEventStream", DomainEventStream::class.java),
        Case("AggregatedDomainEventStream", AggregatedDomainEventStream::class.java),
        Case("CartAggregatedDomainEventStream", AggregatedDomainEventStream::class.java, Cart::class.java),
        Case("StateAggregate", StateAggregate::class.java),
        Case("OrderStateStateAggregate", StateAggregate::class.java, OrderState::class.java),
        Case("OrderStateSnapshot", Snapshot::class.java, OrderState::class.java),
        Case("OrderStateStateEvent", StateEvent::class.java, OrderState::class.java),
        Case("OrderState", OrderState::class.java),
        Case("CartState", CartState::class.java),
    )

    @Test
    fun `referenced components match golden`() {
        assertGolden("components.json", OpenAPISchemaBuilder(defaultSchemaNamePrefix = "golden."))
    }

    @Test
    fun `inline schemas match golden`() {
        val builder = SchemaGeneratorBuilder().customizer {
            it.with(Option.INLINE_ALL_SCHEMAS)
        }
        assertGolden("inline.json", OpenAPISchemaBuilder(schemaGeneratorBuilder = builder))
    }

    private fun assertGolden(name: String, builder: OpenAPISchemaBuilder) {
        val references = cases.associate { case ->
            case.name to builder.generateSchema(case.type, *case.typeParameters)
        }
        val output = linkedMapOf("references" to references, "components" to builder.build().toSortedMap())
        val actual = MAPPER.writeValueAsString(output) + "\n"
        val path = GOLDEN_ROOT.resolve(name)
        if (System.getenv(UPDATE_ENV) == "true") {
            Files.createDirectories(path.parent)
            Files.writeString(path, actual)
            return
        }
        Files.readString(path).assert().isEqualTo(actual)
    }

    private companion object {
        const val UPDATE_ENV = "WOW_OPENAPI_SCHEMA_GOLDEN_UPDATE"
        val GOLDEN_ROOT: Path = Path.of("src/test/resources/golden/schema")
        val MAPPER = ObjectMapperFactory.createJson31().writerWithDefaultPrettyPrinter()
    }
}
