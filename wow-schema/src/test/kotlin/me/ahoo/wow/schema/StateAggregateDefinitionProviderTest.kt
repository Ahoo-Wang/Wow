package me.ahoo.wow.schema

import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test

class StateAggregateDefinitionProviderTest {
    private val jsonSchemaGenerator = JsonSchemaGenerator(emptySet())

    @Test
    fun stateAggregateWithState() {
        val schema = jsonSchemaGenerator.generate(StateAggregate::class.java, MockStateAggregate::class.java)
        println(schema.toPrettyString())
    }

    @Test
    fun snapshotWithState() {
        val schema = jsonSchemaGenerator.generate(Snapshot::class.java, MockStateAggregate::class.java)
        println(schema.toPrettyString())
    }

    @Test
    fun stateEventWithState() {
        val schema = jsonSchemaGenerator.generate(StateEvent::class.java, MockStateAggregate::class.java)
        println(schema.toPrettyString())
    }
}
