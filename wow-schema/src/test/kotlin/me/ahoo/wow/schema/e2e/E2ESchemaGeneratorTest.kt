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

package me.ahoo.wow.schema.e2e

import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaVersion
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import me.ahoo.wow.schema.typed.AggregatedDomainEventStream
import me.ahoo.wow.schema.typed.AggregatedFields
import me.ahoo.wow.schema.typed.query.AggregatedCondition
import me.ahoo.wow.schema.typed.query.AggregatedListQuery
import me.ahoo.wow.schema.typed.query.AggregatedPagedQuery
import me.ahoo.wow.schema.typed.query.AggregatedSingleQuery
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class E2ESchemaGeneratorTest {
    private val jsonSchemaGenerator = SchemaGeneratorBuilder()
        .openapi31(true)
        .schemaVersion(SchemaVersion.DRAFT_2020_12)
        .optionPreset(OptionPreset.PLAIN_JSON)
        .customizer {
        }
        .build()

    companion object {
        private const val RESOURCE_PREFIX = "META-INF/wow-schema-e2e/"

        private fun loadE2EString(resourceName: String): String {
            val resourcePath = "$RESOURCE_PREFIX$resourceName.json"
            val resourceURL = E2ESchemaGeneratorTest::class.java.classLoader.getResource(resourcePath)
            requireNotNull(resourceURL) { "Can not find e2e schema resource: $resourcePath" }
            return resourceURL.openStream().use { it.readAllBytes().toString(Charsets.UTF_8) }
        }

        @JvmStatic
        fun parametersForGenerateTypeParameter(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(CommandMessage::class.java, CreateOrder::class.java, "CreateOrderCommandMessage"),
                Arguments.of(DomainEvent::class.java, OrderCreated::class.java, "OrderCreatedDomainEvent"),
                Arguments.of(
                    AggregatedDomainEventStream::class.java,
                    Cart::class.java,
                    "CartAggregatedDomainEventStream"
                ),
                Arguments.of(
                    AggregatedFields::class.java,
                    Order::class.java,
                    "OrderAggregatedFields"
                ),
                Arguments.of(
                    AggregatedCondition::class.java,
                    Order::class.java,
                    "OrderAggregatedCondition"
                ),
                Arguments.of(
                    AggregatedListQuery::class.java,
                    Order::class.java,
                    "OrderAggregatedListQuery"
                ),
                Arguments.of(
                    AggregatedPagedQuery::class.java,
                    Order::class.java,
                    "OrderAggregatedPagedQuery"
                ),
                Arguments.of(
                    AggregatedSingleQuery::class.java,
                    Order::class.java,
                    "OrderAggregatedSingleQuery"
                ),
            )
        }
    }

    @ParameterizedTest
    @MethodSource("parametersForGenerateTypeParameter")
    fun `should generate type parameterized schema matching e2e snapshot`(
        interfaceType: Class<*>,
        typeParameter: Class<*>,
        resourceName: String
    ) {
        val schema = jsonSchemaGenerator.generateSchema(interfaceType, typeParameter)
        schema.toPrettyString().assert().isEqualTo(loadE2EString(resourceName))
    }

    @Test
    fun `should generate state aggregate schema matching e2e snapshot`() {
        val schema = jsonSchemaGenerator.generateSchema(
            me.ahoo.wow.modeling.state.StateAggregate::class.java,
            MockStateAggregate::class.java
        )
        schema.toPrettyString().assert().isEqualTo(loadE2EString("MockStateAggregate"))
    }

    @Test
    fun `should generate snapshot schema matching e2e snapshot`() {
        val schema = jsonSchemaGenerator.generateSchema(
            me.ahoo.wow.eventsourcing.snapshot.Snapshot::class.java,
            MockStateAggregate::class.java
        )
        schema.toPrettyString().assert().isEqualTo(loadE2EString("MockStateAggregateSnapshot"))
    }

    @Test
    fun `should generate state event schema matching e2e snapshot`() {
        val schema = jsonSchemaGenerator.generateSchema(
            me.ahoo.wow.eventsourcing.state.StateEvent::class.java,
            MockStateAggregate::class.java
        )
        schema.toPrettyString().assert().isEqualTo(loadE2EString("MockStateAggregateStateEvent"))
    }
}
