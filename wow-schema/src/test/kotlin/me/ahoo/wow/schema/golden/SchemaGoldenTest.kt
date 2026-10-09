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

package me.ahoo.wow.schema.golden

import com.github.victools.jsonschema.generator.SchemaGenerator
import com.github.victools.jsonschema.generator.SchemaVersion
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
import me.ahoo.wow.example.api.order.OrderStatus
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.schema.AnnotationFixture
import me.ahoo.wow.schema.ChangeTestName
import me.ahoo.wow.schema.CommandRouteFixture
import me.ahoo.wow.schema.CreateTestAggregate
import me.ahoo.wow.schema.HeaderRouteFixture
import me.ahoo.wow.schema.IsPrefixFixture
import me.ahoo.wow.schema.KotlinFixture
import me.ahoo.wow.schema.OuterFixture
import me.ahoo.wow.schema.PolymorphicFixture
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import me.ahoo.wow.schema.TestAggregate
import me.ahoo.wow.schema.TestAggregateCreated
import me.ahoo.wow.schema.TestState
import me.ahoo.wow.schema.TreeNodeFixture
import me.ahoo.wow.schema.openapi.OpenAPISchemaBuilder
import me.ahoo.wow.schema.typed.AggregatedDomainEventStream
import me.ahoo.wow.schema.web.ServerSentEventNonNullData
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.joda.money.CurrencyUnit
import org.joda.money.Money
import org.junit.jupiter.api.Test
import org.springframework.http.codec.ServerSentEvent
import tools.jackson.databind.JsonNode
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Snapshots the schema of every framework type, Kotlin edge case and fixture under each generator configuration Wow
 * ships: the default builder, the Draft 2020-12 builder without definitions for all objects, and the OpenAPI builder.
 */
class SchemaGoldenTest {
    private class Case(val name: String, val type: Type, vararg val typeParameters: Type)

    private abstract class TypeRef<T> {
        val type: Type = (javaClass.genericSuperclass as ParameterizedType).actualTypeArguments[0]
    }

    private val cases = listOf(
        Case("TestState", TestState::class.java),
        Case("CreateTestAggregate", CreateTestAggregate::class.java),
        Case("ChangeTestName", ChangeTestName::class.java),
        Case("AnnotationFixture", AnnotationFixture::class.java),
        Case("KotlinFixture", KotlinFixture::class.java),
        Case("CommandRouteFixture", CommandRouteFixture::class.java),
        Case("HeaderRouteFixture", HeaderRouteFixture::class.java),
        Case("PolymorphicFixture", PolymorphicFixture::class.java),
        Case("IsPrefixFixture", IsPrefixFixture::class.java),
        Case("StaticNestedFixture", OuterFixture.StaticNestedFixture::class.java),
        Case("TreeNodeFixture", TreeNodeFixture::class.java),
        Case("IntRange", IntRange::class.java),
        Case("CharRange", CharRange::class.java),
        Case("LongRange", LongRange::class.java),
        Case("Money", Money::class.java),
        Case("CurrencyUnit", CurrencyUnit::class.java),
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
        Case("ServerSentEvent", ServerSentEvent::class.java, TestState::class.java),
        Case("ServerSentEventNonNullData", ServerSentEventNonNullData::class.java, TestState::class.java),
        Case("CommandMessage", CommandMessage::class.java),
        Case("CreateTestAggregateCommandMessage", CommandMessage::class.java, CreateTestAggregate::class.java),
        Case("CreateOrderCommandMessage", CommandMessage::class.java, CreateOrder::class.java),
        Case("DomainEvent", DomainEvent::class.java),
        Case("TestAggregateCreatedDomainEvent", DomainEvent::class.java, TestAggregateCreated::class.java),
        Case("DomainEventStream", DomainEventStream::class.java),
        Case("AggregatedDomainEventStream", AggregatedDomainEventStream::class.java),
        Case(
            "TestAggregateAggregatedDomainEventStream",
            AggregatedDomainEventStream::class.java,
            TestAggregate::class.java
        ),
        Case("CartAggregatedDomainEventStream", AggregatedDomainEventStream::class.java, Cart::class.java),
        Case("StateAggregate", StateAggregate::class.java),
        Case("TestStateStateAggregate", StateAggregate::class.java, TestState::class.java),
        Case("MockStateAggregateSnapshot", Snapshot::class.java, MockStateAggregate::class.java),
        Case("MockStateAggregateStateEvent", StateEvent::class.java, MockStateAggregate::class.java),
        Case("OrderState", OrderState::class.java),
        Case("CartState", CartState::class.java),
    )

    @Test
    fun `default builder matches goldens`() {
        assertGoldens("default", SchemaGeneratorBuilder().build())
    }

    @Test
    fun `draft 2020-12 builder matches goldens`() {
        val generator = SchemaGeneratorBuilder()
            .schemaVersion(SchemaVersion.DRAFT_2020_12)
            .customizer { }
            .build()
        assertGoldens("draft-2020-12", generator)
    }

    @Test
    fun `openapi builder matches golden`() {
        val builder = OpenAPISchemaBuilder(defaultSchemaNamePrefix = "golden.")
        val references = cases.associate { case ->
            case.name to builder.generateSchema(case.type, *case.typeParameters)
        }
        val output = linkedMapOf("references" to references, "components" to builder.build().toSortedMap())
        Golden.compare("openapi/components.json", OPENAPI_MAPPER.writeValueAsString(output) + "\n")
            .assert().isNull()
    }

    private fun assertGoldens(config: String, generator: SchemaGenerator) {
        val mismatches = cases.mapNotNull { case ->
            val schema = generator.generateSchema(case.type, *case.typeParameters)
            Golden.compare("schema/$config/${case.name}.json", schema.toPrettyString() + "\n")
        }
        mismatches.assert().isEmpty()
    }

    private companion object {
        val OPENAPI_MAPPER = ObjectMapperFactory.createJson31().writerWithDefaultPrettyPrinter()
    }
}
