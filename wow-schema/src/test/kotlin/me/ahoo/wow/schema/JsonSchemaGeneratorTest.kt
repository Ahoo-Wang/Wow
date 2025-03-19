package me.ahoo.wow.schema

import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.SimpleCommandMessage
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.SimpleDomainEvent
import me.ahoo.wow.event.SimpleDomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventData
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.modeling.DefaultAggregateId
import me.ahoo.wow.modeling.state.SimpleStateAggregate
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class JsonSchemaGeneratorTest {
    private val jsonSchemaGenerator = JsonSchemaGenerator(setOf(WowOption.IGNORE_COMMAND_ROUTE_VARIABLE))

    companion object {
        @JvmStatic
        fun parametersForGenerate(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(AggregateId::class.java, AggregateId::class.java),
                Arguments.of(AggregateId::class.java, DefaultAggregateId::class.java),
                Arguments.of(CommandMessage::class.java, CommandMessage::class.java),
                Arguments.of(CommandMessage::class.java, SimpleCommandMessage::class.java),
                Arguments.of(DomainEvent::class.java, DomainEvent::class.java),
                Arguments.of(DomainEvent::class.java, SimpleDomainEvent::class.java),
                Arguments.of(DomainEventStream::class.java, DomainEventStream::class.java),
                Arguments.of(DomainEventStream::class.java, SimpleDomainEventStream::class.java),
                Arguments.of(StateAggregate::class.java, StateAggregate::class.java),
                Arguments.of(StateAggregate::class.java, SimpleStateAggregate::class.java),
                Arguments.of(Snapshot::class.java, Snapshot::class.java),
                Arguments.of(Snapshot::class.java, SimpleSnapshot::class.java),
                Arguments.of(StateEvent::class.java, StateEvent::class.java),
                Arguments.of(StateEvent::class.java, StateEventData::class.java),
            )
        }

        @JvmStatic
        fun parametersForGenerateTypeParameter(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(CommandMessage::class.java, CreateOrder::class.java, "CreateOrderCommandMessage"),
                Arguments.of(DomainEvent::class.java, OrderCreated::class.java, "OrderCreatedDomainEvent"),
                Arguments.of(StateAggregate::class.java, MockStateAggregate::class.java, "MockStateAggregate"),
                Arguments.of(Snapshot::class.java, MockStateAggregate::class.java, "MockStateAggregateSnapshot"),
                Arguments.of(StateEvent::class.java, MockStateAggregate::class.java, "MockStateAggregateStateEvent"),
            )
        }
    }

    @ParameterizedTest
    @MethodSource("parametersForGenerate")
    fun generate(interfaceType: Class<*>, implType: Class<*>) {
        val schema = jsonSchemaGenerator.generate(implType)
        assertThat(schema, equalTo(WowSchemaLoader.load(interfaceType)))
    }

    @ParameterizedTest
    @MethodSource("parametersForGenerateTypeParameter")
    fun generateTypeParameter(interfaceType: Class<*>, typeParameter: Class<*>, resourceName: String) {
        val schema = jsonSchemaGenerator.generate(interfaceType, typeParameter)
        assertThat(schema.toPrettyString(), equalTo(WowSchemaLoader.loadAsString(resourceName)))
    }

    @Test
    fun ignoreCommandPathRouteVariable() {
        val schema = jsonSchemaGenerator.generate(Patch::class.java)
        assertThat(schema.get("properties"), nullValue())
    }

    @Test
    fun ignoreCommandHeaderRouteVariable() {
        val schema = jsonSchemaGenerator.generate(Header::class.java)
        assertThat(schema.get("properties"), nullValue())
    }

    @Test
    fun notIgnoreCommandPathRouteVariable() {
        val jsonSchemaGenerator = JsonSchemaGenerator(setOf())
        val schema = jsonSchemaGenerator.generate(Patch::class.java)
        assertThat(schema.get("properties"), notNullValue())
    }

    data class Patch(
        @field:CommandRoute.PathVariable
        val field: String,
        @CommandRoute.PathVariable
        val property: String,
        @get:CommandRoute.PathVariable
        val getter: String
    )

    data class Header(
        @field:CommandRoute.HeaderVariable
        val field: String,
        @CommandRoute.HeaderVariable
        val property: String,
        @get:CommandRoute.HeaderVariable
        val getter: String
    )
}
