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

package me.ahoo.wow.schema.typed

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.schema.CreateTestAggregate
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import me.ahoo.wow.schema.TestAggregate
import me.ahoo.wow.schema.TestAggregateCreated
import me.ahoo.wow.schema.TestState
import me.ahoo.wow.schema.typed.query.AggregatedCondition
import me.ahoo.wow.schema.typed.query.AggregatedListQuery
import me.ahoo.wow.schema.typed.query.AggregatedPagedQuery
import me.ahoo.wow.schema.typed.query.AggregatedSingleQuery
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class TypedDefinitionProviderTest {
    private val jsonSchemaGenerator = SchemaGeneratorBuilder().build()

    companion object {
        @JvmStatic
        fun parametersForTypedSchema(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    "CommandMessage",
                    CommandMessage::class.java,
                    CreateTestAggregate::class.java,
                    "body"
                ),
                Arguments.of(
                    "DomainEvent",
                    DomainEvent::class.java,
                    TestAggregateCreated::class.java,
                    "body"
                ),
                Arguments.of(
                    "StateAggregate",
                    StateAggregate::class.java,
                    TestState::class.java,
                    "state"
                ),
                Arguments.of(
                    "Snapshot",
                    Snapshot::class.java,
                    TestState::class.java,
                    "state"
                ),
                Arguments.of(
                    "StateEvent",
                    StateEvent::class.java,
                    TestState::class.java,
                    "state"
                ),
                Arguments.of(
                    "AggregatedCondition",
                    AggregatedCondition::class.java,
                    TestAggregate::class.java,
                    null
                ),
                Arguments.of(
                    "AggregatedListQuery",
                    AggregatedListQuery::class.java,
                    TestAggregate::class.java,
                    null
                ),
                Arguments.of(
                    "AggregatedPagedQuery",
                    AggregatedPagedQuery::class.java,
                    TestAggregate::class.java,
                    null
                ),
                Arguments.of(
                    "AggregatedSingleQuery",
                    AggregatedSingleQuery::class.java,
                    TestAggregate::class.java,
                    null
                ),
            )
        }
    }

    @ParameterizedTest
    @MethodSource("parametersForTypedSchema")
    fun `should generate schema with properties`(
        name: String,
        interfaceType: Class<*>,
        typeParameter: Class<*>,
        expectedPropertyName: String?
    ) {
        val schema = jsonSchemaGenerator.generateSchema(interfaceType, typeParameter).asJsonSchema()
        schema.getProperties().assert().describedAs { "Schema for $name should have properties" }.isNotNull()
        if (expectedPropertyName != null) {
            schema.getProperties()!!.get(expectedPropertyName).assert()
                .describedAs { "Schema for $name should have property '$expectedPropertyName'" }
                .isNotNull()
        }
    }
}
