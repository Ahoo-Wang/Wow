package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class MaterializedSnapshotTest {
    val snapshot = MaterializedSnapshot(
        contextName = "contextName",
        aggregateName = "aggregateName",
        tenantId = "tenantId",
        ownerId = "ownerId",
        aggregateId = "aggregateId",
        version = 1,
        eventId = "eventId",
        firstOperator = "firstOperator",
        operator = "operator",
        firstEventTime = 1,
        eventTime = 1,
        state = "state",
        snapshotTime = 1,
        deleted = false
    )

    @Test
    fun toSmall() {
        val slimSnapshot = snapshot.toSmall { it }
        slimSnapshot.version.assert().isEqualTo(snapshot.version)
        slimSnapshot.firstEventTime.assert().isEqualTo(snapshot.firstEventTime)
        slimSnapshot.state.assert().isEqualTo(snapshot.state)
    }

    @Test
    fun toMedium() {
        val mediumSnapshot = snapshot.toMedium { it }
        mediumSnapshot.tenantId.assert().isEqualTo(snapshot.tenantId)
        mediumSnapshot.ownerId.assert().isEqualTo(snapshot.ownerId)
        mediumSnapshot.version.assert().isEqualTo(snapshot.version)
        mediumSnapshot.eventId.assert().isEqualTo(snapshot.eventId)
        mediumSnapshot.firstOperator.assert().isEqualTo(snapshot.firstOperator)
        mediumSnapshot.operator.assert().isEqualTo(snapshot.operator)
        mediumSnapshot.firstEventTime.assert().isEqualTo(snapshot.firstEventTime)
        mediumSnapshot.eventTime.assert().isEqualTo(snapshot.eventTime)
        mediumSnapshot.state.assert().isEqualTo(snapshot.state)
    }
}
