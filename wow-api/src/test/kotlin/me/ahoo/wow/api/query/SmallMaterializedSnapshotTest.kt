package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class SmallMaterializedSnapshotTest {
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
}
