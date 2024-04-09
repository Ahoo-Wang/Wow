package me.ahoo.wow.eventsourcing.snapshot

import me.ahoo.wow.modeling.state.StateAggregate.Companion.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class SnapshotKtTest {

    @Test
    fun materialize() {
        val stateAggregate = MOCK_AGGREGATE_METADATA.toStateAggregate(MockStateAggregate("1"), 1)
        val snapshot = SimpleSnapshot(stateAggregate)
        val materializedSnapshot = snapshot.materialize()
        assertThat(materializedSnapshot.contextName, equalTo(snapshot.aggregateId.contextName))
        assertThat(materializedSnapshot.aggregateName, equalTo(snapshot.aggregateId.aggregateName))
        assertThat(materializedSnapshot.tenantId, equalTo(snapshot.aggregateId.tenantId))
        assertThat(materializedSnapshot.aggregateId, equalTo(snapshot.aggregateId.id))
        assertThat(materializedSnapshot.version, equalTo(snapshot.version))
        assertThat(materializedSnapshot.eventId, equalTo(snapshot.eventId))
        assertThat(materializedSnapshot.firstOperator, equalTo(snapshot.firstOperator))
        assertThat(materializedSnapshot.operator, equalTo(snapshot.operator))
        assertThat(materializedSnapshot.firstEventTime, equalTo(snapshot.firstEventTime))
        assertThat(materializedSnapshot.eventTime, equalTo(snapshot.eventTime))
        assertThat(materializedSnapshot.state, equalTo(snapshot.state))
        assertThat(materializedSnapshot.snapshotTime, equalTo(snapshot.snapshotTime))
        assertThat(materializedSnapshot.deleted, equalTo(snapshot.deleted))
    }
}
